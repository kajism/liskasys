(ns liskasys.service
  (:require [clj-brnolib.jdbc-common :as jdbc-common]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [liskasys.db :as db]
            [postal.core :as postal]
            [taoensso.timbre :as timbre])
  (:import java.text.Collator
           java.util.Locale))

(def day-formatter (-> (tf/formatter "E dd.MM.yyyy")
                        (tf/with-locale (Locale. "cs"))))

(def cz-collator (Collator/getInstance (Locale. "cs")))

(defn all-work-days-since [ld]
  (->> ld
       (iterate #(t/plus % (t/days 1)))
       (keep #(when (<= (t/day-of-week %) 5) %))))

(defn att-day-with-lunch? [att-day]
  (and (:lunch? att-day)
       (not (:lunch-cancelled? att-day))))

;; Source: https://gist.github.com/werand/2387286
(defn- easter-sunday-for-year [year]
  (let [golden-year (+ 1 (mod year 19))
        div (fn div [& more] (Math/floor (apply / more)))
        century (+ (div year 100) 1)
        skipped-leap-years (- (div (* 3 century) 4) 12)
        correction (- (div (+ (* 8 century) 5) 25) 5)
        d (- (div (* 5 year) 4) skipped-leap-years 10)
        epac (let [h (mod (- (+ (* 11 golden-year) 20 correction)
                             skipped-leap-years) 30)]
               (if (or (and (= h 25) (> golden-year 11)) (= h 24))
                 (inc h) h))
        m (let [t (- 44 epac)]
            (if (< t 21) (+ 30 t) t))
        n (- (+ m 7) (mod (+ d m) 7))
        day (if (> n 31) (- n 31) n)
        month (if (> n 31) 4 3)]
    (t/local-date year (int month) (int day))))

(defn- easter-monday-for-year [year]
  (t/plus (easter-sunday-for-year year) (t/days 1)))

(def easter-monday-for-year-memo (memoize easter-monday-for-year))

(defn- valid-in-year? [bank-holiday year]
  (and (or (nil? (:valid-from-year bank-holiday)) (>= year (:valid-from-year bank-holiday)))
       (or (nil? (:valid-to-year bank-holiday)) (<= year (:valid-to-year bank-holiday)))))

(defn- bank-holiday? [clj-date bank-holidays]
  (let [y (t/year clj-date)]
    (seq (filter (fn [bh]
                   (and (valid-in-year? bh y)
                        (or (and (= (t/day clj-date) (:day bh))
                                 (= (t/month clj-date) (:month bh)))
                            (and (some? (:easter-delta bh))
                                 (t/equal? clj-date
                                           (t/plus (easter-monday-for-year-memo y)
                                                   (t/days (:easter-delta bh))))))))
                 bank-holidays))))

(defn find-children-with-attendance-day [db-spec date]
  (when-not (bank-holiday? (tc/to-local-date date) (jdbc-common/select db-spec :bank-holiday {}))
    (db/select-children-with-attendance-day db-spec date)))

(defn- find-lunch-counts-by-diet-label [db-spec date]
  (let [lunch-types (->> (jdbc-common/select db-spec :lunch-type {})
                         (into [{:id nil :label "běžná"}])
                         (map (juxt :id :label))
                         (into {}))]
    (->> (find-children-with-attendance-day db-spec date)
         (filter att-day-with-lunch?)
         (group-by :lunch-type-id)
         (map (fn [[k v]] [(get lunch-types k) (count v)]))
         (sort-by first cz-collator ))))

(defn close-lunch-order [db-spec date total]
  (let [lunch-order (first (jdbc-common/select db-spec :lunch-order {:date date}))]
    (jdbc-common/save! db-spec :lunch-order (assoc lunch-order
                                                   :date date
                                                   :total total))))

(defn send-lunch-order [db-spec date]
  (let [lunch-counts (not-empty (find-lunch-counts-by-diet-label db-spec date))
        total (apply + (map second lunch-counts))
        subject (str "Objednávka obědů pro Lištičku na " (->> date
                                                              tc/to-date-time
                                                              (tf/unparse day-formatter)))
        msg {:from "daniela.chaloupkova@post.cz"
             :to (mapv :email (db/select-users-with-role db-spec "obedy"))
             :subject subject
             :body [{:type "text/plain; charset=utf-8"
                     :content (str subject "\n"
                                   "-------------------------------------------------\n\n"
                                   "Dle diety:\n"
                                   (apply str
                                          (for [[t c] lunch-counts]
                                            (str t ": " c "\n")))
                                   "-------------------------------------------------\n"
                                   "CELKEM: " total)}]}]
    (close-lunch-order db-spec date total)
    (if-not lunch-counts
      (timbre/info "No lunches for " date ". Sending skipped.")
      (do
        (timbre/info "Sending " (:subject msg) "to" (:to msg))
        (timbre/debug msg)
        (let [result (postal/send-message msg)]
          (if (zero? (:code result))
            (timbre/info "Lunch order has been sent" result)
            (timbre/error "Failed to send email" result)))))))

(defn find-next-attendance-weeks [db-spec child-id weeks]
  (let [bank-holidays (jdbc-common/select db-spec :bank-holiday {})]
    (->> (db/select-next-attendance-weeks db-spec child-id weeks)
         (remove (fn [[date att-day]]
                   (bank-holiday? (tc/to-local-date date) bank-holidays)))
         (into (sorted-map)))))
