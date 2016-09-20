(ns liskasys.main
  (:gen-class)
  (:require [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [duct.component.ragtime :as ragtime]
            [duct.middleware.errors :refer [wrap-hide-errors]]
            [duct.util.runtime :refer [add-shutdown-hook]]
            [liskasys.config :as config]
            [liskasys.datomic-migration :as datomic-migration]
            [liskasys.service :as service]
            [liskasys.system :refer [new-system]]
            [meta-merge.core :refer [meta-merge]]))

(def prod-config
  {:app {:middleware     [[wrap-hide-errors :internal-error]]
         :internal-error (io/resource "errors/500.html")}})

(def config
  (meta-merge config/defaults
              config/environ
              prod-config))

(defn -main [& args]
  (let [system (-> (new-system config)
                   component/start)]
    (println "Started HTTP server on port" (-> system :http :port))
    (when-not (seq (service/find-where (datomic.api/db (-> system :datomic :conn)) {:person/lunch-type nil}))
      (datomic-migration/migrate-to-datomic (-> system :db :spec) (-> system :datomic :conn)))
    (add-shutdown-hook ::stop-system #(component/stop system))))
