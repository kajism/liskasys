(ns liskasys.system.datomic
  (:require [datomic.api :as d]
            [io.rkn.conformity :as c]
            [taoensso.timbre :as timbre]))

(defn start-datomic [uri dbs]
  (let [norms-map (c/read-resource "liskasys/datomic_schema.edn")]
    (reduce
      (fn [out [server-name db-name]]
        (let [db-uri (str uri db-name)
              _ (d/create-database db-uri)
              conn (d/connect db-uri)]
          (timbre/info server-name "Connected to datomic DB" db-name ", going to run conformity")
          (c/ensure-conforms conn norms-map)
          (assoc out server-name conn)))
      {}
      dbs)))

(defn stop-datomic [conns]
  (run!
    (fn [[server-name conn]]
      (when conn
        (timbre/info "Releasing datomic connection of" server-name)
        (d/release conn)))
    conns))
