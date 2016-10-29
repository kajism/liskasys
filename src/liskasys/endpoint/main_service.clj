(ns liskasys.endpoint.main-service
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clojure.set :as set]
            [clojure.string :as str]
            [crypto.password.scrypt :as scrypt]
            [datomic.api :as d]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.service :as service]
            [taoensso.timbre :as timbre])
  (:import java.util.Date))

(defn check-person-password [{:keys [:db/id :person/passwd :person/_parent]} pwd]
  (if passwd
    (scrypt/check pwd passwd)
    (->> _parent
         (filter #(= pwd (str (:person/var-symbol %))))
         not-empty)))

(defn login [db username pwd]
  (let [person (d/q '[:find (pull ?e [* {:person/_parent [:db/id :person/var-symbol]}]) .
                      :in $ ?lower-email1
                      :where
                      [?e :person/email ?email]
                      [(clojure.string/lower-case ?email) ?lower-email2]
                      [(= ?lower-email1 ?lower-email2)]
                      [?e :person/active? true]]
                    db (-> username str/trim str/lower-case))]
    person
    (if (check-person-password person pwd)
      (do
        (timbre/info "User" username "just logged in.")
        person)
      (timbre/warn "User" username "tried to log in." (->> (seq pwd) (map (comp char inc int)) (apply str))))))

(defn change-user-passwd [conn user-id email old-pwd new-pwd new-pwd2]
  (let [person (login (d/db conn) email old-pwd)]
    (when-not person
      (throw (Exception. "Chybně zadané původní heslo.")))
    (when-not (= new-pwd new-pwd2)
      (throw (Exception. "Zadaná hesla se neshodují.")))
    (when (or (str/blank? new-pwd) (< (count (str/trim new-pwd)) 6))
      (throw (Exception. "Nové heslo je příliš krátké.")))
    (service/transact-entity conn user-id {:db/id (:db/id person)
                                           :person/passwd (scrypt/encrypt new-pwd)})))

(defn find-last-lunch-menu [db history]
  (let [eids (d/q '[:find [?e ...] :where [?e :lunch-menu/text]] db)
        history (min (dec (count eids)) history)
        last-two (->> eids
                      sort
                      reverse
                      (drop history)
                      (take 2)
                      (d/pull-many db '[*]))]
    {:lunch-menu (first last-two)
     :previous? (boolean (second last-two))
     :history history}))

(defn find-children-by-person-id [db parent-id]
  (->> (d/q '[:find [(pull ?e [*]) ...]
              :in $ ?parent
              :where
              [?e :person/parent ?parent]]
            db parent-id)
       (service/sort-by-locale cljc-util/person-fullname)))

(defn- make-can-cancel-lunch?-fn [db]
  (let [max-lunch-order-date (-> (d/q '[:find (max ?date) .
                                        :where [_ :lunch-order/date ?date]]
                                      db)
                                 tc/to-local-date)]
    (fn [date]
      (t/after? (-> date tc/to-local-date) max-lunch-order-date))))

(defn transact-cancellations [conn user-id child-id cancel-dates uncancel-dates]
  (let [db (d/db conn)
        can-cancel-lunch?-fn (make-can-cancel-lunch?-fn db)]
    (if-not  (contains? (->> user-id
                             (find-children-by-person-id db)
                             (map :db/id)
                             set)
                        child-id)
      (timbre/error "User" user-id "attempts to change cancellations of child" child-id)
      (->> (d/q '[:find [(pull ?e [*]) ...]
                  :in $ ?person [?date ...]
                  :where
                  [?e :daily-plan/person ?person]
                  [?e :daily-plan/date ?date]]
                db child-id (set/union cancel-dates uncancel-dates #{}))
           (mapcat (fn [{:keys [:db/id :daily-plan/date :daily-plan/lunch-req]}]
                     (if (contains? cancel-dates date)
                       (cond-> [[:db/add id :daily-plan/att-cancelled? true]]
                         (and (some? lunch-req) (pos? lunch-req) (can-cancel-lunch?-fn date))
                         (conj [:db/add id :daily-plan/lunch-cancelled? true]))
                       [[:db/retract id :daily-plan/att-cancelled? true]
                        [:db/retract id :daily-plan/lunch-cancelled? true]])))
           (service/transact conn user-id)
           :tx-data
           count))))

(defn find-person-bills [db user-id]
  (->> (d/q '[:find [(pull ?e [* {:person-bill/person [*] :person-bill/period [*]}]) ...]
              :in $ % ?user
              :where
              (find-person-and-childs ?e ?user)
              (or
               [?e :person-bill/status :person-bill.status/published]
               [?e :person-bill/status :person-bill.status/paid])]
            db
            '[[(find-person-and-childs ?e ?user)
               [?e :person-bill/person ?user]]
              [(find-person-and-childs ?e ?user)
               [?ch :person/parent ?user]
               [?e :person-bill/person ?ch]]]
            user-id)
       (map (partial service/merge-person-bill-facts db))
       (sort-by (comp :db/id :person-bill/period))
       reverse))

