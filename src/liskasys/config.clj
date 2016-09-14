(ns liskasys.config
  (:require [environ.core :refer [env]]))

(def defaults
  ^:displace {:http {:port 3000}})

(def environ
  {:http {:port (some-> env :port Integer.)}
   :db   {:uri  (env :database-url)}
   :datomic {:uri (env :datomic-uri)}
   :nrepl-port (some-> env :nrepl-port Integer.)})
