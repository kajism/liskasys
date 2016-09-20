(ns liskasys.service
  (:require [clj-brnolib.jdbc-common :as jdbc-common]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as s]
            [clojure.string :as str]
            [crypto.password.scrypt :as scrypt]
            [datomic.api :as d]
            [liskasys.db :as db]
            [postal.core :as postal]
            [taoensso.timbre :as timbre])
  (:import java.text.Collator
           java.util.Locale))

(def day-formatter (-> (tf/formatter "E dd.MM.yyyy")
                       (tf/with-locale (Locale. "cs"))))

(def cz-collator (Collator/getInstance (Locale. "cs")))

(defn tomorrow []
  (-> (t/today)
      (t/plus (t/days 1))
      tc/to-date))

(defn zero-patterns? [{:keys [:person/lunch-pattern :person/att-pattern] :as person}]
  (and (or (str/blank? lunch-pattern) (= (set (seq lunch-pattern)) #{\0}))
       (or (str/blank? att-pattern) (= (set (seq att-pattern)) #{\0}))))

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

(defn transact-entity [conn user-id ent]
  (let [ent-id (or (:db/id ent) (d/tempid :db.part/user))
        tx-data (cond-> [(assoc ent :db/id ent-id)]
                  user-id
                  (conj {:db/id (d/tempid :db.part/tx) :tx/person user-id}))
        _ (timbre/info "Transacting" tx-data)
        tx-result @(d/transact conn tx-data)]
    (timbre/debug tx-result)
    (d/pull (:db-after tx-result) '[*] (or (d/resolve-tempid (:db-after tx-result) (:tempids tx-result) ent-id)
                                           ent-id))))

(defn retract-entity
  "Returns the number of retracted datoms (attributes)."
  [conn user-id ent-id]
  (->> [{:db/id (d/tempid :db.part/tx) :tx/person user-id}
        [:db.fn/retractEntity ent-id]]
       timbre/spy
       (d/transact conn)
       deref
       timbre/spy
       :tx-data
       count
       (+ -2)))

(defn retract-attr [conn user-id ent]
  (timbre/debug ent)
  "Returns the number of retracted datoms (attributes)."
  (->> (map (fn [[attr-key attr-val]]
              [:db/retract (:db/id ent) attr-key attr-val])
            (dissoc ent :db/id))
       (into [{:db/id (d/tempid :db.part/tx) :tx/person user-id}])
       (d/transact conn)
       deref
       timbre/spy
       :tx-data
       count
       (+ -2)))

(defn- build-query [db where-m]
  (reduce (fn [query [where-attr where-val]]
            (let [?where-attr (symbol (str "?" (name where-attr)))]
              (cond-> query
                where-val
                (update-in [:query :in] conj ?where-attr)
                true
                (update-in [:query :where] conj (if where-val
                                                  ['?e where-attr ?where-attr]
                                                  ['?e where-attr]))
                where-val
                (update-in [:args] conj where-val))))
          {:query {:find ['[(pull ?e [*]) ...]]
                   :in ['$]
                   :where []}
           :args [db]
           :timeout 2000}
          where-m))

(defn find-where [db where-m]
  (d/query (build-query db where-m)))

(def ent-type->attr
  {:lunch-menu :lunch-menu/from
   :lunch-order :lunch-order/date
   :lunch-type :lunch-type/label
   :bank-holiday :bank-holiday/label
   :school-holiday :school-holiday/label
   :person :person/firstname
   :price-list :price-list/days-1
   :billing-period :billing-period/from-yyyymm
   :person-bill :person-bill/total})

(defn find-by-type [db ent-type where-m]
  (let [attr (get ent-type->attr ent-type ent-type)]
    (cond->> (find-where db (merge {attr nil} where-m))
      (= ent-type :person)
      (map #(-> %
                (dissoc :person/passwd)))
      (= ent-type :person-bill)
      (map #(let [tx (apply max (d/q '[:find [?tx ...] :in $ ?e :where [?e :person-bill/total _ ?tx]] db (:db/id %)))
                  patterns (d/pull (d/as-of db tx)
                                   [:person/var-symbol :person/att-pattern :person/lunch-pattern]
                                   (get-in % [:person-bill/person :db/id]))]
              (merge % patterns))))))

(defn find-by-id [db eid]
  (d/pull db '[*] eid))

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
  (let [subject (str "Objednávka obědů pro Lištičku na " (->> date
                                                              tc/to-date-time
                                                              (tf/unparse day-formatter)))
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
                               (:person/lunch-fund person) (- (:person/lunch-fund person)
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
        (d/transact conn tx-data)
        (send-lunch-order-email date
                                (mapv :person/email (find-persons-with-role db "obedy"))
                                lunch-counts)))))

(defn find-next-attendance-weeks [db-spec child-id weeks]
  (let [bank-holidays (jdbc-common/select db-spec :bank-holiday {})]
    (->> (db/select-next-attendance-weeks db-spec child-id weeks)
         (remove (fn [[date att-day]]
                   (bank-holiday? bank-holidays (tc/to-local-date date))))
         (into (sorted-map)))))

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

(defn make-holiday?-fn [db]
  (let [bank-holidays (find-where db {:bank-holiday/label nil})
        school-holidays (find-where db {:school-holiday/label nil})]
    (fn [ld]
      (or (bank-holiday? bank-holidays ld)
          (school-holiday? school-holidays ld)))))

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
        dates (apply period-dates (make-holiday?-fn db) (billing-period-start-end billing-period))
        out (->>
             (for [person (->> (find-where db {:person/active? true})
                               (remove zero-patterns?))
                   :let [daily-plans (generate-daily-plans person dates)
                         lunch-count (->> daily-plans
                                          (keep :daily-plan/lunch-req)
                                          (reduce + 0))
                         att-price (if (:person/free-att? person)
                                     0
                                     (calculate-att-price price-list
                                                          (- (:billing-period/to-yyyymm billing-period)
                                                             (:billing-period/from-yyyymm billing-period)
                                                             -1)
                                                          (count (->> person :person/att-pattern pattern-map vals (filter (partial = 1))))
                                                          (->> daily-plans
                                                               (filter #(-> % :daily-plan/child-att (= 2)))
                                                               count)))
                         lunch-price (if (:person/free-lunches? person)
                                       0
                                       (:price-list/lunch price-list))
                         id (or (when-let [person-bill (get @person-id->bill (:db/id person))]
                                  (swap! person-id->bill dissoc (:db/id person))
                                  (if (:person-bill/paid? person-bill)
                                    ::paid
                                    (:db/id person-bill)))
                                (d/tempid :db.part/user))]]
               (when-not (= ::paid id)
                 {:db/id id
                  :person-bill/person (:db/id person)
                  :person-bill/period period-id
                  :person-bill/paid? false
                  :person-bill/lunch-count lunch-count
                  :person-bill/att-price att-price
                  :person-bill/total (+ att-price (* lunch-count lunch-price))}))
             (filterv some?))]
    (->> (vals @person-id->bill)
         (map #(vector :db.fn/retractEntity %))
         (into out))))

(defn- transact-period-person-bills [conn user-id period-id tx-data]
  (let [tx-result @(cond->> tx-data
                     user-id
                     (into [{:db/id (d/tempid :db.part/tx) :tx/person user-id}])
                     true
                     (d/transact conn))]
    (find-by-type (:db-after tx-result) :person-bill {:person-bill/period period-id})))

(defn re-generate-person-bills [conn user-id period-id]
  (->> (generate-person-bills-tx (d/db conn) period-id)
       (transact-period-person-bills conn user-id period-id)))

(defn all-period-bills-paid [conn user-id period-id]
  (let [db (d/db conn)
        billing-period (find-by-id db period-id)
        dates (apply period-dates (make-holiday?-fn db) (billing-period-start-end billing-period))]
    (->> (d/q '[:find [(pull ?e [:db/id :person-bill/total :person-bill/att-price
                                 {:person-bill/person [:db/id :person/lunch-pattern :person/att-pattern :person/lunch-fund]}]) ...]
                :in $ ?period-id ?paid?
                :where
                [?e :person-bill/period ?period-id]
                [?e :person-bill/paid? ?paid?]]
              db period-id false)
         (mapcat (fn [{:keys [:db/id :person-bill/person :person-bill/total :person-bill/att-price]}]
                   (->> (generate-daily-plans person dates)
                        (map #(-> % (assoc :db/id (d/tempid :db.part/user)
                                           :daily-plan/bill id)))
                        (into [[:db/add id :person-bill/paid? true]
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
                      :in $ ?email
                      :where
                      [?e :person/email ?email]]
                    db username)]
    (if (check-person-password person pwd)
      person
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
