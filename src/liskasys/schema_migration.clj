(ns liskasys.schema-migration
  (:require [clj-brnolib.jdbc-common :as jdbc-common]
            [clojure.java.jdbc :as jdbc]
            [clojure.pprint :refer [pprint]]))

(defn- users->persons [db-spec]
  (->>
   (for [user (jdbc-common/select db-spec :user {})
         :let [person-id (:id (first (jdbc-common/select db-spec :person (select-keys user [:firstname :lastname]))))
               person (-> user
                          (select-keys [:firstname :lastname :phone :passwd :roles :email])
                          (assoc :child? false
                                 :id person-id))]]
     [(:id user) (:id (jdbc-common/save! db-spec :person person))])
   (into {})))

(defn- children->persons [db-spec]
  (->>
   (for [child (jdbc-common/select db-spec :child {})
         :let [person-id (:id (first (jdbc-common/select db-spec :person {:var-symbol (:var-symbol child)})))
               person (-> child
                          (select-keys [:firstname :lastname :var-symbol :lunch-type-id])
                          (assoc :child? true
                                 :id person-id))]]
     [(:id child) (:id (jdbc-common/save! db-spec :person person))])
   (into {})))

(defn- user-child->parent-child [db-spec user-ids-mapping child-ids-mapping]
  (doseq [user-child (jdbc-common/select db-spec :user-child {})
          :let [parent-child {:parent-id (get user-ids-mapping (:user-id user-child))
                              :child-id (get child-ids-mapping (:child-id user-child))}
                parent-child (assoc parent-child :id (:id (first (jdbc-common/select db-spec :parent-child parent-child))))]]
    (when-not (or (:id parent-child) (= (:parent-id parent-child) (:child-id parent-child)))
      (jdbc-common/insert! db-spec :parent-child parent-child))))

(defn- att->pattern [db-spec]
  (let [atts (->> (jdbc/query db-spec ["select * from \"attendance\" where \"valid-to\" is null "])
                  (group-by :child-id))
        childs (->> (jdbc-common/select db-spec :child {})
                    (map (juxt :id identity))
                    (into {}))
        att-days (->> (jdbc-common/select db-spec :attendance-day {})
                      (group-by :attendance-id))]
    (println "atts:" (set (mapv (comp (juxt :firstname :lastname) childs :child-id first) (vals atts))))
    (doseq [[child-id ch-atts] atts
            :when (> (count ch-atts) 1)]
      (println (:child-id (first ch-atts))))))

(defn copy-rows [db-spec]
  (jdbc/with-db-transaction [tx db-spec]
    (let [child-ids-mapping (children->persons tx)
          user-ids-mapping (users->persons tx)]
      (user-child->parent-child tx user-ids-mapping child-ids-mapping)
      (att->pattern tx))))
