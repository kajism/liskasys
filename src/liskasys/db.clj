(ns liskasys.db
  (:require [clj-brnolib.jdbc-common :as jdbc-common]
            [clj-brnolib.time :as time]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [taoensso.timbre :as timbre]
            [taoensso.truss :as truss])
  (:import java.io.BufferedReader))

(defn tomorrow []
  (-> (t/today)
      (t/plus (t/days 1))
      tc/to-date))

(defn assoc-fullname [person]
  (assoc person :-fullname (str (:person/lastname person) " " (:person/firstname person))))

(defmethod jdbc-common/select :user
  [db-spec table-kw where-m]
  (map assoc-fullname (jdbc-common/select-default db-spec table-kw where-m)))

(defmethod jdbc-common/select :child
  [db-spec table-kw where-m]
  (map assoc-fullname (jdbc-common/select-default db-spec table-kw where-m)))

(defmethod jdbc-common/select :person
  [db-spec table-kw where-m]
  (map assoc-fullname (jdbc-common/select-default db-spec table-kw where-m)))

(defmethod jdbc-common/save! :person
  [db-spec table-kw row]
  (jdbc-common/save!-default db-spec table-kw (dissoc row :-fullname)))

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
                                                    :lunch? (boolean (:lunch? day-spec))})))
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

(defn select-child-attendance-day [db-spec child-id date]
  (let [day-of-week (-> (tc/to-date-time date)
                        t/day-of-week)]
    (timbre/debug "Selecting attendance day for child-id" child-id " at " date)
    (first
     (jdbc/query db-spec [(jdbc-common/esc
                           "SELECT * FROM :attendance-day AS ad"
                           " LEFT JOIN :attendance AS att ON (ad.:attendance-id = att.:id)"
                           " WHERE att.:child-id = ? AND ad.:day-of-week = ?"
                           "  AND (att.:valid-from IS NULL OR att.:valid-from <= ?)"
                           "  AND (att.:valid-to IS NULL OR att.:valid-to >= ?)")
                          child-id day-of-week date date]))))

(defn select-children-with-attendance-day [db-spec date]
  (let [day-of-week (-> (tc/to-date-time date)
                        t/day-of-week)]
    (map assoc-fullname
         (jdbc/query db-spec [(jdbc-common/esc
                               "SELECT ad.:lunch?, ad.:full-day?, ch.:firstname, ch.:lastname, ch.:lunch-type-id, c.:lunch-cancelled? "
                               " FROM :attendance-day AS ad"
                               " LEFT JOIN :attendance AS att ON (ad.:attendance-id = att.:id)"
                               " LEFT JOIN :child AS ch ON (att.:child-id = ch.:id)"
                               " LEFT JOIN :cancellation AS c ON (att.:child-id = c.:child-id AND c.:date = ?)"
                               " WHERE ad.:day-of-week = ?"
                               "  AND (att.:valid-from IS NULL OR att.:valid-from <= ?)"
                               "  AND (att.:valid-to IS NULL OR att.:valid-to >= ?)")
                              date day-of-week date date]))))

(defn can-cancel-lunch? [db-spec date]
  (-> (jdbc-common/select db-spec :lunch-order {:date date})
      first
      nil?))

(defmethod jdbc-common/save! :cancellation
  [db-spec table-kw ent]
  {:pre [(truss/have? number? (:user-id ent))]}
  (let [att-day (select-child-attendance-day db-spec (:child-id ent) (:date ent))]
    (jdbc-common/save!-default db-spec table-kw (merge ent
                                                       {:attendance-day-id (truss/have! number? (:id att-day))
                                                        :lunch-cancelled? (and (:lunch? att-day)
                                                                               (if (some? (:lunch-cancelled? ent))
                                                                                 (:lunch-cancelled? ent)
                                                                                 (can-cancel-lunch? db-spec (:date ent))))}))))

(defn select-next-attendance-weeks [db-spec child-id weeks]
  (timbre/debug "Selecting next attendance weeks for child-id" child-id)
  (let [start-clj-date (-> (tomorrow) tc/to-local-date)]
    (->> (range 14)
         (keep
          #(let [clj-date (t/plus start-clj-date (t/days %))
                 date (time/to-date clj-date)
                 att (select-child-attendance-day db-spec child-id date)]
             (when att
               [date (assoc att :cancellation
                            (first
                             (jdbc-common/select db-spec :cancellation {:child-id child-id :date date})))]))))))

(defn select-children-by-user-id [db-spec user-id]
  (map assoc-fullname
       (jdbc/query db-spec [(jdbc-common/esc
                             "SELECT ch.*"
                             " FROM :child AS ch"
                             " LEFT JOIN :user-child AS uch ON (ch.:id = uch.:child-id)"
                             " WHERE uch.:user-id = ?") user-id])))

(defn clob-to-string [clob]
  "Turn an Oracle Clob into a String"
  (with-open [rdr (java.io.BufferedReader. (.getCharacterStream clob))]
    (str/join "\n" (line-seq rdr))))

(defn select-last-two-lunch-menus [db-spec history]
  (jdbc/query db-spec
              [(jdbc-common/esc "SELECT * FROM :lunch-menu ORDER BY :id DESC LIMIT 2 OFFSET ?") history]
              ;; :row-fn #(update % :text clob-to-string)
              ;;:result-set-fn first
              ))

#_(extend-protocol clojure.java.jdbc/ISQLParameter
  java.io.StringReader
  (set-parameter [v ^java.sql.PreparedStatement stmt ^long i]
    (timbre/spy
     (.setCharacterStream stmt i v))))

(extend-protocol clojure.java.jdbc/IResultSetReadColumn
  java.sql.Clob
  (result-set-read-column [v _ _]
    (clob-to-string v)))

(defn select-last-cancellation-date [db-spec]
  (-> (jdbc/query db-spec [(jdbc-common/esc "SELECT :date FROM :cancellation ORDER BY :date DESC LIMIT 1")])
      first
      :date))

(defn select-users-with-role [db-spec role]
  (jdbc/query db-spec ["SELECT \"email\" FROM \"user\" WHERE \"roles\" LIKE ?" (str "%" role "%")] ))

;; restore: (jdbc/execute! (user/db-spec) ["RUNSCRIPT FROM './uploads/liskasys-db.sql' "])
(defn h2-dump-to-file [db-spec file]
  (timbre/info "Dumping DB to " file)
  (jdbc/query db-spec ["SCRIPT TO ?" file]))

(defn zero-patterns? [{:keys [:person/lunch-pattern :person/att-pattern] :as person}]
  (and (or (nil? lunch-pattern) (= lunch-pattern "0000000"))
       (or (nil? att-pattern) (= att-pattern "0000000"))))

(defn select-active-persons [db-spec]
  (->> (jdbc-common/select db-spec :person {:active? true})
       (remove zero-patterns?)))
