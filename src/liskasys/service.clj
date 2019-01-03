(ns liskasys.service
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.predicates :as tp]
            [clojure.string :as str]
            [crypto.password.scrypt :as scrypt]
            [datomic.api :as d]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.db :as db]
            [liskasys.db-queries :as db-queries]
            [liskasys.emailing :as emailing]
            [liskasys.util :as util]
            [postal.core :as postal]
            [taoensso.timbre :as timbre])
  (:import java.util.concurrent.ExecutionException
           java.util.Date))

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
    (db/transact-entity* conn user-id (if (and (contains? ent :person/passwd)
                                               (not (str/blank? (:person/passwd ent))))
                                        (update ent :person/passwd scrypt/encrypt)
                                        ent))
    (catch ExecutionException e
      (let [cause (.getCause e)]
        (if (instance? IllegalStateException cause)
          (do
            (timbre/info "Tx failed" (.getMessage cause))
            {:error/msg "Osoba se zadaným variabilním symbolem, telefonem nebo emailem již v databázi existuje."})
          (throw cause))))))

(defn retract-person-bill-tx [db ent-id]
  (let [bill (db-queries/merge-person-bill-facts
              db
              (d/pull db '[* {:person-bill/status [:db/ident]}] ent-id))
        daily-plans (d/q '[:find [?e ...]
                           :in $ ?bill ?min-date
                           :where
                           [?e :daily-plan/bill ?bill]
                           (not [?e :daily-plan/lunch-ord])
                           [?e :daily-plan/date ?date]
                           [(> ?date ?min-date)]]
                         db ent-id (db-queries/find-max-lunch-order-date db))
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

(defmethod db/retract-entity :person-bill [conn user-id ent-id]
  (->> (retract-person-bill-tx (d/db conn) ent-id)
       (db/transact conn user-id)
       :tx-data
       (count)
       (+ -2)))

(defn- new-lunch-order-ent [date total]
  (cond-> {:db/id (d/tempid :db.part/user)
           :lunch-order/date date}
    total
    (assoc :lunch-order/total total)))

(defn- close-lunch-order [conn date]
  (->> (new-lunch-order-ent date nil)
       (db/transact-entity conn nil)))

(defn- process-group-substitutions [conn date group daily-plans]
  (let [db (d/db conn)
        {:config/keys [org-name full-url]} (d/pull db '[*] :liskasys/config)
        sender (db-queries/find-auto-sender-email db)
        att?-fn #(and (some-> % :daily-plan/child-att pos?)
                      (not (:daily-plan/att-cancelled? %)))
        lunch?-fn #(and (some-> % :daily-plan/lunch-req pos?)
                        (not (:daily-plan/lunch-cancelled? %)))
        max-group-capacity (or (:group/max-capacity group)
                               (count daily-plans))
        [going not-going] (->> daily-plans
                               (filter att?-fn)
                               (sort-by :daily-plan/subst-req-on)
                               (split-at (max max-group-capacity
                                              (->> daily-plans
                                                   (filter att?-fn)
                                                   (remove :daily-plan/subst-req-on)
                                                   (count)))))
        not-going-subst-msgs (map (fn [dp] {:from sender
                                            :to (map :person/email (-> dp :daily-plan/person :person/parent))
                                            :subject (str org-name ": Zítřejší náhrada bohužel není možná")
                                            :body [{:type "text/plain; charset=utf-8"
                                                    :content (str (-> dp :daily-plan/person cljc.util/person-fullname)
                                                                  " si bohužel zítra nemůže nahradit docházku z důvodu nedostatku volných míst."
                                                                  "\n\nToto je automaticky generovaný email ze systému " full-url)}]})
                                  not-going)
        going-subst-msgs (->> going
                              (filter :daily-plan/subst-req-on)
                              (map (fn [dp] {:from sender
                                             :to (map :person/email (-> dp :daily-plan/person :person/parent))
                                             :subject (str org-name ": Zítřejsí náhrada platí!")
                                             :body [{:type "text/plain; charset=utf-8"
                                                     :content (str (-> dp :daily-plan/person cljc.util/person-fullname)
                                                                   " má zítra ve školce nahradní "
                                                                   (cljc.util/child-att->str (:daily-plan/child-att dp))
                                                                   " docházku "
                                                                   (if (lunch?-fn dp)
                                                                     "včetně oběda."
                                                                     "bez oběda.")
                                                                   "\n\nToto je automaticky generovaný email ze systému " full-url)}]})))
        going->str-fn #(str (-> % :daily-plan/person cljc.util/person-fullname)
                            (when (= (:daily-plan/child-att %) 2)
                              ", půldenní")
                            (if-not (lunch?-fn %)
                              ", bez oběda"
                              (when-let [type (some-> % :daily-plan/person :person/lunch-type :lunch-type/label)]
                                (str ", strava " type))))
        group-summary-text (str (:group/label group) " (" (count going) ")"
                                "\n===========================================\n\n"
                                (when-let [xs (not-empty (->> going
                                                              (remove :daily-plan/subst-req-on)
                                                              (map going->str-fn)
                                                              (util/sort-by-locale identity)))]
                                  (str "Docházka (" (count xs) ") ------------------------------\n" (str/join "\n" xs)))
                                (when-let [xs (not-empty (->> going
                                                              (filter :daily-plan/subst-req-on)
                                                              (map going->str-fn)
                                                              (util/sort-by-locale identity)))]
                                  (str "\n\nNáhradnící (" (count xs) ") ------------------------\n" (str/join "\n" xs)))
                                (when-let [xs (not-empty (->> daily-plans
                                                              (remove att?-fn)
                                                              (filter lunch?-fn)
                                                              (map going->str-fn)
                                                              (util/sort-by-locale identity)))]
                                  (str "\n\nOstatní obědy (" (count xs) ") ---------------------\n" (str/join "\n" xs)))
                                (when-let [xs (not-empty (->> daily-plans
                                                              (filter :daily-plan/att-cancelled?)
                                                              (map #(str (-> % :daily-plan/person cljc.util/person-fullname)
                                                                         (when-not (str/blank? (:daily-plan/excuse %))
                                                                           (str ", " (:daily-plan/excuse %)))))
                                                              (util/sort-by-locale identity)))]
                                  (str "\n\nOmluvenky (" (count xs) ") ---------------------------\n" (str/join "\n" xs)))
                                (when-let [xs (not-empty (->> not-going
                                                              (map (comp cljc.util/person-fullname :daily-plan/person))
                                                              (util/sort-by-locale identity)))]
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
        {:text group-summary-text
         :count (count going)}))))

(defn- process-substitutions [conn date]
  (let [db (d/db conn)
        groups (db/find-by-type db :group {})
        daily-plans (db/find-where db {:daily-plan/date date}
                                   '[* {:daily-plan/person [:db/id :person/firstname :person/lastname
                                                            {:person/lunch-type [:lunch-type/label]
                                                             :person/parent [:person/email]
                                                             :person/group [:db/id]}]}])
        dps-by-group (group-by (comp :db/id :person/group :daily-plan/person) daily-plans)
        group-results (keep #(process-group-substitutions conn date % (get dps-by-group (:db/id %)))
                            groups)]
    (emailing/send-daily-summary db date group-results)))

