(ns liskasys.db-queries
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.predicates :as tp]
            [datomic.api :as d]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.db :as db]
            [liskasys.easter :as easter])
  (:import java.util.Date))

(defn find-max-lunch-order-date [db]
  (or (d/q '[:find (max ?date) .
             :where [_ :lunch-order/date ?date]]
           db)
      #inst "2000"))

(defn find-price-list [db]
  (first (db/find-where db {:price-list/days-1 nil})))

(defn find-persons-with-role [db role]
  (when (not-empty role)
    (d/q '[:find [(pull ?e [*]) ...]
           :in $ ?role
           :where
           [?e :person/roles ?roles]
           [(clojure.string/index-of ?roles ?role)]]
         db
         role)))

(defn find-auto-sender-email [db]
  (or (not-empty (:person/email (first (find-persons-with-role db "koordinátor"))))
      (not-empty (:config/automat-email (d/pull db '[*] :liskasys/config)))))

(defn find-current-period [db]
  (d/q '[:find (pull ?e [*]) .
         :in $ ?today
         :where
         [?e :billing-period/from-yyyymm ?from]
         [(<= ?from ?today)]
         [?e :billing-period/to-yyyymm ?to]
         [(<= ?today ?to)]]
       db (cljc.util/date-yyyymm (Date.))))

(defn find-max-person-paid-period-date [db person-id]
  (let [to-yyyymm (some->> person-id
                           (d/q '[:find (max ?yyyymm) .
                                  :in $ ?person
                                  :where
                                  [?e :person-bill/person ?person]
                                  [?e :person-bill/status :person-bill.status/paid]
                                  [?e :person-bill/period ?p]
                                  [?p :billing-period/to-yyyymm ?yyyymm]]
                                db))]
    (if-not to-yyyymm
      #inst "2000"
      (-> (t/local-date (quot to-yyyymm 100) (rem to-yyyymm 100) 1)
          (t/plus (t/months 1))
          (t/minus (t/days 1))
          (tc/to-date)))))

(defn find-person-daily-plans [db person-id date-from date-to]
  (when (and person-id date-from date-to)
    (->>
     (d/q '[:find [(pull ?e [* {:daily-plan/substituted-by [:db/id :daily-plan/date]}]) ...]
            :in $ ?person ?date-from ?date-to
            :where
            [?e :daily-plan/person ?person]
            [?e :daily-plan/date ?date]
            [(<= ?date-from ?date)]
            [(<= ?date ?date-to)]]
          db person-id date-from date-to)
     (sort-by :daily-plan/date))))

(defn find-att-daily-plans [db date-from date-to]
  (when (and date-from date-to)
    (d/q '[:find [(pull ?e [* {:daily-plan/person [:db/id :person/group]}]) ...]
           :in $ ?date-from ?date-to
           :where
           (or [?e :daily-plan/child-att 1]
               [?e :daily-plan/child-att 2])
           [?e :daily-plan/date ?date]
           [(<= ?date-from ?date)]
           [(<= ?date ?date-to)]]
         db date-from date-to)))

(defn find-next-school-day-date [db from-date]
  (d/q '[:find (min ?date) .
         :in $ ?from-date
         :where
         [_ :daily-plan/date ?date]
         [(< ?from-date ?date)]]
       db from-date))

(defn find-lunch-types [db]
  (db/find-where db {:lunch-type/label nil}))

(defn merge-person-bill-facts [db {:person-bill/keys [lunch-count total att-price status] :as person-bill}]
  (let [tx (apply max (d/q '[:find [?tx ...]
                             :in $ ?e
                             :where
                             [?e :person-bill/total _ ?tx]]
                           db (:db/id person-bill)))
        as-of-db (d/as-of db tx)
        person (d/pull as-of-db
                       [:person/var-symbol :person/att-pattern :person/lunch-pattern :person/firstname :person/lastname :person/email :person/child?
                        {:person/parent [:person/email]}]
                       (get-in person-bill [:person-bill/person :db/id]))
        lunch-price (cljc.util/person-lunch-price person (find-price-list as-of-db))
        total-lunch-price (* lunch-price lunch-count)
        paid-status (d/entid db :person-bill.status/paid)]
    (-> person-bill
        (update :person-bill/person merge person)
        (merge {:-lunch-price lunch-price
                :-total-lunch-price total-lunch-price
                :-from-previous (- total (+ att-price total-lunch-price))
                :-paid? (= (:db/id status) paid-status)}))))

