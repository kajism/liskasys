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

(defn- bank-holiday? [bank-holidays clj-date]
  (let [y (t/year clj-date)]
    (seq (filter (fn [{:keys [:bank-holiday/day :bank-holiday/month :bank-holiday/easter-delta]}]
                   (or (and (= (t/day clj-date) day)
                            (= (t/month clj-date) month))
                       (and (some? easter-delta)
                            (t/equal? clj-date
                                      (t/plus (easter-monday-for-year-memo y)
                                              (t/days easter-delta))))))
                 bank-holidays))))

(defn find-children-with-attendance-day [db-spec date]
  (when-not (bank-holiday? (jdbc-common/select db-spec :bank-holiday {}) (tc/to-local-date date))
    (db/select-children-with-attendance-day db-spec date)))

(defn- find-lunch-counts-by-diet-label [db-spec date]
  (let [lunch-types (->> (jdbc-common/select db-spec :lunch-type {})
                         (into [{:id nil :label "běžná"}])
                         (map (juxt :id :label))
                         (into {}))]
    (->> (find-children-with-attendance-day db-spec date)
         (filter att-day-with-lunch?)
         (group-by :lunch-type-id)
         (map (fn [[k v]] [(get lunch-types k) (count v)]))
         (sort-by first cz-collator))))

(defn close-lunch-order [db-spec date total]
  (let [lunch-order (first (jdbc-common/select db-spec :lunch-order {:date date}))]
    (jdbc-common/save! db-spec :lunch-order (assoc lunch-order
                                                   :date date
                                                   :total total))))

(defn send-lunch-order [db-spec date]
  (let [lunch-counts (not-empty (find-lunch-counts-by-diet-label db-spec date))
        total (apply + (map second lunch-counts))
        subject (str "Objednávka obědů pro Lištičku na " (->> date
                                                              tc/to-date-time
                                                              (tf/unparse day-formatter)))
        msg {:from "daniela.chaloupkova@post.cz"
             :to (mapv :email (db/select-users-with-role db-spec "obedy"))
             :subject subject
             :body [{:type "text/plain; charset=utf-8"
                     :content (str subject "\n"
                                   "-------------------------------------------------\n\n"
                                   "Dle diety:\n"
                                   (apply str
                                          (for [[t c] lunch-counts]
                                            (str t ": " c "\n")))
                                   "-------------------------------------------------\n"
                                   "CELKEM: " total)}]}]
    (close-lunch-order db-spec date total)
    (if-not lunch-counts
      (timbre/info "No lunches for " date ". Sending skipped.")
      (do
        (timbre/info "Sending " (:subject msg) "to" (:to msg))
        (timbre/debug msg)
        (let [result (postal/send-message msg)]
          (if (zero? (:code result))
            (timbre/info "Lunch order has been sent" result)
            (timbre/error "Failed to send email" result)))))))

(defn find-next-attendance-weeks [db-spec child-id weeks]
  (let [bank-holidays (jdbc-common/select db-spec :bank-holiday {})]
    (->> (db/select-next-attendance-weeks db-spec child-id weeks)
         (remove (fn [[date att-day]]
                   (bank-holiday? bank-holidays (tc/to-local-date date))))
         (into (sorted-map)))))

(defn- calculate-att-price [price-list months-count days-per-week half-days-count]
  (+ (* months-count (get price-list (keyword "price-list" (str "days-" days-per-week)) 0))
     (* half-days-count (:price-list/half-day price-list))))

(defn- day-numbers-from-pattern [pattern match-char]
  (->> pattern
       (map-indexed vector)
       (keep (fn [[idx ch]]
               (when (= ch match-char)
                 (inc idx))))
       set))

(defn- period-start-end
  "Returns local dates with end exclusive!!"
  [{:keys [:billing-period/from-yyyymm :billing-period/to-yyyymm] :as period}]
  [(t/local-date (quot from-yyyymm 100) (rem from-yyyymm 100) 1)
   (t/plus (t/local-date (quot to-yyyymm 100) (rem to-yyyymm 100) 1)
           (t/months 1))])

