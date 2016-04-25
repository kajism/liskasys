(ns liskasys.db
  (:require [clj-brnolib.jdbc-common :as jdbc-common]
            [clojure.java.jdbc :as jdbc]))

(defmethod jdbc-common/select :user
  [db-spec table-kw where-m]
  (map #(assoc % :-fullname (str (:lastname %) " " (:firstname %)))
       (jdbc-common/select-default db-spec table-kw where-m)))

(defmethod jdbc-common/save! :user
  [db-spec table-kw ent]
  (jdbc-common/save!-default db-spec table-kw (dissoc ent :-fullname)))

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
