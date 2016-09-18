(ns liskasys.datomic-migration
  (:require [clj-brnolib.jdbc-common :as jdbc-common]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clojure.java.jdbc :as jdbc]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [clojure.string :as str]
            [datomic.api :as d]
            [liskasys.db :as db]
            [liskasys.service :as service]
            [taoensso.timbre :as timbre]))

(defn- lunch-type [{:keys [db-spec db] :as ctx}]
  (->> (jdbc-common/select db-spec :lunch-type {})
       (reduce (fn [ctx {:keys [id label color]}]
                 (let [eid (first (d/q '[:find [?e ...]
                                         :in $ ?label
                                         :where [?e :lunch-type/label ?label]]
                                       db
                                       label))
                       eid (or eid (d/tempid :db.part/user))]
                   (-> ctx
                       (assoc-in [:lunch-type-ids id] eid)
                       (update :tx-data conj {:db/id eid
                                              :lunch-type/label label
                                              :lunch-type/color color}))))
               ctx)))

(defn- lunch-menu [ctx]
  (->> (jdbc-common/select (:db-spec ctx) :lunch-menu {})
       (sort-by :created)
       (reduce (fn [ctx {:keys [id text content-type orig-filename created]}]
                 (let [from (-> created tc/from-sql-date t/with-time-at-start-of-day tc/to-date)
                       eid (or (d/q '[:find ?e .
                                      :in $ ?from
                                      :where [?e :lunch-menu/from ?from]]
                                    (:db ctx)
                                    from)
                               (d/tempid :db.part/user))]
                   (cond-> ctx
                     (not (str/blank? text))
                     (update :tx-data conj {:db/id eid
                                            :lunch-menu/from from
                                            :lunch-menu/text text}))))
               ctx)))

(defn- child [{:keys [db-spec db lunch-type-ids] :as ctx}]
  (->> (jdbc-common/select db-spec :child {})
       (reduce (fn [ctx {:keys [id var-symbol firstname lastname lunch-type-id]}]
                 (let [eid (first (d/q '[:find [?e ...]
                                         :in $ ?var-symbol
                                         :where [?e :person/var-symbol ?var-symbol]]
                                       db
                                       var-symbol))
                       eid (or eid (d/tempid :db.part/user))]
                   (-> ctx
                       (update :tx-data conj (cond-> {:db/id eid
                                                      :person/child? true
                                                      :person/var-symbol var-symbol
                                                      :person/firstname firstname
                                                      :person/lastname lastname}
                                               lunch-type-id
                                               (assoc :person/lunch-type (get lunch-type-ids lunch-type-id)))))))
               ctx)))

(defn- user [{:keys [db-spec db lunch-type-ids] :as ctx}]
  (->> (jdbc-common/select db-spec :user {})
       (reduce (fn [ctx {:keys [id email firstname lastname phone passwd roles]}]
                 (let [eid (first (d/q '[:find [?e ...]
                                         :in $ ?firstname ?lastname
                                         :where
                                         [?e :person/firstname ?firstname]
                                         [?e :person/lastname ?lastname]]
                                       db
                                       firstname
                                       lastname))
                       eid (or eid (d/tempid :db.part/user))]
                   (-> ctx
                       (assoc-in [:user-ids id] eid)
                       (update :tx-data conj (cond-> {:db/id eid
                                                      :person/child? false
                                                      :person/email email
                                                      :person/firstname firstname
                                                      :person/lastname lastname}
                                               phone
                                               (assoc :person/phone phone)
                                               passwd
                                               (assoc :person/passwd passwd)
                                               roles
                                               (assoc :person/roles roles))))))
               ctx)))

(defn- child-ids-mapping [db-spec db]
  (->> (jdbc-common/select db-spec :child {})
       (map (fn [{:keys [id var-symbol]}]
              [id (ffirst (d/q '[:find ?e
                                 :in $ ?var-symbol
                                 :where [?e :person/var-symbol ?var-symbol]]
                               db
                               var-symbol))]))
       (into {})))

