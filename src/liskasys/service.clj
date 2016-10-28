(ns liskasys.service
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojure.set :as set]
            [clojure.string :as str]
            [crypto.password.scrypt :as scrypt]
            [datomic.api :as d]
            [liskasys.cljc.util :as cljc-util]
            [postal.core :as postal]
            [taoensso.timbre :as timbre]
            [clojure.pprint :refer [pprint]])
  (:import java.text.Collator
           java.util.Locale))

(def day-formatter (-> (tf/formatter "E d. MMMM yyyy")
                       (tf/with-locale (Locale. "cs"))))

(defn format-day-date [date]
  (->> date
       tc/to-date-time
       (tf/unparse day-formatter)
       (str/lower-case)))

(def cz-collator (Collator/getInstance (Locale. "cs")))

(defn tomorrow []
  (-> (t/today)
      (t/plus (t/days 1))
      tc/to-date))

(defn all-work-days-since [ld]
  (->> ld
       (iterate #(t/plus % (t/days 1)))
       (keep #(when (<= (t/day-of-week %) 5) %))))

(defn att-day-with-lunch? [att-day]
  (and (:lunch? att-day)
       (not (:lunch-cancelled? att-day))))

;; Source: https://gist.github.com/werand/2387286
(defn- easter-sunday-for-year [year]
  (let [golden-year (+ 1 (mod year 19))
        div (fn div [& more] (Math/floor (apply / more)))
        century (+ (div year 100) 1)
        skipped-leap-years (- (div (* 3 century) 4) 12)
        correction (- (div (+ (* 8 century) 5) 25) 5)
        d (- (div (* 5 year) 4) skipped-leap-years 10)
        epac (let [h (mod (- (+ (* 11 golden-year) 20 correction)
                             skipped-leap-years) 30)]
               (if (or (and (= h 25) (> golden-year 11)) (= h 24))
                 (inc h) h))
        m (let [t (- 44 epac)]
            (if (< t 21) (+ 30 t) t))
        n (- (+ m 7) (mod (+ d m) 7))
        day (if (> n 31) (- n 31) n)
        month (if (> n 31) 4 3)]
    (t/local-date year (int month) (int day))))

(defn- easter-monday-for-year [year]
  (t/plus (easter-sunday-for-year year) (t/days 1)))

(def easter-monday-for-year-memo (memoize easter-monday-for-year))

(defn- *bank-holiday? [{:keys [:bank-holiday/day :bank-holiday/month :bank-holiday/easter-delta]} ld]
  (or (and (= (t/day ld) day)
           (= (t/month ld) month))
      (and (some? easter-delta)
           (t/equal? ld
                     (t/plus (easter-monday-for-year-memo (t/year ld))
                             (t/days easter-delta))))))

(defn- bank-holiday? [bank-holidays ld]
  (seq (filter #(*bank-holiday? % ld) bank-holidays)))

(defn- *school-holiday? [{:keys [:school-holiday/from :school-holiday/to :school-holiday/every-year?]} ld]
  (let [from (tc/from-date from)
        dt (tc/to-date-time ld)
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

(defn- school-holiday? [school-holidays ld]
  (seq (filter #(*school-holiday? % ld) school-holidays)))

(defn transact [conn user-id tx-data]
  (let [tx-data (cond-> (vec tx-data)
                  user-id
                  (conj {:db/id (d/tempid :db.part/tx) :tx/person user-id}))
        _ (timbre/info "Transacting" tx-data)
        tx-result @(d/transact conn tx-data)]
    (timbre/debug tx-result)
    tx-result))

(def tempid? map?)

(defn- coll->tx-data [eid k v old]
  (timbre/debug k v old)
  (let [vs (set v)
        os (set old)]
    (concat
     (map
      #(vector :db/add eid k (if (and (map? %) (not (tempid? (:db/id %))))
                               (:db/id %)
                               %))
      (set/difference vs os))
     (map
      #(vector :db/retract eid k (if (map? %)
                                   (:db/id %)
                                   %))
      (set/difference os vs)))))

(defn- entity->tx-data [db ent]
  (let [eid (:db/id ent)
        old (when-not (tempid? eid) (d/pull db '[*] eid))]
    (mapcat (fn [[k v]]
              (if (or (nil? v) (= v {:db/id nil}))
                (when-let [old-v (get old k)]
                  (list [:db/retract eid k (if (map? old-v)
                                             (:db/id old-v)
                                             old-v)]))
                (when-not (= v (get old k))
                  (if (and (coll? v) (not (map? v)))
                    (coll->tx-data eid k v (get old k))
                    (list [:db/add eid k (if (and (map? v) (not (tempid? (:db/id v))))
                                           (:db/id v)
                                           v)])))))
            (dissoc ent :db/id))))

(defn- transact-entity* [conn user-id ent]
  (let [id (:db/id ent)
        ent (cond-> ent
              (not id)
              (assoc :db/id (d/tempid :db.part/user)))
        tx-result (when-let [tx-data (not-empty (entity->tx-data (d/db conn) ent))]
                    (transact conn user-id tx-data))
        db (or (:db-after tx-result) (d/db conn))]
    (d/pull db '[*] (or id (d/resolve-tempid (:db-after tx-result) (:tempids tx-result) (:db/id ent))))))

(defmulti transact-entity (fn [conn user-id ent]
                            (first (keys (select-keys ent [:daily-plan/date])))))

(defmethod transact-entity :default [conn user-id ent]
  (transact-entity* conn user-id ent))

(defmethod transact-entity :daily-plan/date [conn user-id ent]
  (when-let [old-id (d/q '[:find ?e .
                           :in $ ?date ?pid
                           :where
                           [?e :daily-plan/date ?date]
                           [?e :daily-plan/person ?pid]]
                         (d/db conn)
                         (:daily-plan/date ent)
                         (:db/id (:daily-plan/person ent)))]
    (when (not= old-id (:db/id ent))
      (throw (Exception. "Pro tuto osobu a den již v denním plánu existuje záznam."))))
  (transact-entity* conn user-id ent))

(defn retract-entity*
  "Returns the number of retracted datoms (attributes)."
  [conn user-id ent-id]
  (->> [[:db.fn/retractEntity ent-id]]
       (transact conn user-id)
       :tx-data
       count
       (+ -2)))

(defmulti retract-entity (fn [conn user-id ent-id]
                           (first (keys (select-keys (d/pull (d/db conn) '[*] ent-id)
                                                     [:person-bill/person])))))

(defmethod retract-entity :default [conn user-id ent-id]
  (retract-entity* conn user-id ent-id))

(declare merge-person-bill-facts find-by-type)

(defn max-lunch-order-date [db]
  (d/q '[:find (max ?date) .
         :where [_ :lunch-order/date ?date]]
       db))

(defmethod retract-entity :person-bill/person [conn user-id ent-id]
  (let [db (d/db conn)
        bill (merge-person-bill-facts
              db
              (d/pull db '[* {:person-bill/status [:db/ident]}] ent-id))
        daily-plans (d/q '[:find [?e ...]
                           :in $ ?bill ?min-date
                           :where
                           [?e :daily-plan/bill ?bill]
                           (not [?e :daily-plan/lunch-ord])
                           [?e :daily-plan/date ?date]
                           [(> ?date ?min-date)]]
                         db ent-id (max-lunch-order-date db))
        person (d/pull db '[*] (get-in bill [:person-bill/person :db/id]))]
    (timbre/info "retracting bill" bill "with" (count daily-plans) "plans of person" person)

    (->> daily-plans
         (map (fn [dp-id]
                [:db.fn/retractEntity dp-id]))
         (into [[:db.fn/retractEntity ent-id]
                [:db.fn/cas (:db/id person) :person/lunch-fund (:person/lunch-fund person)
                 (- (:person/lunch-fund person) (:_total-lunch-price bill))]])
         (transact conn user-id)
         :tx-data
         count
         (+ -2))))

(defn retract-attr [conn user-id ent]
  (timbre/debug ent)
  "Returns the number of retracted datoms (attributes)."
  (->> (mapv (fn [[attr-key attr-val]]
              [:db/retract (:db/id ent) attr-key attr-val])
            (dissoc ent :db/id))
       (transact conn user-id)
       :tx-data
       count
       (+ -2)))

(defn- build-query [db pull-pattern where-m]
  (reduce (fn [query [where-attr where-val]]
            (let [?where-attr (symbol (str "?" (name where-attr)))]
              (cond-> query
                where-val
                (update-in [:query :in] conj ?where-attr)
                (not= '?id ?where-attr)
                (update-in [:query :where] conj (if where-val
                                                  ['?id where-attr ?where-attr]
                                                  ['?id where-attr]))
                where-val
                (update-in [:args] conj where-val))))
          {:query {:find [[(list 'pull '?id pull-pattern) '...]]
                   :in ['$]
                   :where []}
           :args [db]
           :timeout 2000}
          where-m))

(defn find-where
  ([db where-m]
   (find-where db where-m '[*]))
  ([db where-m pull-pattern]
   (d/query (build-query db pull-pattern where-m))))

(def ent-type->attr
  {:bank-holiday :bank-holiday/label
   :billing-period :billing-period/from-yyyymm
   :daily-plan :daily-plan/date
   :lunch-menu :lunch-menu/from
   :lunch-order :lunch-order/date
   :lunch-type :lunch-type/label
   :person :person/firstname
   :price-list :price-list/days-1
   :person-bill :person-bill/total
   :school-holiday :school-holiday/label
})

(defn find-by-type-default
  ([db ent-type where-m]
   (find-by-type-default db ent-type where-m '[*]))
  ([db ent-type where-m pull-pattern]
   (let [attr (get ent-type->attr ent-type ent-type)]
     (find-where db (merge {attr nil} where-m) pull-pattern))))

(defmulti find-by-type (fn [db ent-type where-m] ent-type))

(defmethod find-by-type :default [db ent-type where-m]
  (find-by-type-default db ent-type where-m))

(defmethod find-by-type :person [db ent-type where-m]
  (->> (find-by-type-default db ent-type where-m)
       (map #(dissoc % :person/passwd))))

(defn merge-person-bill-facts [db {:person-bill/keys [lunch-count total att-price] :as person-bill}]
  (let [tx (apply max (d/q '[:find [?tx ...]
                             :in $ ?e
                             :where
                             [?e :person-bill/total _ ?tx]]
                           db (:db/id person-bill)))
        as-of-db (d/as-of db tx)
        patterns (d/pull as-of-db
                         [:person/var-symbol :person/att-pattern :person/lunch-pattern]
                         (get-in person-bill [:person-bill/person :db/id]))
        lunch-price (d/q '[:find ?l .
                           :where [_ :price-list/lunch ?l]]
                         as-of-db)
        total-lunch-price (* lunch-price lunch-count)]
    (-> person-bill
        (update :person-bill/person merge patterns)
        (merge {:_lunch-price lunch-price
                :_total-lunch-price total-lunch-price
                :_from-previous (- total (+ att-price total-lunch-price))}))))

(defmethod find-by-type :person-bill [db ent-type where-m]
  (->> (find-by-type-default db ent-type where-m '[* {:person-bill/status [:db/id :db/ident]}])
       (map (partial merge-person-bill-facts db))))

(defn find-by-id [db eid]
  (d/pull db '[*] eid))

(defn make-holiday?-fn [db]
  (let [bank-holidays (find-where db {:bank-holiday/label nil})
        school-holidays (find-where db {:school-holiday/label nil})]
    (fn [ld]
      (or (bank-holiday? bank-holidays ld)
          (school-holiday? school-holidays ld)))))

(defn find-person-daily-plans-with-lunches [db date]
  (d/q '[:find [(pull ?e [:db/id :daily-plan/lunch-req {:daily-plan/person [:db/id :person/lunch-type :person/lunch-fund]}]) ...]
         :in $ ?date
         :where
         [?e :daily-plan/date ?date]
         [?e :daily-plan/lunch-req ?lunch-req]
         (not [?e :daily-plan/lunch-cancelled? true])
         [(pos? ?lunch-req)]]
       db
       date))

(defn- new-lunch-order-ent [date total]
  (cond-> {:db/id (d/tempid :db.part/user)
           :lunch-order/date date}
    total
    (assoc :lunch-order/total total)))

(defn close-lunch-order [conn date]
  (->> (new-lunch-order-ent date nil)
       (transact-entity conn nil)))

(defn- get-lunch-type-map [db]
  (->> (find-where db {:lunch-type/label nil})
       (into [{:db/id nil :lunch-type/label "běžná"}])
       (map (juxt :db/id :lunch-type/label))
       (into {})))

(defn find-persons-with-role [db role]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?role
         :where
         [?e :person/roles ?roles]
         [(clojure.string/index-of ?roles ?role)]]
       db
       role))

(defn- find-lunch-counts-by-diet-label [lunch-types plans-with-lunches]
  (->> plans-with-lunches
       (group-by (comp :db/id :person/lunch-type :daily-plan/person))
       (map (fn [[k v]] [(get lunch-types k) (reduce + 0 (keep :daily-plan/lunch-req v))]))
       (sort-by first cz-collator)))

(defn- send-lunch-order-email [date emails lunch-counts]
  (let [subject (str "Objednávka obědů pro Lištičku na " (format-day-date date))
        msg {:from "daniela.chaloupkova@post.cz"
             :to emails
             :subject subject
             :body [{:type "text/plain; charset=utf-8"
                     :content (str subject "\n"
                                   "-------------------------------------------------\n\n"
                                   "Dle diety:\n"
                                   (apply str
                                          (for [[t c] lunch-counts]
                                            (str t ": " c "\n")))
                                   "-------------------------------------------------\n"
                                   "CELKEM: " (apply + (map second lunch-counts)))}]}]
    (if-not (seq lunch-counts)
      (timbre/info "No lunches for " date ". Sending skipped.")
      (do
        (timbre/info "Sending " (:subject msg) "to" (:to msg))
        (timbre/debug msg)
        (let [result (postal/send-message msg)]
          (if (zero? (:code result))
            (timbre/info "Lunch order has been sent" result)
            (timbre/error "Failed to send email" result)))))))

(defn lunch-order-tx-total [date lunch-price plans-with-lunches]
  (let [out
        (reduce (fn [out {:keys [:db/id :daily-plan/person :daily-plan/lunch-req]}]
                  (-> out
                      (update :tx-data conj
                              [:db.fn/cas (:db/id person) :person/lunch-fund
                               (:person/lunch-fund person) (- (or (:person/lunch-fund person) 0)
                                                              (* lunch-req lunch-price))])
                      (update :tx-data conj
                              [:db/add id :daily-plan/lunch-ord lunch-req])
                      (update :total + lunch-req)))
                {:tx-data []
                 :total 0}
                plans-with-lunches)]
   (update out :tx-data conj (new-lunch-order-ent date (:total out)))))

(defn find-price-list [db]
  (first (find-where db {:price-list/days-1 nil})))

(defn process-lunch-order [conn date]
  (let [db (d/db conn)
        plans-with-lunches (find-person-daily-plans-with-lunches db date)
        lunch-counts (find-lunch-counts-by-diet-label (get-lunch-type-map db) plans-with-lunches)
        total-by-type (apply + (map second lunch-counts))
        {:keys [tx-data total]} (lunch-order-tx-total date (:price-list/lunch (find-price-list db)) plans-with-lunches)]
    (if (not= total total-by-type)
      (timbre/fatal "Invalid lunch totals: processed=" total "by-type=" total-by-type " . Operation skipped!")
      (do
        (transact conn nil tx-data)
        (send-lunch-order-email date
                                (mapv :person/email (find-persons-with-role db "obedy"))
                                lunch-counts)))))

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
              db person-id (tomorrow) to-date)
         (sort-by :daily-plan/date))))

(defn- calculate-att-price [price-list months-count days-per-week half-days-count]
  (+ (* months-count (get price-list (keyword "price-list" (str "days-" days-per-week)) 0))
     (* half-days-count (:price-list/half-day price-list))))

(defn- pattern-map [pattern]
  (->> pattern
       (map-indexed vector)
       (keep (fn [[idx ch]]
               [(inc idx) (- (int ch) (int \0))]))
       (into {})))

(defn- generate-daily-plans
  [{:keys [:person/lunch-pattern :person/att-pattern] person-id :db/id :as person} dates]
  (let [lunch-map (pattern-map lunch-pattern)
        att-map (pattern-map att-pattern)]
    (keep (fn [ld]
            (let [day-of-week (t/day-of-week ld)
                  lunch-req (get lunch-map day-of-week 0)
                  child-att (get att-map day-of-week 0)]
              (when (or (pos? lunch-req) (pos? child-att))
                (cond-> {:daily-plan/person person-id
                         :daily-plan/date (tc/to-date ld)}
                  (pos? lunch-req)
                  (assoc :daily-plan/lunch-req lunch-req)
                  (pos? child-att)
                  (assoc :daily-plan/child-att child-att)))))
          dates)))

(defn- billing-period-start-end
  "Returns local dates with end exclusive!!"
  [{:keys [:billing-period/from-yyyymm :billing-period/to-yyyymm] :as period}]
  [(t/local-date (quot from-yyyymm 100) (rem from-yyyymm 100) 1)
   (t/plus (t/local-date (quot to-yyyymm 100) (rem to-yyyymm 100) 1)
           (t/months 1))])

(defn- period-dates [holiday?-fn from to]
  "Returns all local-dates except holidays from - to (exclusive)."
  (->> from
       (iterate (fn [ld]
                  (t/plus ld (t/days 1))))
       (take-while (fn [ld]
                     (t/before? ld to)))
       (remove holiday?-fn)))

(comment
  (def period-id 17592186046603)
  (def current-bills (d/q '[:find [(pull ?e [*]) ...]
                            :in $ ?period-id
                            :where
                            [?e :person-bill/period ?period-id]]
                          (user/db)
                          period-id))
  (def db (d/as-of (user/db) #inst "2016-10-07"))
  (def first-bills (generate-person-bills-tx db period-id))
  (def first-bills-by-person-id (->> first-bills
                                     (map (juxt (comp :db/id :person-bill/person) identity))
                                     (into {})))

  (def diff (->> current-bills
                 (keep (fn [cb]
                         (let [person-id (get-in cb [:person-bill/person :db/id])
                               ob (get first-bills-by-person-id person-id)]
                           (when (and ob (not= (:person-bill/total cb) (:person-bill/total ob)))
                             (let [diff (clojure.data/diff cb ob)
                                   id (:db/id (nth diff 2))
                                   new-total (:person-bill/total (second diff))
                                   curr-lunch-fund (:person/lunch-fund (d/entity (user/db) person-id))
                                   lunch-fond-diff (- new-total
                                                      (:person-bill/total (first diff)))]
                               (-> [(d/pull (user/db) '[:person/lastname :person/firstname] person-id)]
                                   (into diff)
                                   (conj (cond-> [{:db/id id
                                                   :person-bill/total new-total}]
                                           (:person-bill/status (first diff))
                                           (conj [:db.fn/cas person-id :person/lunch-fund curr-lunch-fund (+ curr-lunch-fund lunch-fond-diff)])))))))))))
  )


(defn- generate-person-bills-tx [db period-id]
  (let [price-list (find-price-list db)
        person-id->bill (atom (->> (d/q '[:find ?person-id (pull ?bill-id [*])
                                          :in $ ?period-id
                                          :where
                                          [?bill-id :person-bill/period ?period-id]
                                          [?bill-id :person-bill/person ?person-id]]
                                        db period-id)
                                   (into {})))
        billing-period (find-by-id db period-id)
        published-status (d/entid db :person-bill.status/published)
        dates (apply period-dates (make-holiday?-fn db) (billing-period-start-end billing-period))
        out (->>
             (for [person (->> (find-where db {:person/active? true})
                               (remove cljc-util/zero-patterns?))
                   :let [daily-plans (generate-daily-plans person dates)
                         lunch-count-next (->> daily-plans
                                               (keep :daily-plan/lunch-req)
                                               (reduce + 0))
                         lunch-count-planned (or (d/q '[:find (sum ?lunch-req) .
                                                        :with ?e
                                                        :in $ ?person
                                                        :where
                                                        [?e :daily-plan/person ?person]
                                                        [?e :daily-plan/lunch-req ?lunch-req]
                                                        (not [?e :daily-plan/lunch-ord])
                                                        (not [?e :daily-plan/lunch-cancelled?])]
                                                      db (:db/id person))
                                                 0)
                         att-price (calculate-att-price price-list
                                              (- (:billing-period/to-yyyymm billing-period)
                                                 (:billing-period/from-yyyymm billing-period)
                                                 -1)
                                              (count (->> person :person/att-pattern pattern-map vals (filter (partial = 1))))
                                              (->> daily-plans
                                                   (filter #(-> % :daily-plan/child-att (= 2)))
                                                   count))
                         lunch-price (:price-list/lunch price-list)
                         old-bill (get @person-id->bill (:db/id person))]]
               (do
                 (when old-bill
                   (swap! person-id->bill dissoc (:db/id person)))
                 (when (or (not old-bill) (< (get-in old-bill [:person-bill/status :db/id]) published-status))
                   (merge (or old-bill {:db/id (d/tempid :db.part/user)
                                        :person-bill/person (:db/id person)
                                        :person-bill/period period-id
                                        :person-bill/status :person-bill.status/new})
                          {:person-bill/lunch-count lunch-count-next
                           :person-bill/att-price att-price
                           :person-bill/total (+ att-price
                                                 (- (* lunch-price
                                                       (+ lunch-count-next lunch-count-planned))
                                                    (or (:person/lunch-fund person) 0)))}))))
             (filterv some?))]
    (->> (vals @person-id->bill)
         (map #(vector :db.fn/retractEntity %))
         (into out))))

(defn- transact-period-person-bills [conn user-id period-id tx-data]
  (let [tx-result (transact conn user-id tx-data)]
    (find-by-type (:db-after tx-result) :person-bill {:person-bill/period period-id})))

(defn re-generate-person-bills [conn user-id period-id]
  (->> (generate-person-bills-tx (d/db conn) period-id)
       (transact-period-person-bills conn user-id period-id)))

(defn publish-all-bills [conn user-id period-id]
  (let [db (d/db conn)
        billing-period (find-by-id db period-id)]
    (->> (d/q '[:find [?e ...]
                :in $ ?period-id
                :where
                [?e :person-bill/period ?period-id]
                [?e :person-bill/status :person-bill.status/new]]
              db period-id)
         (mapv (fn [id]
                 [:db/add id :person-bill/status :person-bill.status/published]))
         (transact-period-person-bills conn user-id period-id))))

(defn set-bill-as-paid [conn user-id bill-id]
  (let [db (d/db conn)
        [[period-id {:keys [:person-bill/person :person-bill/total :person-bill/att-price]}]]
        (d/q '[:find ?period-id (pull ?e [:db/id :person-bill/total :person-bill/att-price
                                          {:person-bill/person [:db/id :person/lunch-pattern :person/att-pattern :person/lunch-fund]}])
               :in $ ?e
               :where
               [?e :person-bill/period ?period-id]
               [?e :person-bill/status :person-bill.status/published]]
             db bill-id)
        order-date (tc/to-local-date (max-lunch-order-date db))
        dates (->> (apply period-dates (make-holiday?-fn db) (billing-period-start-end (find-by-id db period-id)))
                   (drop-while #(not (t/after? (tc/to-local-date %) order-date))))
        tx-result (->> (generate-daily-plans person dates)
                       (map #(-> % (assoc :db/id (d/tempid :db.part/user)
                                          :daily-plan/bill bill-id)))
                       (into [[:db/add bill-id :person-bill/status :person-bill.status/paid]
                              [:db.fn/cas (:db/id person) :person/lunch-fund
                               (:person/lunch-fund person) (+ (or (:person/lunch-fund person) 0)
                                                              (- total att-price))]])
                       (transact conn user-id))]
    (find-by-type (:db-after tx-result) :person-bill {:db/id bill-id})))

#_(defn all-period-bills-paid [conn user-id period-id]
  (let [db (d/db conn)
        billing-period (find-by-id db period-id)
        dates (apply period-dates (make-holiday?-fn db) (billing-period-start-end billing-period))]
    (->> (d/q '[:find [(pull ?e [:db/id :person-bill/total :person-bill/att-price
                                 {:person-bill/person [:db/id :person/lunch-pattern :person/att-pattern :person/lunch-fund]}]) ...]
                :in $ ?period-id ?paid?
                :where
                [?e :person-bill/period ?period-id]
                [?e :person-bill/status :person-bill.status/published]]
              db period-id false)
         (mapcat (fn [{:keys [:db/id :person-bill/person :person-bill/total :person-bill/att-price]}]
                   (->> (generate-daily-plans person dates)
                        (map #(-> % (assoc :db/id (d/tempid :db.part/user)
                                           :daily-plan/bill id)))
                        (into [[:db/add id :person-bill/status :person-bill.status/paid]
                               [:db.fn/cas (:db/id person) :person/lunch-fund
                                (:person/lunch-fund person) (+ (:person/lunch-fund person)
                                                               (- total att-price))]]))))
         (transact-period-person-bills conn user-id period-id))))

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
                      [?e :person/active? true]
                      [(clojure.string/lower-case ?email) ?lower-email2]
                      [(= ?lower-email1 ?lower-email2)]]
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
    (transact-entity conn user-id {:db/id (:db/id person)
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
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?parent
         :where
         [?e :person/parent ?parent]]
       db parent-id))

(defn- make-can-cancel-lunch?-fn [db]
  (fn [date]
    (t/after? (tc/to-local-date date)
              (tc/to-local-date (max-lunch-order-date db)))))

(defn transact-cancellations [conn user-id child-id cancel-dates uncancel-dates]
  (let [db (d/db conn)
        can-cancel-lunch?-fn (make-can-cancel-lunch?-fn db)]
    (if-not  (contains? (->> user-id
                             (find-children-by-person-id db)
                             (map :db/id)
                             set)
                        child-id)
      (timbre/warn "User" user-id "attempts to change cancellations of child" child-id)
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
           (transact conn user-id)
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
       (map (partial merge-person-bill-facts db))
       (sort-by (comp :db/id :person-bill/period))
       reverse))

(defn entity-history [db ent-id]
  (->>
   (d/q '[:find ?tx ?aname ?v ?added
          :in $ ?e
          :where
          [?e ?a ?v ?tx ?added]
          [?a :db/ident ?aname]]
        (d/history db)
        ent-id)
   (map (fn [[txid a v added?]]
          (let [tx (d/pull db '[:db/id :db/txInstant :tx/person] txid)]
            {:a a
             :v v
             :tx tx
             :added? added?})))
   (sort-by last)))

(defn tx-datoms [conn t]
  (let [db (d/db conn)]
    (->> (some-> (d/log conn) (d/tx-range t nil) first :data)
         (map (fn [datom]
                {:e (:e datom)
                 :a (d/ident db (:a datom))
                 :v (:v datom)
                 :added? (:added datom)}))
         (sort-by :added?))))

(defn last-txes
  ([conn]
   (last-txes conn 0))
  ([conn from-idx]
   (last-txes conn from-idx 50))
  ([conn from-idx n]
   (let [db (d/db conn)]
     (->> (some-> (d/log conn) (d/tx-range nil nil))
          reverse
          (filter #(> (count (:data %)) 1))
          (drop from-idx)
          (take n)
          (map (fn [row]
                 (-> (d/pull db '[*] (-> row :t d/t->tx))
                     (assoc :datom-count (count (:data row))))))))))