(defn- generate-daily-plans
  [{:keys [:person/lunch-pattern :person/att-pattern] person-id :db/id :as person} billing-period bank-holidays]
  (let [[from to] (period-start-end billing-period)
        lunch-days (day-numbers-from-pattern lunch-pattern \1)
        full-days (day-numbers-from-pattern att-pattern \1)
        half-days (day-numbers-from-pattern att-pattern \2)]
    (->> from
         (iterate (fn [ld]
                    (t/plus ld (t/days 1))))
         (take-while (fn [ld]
                       (t/before? ld to)))
         (remove (partial bank-holiday? bank-holidays))
         (keep (fn [ld]
                 (let [day-of-week (t/day-of-week ld)
                       lunch? (contains? lunch-days day-of-week)
                       child-att (cond
                                   (contains? full-days day-of-week) 1
                                   (contains? half-days day-of-week) 2
                                   :else 0)]
                   (when (or lunch? (pos? child-att))
                     {:daily-plan/person person-id
                      :daily-plan/date (tc/to-date ld)
                      :daily-plan/lunch? lunch?
                      :daily-plan/child-att child-att})))))))

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
  {:lunch-type :lunch-type/label
   :lunch-menu :lunch-menu/from
   :bank-holiday :bank-holiday/label
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

(defn- generate-person-bills [db period-id]
  (let [billing-period (find-by-id db period-id)
        price-list (first (find-where db {:price-list/days-1 nil}))
        bank-holidays (find-where db {:bank-holiday/label nil})
        person-bill-ids (->> (d/q '[:find ?person-id ?bill-id
                                    :in $ ?period-id
                                    :where
                                    [?bill-id :person-bill/period ?period-id]
                                    [?bill-id :person-bill/person ?person-id]]
                                  db period-id)
                             (into {}))]
    (for [person (->> (find-where db {:person/active? true})
                        (remove db/zero-patterns?))
            :let [daily-plans (generate-daily-plans (timbre/spy person) billing-period bank-holidays)
                  lunch-count (->> daily-plans
                                   (filter :daily-plan/lunch?)
                                   count)
                  att-price (if (:person/free-att? person)
                              0
                              (calculate-att-price price-list
                                                   (- (:billing-period/to-yyyymm billing-period)
                                                      (:billing-period/from-yyyymm billing-period)
                                                      -1)
                                                   (count (day-numbers-from-pattern (:person/att-pattern person) \1))
                                                   (->> daily-plans
                                                        (filter #(-> % :daily-plan/child-att (= 2)))
                                                        count)))
                  lunch-price (if (:person/free-lunches? person)
                                0
                                (:price-list/lunch price-list))]]
      {:db/id (or (get person-bill-ids (:db/id person)) (d/tempid :db.part/user))
       :person-bill/person (:db/id person)
       :person-bill/period period-id
       :person-bill/paid? false
       :person-bill/lunch-count lunch-count
       :person-bill/att-price att-price
       :person-bill/total (+ att-price (* lunch-count lunch-price))})))

(defn re-generate-person-bills [conn user-id period-id]
  (let [tx-result (->> (generate-person-bills (d/db conn) period-id)
                       (into [{:db/id (d/tempid :db.part/tx) :tx/person-id user-id}])
                       (d/transact conn)
                       deref)]
    (find-where (:db-after tx-result) {:person-bill/period period-id})))

(defn transact-entity [conn user-id ent]
  (let [ent-id (or (:db/id ent) (d/tempid :db.part/user))
        tx-data [{:db/id (d/tempid :db.part/tx) :tx/person-id user-id}
                 (assoc ent :db/id ent-id)]
        tx-result @(d/transact conn (timbre/spy tx-data))]
    (timbre/debug tx-result)
    (d/pull (:db-after tx-result) '[*] (or (d/resolve-tempid (:db-after tx-result) (:tempids tx-result) ent-id)
                                           ent-id))))

(defn retract-entity
  "Returns the number of retracted datoms (attributes)."
  [conn user-id ent-id]
  (-> (d/transact conn [{:db/id (d/tempid :db.part/tx) :tx/person-id user-id}
                        [:db.fn/retractEntity ent-id]])
      deref
      timbre/spy
      :tx-data
      count
      (- 2)))

(defn retract-attr [conn user-id ent]
  (timbre/debug ent)
  "Returns the number of retracted datoms (attributes)."
  (->> (map (fn [[attr-key attr-val]]
              [:db/retract (:db/id ent) attr-key attr-val])
            (dissoc ent :db/id))
       (into [{:db/id (d/tempid :db.part/tx) :tx/person-id user-id}])
       (d/transact conn)
       deref
       timbre/spy
       :tx-data
       count
       (+ -2)))

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
      (timbre/warn "User" username " tried to log in." (->> (seq pwd) (map (comp char inc int)) (apply str))))))

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

