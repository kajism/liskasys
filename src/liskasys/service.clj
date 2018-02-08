(ns liskasys.service
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clojure.string :as str]
            [crypto.password.scrypt :as scrypt]
            [datomic.api :as d]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.easter :as easter]
            [liskasys.db :as db]
            [postal.core :as postal]
            [taoensso.timbre :as timbre])
  (:import java.text.Collator
           java.util.concurrent.ExecutionException
           java.util.Locale
           java.util.Date))

(def cs-collator (Collator/getInstance (Locale. "CS")))

(defn sort-by-locale [key-fn coll]
  (sort-by key-fn cs-collator coll))

(defn- *bank-holiday? [{:keys [:bank-holiday/day :bank-holiday/month :bank-holiday/easter-delta]} local-date]
  (or (and (= (t/day local-date) day)
           (= (t/month local-date) month))
      (and (some? easter-delta)
           (t/equal? local-date
                     (t/plus (easter/easter-monday-for-year-ld (t/year local-date))
                             (t/days easter-delta))))))

(defn- bank-holiday? [bank-holidays local-date]
  (seq (filter #(*bank-holiday? % local-date) bank-holidays)))

(defn- *school-holiday? [{:keys [:school-holiday/from :school-holiday/to :school-holiday/every-year?]} local-date]
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

(declare merge-person-bill-facts)

(defn find-max-lunch-order-date [db]
  (or (d/q '[:find (max ?date) .
             :where [_ :lunch-order/date ?date]]
           db)
      #inst "2000"))

(defn- retract-person-bill-tx [db ent-id]
  (let [bill (merge-person-bill-facts
              db
              (d/pull db '[* {:person-bill/status [:db/ident]}] ent-id))
        daily-plans (d/q '[:find [?e ...]
                           :in $ ?bill ?min-date
                           :where
                           [?e :daily-plan/bill ?bill]
                           (not [?e :daily-plan/lunch-ord])
                           [?e :daily-plan/date ?date]
                           [(> ?date ?min-date)]]
                         db ent-id (find-max-lunch-order-date db))
        person (d/pull db '[*] (get-in bill [:person-bill/person :db/id]))
        tx-data (cond-> [[:db.fn/retractEntity ent-id]]
                  (and (= (-> bill :person-bill/status :db/ident) :person-bill.status/paid)
                       (:person/lunch-fund person))
                  (conj [:db.fn/cas (:db/id person) :person/lunch-fund (:person/lunch-fund person)
                         (- (:person/lunch-fund person) (- (:person-bill/total bill) (:person-bill/att-price bill)))]))]
    (timbre/info "preparing retract of bill" bill "with" (count daily-plans) "plans of person" person)
    (->> daily-plans
         (map (fn [dp-id]
                [:db.fn/retractEntity dp-id]))
         (into tx-data))))

(defmethod db/transact-entity :daily-plan [conn user-id ent]
  (let [old-id (d/q '[:find ?e .
                      :in $ ?date ?pid
                      :where
                      [?e :daily-plan/date ?date]
                      [?e :daily-plan/person ?pid]]
                    (d/db conn)
                    (:daily-plan/date ent)
                    (:db/id (:daily-plan/person ent)))
        remove-substitution? (and (:daily-plan/refund? ent)
                                  (:daily-plan/substituted-by ent))]
    (if (and old-id
             (not= old-id (:db/id ent)))
      {:error/msg "Pro tuto osobu a den již v denním plánu existuje jiný záznam."}
      (do
        (when remove-substitution?
          (db/retract-entity conn user-id (get-in ent [:daily-plan/substituted-by :db/id])))
        (db/transact-entity* conn user-id (cond-> ent
                                            remove-substitution?
                                            (dissoc :daily-plan/substituted-by)))))))

(defmethod db/transact-entity :person [conn user-id ent]
  (try
    (db/transact-entity* conn user-id (update ent :person/passwd #(when % (scrypt/encrypt %))))
    (catch ExecutionException e
      (let [cause (.getCause e)]
        (if (instance? IllegalStateException cause)
          (do
            (timbre/info "Tx failed" (.getMessage cause))
            {:error/msg "Osoba se zadaným variabilním symbolem nebo emailem již v databázi existuje."})
          (throw cause))))))

(defmethod db/retract-entity :person-bill [conn user-id ent-id]
  (->> (retract-person-bill-tx (d/db conn) ent-id)
       (db/transact conn user-id)
       :tx-data
       count
       (+ -2)))

(defn find-price-list [db]
  (first (db/find-where db {:price-list/days-1 nil})))

(defn- person-lunch-price [{child? :person/child?} {lunch-child :price-list/lunch lunch-adult :price-list/lunch-adult}]
  (if child?
    lunch-child
    (or lunch-adult lunch-child)))

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
        lunch-price (person-lunch-price person (find-price-list db))
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

(defmethod db/find-by-type :daily-plan [db ent-type where-m]
  (db/find-by-type-default db ent-type where-m '[* {:daily-plan/_substituted-by [:db/id]}]))

(defn make-holiday?-fn [db]
  (let [bank-holidays (db/find-where db {:bank-holiday/label nil})
        school-holidays (db/find-where db {:school-holiday/label nil})]
    (fn [ld]
      (or (bank-holiday? bank-holidays ld)
          (school-holiday? school-holidays ld)))))

(defn- find-person-daily-plans-with-lunches [db date]
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

(defn find-persons-with-role [db role]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?role
         :where
         [?e :person/roles ?roles]
         [(clojure.string/index-of ?roles ?role)]]
       db
       role))

(defn- new-lunch-order-ent [date total]
  (cond-> {:db/id (d/tempid :db.part/user)
           :lunch-order/date date}
    total
    (assoc :lunch-order/total total)))

(defn- close-lunch-order [conn date]
  (->> (new-lunch-order-ent date nil)
       (db/transact-entity conn nil)))

(defn auto-sender-email [db]
  (or (not-empty (:person/email (first (find-persons-with-role db "koordinátor"))))
      (not-empty (:config/automat-email (d/pull db '[*] :liskasys/config)))))

(defn- process-group-substitutions [conn date group daily-plans]
  (let [db (d/db conn)
        {:config/keys [org-name full-url]} (d/pull db '[*] :liskasys/config)
        sender (auto-sender-email db)
        att?-fn #(and (some-> % :daily-plan/child-att pos?)
                      (not (:daily-plan/att-cancelled? %)))
        lunch?-fn #(and (some-> % :daily-plan/lunch-req pos?)
                        (not (:daily-plan/lunch-cancelled? %)))
        [going not-going] (->> daily-plans
                               (filter att?-fn)
                               (sort-by :daily-plan/subst-req-on)
                               (partition-all (or (:group/max-capacity group)
                                                  (count daily-plans))))
        not-going-subst-msgs (map (fn [dp] {:from sender
                                            :to (map :person/email (-> dp :daily-plan/person :person/parent))
                                            :subject (str org-name ": Zítřejší náhrada bohužel není možná")
                                            :body [{:type "text/plain; charset=utf-8"
                                                    :content (str (-> dp :daily-plan/person cljc-util/person-fullname)
                                                                  " si bohužel zítra nemůže nahradit docházku z důvodu nedostatku volných míst."
                                                                  "\n\nToto je automaticky generovaný email ze systému " full-url)}]})
                                  not-going)
        going-subst-msgs (->> going
                              (filter :daily-plan/subst-req-on)
                              (map (fn [dp] {:from sender
                                             :to (map :person/email (-> dp :daily-plan/person :person/parent))
                                             :subject (str org-name ": Zítřejsí náhrada platí!")
                                             :body [{:type "text/plain; charset=utf-8"
                                                     :content (str (-> dp :daily-plan/person cljc-util/person-fullname)
                                                                   " má zítra ve školce nahradní "
                                                                   (cljc-util/child-att->str (:daily-plan/child-att dp))
                                                                   " docházku "
                                                                   (if (lunch?-fn dp)
                                                                     "včetně oběda."
                                                                     "bez oběda.")
                                                                   "\n\nToto je automaticky generovaný email ze systému " full-url)}]})))
        going->str-fn #(str (-> % :daily-plan/person cljc-util/person-fullname)
                            (when (= (:daily-plan/child-att %) 2)
                              ", půldenní")
                            (if-not (lunch?-fn %)
                              ", bez oběda"
                              (when-let [type (some-> % :daily-plan/person :person/lunch-type :lunch-type/label)]
                                (str ", strava " type))))
        group-summary-text (str (:group/label group)
                                "\n===========================================\n\n"
                                (when-let [xs (not-empty (->> going
                                                              (remove :daily-plan/subst-req-on)
                                                              (map going->str-fn)
                                                              (sort-by-locale identity)))]
                                  (str "Docházka (" (count xs) ") ------------------------------\n" (str/join "\n" xs)))
                                (when-let [xs (not-empty (->> going
                                                              (filter :daily-plan/subst-req-on)
                                                              (map going->str-fn)
                                                              (sort-by-locale identity)))]
                                  (str "\n\nNáhradnící (" (count xs) ") ------------------------\n" (str/join "\n" xs)))
                                (when-let [xs (not-empty (->> daily-plans
                                                              (remove att?-fn)
                                                              (filter lunch?-fn)
                                                              (map going->str-fn)
                                                              (sort-by-locale identity)))]
                                  (str "\n\nOstatní obědy (" (count xs) ") ---------------------\n" (str/join "\n" xs)))
                                "\n\n-------------------------------------------\n"
                                (when-let [xs (not-empty (->> daily-plans
                                                              (filter :daily-plan/att-cancelled?)
                                                              (map (comp cljc-util/person-fullname :daily-plan/person))
                                                              (sort-by-locale identity)))]
                                  (str "\nOmluvenky (" (count xs) ") ---------------------------\n" (str/join "\n" xs)))
                                (when-let [xs (not-empty (->> not-going
                                                              (map (comp cljc-util/person-fullname :daily-plan/person))
                                                              (sort-by-locale identity)))]
                                  (str "\n\nNáhradníci, kteří se nevešli (" (count xs) ") ------\n" (str/join "\n" xs)))
                                "\n\n")]
    (if-not (seq daily-plans)
      (timbre/info "No daily plans for" date "group" (:group/label group))
      (do
        (db/transact conn nil (mapv (comp #(vector :db.fn/retractEntity %) :db/id) not-going))
        (doseq [msg  not-going-subst-msgs]
          (timbre/info "Sending to not going" msg)
          (timbre/info (postal/send-message msg)))
        (doseq [msg  going-subst-msgs]
          (timbre/info "Sending to going" msg)
          (timbre/info (postal/send-message msg)))
        group-summary-text))))

(defn- process-substitutions [conn date]
  (let [db (d/db conn)
        groups (db/find-where db {:group/label nil})
        daily-plans (db/find-where db {:daily-plan/date date}
                                   '[* {:daily-plan/person [:db/id :person/firstname :person/lastname
                                                            {:person/lunch-type [:lunch-type/label]
                                                             :person/parent [:person/email]
                                                             :person/group [:db/id]}]}])
        dps-by-group (group-by (comp :db/id :person/group :daily-plan/person) daily-plans)
        summary-text (reduce (fn [out group]
                               (str out (process-group-substitutions conn date group (get dps-by-group (:db/id group)))))
                             ""
                             groups)
        {:config/keys [org-name full-url]} (d/pull db '[*] :liskasys/config)
        sender (auto-sender-email db)
        admin-subj (str org-name ": Denní souhrn na " (time/format-day-date date))
        summary-msg {:from sender
                     :to (-> #{}
                             #_(into (map :person/email (find-persons-with-role db "admin")))
                             (into (map :person/email (find-persons-with-role db "průvodce")))
                             (vec))
                     :subject admin-subj
                     :body [{:type "text/plain; charset=utf-8"
                             :content (str admin-subj "\n\n"
                                           summary-text
                                           "\n\nToto je automaticky generovaný email ze systému " full-url)}]}]

    (timbre/info "Sending summary msg" summary-msg)
    (timbre/info (postal/send-message summary-msg))))

(defn- find-lunch-types-by-id [db]
  (->> (db/find-where db {:lunch-type/label nil})
       (into [{:db/id nil :lunch-type/label "běžná"}])
       (map (juxt :db/id :lunch-type/label))
       (into {})))

(defn- find-lunch-counts-by-diet-label [lunch-types plans-with-lunches]
  (->> plans-with-lunches
       (group-by (comp :db/id :person/lunch-type :daily-plan/person))
       (map (fn [[k v]]
              [(get lunch-types k) (reduce + 0 (keep :daily-plan/lunch-req v))]))
       (sort-by-locale first)))

(defn- send-lunch-order-email [date org-name from tos plans-with-lunches lunch-types-by-id]
  (let [subject (str org-name ": Objednávka obědů na " (time/format-day-date date))
        plans-by-child? (group-by #(boolean (get-in % [:daily-plan/person :person/child?])) plans-with-lunches)
        msg {:from from
             :to tos
             :subject subject
             :body [{:type "text/plain; charset=utf-8"
                     :content (str subject "\n"
                                   "-------------------------------------------------\n\n"
                                   (when-let [plans (get plans-by-child? true)]
                                     (str
                                      "* DĚTI\n"
                                      (str/join "\n"
                                                (for [[t c] (find-lunch-counts-by-diet-label lunch-types-by-id plans)]
                                                  (str "  " t ": " c)))))
                                   (when-let [plans (get plans-by-child? false)]
                                     (str
                                      "\n\n* DOSPĚLÍ\n"
                                      (str/join "\n"
                                                (for [[t c] (find-lunch-counts-by-diet-label lunch-types-by-id plans)]
                                                  (str "  " t ": " c)))))
                                   "\n-------------------------------------------------\n"
                                   "CELKEM: " (count plans-with-lunches) "\n\n")}]}]
    #_(print (get-in msg [:body 0 :content]))
    (if-not (seq plans-with-lunches)
      (timbre/info "No lunches for " date ". Sending skipped.")
      (do
        (timbre/info "Sending " (:subject msg) "to" (:to msg))
        (timbre/debug msg)
        (let [result (postal/send-message msg)]
          (if (zero? (:code result))
            (timbre/info "Lunch order has been sent" result)
            (timbre/error "Failed to send email" result)))))))

(defn- lunch-order-tx-total [date price-list plans-with-lunches]
  (let [out
        (reduce (fn [out {:keys [:db/id :daily-plan/person :daily-plan/lunch-req] :as plan-with-lunch}]
                  (if-not person
                    (do
                      (timbre/error "missing person of plan-with-lunch" plan-with-lunch)
                      out)
                    (-> out
                        (update :tx-data conj
                                [:db.fn/cas (:db/id person) :person/lunch-fund
                                 (:person/lunch-fund person) (- (or (:person/lunch-fund person) 0)
                                                                (* lunch-req (person-lunch-price person price-list)))])
                        (update :tx-data conj
                                [:db/add id :daily-plan/lunch-ord lunch-req])
                        (update :total + lunch-req))))
                {:tx-data []
                 :total 0}
                plans-with-lunches)]
    (update out :tx-data conj (new-lunch-order-ent date (:total out)))))

(defn- date-yyyymm [date]
  (let [ld (tc/to-local-date date)]
    (+ (* (t/year ld) 100)
       (t/month ld))))

(defn find-previous-periods [db before-date]
  (->> (d/q '[:find [(pull ?e [*]) ...]
              :in $ ?before-date
              :where
              [?e :billing-period/to-yyyymm ?to]
              [(< ?to ?before-date)]]
            db (date-yyyymm before-date))
       (sort-by :billing-period/to-yyyymm)
       (reverse)))

(defn find-current-period [db]
  (d/q '[:find (pull ?e [*]) .
         :in $ ?today
         :where
         [?e :billing-period/from-yyyymm ?from]
         [(<= ?from ?today)]
         [?e :billing-period/to-yyyymm ?to]
         [(<= ?today ?to)]]
       db (date-yyyymm (Date.))))

(defn- process-lunch-order [conn date]
  (let [db (d/db conn)
        plans-with-lunches (find-person-daily-plans-with-lunches db date)
        {:keys [tx-data total]} (lunch-order-tx-total date (find-price-list db) plans-with-lunches)
        {:config/keys [org-name full-url]} (d/pull db '[*] :liskasys/config)]
    (do
      (db/transact conn nil tx-data)
      (send-lunch-order-email date
                              org-name
                              (auto-sender-email db)
                              (mapv :person/email (find-persons-with-role db "obědy"))
                              plans-with-lunches
                              (find-lunch-types-by-id db)))))

(defn- calculate-att-price [price-list months-count days-per-week half-days-count]
  (let [months-count (if (< months-count 0)
                       0
                       months-count)]
    (+ (* months-count (get price-list (keyword "price-list" (str "days-" days-per-week)) 0))
       (* half-days-count (:price-list/half-day price-list)))))

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

(defn- period-dates [holiday?-fn from to]
  "Returns all local-dates except holidays from - to (exclusive)."
  (->> from
       (iterate (fn [ld]
                  (t/plus ld (t/days 1))))
       (take-while (fn [ld]
                     (t/before? ld to)))
       (remove holiday?-fn)))

(defn- find-lunch-count-planned [db person-id]
  (or (d/q '[:find (sum ?lunch-req) .
             :with ?e
             :in $ ?person
             :where
             [?e :daily-plan/person ?person]
             [?e :daily-plan/lunch-req ?lunch-req]
             (not [?e :daily-plan/lunch-ord])
             (not [?e :daily-plan/lunch-cancelled? true])]
           db person-id)
      0))

(defn- find-period-daily-plans [db period-id]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?period-id
         :where
         [?e :daily-plan/bill ?bill-id]
         [?bill-id :person-bill/period ?period-id]]
       db period-id))

(defn- generate-person-bills-tx [db period-id]
  (let [billing-period (db/find-by-id db period-id)
        dates (apply period-dates (make-holiday?-fn db) (cljc-util/period-start-end billing-period))
        second-month-start (-> (cljc-util/period-start-end billing-period)
                               (first)
                               (t/plus (t/months 1)))
        person-id--2nd-previous-dps (some->> (find-previous-periods db (first dates))
                                             (second)
                                             :db/id
                                             (find-period-daily-plans db)
                                             (group-by #(get-in % [:daily-plan/person :db/id]))
                                             (into {}))
        published-status (d/entid db :person-bill.status/published)
        person-id--bill (atom (->> (db/find-where db {:person-bill/period period-id})
                                   (map #(vector (get-in % [:person-bill/person :db/id]) %))
                                   (into {})))
        price-list (find-price-list db)
        out (->>
             (for [person (->> (db/find-where db {:person/active? true})
                               (remove cljc-util/zero-patterns?))
                   :let [daily-plans (generate-daily-plans person dates)
                         previous-dps (get person-id--2nd-previous-dps (:db/id person))
                         months-count (cond-> (- (:billing-period/to-yyyymm billing-period)
                                                 (:billing-period/from-yyyymm billing-period)
                                                 -1)
                                        (some-> (:person/start-date person) (tc/to-local-date) (t/before? second-month-start) (not))
                                        (dec)
                                        (some :daily-plan/refund? previous-dps)
                                        (dec)
                                        (and (seq previous-dps) (every? :daily-plan/refund? previous-dps))
                                        (dec))
                         att-price (calculate-att-price price-list
                                                        months-count
                                                        (count (->> person :person/att-pattern pattern-map vals (filter (partial = 1))))
                                                        (->> daily-plans
                                                             (filter #(-> % :daily-plan/child-att (= 2)))
                                                             count))
                         lunch-count-next (->> daily-plans
                                               (keep :daily-plan/lunch-req)
                                               (reduce + 0))
                         existing-bill (get @person-id--bill (:db/id person))]]
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
                           :person-bill/total (+ att-price
                                                 (- (* (person-lunch-price person price-list)
                                                       (+ lunch-count-next (find-lunch-count-planned db (:db/id person))))
                                                    (or (:person/lunch-fund person) 0)))}))))
             (filterv some?))]
    (->> (vals @person-id--bill)
         (mapcat #(retract-person-bill-tx db (:db/id %)))
         (into out))))

(defn- transact-period-person-bills [conn user-id period-id tx-data]
  (let [tx-result (db/transact conn user-id tx-data)]
    (db/find-by-type (:db-after tx-result) :person-bill {:person-bill/period period-id})))

(defn re-generate-person-bills [conn user-id period-id]
  (->> (generate-person-bills-tx (d/db conn) period-id)
       (transact-period-person-bills conn user-id period-id)))

(defn publish-all-bills [conn user-id period-id]
  (let [db (d/db conn)
        {:config/keys [org-name full-url]} (d/pull db '[*] :liskasys/config)
        billing-period (db/find-by-id db period-id)
        new-bill-ids (d/q '[:find [?e ...]
                            :in $ ?period-id
                            :where
                            [?e :person-bill/period ?period-id]
                            [?e :person-bill/status :person-bill.status/new]]
                          db period-id)
        out (->> new-bill-ids
                 (mapv (fn [id]
                         [:db/add id :person-bill/status :person-bill.status/published]))
                 (transact-period-person-bills conn user-id period-id))]
    (doseq [id new-bill-ids]
      (let [bill (first (db/find-by-type db :person-bill {:db/id id}))
            price-list (find-price-list db)
            subject (str org-name ": Platba školkovného a obědů na období " (-> bill :person-bill/period cljc-util/period->text))
            msg {:from (auto-sender-email db)
                 :to (or (-> bill :person-bill/person :person/email)
                         (mapv :person/email (-> bill :person-bill/person :person/parent)))
                 :subject subject
                 :body [{:type "text/plain; charset=utf-8"
                         :content (str subject "\n"
                                       "---------------------------------------------------------------------------------\n\n"
                                       "Číslo účtu: " (:price-list/bank-account price-list) "\n"
                                       "Částka: " (/ (:person-bill/total bill) 100) " Kč\n"
                                       "Variabilní symbol: " (-> bill :person-bill/person :person/var-symbol) "\n"
                                       "Do poznámky: " (-> bill :person-bill/person cljc-util/person-fullname) " "
                                       (-> bill :person-bill/period cljc-util/period->text) "\n"
                                       "Splatnost do: " (or (:price-list/payment-due-date (find-price-list db)) "20. dne tohoto měsíce") "\n\n"
                                       "Pro QR platbu přejděte na " full-url " menu Platby"
                                       "\n\nToto je automaticky generovaný email ze systému " full-url)}]}]
        (timbre/info "Sending info about published payment" msg)
        (timbre/info (postal/send-message msg))))
    out))

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
        order-date (tc/to-local-date (find-max-lunch-order-date db))
        dates (cond->> (apply period-dates (make-holiday?-fn db) (cljc-util/period-start-end (db/find-by-id db period-id)))
                order-date
                (drop-while #(not (t/after? (tc/to-local-date %) order-date))))
        tx-result (->> (generate-daily-plans person dates)
                       (map #(-> % (assoc :db/id (d/tempid :db.part/user)
                                          :daily-plan/bill bill-id)))
                       (into [[:db/add bill-id :person-bill/status :person-bill.status/paid]
                              [:db.fn/cas (:db/id person) :person/lunch-fund
                               (:person/lunch-fund person) (+ (or (:person/lunch-fund person) 0)
                                                              (- total att-price))]])
                       (db/transact conn user-id))]
    (db/find-by-type (:db-after tx-result) :person-bill {:db/id bill-id})))

#_(defn all-period-bills-paid [conn user-id period-id]
    (let [db (d/db conn)
          billing-period (find-by-id db period-id)
          dates (apply period-dates (make-holiday?-fn db) (cljc-util/period-start-end billing-period))]
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

(defn find-max-person-paid-period-date [db person-id]
  (when-let [to-yyyymm (d/q '[:find (max ?yyyymm) .
                              :in $ ?person
                              :where
                              [?e :person-bill/person ?person]
                              [?e :person-bill/status :person-bill.status/paid]
                              [?e :person-bill/period ?p]
                              [?p :billing-period/to-yyyymm ?yyyymm]]
                            db person-id)]
    (-> (t/local-date (quot to-yyyymm 100) (rem to-yyyymm 100) 1)
        (t/plus (t/months 1))
        (t/minus (t/days 1))
        tc/to-date)))

(defn find-person-daily-plans [db person-id date-from date-to]
  (when (and person-id date-from date-to)
    (->>
     (d/q '[:find [(pull ?e [*]) ...]
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

(defn- find-next-school-day-date [db from-date]
  (d/q '[:find (min ?date) .
         :in $ ?from-date
         :where
         [_ :daily-plan/date ?date]
         [(< ?from-date ?date)]]
       db from-date))

(defn- next-lunch-order-date
  ([db]
   (next-lunch-order-date db (time/today)))
  ([db from-date]
   (let [last-order-date (find-max-lunch-order-date db)
         next-school-day-date (find-next-school-day-date db from-date)]
     (when (or (not last-order-date)
               (not next-school-day-date)
               (and (> (.getTime next-school-day-date) (.getTime last-order-date))
                    (< (- (.getTime next-school-day-date) (.getTime from-date)) (* 14 24 60 60 1000)) ;; max 14 days ahead
))
       next-school-day-date))))

(defn process-lunch-order-and-substitutions [conn]
  (when-let [date (next-lunch-order-date (d/db conn))]
    (timbre/info "Processing lunch order for" date)
    (close-lunch-order conn date)
    (Thread/sleep 5000)
    (process-substitutions conn date)
    (process-lunch-order conn date)))
