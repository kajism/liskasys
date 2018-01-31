(ns liskasys.component.datomic
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [environ.core :refer [env]]
            [io.rkn.conformity :as conformity]
            [liskasys.config :as config]
            [liskasys.service :as service]
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