(defn- user-child [{:keys [db db-spec user-ids] :as ctx}]
  (let [person-parents (d/q '[:find ?e ?p :where [?e :person/parent ?p]] db)
        child-ids (child-ids-mapping db-spec db)]
    (as-> (jdbc-common/select db-spec :user-child {}) $
         (reduce (fn [ctx {:keys [user-id child-id]}]
                   (update ctx :tx-data conj {:db/id (get child-ids child-id)
                                              :person/parent (get user-ids user-id)}))
                 ctx
                 $)
         (assoc $ :child-ids child-ids))))

(defn- attendance [{:keys [db-spec db child-ids] :as ctx}]
  (let [persons-by-id (->> (d/q '[:find [(pull ?e [*]) ...] :where [?e :person/firstname]] db)
                           (map (juxt :db/id identity))
                           (into {}))
        att-id->days (->> (jdbc-common/select db-spec :attendance-day {})
                          (group-by :attendance-id))]
    (->> (->> (jdbc/query db-spec ["select * from \"attendance\" where \"valid-to\" is null or \"valid-to\" > now()"])
              (group-by :child-id))
         (reduce (fn [ctx [child-id ch-atts]]
                   (let [att-days (->> ch-atts
                                       first
                                       :id
                                       att-id->days
                                       (mapv (juxt :day-of-week identity))
                                       (into {}))
                         person (-> child-id
                                    child-ids
                                    persons-by-id
                                    (assoc :person/lunch-pattern (->> (range 1 6)
                                                                      (map (comp :lunch? att-days))
                                                                      (map #(if % 1 0))
                                                                      (apply str))
                                           :person/att-pattern (->> (range 1 6)
                                                                    (map (comp :full-day? att-days))
                                                                    (map #(case % nil 0 true 1 false 2))
                                                                    (apply str))))]
                     (when (> (count ch-atts) 1)
                       (timbre/warn "more than 1 att:" (:child-id (first ch-atts))))
                     (update ctx :tx-data conj (cond-> (select-keys person [:db/id :person/att-pattern :person/lunch-pattern])
                                                 (not (:person/child? person))
                                                 (assoc :person/att-pattern "0000000")))))
                 ctx))))

(defn- set-person-active? [ctx]
  (->> (d/q '[:find [(pull ?e [:db/id {:person/_parent 1} :person/lunch-pattern :person/att-pattern :person/child?]) ...]
              :where
              [?e :person/firstname]]
            (:db ctx))
       (reduce (fn [ctx person]
                 (update ctx :tx-data conj {:db/id (:db/id person)
                                            :person/active? (not (and (db/zero-patterns? person)
                                                                      (or (:person/child? person)
                                                                          (not (some (complement db/zero-patterns?)
                                                                                     (:person/_parent person))))))}))
               ctx)))

(defn- lunch-order [{:keys [db-spec db] :as ctx}]
  (->> (jdbc-common/select db-spec :lunch-order {})
       (reduce (fn [ctx {:keys [id total] :as row}]
                 (let [date (-> (:date row) tc/from-sql-date tc/to-date)
                       daily-plans (->> (service/find-where db {:daily-plan/date date
                                                                :daily-plan/lunch-req nil})
                                        (remove :daily-plan/lunch-cancelled?))
                       total-req (->> daily-plans
                                      (map :daily-plan/lunch-req)
                                      (reduce + 0))]
                   (when (not= total-req total)
                     (timbre/warn date "Total lunch requested" total-req "but ordered" total)
                     #_(timbre/info date "Total lunch requested and ordered" total-req))

                   ;;TODO decrease :person/lunch-fund => call service/make-lunch-order

                   #_(update ctx :tx-data conj {:db/id (d/tempid :db.part/user)
                                              :lunch-order/date date
                                              :lunch-order/total total})))
               ctx)))

(defn- cancellation [{:keys [db-spec db child-ids] :as ctx}]
  (let [daily-plans (->> (service/find-where db {:daily-plan/date nil})
                         (group-by :daily-plan/date)
                         (reduce (fn [out [date plans]]
                                   (assoc out date (->> plans
                                                        (map (juxt #(get-in % [:daily-plan/person :db/id])
                                                                   identity))
                                                        (into {}))))
                                 {}))]
    (->> (jdbc-common/select db-spec :cancellation {})
         (reduce (fn [ctx {:keys [id child-id date lunch-cancelled?] :as row}]
                   (let [date (-> (:date row) tc/from-sql-date tc/to-date)]
                     (if-let [daily-plan (get-in daily-plans [date (get child-ids child-id)])]
                       (update ctx :tx-data conj {:db/id (:db/id daily-plan)
                                                  :daily-plan/att-cancelled? true
                                                  :daily-plan/lunch-cancelled? lunch-cancelled?})
                       ctx)))
                 ctx))))

(defn- transact [conn ctx]
  (pprint (dissoc ctx :db-spec :db :lunch-type-ids))
  (let [tx-data @(d/transact conn (:tx-data ctx))]
    (pprint tx-data)
    (assoc ctx :db (:db-after tx-data) :tx-data [])))

(defn- create-period-bills-plans [conn]
  (when-not (seq (service/find-where (d/db conn) {:billing-period/from-yyyymm nil}))
    (let [{:keys [:db/id] :as billing-periond} (service/transact-entity conn nil {:billing-period/from-yyyymm 201609
                                                                                  :billing-period/to-yyyymm 201610})]
      (service/re-generate-person-bills conn nil id)
      (service/all-period-bills-paid conn nil id))))

(defn migrate-to-datomic [db-spec conn]
  (->> {:db-spec db-spec
        :db (d/db conn)
        :tx-data []}
       lunch-type
       lunch-menu
       child
       (transact conn)
       user
       user-child
       (transact conn)
       attendance
       (transact conn)
       set-person-active?
       (transact conn))
  (create-period-bills-plans conn)
  (->> {:db-spec db-spec
        :db (d/db conn)
        :child-ids (child-ids-mapping db-spec (d/db conn))
        :tx-data []}
       cancellation
       (transact conn)
       lunch-order
       (transact conn)))


