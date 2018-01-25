(ns liskasys.component.datomic
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [environ.core :refer [env]]
            [io.rkn.conformity :as conformity]
            [liskasys.cljc.domains :as domains]
            [liskasys.service :as service]
            [taoensso.timbre :as timbre]
            [clojure.string :as str]))

(defrecord Datomic [uri conns]
  component/Lifecycle
  (start [component]
    (let [norms-map (conformity/read-resource "liskasys/datomic_schema.edn")]
      (reduce
       (fn [out server-name]
         (if-let [db-key (domains/dbk server-name)]
           (let [uri (str uri (name db-key))
                 _ (d/create-database uri)
                 conn (d/connect uri)]
             (timbre/info db-key "Connected to datomic, going to run conformity")
             (conformity/ensure-conforms conn norms-map)
             (assoc-in out [:conns db-key] conn))
           (do
             (timbre/fatal "No database key found for server name" server-name)
             out)))
       component
       (str/split (:app-domains env) #"\s+"))))
  (stop [component]
    (reduce
     (fn [out [db-key conn]]
       (when conn
         (timbre/info db-key "Releasing datomic connection")
         (d/release conn))
       (assoc-in out [:conns db-key] nil))
     component
     conns)))

(defn datomic [config]
  (map->Datomic config))
