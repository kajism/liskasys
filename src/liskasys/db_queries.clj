(ns liskasys.db-queries
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.predicates :as tp]
            [clojure.string :as str]
            [datomic.api :as d]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.db :as db]
            [liskasys.easter :as easter]
            [taoensso.timbre :as timbre])
  (:import java.util.Date))

(defmethod db/find-by-type :person [db ent-type where-m]
  (->> (db/find-by-type-default db ent-type where-m)
       (map #(dissoc % :person/passwd))))

(defmethod db/find-by-type :daily-plan [db ent-type where-m]
  (db/find-by-type-default db ent-type where-m '[* {:daily-plan/_substituted-by [:db/id]}]))

(declare merge-person-bill-facts)
(defmethod db/find-by-type :person-bill [db ent-type where-m]
  (->> (db/find-by-type-default db ent-type where-m '[* {:person-bill/period [*]
                                                         :person-bill/status [:db/id :db/ident]}])
       (map (partial merge-person-bill-facts db))))

(defn find-max-lunch-order-date [db]
  (or (d/q '[:find (max ?date) .
             :where [_ :lunch-order/date ?date]]
           db)
      #inst "2000"))

(defn find-price-lists [db]
  (->> (db/find-by-type db :price-list {})
       (map (juxt :db/id identity))
       (into {})))

(defn find-price-list [db person-id]
  (or
   (when person-id
     (d/q '[:find (pull ?e [*]) .
            :in $ ?person-id
            :where
            [?person-id :person/price-list ?e]]
          db person-id))
   (first (vals (find-price-lists db))) ;; for history before :person/price-list
   ))

(defn find-person-by-email
  "Used for user login."
  [db email]
  (let [p (d/q '[:find (pull ?e [* {:person/_parent [:db/id :person/vs :person/active?]}]) . ;; :person/vs is necessary for passwd check!
                 :in $ ?lower-email1
                 :where
                 [?e :person/email ?email]
                 [(clojure.string/lower-case ?email) ?lower-email2]
                 [(= ?lower-email1 ?lower-email2)]
                 [?e :person/active? true]]
               db (-> email str/trim str/lower-case))]
    (cond-> p
      (not (cljc.util/zero-patterns? p))
      (update :person/_parent conj (select-keys p [:db/id :person/active?])))))

(defn find-persons-with-role [db role]
  (when-not (str/blank? role)
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

(defn find-att-daily-plans
  "Finds all daily plans of a given date on in a range of dates from (inclusive) to (inclusive)"
  ([db date]
   (find-att-daily-plans db date date))
  ([db date-from date-to]
   (when (and date-from date-to)
     (d/q '[:find [(pull ?e [* {:daily-plan/person [:db/id :person/group]}]) ...]
            :in $ ?date-from ?date-to
            :where
            (or [?e :daily-plan/child-att 1]
                [?e :daily-plan/child-att 2])
            [?e :daily-plan/date ?date]
            [(<= ?date-from ?date)]
            [(<= ?date ?date-to)]]
          db date-from date-to))))

(defn find-next-school-day-date [db from-date]
  (d/q '[:find (min ?date) .
         :in $ ?from-date
         :where
         [_ :daily-plan/date ?date]
         [(< ?from-date ?date)]]
       db from-date))

(defn merge-person-bill-facts [db {:person-bill/keys [lunch-count total att-price status] :as person-bill}]
  (let [tx (apply max (d/q '[:find [?tx ...]
                             :in $ ?e
                             :where
                             [?e :person-bill/total _ ?tx]]
                           db (:db/id person-bill)))
        as-of-db (d/as-of db tx)
        person-id (get-in person-bill [:person-bill/person :db/id])
        person (d/pull as-of-db
                       [:person/vs :person/var-symbol :person/att-pattern :person/lunch-pattern :person/firstname :person/lastname :person/email :person/child?
                        {:person/group [:db/id :group/pattern :group/subst-group]}
                        {:person/parent [:person/email]}]
                       person-id)
        price-list (find-price-list as-of-db person-id)
        person (cond-> person
                 (and (nil? (:person/vs person))
                      (some? (:person/var-symbol person)))
                 (assoc :person/vs (str (:person/var-symbol person)))
                 :always
                 (assoc :person/price-list price-list))
        lunch-price (cljc.util/person-lunch-price person price-list)
        total-lunch-price (* lunch-price lunch-count)
        paid-status (d/entid db :person-bill.status/paid)]
    (-> person-bill
        (update :person-bill/person merge person)
        (merge {:-lunch-price lunch-price
                :-total-lunch-price total-lunch-price
                :-from-previous (- total (+ att-price total-lunch-price))
                :-paid? (or (= (:db/id status) paid-status)
                            (= (:db/ident status) :person-bill.status/paid))}))))

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

(defn find-person-daily-plans-with-lunches [db date]
  (d/q '[:find [(pull ?e [:db/id :daily-plan/lunch-req
                          {:daily-plan/person [:db/id :person/lunch-type :person/lunch-fund :person/child?
                                               :person/child-portion? :person/price-list
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

(defn make-holiday?-fn
  ([db]
   (make-holiday?-fn db false))
  ([db higher-school?]
   (let [bank-holidays (db/find-by-type db :bank-holiday {})
         school-holidays (cond->> (db/find-by-type db :school-holiday {})
                           (not higher-school?)
                           (remove :school-holiday/higher-schools-only?))]
     (fn [ld]
       (or (bank-holiday? bank-holidays ld)
           (school-holiday? school-holidays ld))))))

(defn- days-to-go [db excl-from incl-to]
  (let [{:config/keys [order-workdays-only?]} (d/pull db '[*] :liskasys/config)]
    (cljc.util/period-local-dates (if order-workdays-only?
                                    (some-fn tp/weekend?
                                             (partial bank-holiday? (db/find-by-type db :bank-holiday {})))
                                    (constantly false))
                                  (t/plus (time/to-ld excl-from) (t/days 1))
                                  (t/plus (time/to-ld incl-to) (t/days 1)))))

(defn find-next-lunch-order-date
  "This returns the day for which the lunches should be ordered as late as possible according to the flag order-workdays-only?"
  ([db]
   (find-next-lunch-order-date db (time/today)))
  ([db at-date]
   (when-let [next-school-day-date (find-next-school-day-date db at-date)]
     (when (and
            (= 1 (count (days-to-go db at-date next-school-day-date)))
            (> (.getTime next-school-day-date) (.getTime (find-max-lunch-order-date db))))
       next-school-day-date))))

(defn find-active-children-by-person-id
  "Returns children plus possibly person itself if has lunch pattern to allow its cancellations."
  [db parent-id include-parent-with-lunches?]
  (let [out (d/q '[:find [(pull ?e [* {:person/group [*]}]) ...]
                   :in $ ?parent
                   :where
                   [?e :person/parent ?parent]
                   [?e :person/active? true]]
                 db parent-id)
        p (d/pull db '[*] parent-id)]
    (cond-> out
      (and include-parent-with-lunches?
           (not (cljc.util/zero-patterns? p)))
      (conj p))))

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

(defn- HHmm--int [s]
  (-> s
      (str/replace ":" "")
      (cljc.util/parse-int)))

(defn make-can-cancel-today?-fn [db]
  (let [{:config/keys [cancel-time can-cancel-after-lunch-order?]} (d/pull db '[*] :liskasys/config)]
    (fn [date]
      (let [now (Date.)]
        (and can-cancel-after-lunch-order?
             (or (< (.getTime now) (.getTime date))
                 (and (= (time/to-format now time/ddMMyyyy)
                         (time/to-format date time/ddMMyyyy))
                      (< (HHmm--int (time/to-format now time/HHmm))
                         (HHmm--int cancel-time)))))))))

(defn find-next-person-daily-plans
  "Select ongoing DPs to be offered for cancellation. Already substituted DPs are excluded."
  [db person-id]
  (let [date-from (find-max-lunch-order-date db)
        date-to (find-max-person-paid-period-date db person-id)
        can-cancel-today?-fn (make-can-cancel-today?-fn db)
        out (find-person-daily-plans db person-id date-from date-to)]
    (cond->> out
      (and (= date-from (:daily-plan/date (first out)))
           (or (not (can-cancel-today?-fn date-from))
               ;; aby se nemohli znovu prihlasit ti, co vyzaduji obed, ale nebyl objednan:
               (and (pos-int? (:daily-plan/lunch-req (first out)))
                    (not (pos-int? (:daily-plan/lunch-ord (first out)))))))
      (drop 1)
      true
      (remove (let [date-from-ms (some-> date-from (.getTime))]
                #(some-> % :daily-plan/substituted-by :daily-plan/date (.getTime) (<= date-from-ms)))))))

(defn find-school-year-previous-periods [db before-date]
  (let [to-yyyymm (cljc.util/date-yyyymm before-date)
        from-yyyymm (cljc.util/last-september to-yyyymm)]
    (->> (d/q '[:find [(pull ?e [*]) ...]
                :in $ ?start ?end
                :where
                [?e :billing-period/from-yyyymm ?from]
                [(>= ?from ?start)]
                [?e :billing-period/to-yyyymm ?to]
                [(< ?to ?end)]]
              db from-yyyymm to-yyyymm)
         (sort-by :billing-period/to-yyyymm)
         (reverse))))

(defn find-person-substs
  ([db person-id]
   (find-person-substs db person-id (time/today)))
  ([db person-id at-date]
   (let [{:config/keys [max-subst-periods future-subst?]} (d/pull db '[*] :liskasys/config)
         date-from (some-> (or (last (take max-subst-periods (find-school-year-previous-periods db at-date)))
                               (find-current-period db)
                               {:billing-period/from-yyyymm 200001
                                :billing-period/to-yyyymm 200001})
                           (cljc.util/period-start-end-lds)
                           (first)
                           (tc/to-date))
         max-paid-date (find-max-person-paid-period-date db person-id)
         substable-dps (->> (find-person-daily-plans db person-id date-from (if future-subst?
                                                                              max-paid-date
                                                                              at-date))
                            (filter #(and (:daily-plan/att-cancelled? %)
                                          (not (:daily-plan/substituted-by %))
                                          (not (:daily-plan/subst-req-on %))
                                          (not (:daily-plan/refund? %)))))
         all-next-dps (find-att-daily-plans db
                                            (some-> (find-max-lunch-order-date db)
                                                    (tc/to-local-date)
                                                    (t/plus (t/days 1))
                                                    (tc/to-date))
                                            max-paid-date)
         higher-schools-holiday-ld? (make-holiday?-fn db true)]
     (timbre/debug "finding-person-substs from" date-from "to" max-paid-date "all plans count" (count all-next-dps))
     {:person (db/find-by-id db person-id)
      :groups (db/find-by-type db :group {})
      :substable-dps substable-dps
      :dp-gap-days (->> all-next-dps
                        (group-by :daily-plan/date)
                        (reduce (fn [out [date plans]]
                                  (if (or (higher-schools-holiday-ld? (tc/to-local-date date))
                                          (some #(and (= person-id (get-in % [:daily-plan/person :db/id]))
                                                      (not (:daily-plan/subst-req-on %)))
                                                plans))
                                    out
                                    (->> plans
                                         (remove :daily-plan/att-cancelled?)
                                         (assoc out date))))
                                (sorted-map)))
      :can-subst? (and (not-empty substable-dps)
                       (->> all-next-dps
                            (filter #(and (= person-id (get-in % [:daily-plan/person :db/id]))
                                          (:daily-plan/subst-req-on %)))
                            (count)
                            (> 2)))})))

(defn find-bank-account [db person-id]
  (get (find-price-list db person-id) :price-list/bank-account ""))

(defn find-period-daily-plans [db period-id]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?period-id
         :where
         [?e :daily-plan/bill ?bill-id]
         [?bill-id :person-bill/period ?period-id]]
       db period-id))

(defn find-lunch-count-planned [db person-id from]
  (or (d/q '[:find (sum ?lunch-req) .
             :with ?e
             :in $ ?person ?from
             :where
             [?e :daily-plan/person ?person]
             [?e :daily-plan/lunch-req ?lunch-req]
             [?e :daily-plan/date ?d]
             [(>= ?d ?from)]
             (not [?e :daily-plan/lunch-ord])
             (not [?e :daily-plan/lunch-cancelled? true])]
           db person-id from)
      0))

(defn find-daily-plans-by-date [db date]
  (db/find-where db {:daily-plan/date date}
                 '[* {:daily-plan/person [:db/id :person/firstname :person/lastname
                                          {:person/lunch-type [:lunch-type/label]
                                           :person/parent [:person/email]
                                           :person/group [:db/id]}]}]))

(defn find-monthly-lunch-totals-per-person [db yyyymm]
  (let [from (time/to-date (cljc.util/yyyymm-start-ld yyyymm))
        to (time/to-date (cljc.util/yyyymm-start-ld (cljc.util/next-yyyymm yyyymm)))]
    (->>
     (d/q '[:find (pull ?p [:db/id :person/firstname :person/lastname]) (sum ?ord)
            :in $ ?from ?to
            :with ?e
            :where
            [?e :daily-plan/date ?d]
            [(>= ?d ?from)]
            [(< ?d ?to)]
            [?e :daily-plan/lunch-ord ?ord]
            [(> ?ord 0)]
            [?e :daily-plan/person ?p]]
          db from to)
     (sort-by (juxt (comp :person/lastname first)
                    (comp :person/firstname first))))))

(defn find-down-payments [db yyyymm]
  (let [[total att-price] (first
                           (d/q '[:find (sum ?total) (sum ?att-price)
                                  :in $ ?yyyymm
                                  :with ?pb
                                  :where
                                  [?period :billing-period/from-yyyymm ?yyyymm]
                                  [?pb :person-bill/period ?period]
                                  [?pb :person-bill/total ?total]
                                  [?pb :person-bill/status :person-bill.status/paid]
                                  [?pb :person-bill/att-price ?att-price]]
                                db yyyymm))]
    (if total
      (- total att-price)
      0)))

(defn find-lunch-fund-total [db to]
  (d/q '[:find (sum ?lf) .
         :in $
         :with ?e
         :where
         [?e :person/lunch-fund ?lf]]
       (d/as-of db to)))

(defn find-lunch-fund-substraction-total [conn from to]
  (let [db (d/db conn)
        lunch-order-txs (d/q '[:find [?tx ...]
                               :in $ ?from ?to
                               :where
                               [?e :lunch-order/date ?d]
                               [(>= ?d ?from)]
                               [(< ?d ?to)]
                               [?e :lunch-order/total ?t ?tx]
                               [(> ?t 0)]]
                             db from to)]
    (->> lunch-order-txs
         (mapcat #(->> (db/tx-datoms conn %)
                       (filter (fn [{:keys [a]}]
                                 (= a :person/lunch-fund)))
                       (group-by :e)
                       (map (fn [[e datoms]]
                              (let [m (->> datoms
                                           (map (juxt :added? :v))
                                           (into {}))]
                                (- (or (get m false) 0)
                                   (or (get m true) 0)))))))
         (reduce +))))

(defn find-monthly-lunch-fund-totals [conn yyyymm]
  (let [from (time/to-date (cljc.util/yyyymm-start-ld yyyymm))
        to (time/to-date (cljc.util/yyyymm-start-ld (cljc.util/next-yyyymm yyyymm)))
        db (d/db conn)
        portions (->>
                  (d/q '[:find ?ch (sum ?ord)
                         :in $ ?from ?to
                         :with ?e
                         :where
                         [?e :daily-plan/date ?d]
                         [(>= ?d ?from)]
                         [(< ?d ?to)]
                         [?e :daily-plan/lunch-ord ?ord]
                         [(> ?ord 0)]
                         [?e :daily-plan/person ?p]
                         [?p :person/child? ?ch]]
                       db from to)
                  (into {}))
        total-portions (or
                        (d/q '[:find (sum ?ord) .
                               :in $ ?from ?to
                               :with ?e
                               :where
                               [?e :daily-plan/date ?d]
                               [(>= ?d ?from)]
                               [(< ?d ?to)]
                               [?e :daily-plan/lunch-ord ?ord]]
                             db from to)
                        0)
        lunch-fund (find-lunch-fund-total db to)
        ;; down-payments (find-down-payments db yyyymm)
        ;; next-down-payments (find-down-payments db (cljc.util/next-yyyymm yyyymm))
        lunch-total-cents (find-lunch-fund-substraction-total conn from to)
        out {:total-portions total-portions
             :adult-portions (get portions false 0)
             :child-portions (get portions true 0)
             ;; :total-lunch-down-payment-cents down-payments
             ;; :next-total-lunch-down-payment-cents next-down-payments
             :lunch-total-cents lunch-total-cents
             :total-lunch-fund-cents lunch-fund}]
    (when-not (= total-portions (reduce + (vals portions)))
      (throw (ex-info "Invalid total count of lunches" out)))
    out))
