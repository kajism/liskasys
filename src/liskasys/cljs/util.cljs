(ns liskasys.cljs.util
  (:require [cljs-time.coerce :as ctc]
            [cljs-time.format :as ctf]
            [clojure.string :as str]
            [liskasys.cljc.schema :as schema]
            [re-frame.core :as re-frame]
            [schema.core :as s]))

(defonce id-counter (atom 0))
(defn new-id []
  (swap! id-counter dec))

(def cid-counter (atom 0))
(defn new-cid []
  (swap! cid-counter inc))

(def formatter-ddMMyyyy (ctf/formatter "dd.MM.yyyy"))

(def formatter-ddMMyyyyHHmm (ctf/formatter "dd.MM.yyyy HH:mm"))

(defn to-date
  "Prevede z cljs.time date objektu do js Date"
  [date]
  (ctc/to-date date))

(defn from-date
  "Prevede z js Date do cljs.time date"
  [date]
  (ctc/from-date date))

(defn date-to-str [date]
  (if (nil? date)
    ""
    (ctf/unparse formatter-ddMMyyyy (from-date date))))

(defn str-to-date [str]
  (when-not (str/blank? str)
    (to-date (ctf/parse formatter-ddMMyyyy str))))

(defn datetime-to-str [date]
  (if (nil? date)
    ""
    (ctf/unparse formatter-ddMMyyyyHHmm (from-date date))))

(defn str-to-datetime [str]
  (when-not (str/blank? str)
    (to-date (ctf/parse formatter-ddMMyyyyHHmm str))))

(defn float? [x]
  "clojure have this 'natively', clojuresript does not"
  (and (cljs.core/number? x) (not= 0 (cljs.core/mod x 1))))

(defn abs [n] (max n (- n)))

(defn valid-schema?
  "validate the given db, writing any problems to console.error"
  [db]
  (let [res (s/check schema/AppDb db)]
    (if (some? res)
      (.error js/console (str "schema problem: " res)))))

(def debug-mw [(when ^boolean goog.DEBUG re-frame/debug)
               (when ^boolean goog.DEBUG (re-frame/after valid-schema?))])

(defn dissoc-temp-keys [m]
  (into {} (remove (fn [[k v]]
                     (or (str/starts-with? (name k) "-")
                         (and (str/starts-with? (name k) "_")
                              (sequential? v))))
                   m)))

(defn sort-by-locale
  "Tridi spravne cestinu (pouziva funkci js/String.localeCompare). keyfn musi vracet string!"
  [keyfn coll]
  (sort-by (comp str/capitalize str keyfn) #(.localeCompare %1 %2) coll))

(defn parse-int [s]
  (when s
    (let [n (js/parseInt (str/replace s #"\s+" ""))]
      (if (js/isNaN n)
        nil
        n))))

(defn parse-float [s]
  (when s
    (let [n (js/parseFloat (-> s
                               (str/replace #"\s+" "")
                               (str/replace #"," ".")))]
      (if (js/isNaN n)
        nil
        n))))

(defn boolean->text [b]
  (if b "Ano" "Ne"))

(defn money->text [n]
  (->> n
       str
       reverse
       (partition-all 3)
       (map #(apply str %))
       (str/join " ")
       str/reverse))

(defn file-size->str [n]
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
