(ns clj-brnolib.time
  (:require #?@(:cljs [[cljs-time.coerce :as time-coerce]
                       [cljs-time.core :as time]
                       [cljs-time.format :as time-format]]
                :clj  [[clj-time.coerce :as time-coerce]
                       [clj-time.core :as time]
                       [clj-time.format :as time-format]])
            [clojure.string :as str]))

(defn to-date
  "Prevede z cljs.time date objektu do java.util.Date resp. js/Date"
  [date]
  (when date
    (time-coerce/to-date date)))

(defn from-date
  "Prevede z js Date do cljs.time date"
  [date]
  (when date
    (time-coerce/from-date date)))

(def dMyyyy (time-format/formatter "d. M. yyyy"))
(def ddMMyyyy (time-format/formatter "dd.MM.yyyy"))
(def dMyyyyHmmss (time-format/formatter "d.M.yyyy H:mm:ss" #?(:clj (time/default-time-zone))))
(def ddMMyyyyHHmmss (time-format/formatter "dd.MM.yyyy HH:mm:ss" #?(:clj (time/default-time-zone))))
(def ddMMyyyyHHmm (time-format/formatter "dd.MM.yyyy HH:mm" #?(:clj (time/default-time-zone))))
(def yyyyMMdd-HHmm (time-format/formatter "yyyyMMdd-HHmm" #?(:clj (time/default-time-zone))))
(def HHmm (time-format/formatter "HH:mm" #?(:clj (time/default-time-zone))))

(defn to-format [date formatter]
  (if (nil? date)
    ""
    (->> date
         from-date
         #?(:cljs time/to-default-time-zone)
         (time-format/unparse formatter))))

(defn from-format [str formatter]
  (when-not (str/blank? str)
    (->> str
         (time-format/parse formatter)
         #?(:cljs time/from-default-time-zone)
         to-date)))

(defn time-plus-hours [date n]
  (time/plus (from-date date) (time/hours n)))

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

