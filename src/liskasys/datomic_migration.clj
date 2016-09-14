(ns liskasys.datomic-migration
  (:require [clj-brnolib.jdbc-common :as jdbc-common]
            [clojure.pprint :refer [pprint]]
            [datomic.api :as d]
            [taoensso.timbre :as timbre]))

(defn- lunch-type [ctx]
  (->> (jdbc-common/select (:db-spec ctx) :lunch-type {})
       (reduce (fn [ctx {:keys [id label color]}]
                 (let [tempid (d/tempid :db.part/user)]
                   (-> ctx
                       (assoc-in [:lunch-type-ids id] tempid)
                       (update :tx-data conj {:db/id tempid
                                              :lunch-type/label label
                                              :lunch-type/color color}))))
               ctx)))


;;TODO in separate TX, need existing :db/id to rename files
(defn- lunch-menu [ctx]
  (->> (jdbc-common/select (:db-spec ctx) :lunch-menu {})
       (reduce (fn [ctx {:keys [id text content-type orig-filename]}]
                 (let [tempid (d/tempid :db.part/user)]
                   (-> ctx
                       (update :tx-data conj {:db/id tempid
                                              :file/category :file.category/lunch-menu
                                              :file/content-type content-type
                                              :file/orig-filename orig-filename}))))
               ctx)))

(defn- lunch-order [ctx]
  (->> (jdbc-common/select (:db-spec ctx) :lunch-order {})
       (reduce (fn [ctx {:keys [id date total]}]
                 (let [tempid (d/tempid :db.part/user)]
                   (-> ctx
                       (update :tx-data conj {:db/id tempid
                                              :lunch-order/date date
                                              :lunch-order/total total}))))
               ctx)))

(defn- child [ctx]
  ctx)

(defn- user [ctx]
  ctx)

(defn- user-child [ctx]
  ctx)

(defn- attendance [ctx]
  ctx)

(defn- transact [conn ctx]
  (pprint (dissoc ctx :db-spec))
  ;;@(d/transact conn (:tx-data ctx))
  )

(defn migrate [db-spec conn]
  (->> {:db-spec db-spec
        :tx-data []}
       lunch-type
       lunch-order
       child
       user
       user-child
       attendance
       (transact conn)))
