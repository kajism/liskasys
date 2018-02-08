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
            [liskasys.easter :as easter]
            [liskasys.service :as service]
            [postal.core :as postal]
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

(defn find-active-children-by-person-id [db parent-id]
  (->> (d/q '[:find [(pull ?e [*]) ...]
              :in $ ?parent
              :where
              [?e :person/parent ?parent]
              [?e :person/active? true]]
            db parent-id)
       (service/sort-by-locale cljc.util/person-fullname)))

(defn- make-can-cancel-lunch?-fn [db]
  (let [max-lunch-order-date (tc/to-local-date (service/find-max-lunch-order-date db))]
    (fn [date]
      (t/after? (tc/to-local-date date) max-lunch-order-date))))

(defn- HHmm--int [s]
  (-> s
      (str/replace ":" "")
      (cljc.util/parse-int)))

(defn- make-can-cancel-today?-fn [db]
  (let [{:config/keys [cancel-time]} (d/pull db '[*] :liskasys/config)]
    (fn [date]
      (let [now (Date.)]
        (or (< (.getTime now) (.getTime date))
            (and (= (time/to-format now time/ddMMyyyy)
                    (time/to-format date time/ddMMyyyy))
                 (< (HHmm--int (time/to-format now time/HHmm))
                    (HHmm--int cancel-time))))))))

(defn transact-cancellations [conn user-id child-id cancel-dates uncancel-dates excuses-by-date]
  (let [db (d/db conn)
        can-cancel-lunch?-fn (make-can-cancel-lunch?-fn db)
        can-cancel-today?-fn (make-can-cancel-today?-fn db)]
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
                         ;; (and subst-req-on (not lunch-ord))
                         ;; (conj [:db.fn/retractEntity id])
)
                       :else
                       (cond-> [[:db/retract id :daily-plan/att-cancelled? true]
                                [:db/retract id :daily-plan/lunch-cancelled? true]]
                         excuse
                         (conj [:db/retract id :daily-plan/excuse excuse])
                         substituted-by
                         (conj [:db.fn/retractEntity (:db/id substituted-by)])))))
           (db/transact conn user-id)
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
  (let [date-from (service/find-max-lunch-order-date db)
        date-to (service/find-max-person-paid-period-date db person-id)
        can-cancel-today?-fn (make-can-cancel-today?-fn db)
        out (service/find-person-daily-plans db person-id date-from date-to)]
    (cond->> out
      (and (= date-from (:daily-plan/date (first out)))
           (or (not (pos-int? (:daily-plan/lunch-ord (first out))))
               (not (can-cancel-today?-fn date-from))))
      (drop 1))))

(defn find-previous-periods [db before-date]
  (->> (d/q '[:find [(pull ?e [*]) ...]
              :in $ ?before-date
              :where
              [?e :billing-period/to-yyyymm ?to]
              [(< ?to ?before-date)]]
            db (cljc.util/date-yyyymm before-date))
       (sort-by :billing-period/to-yyyymm)
       (reverse)))

(defn find-person-substs [db person-id]
  (let [person (db/find-by-id db person-id)
        group (db/find-by-id db (get-in person [:person/group :db/id]))
        date-from (some-> (or (first (find-previous-periods db (Date.)))
                              (service/find-current-period db)
                              {:billing-period/from-yyyymm 200001
                               :billing-period/to-yyyymm 200001})
                          (cljc.util/period-start-end)
                          (first)
                          (tc/to-date))
        date-to (service/find-max-person-paid-period-date db person-id)
        substable-dps (->> (service/find-person-daily-plans db person-id date-from date-to)
                           (filter #(and (:daily-plan/att-cancelled? %)
                                         (not (:daily-plan/substituted-by %))
                                         (not (:daily-plan/subst-req-on %))
                                         (not (:daily-plan/refund? %)))))
        all-plans (service/find-att-daily-plans db
                                                (some-> (service/find-max-lunch-order-date db)
                                                        (tc/to-local-date)
                                                        (t/plus (t/days 1))
                                                        (tc/to-date))
                                                date-to)]
    (timbre/debug "finding-person-substs from" date-from "to" date-to "all plans count" (count all-plans))
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
      (db/transact conn user-id [(cond-> {:db/id db-id
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

(defn make-holiday?-fn [db]
  (let [bank-holidays (db/find-where db {:bank-holiday/label nil})
        school-holidays (db/find-where db {:school-holiday/label nil})]
    (fn [ld]
      (or (bank-holiday? bank-holidays ld)
          (school-holiday? school-holidays ld)))))

(defn- period-dates [holiday?-fn from to]
  "Returns all local-dates except holidays from - to (exclusive)."
  (->> from
       (iterate (fn [ld]
                  (t/plus ld (t/days 1))))
       (take-while (fn [ld]
                     (t/before? ld to)))
       (remove holiday?-fn)))

(defn- find-period-daily-plans [db period-id]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?period-id
         :where
         [?e :daily-plan/bill ?bill-id]
         [?bill-id :person-bill/period ?period-id]]
       db period-id))

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

(defn- pattern-map [pattern]
  (->> pattern
       (map-indexed vector)
       (keep (fn [[idx ch]]
               [(inc idx) (- (int ch) (int \0))]))
       (into {})))

(defn- calculate-att-price [price-list months-count days-per-week half-days-count]
  (let [months-count (if (< months-count 0)
                       0
                       months-count)]
    (+ (* months-count (get price-list (keyword "price-list" (str "days-" days-per-week)) 0))
       (* half-days-count (:price-list/half-day price-list)))))

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

(defn- generate-person-bills-tx [db period-id]
  (let [billing-period (db/find-by-id db period-id)
        dates (apply period-dates (make-holiday?-fn db) (cljc.util/period-start-end billing-period))
        second-month-start (-> (cljc.util/period-start-end billing-period)
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
        price-list (db/find-price-list db)
        out (->>
             (for [person (->> (db/find-where db {:person/active? true})
                               (remove cljc.util/zero-patterns?))
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
                                                 (- (* (service/person-lunch-price person price-list)
                                                       (+ lunch-count-next (find-lunch-count-planned db (:db/id person))))
                                                    (or (:person/lunch-fund person) 0)))}))))
             (filterv some?))]
    (->> (vals @person-id--bill)
         (mapcat #(service/retract-person-bill-tx db (:db/id %)))
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
            price-list (db/find-price-list db)
            subject (str org-name ": Platba školkovného a obědů na období " (-> bill :person-bill/period cljc.util/period->text))
            msg {:from (service/auto-sender-email db)
                 :to (or (-> bill :person-bill/person :person/email)
                         (mapv :person/email (-> bill :person-bill/person :person/parent)))
                 :subject subject
                 :body [{:type "text/plain; charset=utf-8"
                         :content (str subject "\n"
                                       "---------------------------------------------------------------------------------\n\n"
                                       "Číslo účtu: " (:price-list/bank-account price-list) "\n"
                                       "Částka: " (/ (:person-bill/total bill) 100) " Kč\n"
                                       "Variabilní symbol: " (-> bill :person-bill/person :person/var-symbol) "\n"
                                       "Do poznámky: " (-> bill :person-bill/person cljc.util/person-fullname) " "
                                       (-> bill :person-bill/period cljc.util/period->text) "\n"
                                       "Splatnost do: " (or (:price-list/payment-due-date (db/find-price-list db)) "20. dne tohoto měsíce") "\n\n"
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
        order-date (tc/to-local-date (service/find-max-lunch-order-date db))
        dates (cond->> (apply period-dates (make-holiday?-fn db) (cljc.util/period-start-end (db/find-by-id db period-id)))
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