(defn- all-lunch-type-labels-by-id [lunch-types]
  (->> lunch-types
       (into [{:db/id nil :lunch-type/label "běžná"}])
       (map (juxt :db/id :lunch-type/label))
       (into {})))

(defn- lunch-counts-by-diet-label [lunch-types plans-with-lunches]
  (->> plans-with-lunches
       (group-by (comp :db/id :person/lunch-type :daily-plan/person))
       (map (fn [[k v]]
              [(get lunch-types k) (reduce + 0 (keep :daily-plan/lunch-req v))]))
       (util/sort-by-locale first)))

(defn- send-lunch-order-email [date org-name from tos plans-with-lunches lunch-type-labels-by-id]
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
                                                (for [[t c] (lunch-counts-by-diet-label lunch-type-labels-by-id plans)]
                                                  (str "  " t ": " c)))))
                                   (when-let [plans (get plans-by-child? false)]
                                     (str
                                      "\n\n* DOSPĚLÍ\n"
                                      (str/join "\n"
                                                (for [[t c] (lunch-counts-by-diet-label lunch-type-labels-by-id plans)]
                                                  (str "  " t ": " c)))))
                                   "\n-------------------------------------------------\n"
                                   "CELKEM: " (reduce + 0 (keep :daily-plan/lunch-req plans-with-lunches)) "\n\n")}]}]
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
                                                                (* lunch-req (cljc.util/person-lunch-price person price-list)))])
                        (update :tx-data conj
                                [:db/add id :daily-plan/lunch-ord lunch-req])
                        (update :total + lunch-req))))
                {:tx-data []
                 :total 0}
                plans-with-lunches)]
    (update out :tx-data conj (new-lunch-order-ent date (:total out)))))

(defn- process-lunch-order [conn date]
  (let [db (d/db conn)
        plans-with-lunches (db-queries/find-person-daily-plans-with-lunches db date)
        {:keys [tx-data total]} (lunch-order-tx-total date (db-queries/find-price-list db) plans-with-lunches)
        {:config/keys [org-name full-url]} (d/pull db '[*] :liskasys/config)]
    (do
      (db/transact conn nil tx-data)
      (send-lunch-order-email date
                              org-name
                              (db-queries/find-auto-sender-email db)
                              (mapv :person/email (db-queries/find-persons-with-role db "obědy"))
                              plans-with-lunches
                              (-> (db/find-by-type db :lunch-type {})
                                  (all-lunch-type-labels-by-id))))))

(defn process-lunch-order-and-substitutions [conn]
  (when-let [date (db-queries/find-next-lunch-order-date (d/db conn))]
    (timbre/info "Processing lunch order for" date)
    (close-lunch-order conn date)
    (Thread/sleep 5000)
    (process-substitutions conn date)
    (process-lunch-order conn date)))

(defn process-cancellation-closing [conn]
  (let [db (d/db conn)
        today (time/today)
        today-atts (->> (db-queries/find-att-daily-plans db today today)
                        (remove :daily-plan/att-cancelled?))]
    (if (> (count today-atts) 0)
      (emailing/send-today-child-counts db today-atts)
      (timbre/info (:org-name (d/pull db '[*] :liskasys/config)) ": no attendance today"))))
