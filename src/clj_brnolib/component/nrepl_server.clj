(ns clj-brnolib.component.nrepl-server
  (:require [clojure.tools.nrepl.server :as nrepl]
            [com.stuartsierra.component :as component]))

(defrecord NReplServer [port server]
  component/Lifecycle
  (start [component]
    (if port
      (assoc component :server (nrepl/start-server :port port))
      component))
  (stop [component]
    (when server
      (nrepl/stop-server server))
    (assoc component :server nil)))

(defn nrepl-server [port]
  (map->NReplServer {:port port}))
