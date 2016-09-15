(ns liskasys.datomic-migration
  (:require [clj-brnolib.jdbc-common :as jdbc-common]
            [clojure.java.jdbc :as jdbc]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [datomic.api :as d]
            [taoensso.timbre :as timbre]
            [liskasys.db :as db]))

(defn find-all [db attr]
  (d/q [:find '[(pull ?e [*]) ...] :where ['?e attr]] db))

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

;;TODO in separate TX, need existing :db/id to name files
(defn- lunch-menu [ctx]
  (->> (jdbc-common/select (:db-spec ctx) :lunch-menu {})
       (reduce (fn [ctx {:keys [id text content-type orig-filename]}]
                 (let [tempid (d/tempid :db.part/user)]
                   (-> ctx
                       (update :tx-data conj {:db/id tempid
                                              :file/category :file.category/lunch-menu
                                              :file/content-type content-type
                                              :file/orig-filename orig-filename}))))
               ctx)))

(defn- lunch-order [ctx]
  (->> (jdbc-common/select (:db-spec ctx) :lunch-order {})
       (reduce (fn [ctx {:keys [id date total]}]
                 (update ctx :tx-data conj {:db/id (d/tempid :db.part/user)
                                            :lunch-order/date date
                                            :lunch-order/total total}))
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

(defn child-ids-mapping [db-spec db]
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
    (->> (jdbc-common/select db-spec :user-child {})
         (reduce (fn [ctx {:keys [user-id child-id]}]
                   (update ctx :tx-data conj {:db/id (get child-ids child-id)
                                              :person/parent (get user-ids user-id)}))
                 ctx))))

(defn- attendance [{:keys [db-spec db] :as ctx}]
  (let [persons-by-id (->> (find-all db :person/firstname)
                           (map (juxt :db/id identity))
                           (into {}))
        att-id->days (->> (jdbc-common/select db-spec :attendance-day {})
                          (group-by :attendance-id))
        child-ids (child-ids-mapping db-spec db)]
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
                                    (assoc :person/lunch-pattern (->> (range 1 8)
                                                                      (map (comp :lunch? att-days))
                                                                      (map #(if % 1 0))
                                                                      (apply str))
                                           :person/att-pattern (->> (range 1 8)
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
  (->> (d/q '[:find [(pull ?e [:db/id :person/_parent :person/lunch-pattern :person/att-pattern :person/child?]) ...]
              :where
              [?e :person/firstname]]
            (:db ctx))
       (reduce (fn [ctx person]
                 (update ctx :tx-data conj {:db/id (:db/id person)
                                            :person/active? (not (and (db/zero-patterns? person)
                                                                      (or (:person/child? person)
                                                                          (zero? (count (:person/_parent person))))))}))
               ctx)))

(defn- transact [conn ctx]
  (pprint (dissoc ctx :db-spec :db :lunch-type-ids))
  (let [tx-data @(d/transact conn (:tx-data ctx))]
    (pprint tx-data)
    (assoc ctx :db (:db-after tx-data) :tx-data [])))

(defn migrate-to-datomic [db-spec conn]
  (->> {:db-spec db-spec
        :db (d/db conn)
        :tx-data []}
       lunch-type
       lunch-order
       child
       (transact conn)
       user
       user-child
       (transact conn)
       attendance
       (transact conn)
       set-person-active?
       (transact conn)))
