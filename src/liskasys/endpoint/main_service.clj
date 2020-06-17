(ns liskasys.endpoint.main-service
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clojure.set :as set]
            [clojure.string :as str]
            [crypto.password.scrypt :as scrypt]
            [datomic.api :as d]
            [environ.core :refer [env]]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.db :as db]
            [liskasys.db-queries :as db-queries]
            [liskasys.emailing :as emailing]
            [liskasys.service :as service]
            [taoensso.timbre :as timbre])
  (:import java.util.Date))

(defn check-person-password [{:keys [:db/id :person/passwd :person/_parent]} pwd]
  (or (and (:dev env) (str/blank? pwd))
      (if passwd
        (scrypt/check pwd passwd)
        (->> _parent
             (filter #(= pwd (str (:person/vs %))))
             (not-empty)))))

(defn login [db username pwd]
  (let [person (db-queries/find-person-by-email db username)]
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
    (db/transact-entity conn user-id {:db/id (:db/id person)
                                      :person/passwd (scrypt/encrypt new-pwd)})))

(defn find-last-lunch-menu [db history]
  (let [id-dates (->> (d/q '[:find ?e ?date :where [?e :lunch-menu/from ?date]] db)
                      (sort-by second)
                      (reverse))
        last-date (second (first id-dates))
        new-history (min (dec (count id-dates))
                         (or history
                             (->> id-dates
                                  (map second)
                                  (take-while #(t/before? (t/now)
                                                          (t/minus (tc/from-date %)
                                                                   (t/days 2) ;;don't show new before Saturday
                                                                   )))
                                  (count))))
        last-two (->> id-dates
                      (drop new-history)
                      (take 2)
                      (map first)
                      (d/pull-many db '[*]))]
    {:lunch-menu (first last-two)
     :previous? (boolean (second last-two))
     :history new-history}))

(defn- make-can-cancel-lunch?-fn [db]
  (let [max-lunch-order-date (tc/to-local-date (db-queries/find-max-lunch-order-date db))]
    (fn [date]
      (t/after? (tc/to-local-date date) max-lunch-order-date))))

(defn transact-cancellations [conn user-id child-id cancel-dates uncancel-dates excuses-by-date]
  (let [db (d/db conn)
        can-cancel-lunch?-fn (make-can-cancel-lunch?-fn db)
        can-cancel-today?-fn (db-queries/make-can-cancel-today?-fn db)
        dates-union (set/union cancel-dates uncancel-dates (set (keys excuses-by-date)))]
    (if-not  (contains? (->>
                         (db-queries/find-active-children-by-person-id db user-id true)
                         (map :db/id)
                         (set))
                        child-id)
      (timbre/error "User" user-id "attempts to change cancellations of child" child-id)
      (->> (d/q '[:find [(pull ?e [*]) ...]
                  :in $ ?person [?date ...]
                  :where
                  [?e :daily-plan/person ?person]
                  [?e :daily-plan/date ?date]]
                db child-id dates-union)
           (mapcat (fn [{:keys [:db/id] :daily-plan/keys [date lunch-req lunch-ord substituted-by subst-req-on excuse]}]
                     (cond
                       (and (not (can-cancel-lunch?-fn date))
                            (not (can-cancel-today?-fn date)))
                       []
                       (contains? cancel-dates date)
                       (cond-> [[:db/add id :daily-plan/att-cancelled? true]]
                         (contains? excuses-by-date date)
                         (conj [:db/add id :daily-plan/excuse (get excuses-by-date date)])
                         (and (some? lunch-req) (pos? lunch-req) (can-cancel-lunch?-fn date))
                         (conj [:db/add id :daily-plan/lunch-cancelled? true])
                         (and subst-req-on (can-cancel-lunch?-fn date))
                         (conj [:db.fn/retractEntity id]))
                       (contains? uncancel-dates date)
                       (cond-> [[:db/retract id :daily-plan/att-cancelled? true]
                                [:db/retract id :daily-plan/lunch-cancelled? true]]
                         excuse
                         (conj [:db/retract id :daily-plan/excuse excuse])
                         substituted-by
                         (conj [:db.fn/retractEntity (:db/id substituted-by)]))
                       :else
                       [[:db/add id :daily-plan/excuse (get excuses-by-date date)]])))
           (db/transact conn user-id)
           :tx-data
           count))))

(defn request-substitution [conn user-id child-id req-date]
  (let [db (d/db conn)
        {:keys [person groups substable-dps dp-gap-days can-subst?]} (db-queries/find-person-substs db child-id)
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
        plans (get dp-gap-days req-date)
        {:keys [group group-plans]} (cljc.util/select-group-for-subst plans person groups)]
    (when (and can-subst? (seq plans))
      (db/transact conn user-id [(cond-> {:db/id db-id
                                          :daily-plan/person child-id
                                          :daily-plan/group (:db/id group)
                                          :daily-plan/date req-date
                                          :daily-plan/child-att (:daily-plan/child-att substituted)
                                          :daily-plan/subst-req-on (Date.)}
                                   lunch-req?
                                   (assoc :daily-plan/lunch-req 1))
                                 [:db.fn/cas (:db/id substituted) :daily-plan/substituted-by nil db-id]]))))

(defn- pattern-map [pattern]
  (->> pattern
       (map-indexed vector)
       (keep (fn [[idx ch]]
               [(inc idx) (- (int ch) (int \0))]))
       (into {})))

(defn- calculate-att-price [price-list months-count days-per-week half-days-per-week]
  (let [months-count (max 0 months-count)]
    (+ (* months-count (get price-list (keyword "price-list" (str "days-" days-per-week)) 0))
       (* months-count half-days-per-week (:price-list/half-day price-list)))))

(defn- generate-daily-plans
  [{:person/keys [lunch-pattern att-pattern start-date group] person-id :db/id :as person} dates]
  (let [lunch-map (pattern-map lunch-pattern)
        att-map (pattern-map att-pattern)
        group-map (pattern-map (:group/pattern group))
        start-date (tc/to-local-date (or start-date #inst "2000"))]
    (->> dates
         (drop-while #(t/before? (tc/to-local-date %) start-date))
         (keep (fn [ld]
                 (let [day-of-week (t/day-of-week ld)
                       lunch-req (get lunch-map day-of-week 0)
                       child-att (get att-map day-of-week 0)]
                   (when (or (pos? lunch-req) (pos? child-att))
                     (let [group-id (:db/id (if (= 1 (get group-map day-of-week 1))
                                              group
                                              (or (:group/subst-group group) group)))]
                       (cond-> {:daily-plan/person person-id
                                :daily-plan/date (tc/to-date ld)}
                         group-id
                         (assoc :daily-plan/group group-id)
                         (pos? lunch-req)
                         (assoc :daily-plan/lunch-req lunch-req)
                         (pos? child-att)
                         (assoc :daily-plan/child-att child-att))))))))))

(defn- generate-person-bills-tx [db period-id]
  (let [{:billing-period/keys [from-yyyymm to-yyyymm] :as billing-period} (db/find-by-id db period-id)
        [period-start-ld period-end-ld] (cljc.util/period-start-end-lds billing-period)
        person-id--2nd-previous-dps (some->> (db-queries/find-school-year-previous-periods db (tc/to-date period-start-ld))
                                             (second)
                                             :db/id
                                             (db-queries/find-period-daily-plans db)
                                             (group-by #(get-in % [:daily-plan/person :db/id]))
                                             (into {}))
        published-status (d/entid db :person-bill.status/published)
        person-id--bill (atom (->> (db/find-where db {:person-bill/period period-id})
                                   (map #(vector (get-in % [:person-bill/person :db/id]) %))
                                   (into {})))
        price-lists (db-queries/find-price-lists db)
        second-month-start (t/plus period-start-ld (t/months 1))
        day-after-last-order-ld (t/plus (tc/to-local-date (db-queries/find-max-lunch-order-date db))
                                        (t/days 1))
        dates (cljc.util/period-local-dates (db-queries/make-holiday?-fn db)
                                            (if (t/after? period-start-ld day-after-last-order-ld)
                                              period-start-ld
                                              day-after-last-order-ld)
                                            period-end-ld)
        {:config/keys [att-payment-months]} (d/pull db '[*] :liskasys/config)
        out (->>
             (for [person (->> (db/find-where db {:person/active? true})
                               (remove #(or (cljc.util/zero-patterns? %)
                                            (some-> (:person/start-date %) (tc/to-local-date) (t/after? period-end-ld)))))
                   :let [daily-plans (generate-daily-plans person dates)
                         previous-dps (get person-id--2nd-previous-dps (:db/id person))
                         billing-period-months (inc (- (:billing-period/to-yyyymm billing-period)
                                                       (:billing-period/from-yyyymm billing-period)))
                         att-payment-months (or (:person/att-payment-months person) att-payment-months 1)
                         month (rem from-yyyymm 100)
                         months-count (cond
                                        (and (or (= att-payment-months 10) (= att-payment-months 12)) ;; yearly payments
                                             (= 9 month) ;; in September
                                             )
                                        att-payment-months
                                        (and (= att-payment-months 6) ;; half year payment including summer holidays
                                             (contains? #{9 3} month) ;; in September and March
                                             )
                                        att-payment-months
                                        (and (= att-payment-months 5) ;; half year payment without summer holidays
                                             (contains? #{9 3} month) ;; in September and March
                                             )
                                        (if (= month 9) 6 4)
                                        (and (= att-payment-months 4) ;; quoterly payment in September, December, March, June including summer holidays
                                             (contains? #{9 12 3 6} month))
                                        3
                                        (and (= att-payment-months 3) ;; quoterly payment in September, December or March without summer holidays
                                             (contains? #{9 12 3 6} month))
                                        (if (= month 6) 1 3)
                                        (= att-payment-months 1)
                                        billing-period-months
                                        :else
                                        0)
                         months-count (cond-> months-count
                                        (some-> (:person/start-date person) (tc/to-local-date) (t/before? second-month-start) (not))
                                        (dec)
                                        (some :daily-plan/refund? previous-dps)
                                        (dec)
                                        (and (seq previous-dps) (every? :daily-plan/refund? previous-dps))
                                        (dec))
                         price-list (get price-lists (get-in person [:person/price-list :db/id]))
                         att-price (calculate-att-price price-list
                                                        months-count
                                                        (count (->> person :person/att-pattern pattern-map vals (filter (partial = 1))))
                                                        (count (->> person :person/att-pattern pattern-map vals (filter (partial = 2)))))
                         lunch-count-next (->> daily-plans
                                               (keep :daily-plan/lunch-req)
                                               (reduce + 0))
                         existing-bill (get @person-id--bill (:db/id person))
                         lunch-price (* (cljc.util/person-lunch-price person price-list)
                                        (+ lunch-count-next
                                           (db-queries/find-lunch-count-planned db (:db/id person)
                                                                                (tc/to-date day-after-last-order-ld))))

                         lunch-total (- lunch-price
                                        (or (:person/lunch-fund person) 0))
                         total (+ att-price (max lunch-total 0))]]
               (do
                 (when existing-bill
                   (swap! person-id--bill dissoc (:db/id person)))
                 (when (or (not existing-bill)
                           (< (get-in existing-bill [:person-bill/status :db/id]) published-status))
                   (merge (or existing-bill {:db/id (d/tempid :db.part/user)
                                             :person-bill/person (:db/id person)
                                             :person-bill/period period-id
                                             :person-bill/status :person-bill.status/new})
                          {:person-bill/lunch-count lunch-count-next
                           :person-bill/att-price att-price
                           :person-bill/total (if (< total 0) 0 total)}))))
             (filterv some?))]
    (->> (vals @person-id--bill)
         (mapcat #(service/retract-person-bill-tx db (:db/id %) true))
         (into out))))

(defn- transact-period-person-bills [conn user-id period-id tx-data]
  (let [tx-result (db/transact conn user-id tx-data)]
    (db/find-by-type (:db-after tx-result) :person-bill {:person-bill/period period-id})))

(defn re-generate-person-bills [conn user-id period-id]
  (->> (generate-person-bills-tx (d/db conn) period-id)
       (transact-period-person-bills conn user-id period-id)))

(defn publish-all-bills [conn user-id period-id]
  (let [db (d/db conn)
        ;;billing-period (db/find-by-id db period-id)
        send-bill-published-mail (emailing/make-bill-published-sender db)
        new-bill-ids (d/q '[:find [?e ...]
                            :in $ ?period-id
                            :where
                            [?e :person-bill/period ?period-id]
                            [?e :person-bill/status :person-bill.status/new]]
                          db period-id)]
    (doseq [id new-bill-ids]
      (let [bill (first (db/find-by-type db :person-bill {:db/id id}))]
        (send-bill-published-mail bill)))
    (->> new-bill-ids
         (mapv (fn [id]
                 [:db/add id :person-bill/status :person-bill.status/published]))
         (transact-period-person-bills conn user-id period-id))))

(defn set-bill-as-paid [conn user-id bill-id]
  (let [db (d/db conn)
        [[period-id bill]]
        (d/q '[:find ?period-id
               (pull ?e [* {:person-bill/person
                            [:db/id :person/lunch-pattern :person/att-pattern
                             :person/lunch-fund :person/start-date :person/group]}])
               :in $ ?e
               :where
               [?e :person-bill/period ?period-id]
               [?e :person-bill/status :person-bill.status/published]]
             db bill-id)]
    (when bill
      (let [order-date (tc/to-local-date (db-queries/find-max-lunch-order-date db))
            dates (->> period-id
                       (db/find-by-id db)
                       (cljc.util/period-start-end-lds)
                       (apply cljc.util/period-local-dates (db-queries/make-holiday?-fn db))
                       (drop-while #(not (t/after? (tc/to-local-date %) order-date))))
            bill (db-queries/merge-person-bill-facts db bill) ;; ensure data as of bill generation
            {:person-bill/keys [person total att-price]} bill
            tx-result (->> (generate-daily-plans person dates)
                           (map #(-> % (assoc :db/id (d/tempid :db.part/user)
                                              :daily-plan/bill bill-id)))
                           (into [[:db/add bill-id :person-bill/status :person-bill.status/paid]
                                  [:db.fn/cas (:db/id person) :person/lunch-fund
                                   (:person/lunch-fund person) (+ (or (:person/lunch-fund person) 0)
                                                                  (- total att-price))]])
                           (db/transact conn user-id))]
        (db/find-by-type (:db-after tx-result) :person-bill {:db/id bill-id})))))

