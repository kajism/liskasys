(ns liskasys.cljc.time
  #?@(:clj
       [(:require
         [clj-time.coerce :as tc]
         [clj-time.core :as t]
         [clj-time.format :as tf]
         [clojure.edn :as edn]
         [clojure.string :as str])
        (:import java.util.Locale)]
       :cljs
       [(:require
         [cljs-time.coerce :as tc]
         [cljs-time.core :as t]
         [cljs-time.format :as tf]
         [cljs.tools.reader.edn :as edn]
         [clojure.string :as str])]))

(defn to-date
  "Prevede z cljs.time date objektu do java.util.Date resp. js/Date"
  [date]
  (when date
    (tc/to-date date)))

(defn from-date
  "Prevede z js Date do cljs.time date"
  [date]
  (when date
    (tc/from-date date)))

(def dMyyyy (tf/formatter "d.M.yyyy"))
(def ddMMyyyy (tf/formatter "dd.MM.yyyy"))
(def dMyyyyHmmss (tf/formatter "d.M.yyyy H:mm:ss" #?(:clj (t/default-time-zone))))
(def ddMMyyyyHHmmss (tf/formatter "dd.MM.yyyy HH:mm:ss" #?(:clj (t/default-time-zone))))
(def ddMMyyyyHHmm (tf/formatter "dd.MM.yyyy HH:mm" #?(:clj (t/default-time-zone))))
(def yyyyMMdd-HHmm (tf/formatter "yyyyMMdd-HHmm" #?(:clj (t/default-time-zone))))
(def HHmm (tf/formatter "HH:mm" #?(:clj (t/default-time-zone))))

(defn to-format [date formatter]
  (if (nil? date)
    ""
    (->> date
         from-date
         #?(:cljs t/to-default-time-zone)
         (tf/unparse formatter))))

(defn- adjust-time [date]
  #?(:cljs (t/from-default-time-zone date)
     :clj date))

(defn from-format [s formatter]
  (when-not (str/blank? s)
    (cond-> (tf/parse formatter s)
      (not (or (= formatter dMyyyy) (= formatter ddMMyyyy)))
      adjust-time
      true
      to-date)))

(defn from-dMyyyy [s]
  (when-not (str/blank? s)
    (let [today (t/today)
          s (str/replace s #"\s" "")
          end-year  (->> (re-find #"\d{1,2}\.\d{1,2}\.(\d{1,4})$" s)
                         second
                         (drop-while #(= % \0))
                         (apply str))
          s (str s (when (and (<= (count (re-seq #"\." s)) 1)
                              (not (str/ends-with? s ".")))
                     "."))
          s (cond
              (= (count (re-seq #"\." s)) 1)
              (str s (t/month today) "." (t/year today))
              (and (= (count (re-seq #"\." s)) 2)
                   (str/ends-with? s "."))
              (str s (t/year today))
              (and (= (count (re-seq #"\." s)) 2)
                   (< (count end-year) 4))
              (str (subs s 0 (- (count s) (count end-year)))
                   (+ 2000 (edn/read-string end-year)))
              :else s)]
      (from-format s dMyyyy))))

(defn time-plus [date period]
  (when date
    (-> date
        (tc/from-date)
        (t/plus period)
        (tc/to-date))))

(defn min->sec [min]
  (when min
    (* min 60)))

(defn hour->sec [hour]
  (when hour
    (* hour (min->sec 60))))

(defn hour->millis [hour]
  (when hour
    (* (hour->sec hour) 1000)))

(def week-days (array-map 1 "pondělí" 2 "úterý" 3 "středa" 4  "čtvrtek" 5 "pátek" 6 "sobota" 7 "neděle"))

(def day-formatter (-> (tf/formatter "E d. MMMM yyyy")
                       #?(:clj (tf/with-locale (Locale. "cs")))))

(defn format-day-date [date]
  (->> date
       tc/to-date-time
       (tf/unparse day-formatter)
       (str/lower-case)))

(defn today []
  (-> (t/today)
      (tc/to-date)))

(defn tomorrow []
  (-> (t/today)
      (t/plus (t/days 1))
      (tc/to-date)))
