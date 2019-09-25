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

(defn retract-person-bill-tx
  ([db ent-id]
   (retract-person-bill-tx db ent-id false))
  ([db ent-id unpaid-only?]
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
     (when-not (and unpaid-only? (:-paid? bill))
       (timbre/info "preparing retract of bill" bill "with" (count daily-plans) "plans of person" person)
       (->> daily-plans
            (map (fn [dp-id]
                   [:db.fn/retractEntity dp-id]))
            (into tx-data))))))

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

(defn- daily-summary [group group-dps]
  (when (seq group-dps)
    (let [max-group-capacity (or (:group/max-capacity group)
                                 (count group-dps))
          [going not-going] (->> group-dps
                                 (filter cljc.util/daily-plan-attendance?)
                                 (sort-by :daily-plan/subst-req-on)
                                 (split-at (max max-group-capacity
                                                (->> group-dps
                                                     (filter cljc.util/daily-plan-attendance?)
                                                     (remove :daily-plan/subst-req-on)
                                                     (count)))))]
      {:group group
       :not-going not-going
       :going going
       :cancelled (filter :daily-plan/att-cancelled? group-dps)
       :other-lunches (->> group-dps
                           (remove cljc.util/daily-plan-attendance?)
                           (filter cljc.util/daily-plan-lunch?))})))

(defn prepare-daily-summaries [db date]
  (let [dps-by-group-id (->> (db-queries/find-daily-plans-by-date db date)
                             (group-by #(get-in % [:daily-plan/group :db/id])))]
    (keep (fn [group]
            (daily-summary group (get dps-by-group-id (:db/id group))))
          (db/find-by-type db :group {}))))

(defn- process-substitutions [conn date]
  (let [db (d/db conn)
        daily-summaries (prepare-daily-summaries db date)
        send-substitution-result-to-parents (emailing/make-substitution-result-sender db)]

    (doseq [{:keys [not-going going]} daily-summaries]
      (db/transact conn nil (mapv #(-> [:db.fn/retractEntity (:db/id %)])
                                  not-going))
      (doseq [daily-plan not-going]
        (send-substitution-result-to-parents daily-plan false))

      (doseq [daily-plan (filter :daily-plan/subst-req-on going)]
        (send-substitution-result-to-parents daily-plan true)))

    (emailing/send-daily-summary db date daily-summaries)))

(defn- lunch-order-tx-total [date price-lists plans-with-lunches]
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
                                                                (* lunch-req (cljc.util/person-lunch-price person (get price-lists (get-in person [:person/price-list :db/id])))))])
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
        {:keys [tx-data total]} (lunch-order-tx-total date (db-queries/find-price-lists-by-id db) plans-with-lunches)]
    (db/transact conn nil tx-data)
    (if (seq plans-with-lunches)
      (emailing/send-lunch-order-emails db date plans-with-lunches)
      (timbre/info (:config/org-name (d/pull db '[*] :liskasys/config))
                   ": no lunches for " date ". Sending skipped."))))

(defn process-lunch-order-and-substitutions [conn]
  (when-let [date (db-queries/find-next-lunch-order-date (d/db conn))]
    (timbre/info "Processing lunch order for" date)
    (close-lunch-order conn date)
    (Thread/sleep 5000)
    (process-substitutions conn date)
    (process-lunch-order conn date)))

(defn process-cancellation-closing [conn]
  (let [db (d/db conn)
        today-atts (->> (db-queries/find-att-daily-plans db (time/today))
                        (remove :daily-plan/att-cancelled?))]
    (if (> (count today-atts) 0)
      (emailing/send-today-child-counts db today-atts)
      (timbre/info (:org-name (d/pull db '[*] :liskasys/config)) ": no attendance today"))))
