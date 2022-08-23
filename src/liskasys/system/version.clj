(ns liskasys.system.version
  (:require [clojure.java.io :as io]))

(defn get-app-version-info-number*
  ([] (get-app-version-info-number* (some-> (io/resource "META-INF/maven/liskasys/liskasys/pom.properties") (slurp))))
  ([pom-properties]
   (or (some->>  pom-properties
                 (re-find #"version=\d\.\d\.(\d+)")
                 (second)
                 (parse-long))
       0)))

(def get-app-version-info-number (memoize get-app-version-info-number*))
