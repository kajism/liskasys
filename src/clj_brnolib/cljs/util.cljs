(ns clj-brnolib.cljs.util
  (:require [clojure.string :as str]
            [cognitect.transit :as transit]
            [schema.core :as s]))

(def client-id-counter (atom 0))
(defn new-cid []
  (swap! client-id-counter inc))

(defn dissoc-temp-keys [m]
  (into {} (remove (fn [[k v]]
                     (or (str/starts-with? (name k) "-")
                         (and (str/starts-with? (name k) "_")
                              (sequential? v))))
                   m)))

(defn sort-by-locale
  "Tridi spravne cestinu (pouziva funkci js/String.localeCompare). keyfn musi vracet string!"
  [key-fn coll]
  (sort-by (comp str key-fn) #(.localeCompare %1 %2) coll))

(defn remove-spaces [s]
  (-> s
      str
      (str/replace #"\s+" "")))

(defn parse-int [s]
  (when s
    (let [n (js/parseInt (remove-spaces s))]
      (if (js/isNaN n)
        nil
        n))))

(defn parse-float [s]
  (when s
    (let [n (js/parseFloat (-> s
                               remove-spaces
                               (str/replace #"," ".")))]
      (if (js/isNaN n)
        nil
        n))))

(defn parse-bigdec [s]
  (when-let [n (parse-float s)]
    (transit/bigdec (str n))))

(defn boolean->text [b]
  (if b "Ano" "Ne"))

(defn money->text [n]
  (let [[i d] (-> n
                  str
                  (str/replace "." ",")
                  (str/split #"\,"))]
    (str (->> i
              reverse
              (partition-all 3)
              (map #(apply str %))
              (str/join " ")
              str/reverse)
         (when d
           (str "," d)))))

(defn bigdec->str [n]
  (when n
    (.-rep n)))

(defn bigdec->float [n]
  (parse-float (bigdec->str n)))

(defn file-size->text [n]
  (cond
    (nil? n) ""
    (neg? n) "-"
    (zero? n) "0"
    :else
    (reduce (fn [div label]
              (let [q (quot n div)]
                (if (pos? q)
                  (reduced (str (.toFixed (/ n div) 1) " " label))
                  (/ div 1000))))
            1000000000000
            ["TB" "GB" "MB" "kB" "B"])))
