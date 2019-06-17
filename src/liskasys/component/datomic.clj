(ns liskasys.component.datomic
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [io.rkn.conformity :as conformity]
            [liskasys.config :as config]
            [liskasys.db-queries :as db-queries]
            [taoensso.timbre :as timbre]))

(defrecord Datomic [uri conns]
  component/Lifecycle
  (start [component]
    (let [norms-map (conformity/read-resource "liskasys/datomic_schema.edn")]
      (reduce
       (fn [out [server-name db-name]]
         (let [uri (str uri db-name)
               _ (d/create-database uri)
               conn (d/connect uri)]
           (timbre/info server-name "connected to datomic DB" db-name ", going to run conformity")
           (conformity/ensure-conforms conn norms-map)

           (let [db (d/db conn)]
             (when-let [person-ids (not-empty
                                    (d/q '[:find [?e ...]
                                           :where
                                           [?e :person/active?]
                                           (not [?e :person/price-list])]
                                         db))]
               (let [pl (db-queries/find-price-list db)]
                 (timbre/info server-name "adding default price-list" (:price-list/label pl) "to persons")
                 (->> person-ids
                      (map #(-> [:db/add % :person/price-list (:db/id pl)]))
                      (d/transact conn)))))

           (assoc-in out [:conns server-name] conn)))
       component
       config/dbs)))
  (stop [component]
    (reduce
     (fn [out [server-name conn]]
       (when conn
         (timbre/info "Releasing datomic connection of" server-name)
         (d/release conn))
       (assoc-in out [:conns server-name] nil))
     component
     conns)))

(defn datomic [config]
  (map->Datomic config))
