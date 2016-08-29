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

(defn- select-lunch-counts-by-diet-label [db-spec date]
  (let [lunch-types (->> (jdbc-common/select db-spec :lunch-type {})
                         (into [{:id nil :label "běžná"}])
                         (map (juxt :id :label))
                         (into {}))]
    (->> (db/select-children-with-attendance-day db-spec date)
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
  (let [lunch-counts (not-empty (select-lunch-counts-by-diet-label db-spec date))
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
          (if (:error result)
            (timbre/error "Failed to send email" result)
            (timbre/info "Lunch order has been sent" result)))))))
