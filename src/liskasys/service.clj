(ns liskasys.service
  (:require [clj-brnolib.jdbc-common :as jdbc-common]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as s]
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

(defn- valid-in-year? [bank-holiday year]
  (and (or (nil? (:valid-from-year bank-holiday)) (>= year (:valid-from-year bank-holiday)))
       (or (nil? (:valid-to-year bank-holiday)) (<= year (:valid-to-year bank-holiday)))))

(defn- bank-holiday? [bank-holidays clj-date]
  (let [y (t/year clj-date)]
    (seq (filter (fn [bh]
                   (and (valid-in-year? bh y)
                        (or (and (= (t/day clj-date) (:day bh))
                                 (= (t/month clj-date) (:month bh)))
                            (and (some? (:easter-delta bh))
                                 (t/equal? clj-date
                                           (t/plus (easter-monday-for-year-memo y)
                                                   (t/days (:easter-delta bh))))))))
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
         (sort-by first cz-collator ))))

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

(defn find-last-price-list [db-spec]
  (->> (jdbc-common/select db-spec :price-list {})
       (sort-by :valid-from)
       last))

(defn- calculate-att-price [price-list months-count days-per-week half-days-count]
  (+ (* months-count (get price-list (keyword (str "days-" days-per-week)) 0))
     (* half-days-count (:half-day price-list))))

(defn- day-numbers-from-pattern [pattern match-char]
  (->> pattern
       (map-indexed vector)
       (keep (fn [[idx ch]]
               (when (= ch match-char)
                 (inc idx))))
       set))

(defn- period-start-end
  "Returns local dates with end exclusive!!"
  [{:keys [from-yyyymm to-yyyymm] :as period}]
  [(t/local-date (quot from-yyyymm 100) (rem from-yyyymm 100) 1)
   (t/plus (t/local-date (quot to-yyyymm 100) (rem to-yyyymm 100) 1)
           (t/months 1))])

(defn- generate-daily-plan
  [{:keys [lunch-pattern att-pattern] person-id :id :as person} billing-period bank-holidays]
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
                        {:person-id person-id
                         :date (tc/to-date ld)
                         :lunch? lunch?
                         :child-att child-att})))))))

(defn- generate-person-bills [db-spec period-id]
  (let [billing-period (first (jdbc-common/select db-spec :billing-period {:id period-id}))
        price-list (find-last-price-list db-spec)
        bank-holidays (jdbc-common/select db-spec :bank-holiday {})]
    (doseq [person (db/select-active-persons db-spec)
            :let [daily-plans (generate-daily-plan (timbre/spy person) billing-period bank-holidays)
                  lunch-count (->> daily-plans
                                   (filter :lunch?)
                                   count)
                  att-price (if (:free-att? person)
                              0
                              (calculate-att-price price-list
                                                   (- (:to-yyyymm billing-period)
                                                      (:from-yyyymm billing-period)
                                                      -1)
                                                   (count (day-numbers-from-pattern (:att-pattern person) \1))
                                                   (->> daily-plans
                                                        (filter #(-> % :child-att (= 2)))
                                                        count)))
                  lunch-price (if (:free-lunches? person)
                                0
                                (:lunch price-list))]]
      (jdbc-common/insert! db-spec :person-bill (-> person
                                                    (select-keys [:id :var-symbol :att-pattern :lunch-pattern])
                                                    (s/rename-keys {:id :person-id})
                                                    (merge {:period-id period-id
                                                            :paid? false
                                                            :total-lunches lunch-count
                                                            :att-price-cents att-price
                                                            :total-cents (+ att-price (* lunch-count lunch-price))}))))))

(defn re-generate-person-bills [db-spec period-id]
  (jdbc/with-db-transaction [tx db-spec]
    (jdbc-common/delete! tx :person-bill {:period-id period-id :paid? false})
    (generate-person-bills tx period-id)
    (jdbc-common/select tx :person-bill {:period-id period-id})))

(def ent-type->attr
  {:lunch-type :lunch-type/label
   :person :person/firstname})

(defn- build-query [db attr where-m]
  (reduce (fn [query [where-attr where-val]]
                        (let [?where-attr (symbol (str "?" (name where-attr)))]
                          (-> query
                              (update-in [:query :in] conj ?where-attr)
                              (update-in [:query :where] conj ['?e where-attr ?where-attr])
                              (update-in [:args] conj where-val))))
                      {:query {:find ['[(pull ?e [*]) ...]]
                               :in ['$]
                               :where [['?e attr]]}
                       :args [db]
                       :timeout 2000}
                      where-m))

(defn find-all [db entity-type where-m]
  (let [attr (get ent-type->attr entity-type entity-type)
        query (build-query db attr where-m)]
    (cond->> (d/query query)
      (= entity-type :person)
      (map #(-> %
                db/assoc-fullname
                (dissoc :person/passwd))))))

(defn transact-entity [conn user-id ent]
  (let [ent-id (or (:db/id ent) (d/tempid :db.part/user))
        tx-data [{:db/id (d/tempid :db.part/tx) :tx/person-id user-id}
                 (assoc ent :db/id ent-id)]
        tx-result @(d/transact conn (timbre/spy tx-data))]
    (timbre/debug tx-result)
    (d/pull (:db-after tx-result) '[*] (or (d/resolve-tempid (:db-after tx-result) (:tempids tx-result) ent-id)))))

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
