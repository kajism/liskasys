(ns liskasys.schema-migration
  (:require [clj-brnolib.jdbc-common :as jdbc-common]
            [clojure.java.jdbc :as jdbc]
            [clojure.pprint :refer [pprint]]
            [liskasys.db :as db]
            [taoensso.timbre :as timbre]))

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

(defn- att->pattern [db-spec child-ids-mapping]
  (let [atts (->> (jdbc/query db-spec ["select * from \"attendance\" where \"valid-to\" is null or \"valid-to\" > now()"])
                  (group-by :child-id))
        persons-by-id (->> (jdbc-common/select db-spec :person {})
                           (map (juxt :id identity))
                           (into {}))
        att-id->days (->> (jdbc-common/select db-spec :attendance-day {})
                          (group-by :attendance-id))]
    (doseq [[child-id ch-atts] atts
            :let [att-days (->> ch-atts
                                first
                                :id
                                att-id->days
                                (mapv (juxt :day-of-week identity))
                                (into {}))
                  person (-> child-id
                             child-ids-mapping
                             persons-by-id
                             (assoc :lunch-pattern (->> (range 1 8)
                                                        (map (comp :lunch? att-days))
                                                        (map #(if % 1 0))
                                                        (apply str))
                                    :att-pattern (->> (range 1 8)
                                                      (map (comp :full-day? att-days))
                                                      (map #(case % nil 0 true 1 false 2))
                                                      (apply str))))]]
      (when (> (count ch-atts) 1)
        (timbre/warn "more than 1 att:" (:child-id (first ch-atts))))
      (jdbc-common/save! db-spec :person (cond-> person
                                           (not (:child? person))
                                           (assoc :att-pattern "0000000"))))))

(defn- deactivate-zero-pattern-kids+parents [db-spec]
  (doseq [child (jdbc-common/select db-spec :person {:child? true :active? true})
          :when (db/zero-patterns? child)]
    (jdbc-common/save! db-spec :person (assoc child :active? false)))
  (let [parent->child-ids (->> (jdbc-common/select db-spec :parent-child {})
                               (group-by :parent-id))
        childs-by-id (->> (jdbc-common/select db-spec :person {:child? true})
                          (map (juxt :id identity))
                          (into {}))]
    (doseq [adult (jdbc-common/select db-spec :person {:child? false :active? true})
            :when (and (db/zero-patterns? adult)
                       (->> adult
                            :id
                            parent->child-ids
                            (map (comp childs-by-id :child-id))
                            (every? (complement :active?))))]
      (jdbc-common/save! db-spec :person (assoc adult :active? false)))))

(defn copy-rows [db-spec]
  (jdbc/with-db-transaction [tx db-spec]
    (let [child-ids-mapping (children->persons tx)
          user-ids-mapping (users->persons tx)]
      (user-child->parent-child tx user-ids-mapping child-ids-mapping)
      (att->pattern tx child-ids-mapping)
      (deactivate-zero-pattern-kids+parents tx))))
