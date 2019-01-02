(ns liskasys.cljs.util
  (:require [clojure.string :as str]
            [cognitect.transit :as transit]
            [liskasys.cljc.util :as cljc.util]
            [re-com.core :as re-com]))

(defn sort-by-locale
  "Tridi spravne cestinu (pouziva funkci js/String.localeCompare). keyfn musi vracet string!"
  [key-fn coll]
  (sort-by (comp str key-fn) #(.localeCompare %1 %2) coll))

(defn parse-float [s]
  (when s
    (let [n (js/parseFloat (-> s
                               cljc.util/remove-spaces
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

(defn hiccup->val [x]
  (cond
    (and (vector? x) (fn? (first x)))
    (->> x
         rest
         (apply hash-map)
         :label)
    (vector? x)
    (let [out (keep hiccup->val x)]
      (if (> (count out) 1)
        (apply str out)
        (first out)))
    (map? x)
    nil
    (fn? x)
    nil
    (symbol? x)
    nil
    (keyword? x)
    nil
    (transit/bigdec? x)
    (parse-float (.-rep x))
    :else
    x))

(defn dp-class [%]
  (cond (:daily-plan/absence? %) "absence"
        (:daily-plan/att-cancelled? %) "cancelled"
        :else "present"))
