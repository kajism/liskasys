(ns liskasys.component.datomic
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [io.rkn.conformity :as conformity]
            [liskasys.service :as service]
            [taoensso.timbre :as timbre]))

(defrecord Datomic [uri conn]
  component/Lifecycle
  (start [component]
    (let [_ (d/create-database uri)
          conn (d/connect uri)
          norms-map (conformity/read-resource "liskasys/datomic_schema.edn")]
      (timbre/info "Connected to datomic, going to run conformity")
      (conformity/ensure-conforms conn norms-map)
      (assoc component :conn conn)))
  (stop [component]
    (when conn
      (timbre/info "Releasing datomic connection")
      (d/release conn))
    (assoc component :conn nil)))

(defn datomic [config]
  (map->Datomic config))
