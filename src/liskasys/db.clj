(ns liskasys.db
  (:require [clj-brnolib.jdbc-common :as jdbc-common]))

(defmethod jdbc-common/select :user
  [db-spec table-kw where-m]
  (map #(assoc % :-fullname (str (:lastname %) " " (:firstname %)))
       (jdbc-common/select-default db-spec table-kw where-m)))
