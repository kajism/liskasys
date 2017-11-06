(ns liskasys.endpoint.main-service
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clojure.set :as set]
            [clojure.string :as str]
            [crypto.password.scrypt :as scrypt]
            [datomic.api :as d]
            [environ.core :refer [env]]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.service :as service]
            [taoensso.timbre :as timbre])
  (:import java.io.FileInputStream
           java.util.Date))

(defn check-person-password [{:keys [:db/id :person/passwd :person/_parent]} pwd]
  (or (and (:dev env) (str/blank? pwd))
      (if passwd
        (scrypt/check pwd passwd)
        (->> _parent
             (filter #(= pwd (str (:person/var-symbol %))))
             not-empty))))

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
  (let [id-dates (->> (d/q '[:find ?e ?date :where [?e :lunch-menu/from ?date]] db)
                      (sort-by second)
                      (reverse))
        last-date (second (first id-dates))
        new-history (min (dec (count id-dates))
                         (or history
                             (if (and last-date
                                      (t/before? (t/now)
                                                 (t/minus (tc/from-date last-date)
                                                          (t/days 2)))) ;;don't show new before Saturday
                               1
                               0)))
        last-two (->> id-dates
                      (drop new-history)
                      (take 2)
                      (map first)
                      (d/pull-many db '[*]))]
    {:lunch-menu (first last-two)
     :previous? (boolean (second last-two))
     :history new-history}))

(defn find-active-children-by-person-id [db parent-id]
  (->> (d/q '[:find [(pull ?e [*]) ...]
              :in $ ?parent
              :where
              [?e :person/parent ?parent]
              [?e :person/active? true]]
            db parent-id)
       (service/sort-by-locale cljc-util/person-fullname)))

(defn- make-can-cancel-lunch?-fn [db]
  (let [max-lunch-order-date (-> (d/q '[:find (max ?date) .
                                        :where [_ :lunch-order/date ?date]]
                                      db)
                                 tc/to-local-date)]
    (fn [date]
      (t/after? (-> date tc/to-local-date) max-lunch-order-date))))

(defn transact-cancellations [conn user-id child-id cancel-dates uncancel-dates excuses-by-date]
  (let [db (d/db conn)
        can-cancel-lunch?-fn (make-can-cancel-lunch?-fn db)]
    (if-not  (contains? (->> user-id
                             (find-active-children-by-person-id db)
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
           (mapcat (fn [{:keys [:db/id :daily-plan/date :daily-plan/lunch-req :daily-plan/substituted-by :daily-plan/subst-req-on :daily-plan/excuse]}]
                     (if (contains? cancel-dates date)
                       (cond-> [[:db/add id :daily-plan/att-cancelled? true]]
                         (contains? excuses-by-date date)
                         (conj [:db/add id :daily-plan/excuse (get excuses-by-date date)])
                         (and (some? lunch-req) (pos? lunch-req) (can-cancel-lunch?-fn date))
                         (conj [:db/add id :daily-plan/lunch-cancelled? true])
                         subst-req-on
                         (conj [:db.fn/retractEntity id]))
                       (cond-> [[:db/retract id :daily-plan/att-cancelled? true]
                                [:db/retract id :daily-plan/lunch-cancelled? true]]
                         excuse
                         (conj [:db/retract id :daily-plan/excuse excuse])
                         substituted-by
                         (conj [:db.fn/retractEntity (:db/id substituted-by)])))))
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

#_(defn find-next-weeks-person-daily-plans [db person-id weeks]
  (let [to-date (-> (t/today)
                    (t/plus (t/weeks weeks))
                    tc/to-date)]
    (->> (service/find-person-daily-plans db person-id (time/tomorrow) to-date)
         (sort-by :daily-plan/date))))

(defn find-next-person-daily-plans [db person-id]
  (let [date-from (time/time-plus (service/find-max-lunch-order-date db) (t/days 1))
        date-to (service/find-max-person-paid-period-date db person-id)]
    (->> (service/find-person-daily-plans db person-id date-from date-to)
         (sort-by :daily-plan/date))))

(defn find-person-substs [db person-id]
  (let [person (service/find-by-id db person-id)
        group (service/find-by-id db (get-in person [:person/group :db/id]))
        date-from (some-> (or (first (service/find-previous-periods db (Date.)))
                              (service/find-current-period db))
                          (cljc-util/period-start-end)
                          (first)
                          (tc/to-date))
        date-to (service/find-max-person-paid-period-date db person-id)
        substable-dps (->> (service/find-person-daily-plans db person-id date-from date-to)
                           (filter #(and (:daily-plan/att-cancelled? %)
                                         (not (:daily-plan/substituted-by %))
                                         (not (:daily-plan/subst-req-on %))
                                         (not (:daily-plan/refund? %))))
                           (sort-by :daily-plan/date))
        all-plans (service/find-att-daily-plans db
                                                (-> (service/find-max-lunch-order-date db)
                                                    (tc/to-local-date)
                                                    (t/plus (t/days 1))
                                                    (tc/to-date))
                                                date-to)]
    (timbre/debug "finding-person-substs from" date-from "to" date-to)
    {:group group
     :substable-dps substable-dps
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
                           (count)
                           (> 2)))}))

(defn request-substitution [conn user-id child-id req-date]
  (let [db (d/db conn)
        {:keys [substable-dps dp-gap-days can-subst?]} (find-person-substs db child-id)
        db-id (d/tempid :db.part/user)
        substituted (or (->> substable-dps
                             (filter #(= 1 (:daily-plan/child-att %)))
                             (first)) ;;full-day attendance preference
                        (->> substable-dps
                             (filter #(= 2 (:daily-plan/child-att %)))
                             (first)))
        lunch-req? (some #(and (some-> % :daily-plan/lunch-req pos?)
                               (= (:daily-plan/child-att %) (:daily-plan/child-att substituted)))
                         substable-dps) ;; if have lunch some day with the same att type
        ]
    (when (and can-subst? (contains? dp-gap-days req-date))
      (service/transact conn user-id [(cond-> {:db/id db-id
                                               :daily-plan/person child-id
                                               :daily-plan/date req-date
                                               :daily-plan/child-att (:daily-plan/child-att substituted)
                                               :daily-plan/subst-req-on (Date.)}
                                        lunch-req?
                                        (assoc :daily-plan/lunch-req 1))
                                      [:db.fn/cas (:db/id substituted) :daily-plan/substituted-by nil db-id]]))))

(defn file-to-byte-array [f]
  (let [ary (byte-array (.length f))
        is (FileInputStream. f)]
    (.read is ary)
    (.close is)
    ary))
