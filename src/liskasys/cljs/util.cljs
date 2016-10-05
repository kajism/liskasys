(ns liskasys.cljs.util
  (:require [clojure.string :as str]
            [cognitect.transit :as transit]
            [liskasys.cljc.util :as cljc-util]
            [schema.core :as s]))

(defn sort-by-locale
  "Tridi spravne cestinu (pouziva funkci js/String.localeCompare). keyfn musi vracet string!"
  [key-fn coll]
  (sort-by (comp str key-fn) #(.localeCompare %1 %2) coll))

(defn parse-float [s]
  (when s
    (let [n (js/parseFloat (-> s
                               cljc-util/remove-spaces
                               (str/replace #"," ".")))]
      (if (js/isNaN n)
        nil
        n))))

(defn parse-bigdec [s]
  (when-let [n (parse-float s)]
    (transit/bigdec (str n))))

(defn bigdec->str [n]
  (when n
    (.-rep n)))

(defn bigdec->float [n]
  (parse-float (bigdec->str n)))