(defmethod db/find-by-type :person-bill [db ent-type where-m]
  (->> (db/find-by-type-default db ent-type where-m '[* {:person-bill/period [*]
                                                         :person-bill/status [:db/id :db/ident]}])
       (map (partial merge-person-bill-facts db))))

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
       (map (partial merge-person-bill-facts db))
       (sort-by (comp :db/id :person-bill/period))
       reverse))

(defmethod db/find-by-type :daily-plan [db ent-type where-m]
  (db/find-by-type-default db ent-type where-m '[* {:daily-plan/_substituted-by [:db/id]}]))

(defn find-person-daily-plans-with-lunches [db date]
  (d/q '[:find [(pull ?e [:db/id :daily-plan/lunch-req
                          {:daily-plan/person [:db/id :person/lunch-type :person/lunch-fund :person/child?
                                               {:person/group [*]}]}]) ...]
         :in $ ?date
         :where
         [?e :daily-plan/date ?date]
         [?e :daily-plan/lunch-req ?lunch-req]
         (not [?e :daily-plan/lunch-cancelled? true])
         [(pos? ?lunch-req)]]
       db
       date))

(defn- *bank-holiday? [{:bank-holiday/keys [day month easter-delta]} local-date]
  (or (and (= (t/day local-date) day)
           (= (t/month local-date) month))
      (and (some? easter-delta)
           (t/equal? local-date
                     (t/plus (easter/easter-monday-for-year-ld (t/year local-date))
                             (t/days easter-delta))))))

(defn- bank-holiday? [bank-holidays local-date]
  (seq (filter #(*bank-holiday? % local-date) bank-holidays)))

(defn- *school-holiday? [{:school-holiday/keys [from to every-year?]} local-date]
  (let [from (tc/from-date from)
        dt (tc/to-date-time local-date)
        from (if-not every-year?
               from
               (let [from (t/date-time (t/year dt) (t/month from) (t/day from))]
                 (if-not (t/after? from dt)
                   from
                   (t/minus from (t/years 1)))))
        to (tc/from-date to)
        to (if-not every-year?
             to
             (let [to (t/date-time (t/year from) (t/month to) (t/day to))]
               (if-not (t/before? to from)
                 to
                 (t/plus to (t/years 1)))))]
    (t/within? from to dt)))

(defn- school-holiday? [school-holidays local-date]
  (seq (filter #(*school-holiday? % local-date) school-holidays)))

(defn make-holiday?-fn [db]
  (let [bank-holidays (db/find-where db {:bank-holiday/label nil})
        school-holidays (db/find-where db {:school-holiday/label nil})]
    (fn [ld]
      (or (bank-holiday? bank-holidays ld)
          (school-holiday? school-holidays ld)))))

(defn- days-to-go [db excl-from incl-to]
  (let [{:config/keys [order-workdays-only?]} (d/pull db '[*] :liskasys/config)]
    (cljc.util/period-local-dates (if order-workdays-only?
                          (some-fn tp/weekend?
                                   (partial bank-holiday? (db/find-where db {:bank-holiday/label nil})))
                          (constantly false))
                        (t/plus (time/to-ld excl-from) (t/days 1))
                        (t/plus (time/to-ld incl-to) (t/days 1)))))

(defn find-next-lunch-order-date
  "This returns the day for which the lunches should be ordered as late as possible according to the flag order-workdays-only?"
  ([db]
   (find-next-lunch-order-date db (time/today)))
  ([db at-date]
   (when-let [next-school-day-date (find-next-school-day-date db at-date)]
     (when (= (count (days-to-go db
                                 (second (sort [(find-max-lunch-order-date db) at-date])) ;; choose higher date
                                 next-school-day-date))
              1)
       next-school-day-date))))
