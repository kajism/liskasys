(ns liskasys.datomic-migration
  (:require [clj-brnolib.jdbc-common :as jdbc-common]
            [clojure.pprint :refer [pprint]]
            [datomic.api :as d]
            [taoensso.timbre :as timbre]
            [clojure.set :as set]))

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
                       (assoc-in [:child-ids id] eid)
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

(defn- user-child->parent-child [db-spec user-ids-mapping child-ids-mapping]
  (doseq [user-child (jdbc-common/select db-spec :user-child {})
          :let [parent-child {:parent-id (get user-ids-mapping (:user-id user-child))
                              :child-id (get child-ids-mapping (:child-id user-child))}
                parent-child (assoc parent-child :id (:id (first (jdbc-common/select db-spec :parent-child parent-child))))]]
    (when-not (or (:id parent-child) (= (:parent-id parent-child) (:child-id parent-child)))
      (jdbc-common/insert! db-spec :parent-child parent-child))))

(defn- user-child [{:keys [db db-spec child-ids user-ids] :as ctx}]
  (let [person-parents (d/q '[:find ?e ?p :where [?e :person/parent ?p]] db)]
    (->> (jdbc-common/select db-spec :user-child {})
         (reduce (fn [ctx {:keys [user-id child-id]}]
                   (cond-> ctx
                     (not (contains? person-parents [(get child-ids child-id)
                                                     (get user-ids user-id)]))
                     (update :tx-data conj {:db/id (get child-ids child-id)
                                            :person/parent (get user-ids user-id)})))
                 ctx))))

(defn- attendance [ctx]
  ctx)

(defn find-all [db attr]
  (d/q [:find '[(pull ?e [*]) ...] :where ['?e attr]] db))

(defn- transact [conn ctx]
  (pprint (dissoc ctx :db-spec :db :lunch-type-ids :child-ids))
  (let [tx-data @(d/transact conn (:tx-data ctx))]
    (pprint tx-data)
    (assoc ctx :db (:db-after tx-data) :tx-data [])))

(defn migrate [db-spec conn]
  (->> {:db-spec db-spec
        :db (d/db conn)
        :tx-data []}
       lunch-type
       lunch-order
       child
       (transact conn)
       user
       user-child
       attendance
       (transact conn)))
