(ns clj-brnolib.jdbc-common
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [taoensso.timbre :as timbre])
  (:import java.util.Date))

(defn esc
  "Prevadi (escapuje) nazvy DB tabulek a sloupcu z keywordu do stringu ohraniceneho uvozovkami.
   Diky tomu muzeme mit nazvy tabulek a sloupcu v Clojure tvaru s pomlckou.
   Umi prevadet samostatne keywordy, mapy s keywordovymi klici (rows) a taky keywordy v retezcich - sql dotazech,
   kde navic retezce umi spojujit, takze neni potreba pouzit (str)"
  ([to-esc]
   (cond
     (keyword? to-esc)
     (format "\"%s\"" (name to-esc))
     (map? to-esc)
     (->> to-esc
          (map (fn [[k v]]
                 [(esc k) v]))
          (into {}))
     :default
     (esc to-esc "")))
  ([s & ss]
   (str/replace (str s (apply str ss)) #":([a-z0-9\-?]+)" "\"$1\"")))

(defn where
  ([where-m]
   (where "" where-m))
  ([prefix where-m]
   (let [prefix (if (str/blank? prefix) "" (str prefix "."))]
     (->> where-m
          (map (fn [[k v]] (str prefix (esc k)
                                (if (coll? v)
                                  (str " IN (" (str/join "," (take (count v) (repeat "?"))) ")")
                                  " = ?"))))
          (str/join " AND ")
          not-empty))))

(defmulti select (fn [db-spec table-kw where-m]
                   table-kw))

(defn select-default [db-spec table-kw where-m]
  (timbre/debug "Selecting" table-kw "where" where-m)
  (jdbc/query db-spec (into [(str "SELECT * FROM " (esc table-kw) (when-let [w (where where-m)] (str " WHERE " w)))]
                            (flatten (vals where-m)))))

(defmethod select :default [db-spec table-kw where-m]
  (select-default db-spec table-kw where-m))

(defn- h2-inserted-id [insert-result]
  (-> insert-result
      first
      vals
      first))

(defn insert! [db-spec table-kw row]
  (->
   (jdbc/insert! db-spec
                 (esc table-kw)
                 (esc row))
   h2-inserted-id))

(defn update! [db-spec table-kw row]
  (jdbc/update! db-spec
                (esc table-kw)
                (esc row)
                ["\"id\" = ?" (:id row)])
  (:id row))

(defmulti save! (fn [db-spec table-kw row]
                  table-kw))

(defn save!-default [db-spec table-kw row]
  (timbre/info "Saving" table-kw "row" row)
  (let [id (if (:id row)
             (update! db-spec table-kw row)
             (insert! db-spec table-kw row))]
    (first (select db-spec table-kw {:id id}))))

(defmethod save! :default [db-spec table-kw row]
  (save!-default db-spec table-kw row))

(defmulti delete! (fn [db-spec table-kw where-m]
                    table-kw))

(defn delete!-default [db-spec table-kw where-m]
  (timbre/info "Deleting from" table-kw "where" where-m)
  (jdbc/delete! db-spec (esc table-kw) (into [(where where-m)]
                                             (flatten (vals where-m)))))

(defmethod delete! :default [db-spec table-kw id]
  (delete!-default db-spec table-kw id))
