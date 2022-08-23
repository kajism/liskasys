(ns liskasys.config
  (:require [clojure.string :as str]
            [environ.core :refer [env]]))

(def conf
  {:datomic {:uri (or (env :datomic-uri) "datomic:free://localhost:4334/")
             :dbs (zipmap (str/split (or (:app-domains env) "") #"\s+")
                          (str/split (or (:app-dbs env) "") #"\s+"))}
   :http-port (or (some-> ^String (env :port) (parse-long)) 3002)
   :nrepl-port (or (some-> ^String (env :nrepl-port) (parse-long)) 7002)
   :upload-dir (:upload-dir env)})
