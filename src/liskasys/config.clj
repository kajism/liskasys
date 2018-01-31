(ns liskasys.config
  (:require [clojure.string :as str]
            [environ.core :refer [env]]))

(def defaults
  ^:displace {:http {:port 3000}})

(def environ
  {:http {:port (some-> env :port Integer.)}
   :datomic {:uri (env :datomic-uri)}
   :nrepl-port (some-> env :nrepl-port Integer.)})

(def dbs (zipmap (str/split (or (:app-domains env) "") #"\s+")
                 (str/split (or (:app-dbs env) "") #"\s+")))
