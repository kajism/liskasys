(ns liskasys.system
  (:require [liskasys.config :refer [conf]]
            [liskasys.system.datomic :as datomic]
            [liskasys.endpoint.handler :as handler]
            [liskasys.system.logging] ;;to configure timbre
            [liskasys.system.scheduler :as scheduler]
            [integrant.core :as ig]
            [nrepl.server]
            [ring.adapter.undertow :as undertow]
            [taoensso.timbre :as timbre])
  (:import (io.undertow Undertow)))

(Thread/setDefaultUncaughtExceptionHandler
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ thread ex]
      (timbre/error ex "Uncaught exception on" (.getName thread)))))

(def config
  {:datomic/conns {:uri (get-in conf [:datomic :uri])
                  :dbs (get-in conf [:datomic :dbs])}
   :app/handler {:datomic (ig/ref :datomic/conns)}
   :http/server {:handler (ig/ref :app/handler)
                 :port (:http-port conf)}
   :nrepl/server {:port (:nrepl-port conf)}
   :app/scheduler {:datomic (ig/ref :datomic/conns)}})

(defmethod ig/init-key :nrepl/server [_ {:keys [port]}]
  (timbre/info "Starting nrepl server on port " port)
  (nrepl.server/start-server :port port))

(defmethod ig/halt-key! :nrepl/server [_ server]
  (nrepl.server/stop-server server)
  (timbre/info "Stopped nrepl server"))

(defmethod ig/init-key :datomic/conns [_ {:keys [uri dbs]}]
  (datomic/start-datomic uri dbs))

(defmethod ig/halt-key! :datomic/conns [_ conn]
  (datomic/stop-datomic conn))

(defmethod ig/init-key :app/handler [_ deps]
  (handler/make-handler deps))

(defmethod ig/init-key :http/server [_ {:keys [handler port]}]
  (timbre/info "Starting HTTP server on port" port)
  (undertow/run-undertow handler {:host "0.0.0.0"
                                  :port port}))

(defmethod ig/halt-key! :http/server [_ ^Undertow server]
  (.stop server)
  (timbre/info "Stopped HTTP server"))

(defmethod ig/init-key :app/scheduler [_ deps]
  (scheduler/start deps))

(defmethod ig/halt-key! :app/scheduler [_ scheduler]
  (scheduler/stop scheduler))