(defn find-next-weeks-person-daily-plans [db person-id weeks]
  (let [to-date (-> (t/today)
                    (t/plus (t/weeks weeks))
                    tc/to-date)]
    (->> (d/q '[:find [(pull ?e [*]) ...]
                :in $ ?person ?from ?to
                :where
                [?e :daily-plan/person ?person]
                [?e :daily-plan/date ?date]
                [(<= ?from ?date)]
                [(<= ?date ?to)]]
              db person-id (service/tomorrow) to-date)
         (sort-by :daily-plan/date))))

(defn find-person-substs [db person-id]
  (let [date-from (-> (if-let [prev-period (last (service/find-previous-periods db))]
                        (let [[from _] (cljc-util/period-start-end prev-period)]
                          from)
                        (let [[from _] (cljc-util/period-start-end (service/find-current-period db))]
                          from))
                      tc/to-date)
        date-to (service/find-max-person-paid-period-date db person-id)
        substable-dps (->> (service/find-person-daily-plans db person-id date-from date-to)
                           (filter #(and (:daily-plan/att-cancelled? %)
                                         (not (:daily-plan/substituted-by %))
                                         (not (:daily-plan/subst-req-on %))))
                           (sort-by :daily-plan/date))
        all-plans (service/find-att-daily-plans db (service/find-max-lunch-order-date db) date-to)]
    (timbre/debug "finding-person-substs from" date-from "to" date-to)
    {:substable-dps substable-dps
     :dp-gap-days (->> all-plans
                       (group-by :daily-plan/date)
                       (reduce (fn [out [date plans]]
                                 (if (some #(and (= person-id (get-in % [:daily-plan/person :db/id]))
                                                 (not (:daily-plan/subst-req-on %)))
                                           plans)
                                   out
                                   (assoc out date (remove #(:daily-plan/att-cancelled? %) plans))))
                               (sorted-map)))
     :can-subst? (and (not-empty substable-dps)
                      (->> all-plans
                           (filter #(and (= person-id (get-in % [:daily-plan/person :db/id]))
                                         (:daily-plan/subst-req-on %)))
                           count
                           (> 2)))}))

(defn request-substitution [conn user-id child-id req-date]
  (let [db (d/db conn)
        {:keys [substable-dps dp-gap-days can-subst?]} (find-person-substs db child-id)
        db-id (d/tempid :db.part/user)]
    (when (and can-subst? (contains? dp-gap-days req-date))
      (service/transact conn user-id [{:db/id db-id
                                       :daily-plan/person child-id
                                       :daily-plan/date req-date
                                       :daily-plan/lunch-req 1 ;;TODO
                                       :daily-plan/child-att 1 ;;TODO
                                       :daily-plan/subst-req-on (Date.)}
                                      [:db/add (-> substable-dps first :db/id) :daily-plan/substituted-by db-id]]))))