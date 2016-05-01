(ns liskasys.db
  (:require [clj-brnolib.jdbc-common :as jdbc-common]
            [clj-brnolib.time :as time]
            [clj-brnolib.tools :as tools]
            clj-time.coerce
            [clj-time.core :as clj-time]
            [clojure.java.jdbc :as jdbc]
            [taoensso.truss :as truss]
            [taoensso.timbre :as timbre])
  (:import java.util.Date))

(defn assoc-fullname [person]
  (assoc person :-fullname (str (:lastname person) " " (:firstname person))))

(defmethod jdbc-common/select :user
  [db-spec table-kw where-m]
  (map assoc-fullname (jdbc-common/select-default db-spec table-kw where-m)))

(defmethod jdbc-common/select :child
  [db-spec table-kw where-m]
  (map assoc-fullname (jdbc-common/select-default db-spec table-kw where-m)))

(defmethod jdbc-common/select :attendance
  [db-spec table-kw where-m]
  (let [att-id->days (->> (jdbc-common/select-default db-spec :attendance-day {})
                          (group-by :attendance-id))]
    (->> (jdbc-common/select-default db-spec table-kw where-m)
         (map #(assoc % :days (->> (get att-id->days (:id %))
                                   (map (juxt :day-of-week
                                              (fn [att-day]
                                                {:type (if (:full-day? att-day) 1 2)
                                                 :lunch? (:lunch? att-day)})))
                                   (into {})))))))

(defmethod jdbc-common/save! :attendance
  [db-spec table-kw ent]
  (let [days (:days ent)
        ent (jdbc-common/save!-default db-spec table-kw (dissoc ent :days))]
    (jdbc-common/delete! db-spec :attendance-day {:attendance-id (:id ent)})
    (doseq [[day day-spec] days]
      (when (:type day-spec)
        (jdbc-common/save! db-spec :attendance-day {:attendance-id (:id ent)
                                                    :day-of-week day
                                                    :full-day? (= (:type day-spec) 1)
                                                    :lunch? (:lunch? day-spec)})))
    (first (jdbc-common/select db-spec table-kw {:id (:id ent)}))))

(defmethod jdbc-common/select :cancellation
  [db-spec table-kw where-m]
  (timbre/debug "Selecting cancellations where" where-m)
  (->>
   (jdbc/query db-spec (into [(jdbc-common/esc
                               (str "SELECT c.*, u.:lastname :uln, u.:firstname :ufn, ch.:lastname chln, ch.:firstname chfn"
                                    " FROM :cancellation AS c"
                                    " LEFT JOIN :user AS u ON (c.:user-id = u.:id)"
                                    " LEFT JOIN :child AS ch ON (c.:child-id = ch.:id)"
                                    (when-let [w (jdbc-common/where "c" where-m)]
                                      (str " WHERE " w))))]
                             (flatten (vals where-m))))
   (map (fn [row]
          (-> row
              (dissoc :uln :ufn :chln :chfn)
              (assoc :-child-fullname (str (:chln row) " " (:chfn row)))
              (assoc :-user-fullname (str (:uln row) " " (:ufn row))))))))

(defn- can-cancel-lunch? [date limit-hour]
  (not
   (clj-time/before?
    date
    (-> (clj-time/now)
        clj-time.coerce/to-local-date
        (clj-time/plus (clj-time/days
                        (if (< (clj-time/hour (clj-time/time-now)) limit-hour)
                          1
                          2)))
        clj-time.coerce/to-date-time))))

(def lunch-storno-limit-hour 10)

(defn select-attendance-days [db-spec child-id date]
  (timbre/debug "Selecting attendance days for child-id" child-id " at " date)
  (->>
   (jdbc/query db-spec [(str "SELECT * FROM " (jdbc-common/esc :attendance-day) " AS ad"
                             " LEFT JOIN " (jdbc-common/esc :attendance) " AS att ON (ad.\"attendance-id\" = att.\"id\")"
                             " WHERE att.\"child-id\"=?"
                             "  AND (att.\"valid-from\" IS NULL OR att.\"valid-from\" < ?)"
                             "  AND (att.\"valid-to\" IS NULL OR att.\"valid-to\" > ?)")
                        child-id date date];; TODO (esc ":att.id ...")
               )
   (map (juxt :day-of-week identity))
   (into {})))

(defn select-attendance-day [db-spec child-id date day-of-week]
  (get (select-attendance-days db-spec child-id date) day-of-week))

(defmethod jdbc-common/save! :cancellation
  [db-spec table-kw ent]
  {:pre [(truss/have? number? (:user-id ent))]}
  (let [date (time/from-date (:date ent))
        day-of-week (clj-time/day-of-week date)
        att-day (select-attendance-day db-spec (:child-id ent) (:date ent) day-of-week)]
    (jdbc-common/save!-default db-spec table-kw (merge ent
                                                       {:attendance-day-id (truss/have! number? (:id att-day))
                                                        :lunch-cancelled? (and (:lunch? att-day)
                                                                               (can-cancel-lunch? date lunch-storno-limit-hour))}))))

(defn select-next-attendance-weeks [db-spec child-id weeks]
  (timbre/debug "Selecting next attendance weeks for child-id" child-id)
  (let [today (clj-time/today)]
    (->> (range 14)
         (keep
          #(let [clj-date (clj-time/plus today (clj-time/days %))
                 date (time/to-date clj-date)
                 att (select-attendance-day db-spec child-id
                                            date
                                            (clj-time/day-of-week clj-date))
                 cancellation (first (jdbc-common/select db-spec :cancellation {:child-id child-id :date date}))]
             (when att
               [date (assoc att :cancellation cancellation)])))
         (into {}))))

(defn select-children-by-user-id [db-spec user-id]
  (map assoc-fullname
       (jdbc/query db-spec [(jdbc-common/esc
                             "SELECT ch.*"
                             " FROM :child AS ch"
                             " LEFT JOIN :user-child AS uch ON (ch.:id = uch.:child-id)"
                             " WHERE uch.:user-id = ?") user-id])))

