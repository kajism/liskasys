(ns liskasys.db
  (:require [clj-brnolib.jdbc-common :as jdbc-common]
            [clj-brnolib.time :as time]
            [clj-brnolib.tools :as tools]
            clj-time.coerce
            [clj-time.core :as clj-time]
            [clojure.java.jdbc :as jdbc]))

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
    (jdbc/delete! db-spec (jdbc-common/esc :attendance-day)
                  ["\"attendance-id\" = ?" (:id ent)])
    (doseq [[day day-spec] days]
      (when (:type day-spec)
        (jdbc-common/save! db-spec :attendance-day {:attendance-id (:id ent)
                                                    :day-of-week day
                                                    :full-day? (= (:type day-spec) 1)
                                                    :lunch? (:lunch? day-spec)})))
    (first (jdbc-common/select db-spec table-kw {:id (:id ent)}))))

(defmethod jdbc-common/select :cancellation
  [db-spec table-kw where-m]
  (->>
   (jdbc/query db-spec (into [(jdbc-common/esc
                               (str "SELECT c.*, u.:lastname :uln, u.:firstname :ufn, ch.:lastname chln, ch.:firstname chfn"
                                    " FROM :cancellation AS c"
                                    " LEFT JOIN :user AS u ON (c.:user-id = u.:id)"
                                    " LEFT JOIN :child AS ch ON (c.:child-id = ch.:id)"
                                    (jdbc-common/where where-m)))]
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

(defn- select-attendance-day [db-spec child-id date day-of-week]
  (->>
   (jdbc/query db-spec [(str "SELECT * FROM " (jdbc-common/esc :attendance-day) " AS ad"
                             " LEFT JOIN " (jdbc-common/esc :attendance) " AS att ON (ad.\"attendance-id\" = att.\"id\")"
                             " WHERE att.\"child-id\"=?"
                             "  AND (att.\"valid-from\" IS NULL OR att.\"valid-from\" < ?)"
                             "  AND (att.\"valid-to\" IS NULL OR att.\"valid-to\" > ?)")
                        child-id date date]);; TODO (esc ":att.id ...")
   (tools/ffilter #(= day-of-week (:day-of-week %))))) ;;TODO put this into SELECT

(defmethod jdbc-common/save! :cancellation
  [db-spec table-kw ent]
  (let [user-id (:id (first (jdbc-common/select db-spec :user {}))) ;;TODO logged in user
        date (time/from-date (:date ent))
        day-of-week (clj-time/day-of-week date)
        att-day (select-attendance-day db-spec (:child-id ent) (:date ent) day-of-week)]
    (jdbc-common/save!-default db-spec table-kw (merge ent
                                                       {:attendance-day-id (:id att-day)
                                                        :lunch-cancelled? (and (:lunch? att-day)
                                                                               (can-cancel-lunch? date lunch-storno-limit-hour))
                                                        :user-id user-id}))))
