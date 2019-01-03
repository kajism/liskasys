(ns liskasys.db
  (:require [clojure.set :as set]
            [datomic.api :as d]
            [taoensso.timbre :as timbre])
  (:import java.util.concurrent.ExecutionException))

(def ent-type--attr
  {:bank-holiday :bank-holiday/label
   :billing-period :billing-period/from-yyyymm
   :daily-plan :daily-plan/date
   :lunch-menu :lunch-menu/from
   :lunch-order :lunch-order/date
   :lunch-type :lunch-type/label
   :person :person/active?
   :price-list :price-list/days-1
   :person-bill :person-bill/total
   :school-holiday :school-holiday/label
   :group :group/label
   :config :config/org-name
   :class-register :class-register/date})

(def ent-attr--type (reduce (fn [out [ent-type attr]]
                              (assoc out attr ent-type))
                            {}
                            ent-type--attr))

(defn- build-query [db pull-pattern where-m]
  (reduce (fn [query [where-attr where-val]]
            (let [?where-attr (symbol (str "?" (name where-attr)))]
              (cond-> query
                where-val
                (update-in [:query :in] conj ?where-attr)
                (not= '?id ?where-attr)
                (update-in [:query :where] conj (if where-val
                                                  ['?id where-attr ?where-attr]
                                                  ['?id where-attr]))
                where-val
                (update-in [:args] conj where-val))))
          {:query {:find [[(list 'pull '?id pull-pattern) '...]]
                   :in ['$]
                   :where []}
           :args [db]
           :timeout 2000}
          where-m))

(defn find-where
  ([db where-m]
   (find-where db where-m '[*]))
  ([db where-m pull-pattern]
   (d/query (build-query db pull-pattern where-m))))

(defn find-by-type-default
  ([db ent-type where-m]
   (find-by-type-default db ent-type where-m '[*]))
  ([db ent-type where-m pull-pattern]
   (let [attr (get ent-type--attr ent-type ent-type)]
     (find-where db (merge {attr nil} where-m) pull-pattern))))

(defmulti find-by-type (fn [db ent-type where-m] ent-type))

(defmethod find-by-type :default [db ent-type where-m]
  (find-by-type-default db ent-type where-m))

(defn transact [conn user-id tx-data]
  (if (seq tx-data)
    (let [tx-data (cond-> (vec tx-data)
                    user-id
                    (conj {:db/id (d/tempid :db.part/tx) :tx/person user-id}))
          _ (timbre/info "Transacting" tx-data)
          tx-result @(d/transact conn tx-data)]
      (timbre/debug tx-result)
      tx-result)
    {:db-after (d/db conn)}))

(defn retract-entity*
  "Returns the number of retracted datoms (attributes)."
  [conn user-id ent-id]
  (->> [[:db.fn/retractEntity ent-id]]
       (transact conn user-id)
       :tx-data
       count
       (+ -2)))

(defn ent-type [ent]
  (or (get ent-attr--type (first (keys (select-keys ent (keys ent-attr--type)))))
      (timbre/error "ent-type not found for" ent)))

(defmulti retract-entity (fn [conn user-id ent-id]
                           (ent-type (d/pull (d/db conn) '[*] ent-id))))

(defmethod retract-entity :default [conn user-id ent-id]
  (retract-entity* conn user-id ent-id))

(def tempid? map?)

(defn- coll->tx-data [eid k v old]
  (timbre/debug k v old)
  (let [vs (set v)
        os (set old)]
    (concat
     (map
      #(vector :db/add eid k (if (and (map? %) (not (tempid? (:db/id %))))
                               (:db/id %)
                               %))
      (set/difference vs os))
     (map
      #(vector :db/retract eid k (if (map? %)
                                   (:db/id %)
                                   %))
      (set/difference os vs)))))

(defn- entity->tx-data [db ent]
  (let [eid (:db/id ent)
        old (when-not (tempid? eid) (d/pull db '[*] eid))]
    (mapcat (fn [[k v]]
              (if (or (nil? v) (= v {:db/id nil}))
                (when-let [old-v (get old k)]
                  (list [:db/retract eid k (if (map? old-v)
                                             (:db/id old-v)
                                             old-v)]))
                (when-not (= v (get old k))
                  (if (and (coll? v) (not (map? v)))
                    (coll->tx-data eid k v (get old k))
                    (list [:db/add eid k (if (and (map? v) (not (tempid? (:db/id v))))
                                           (:db/id v)
                                           v)])))))
            (dissoc ent :db/id))))

(defn transact-entity* [conn user-id ent]
  (let [id (:db/id ent)
        ent (cond-> ent
              (not id)
              (assoc :db/id (d/tempid :db.part/user)))
        tx-result (transact conn user-id (entity->tx-data (d/db conn) ent))
        db (:db-after tx-result)
        id (or id (d/resolve-tempid db (:tempids tx-result) (:db/id ent)))]
    (first (if-let [et (ent-type ent)]
             (find-by-type db et {:db/id id})
             (d/pull db '[*] id)))))

(defmulti transact-entity (fn [conn user-id ent]
                            (ent-type ent)))

(defmethod transact-entity :default [conn user-id ent]
  (transact-entity* conn user-id ent))

(defn retract-attr [conn user-id ent]
  (timbre/debug ent)
  "Returns the number of retracted datoms (attributes)."
  (->> (mapv (fn [[attr-key attr-val]]
               [:db/retract (:db/id ent) attr-key attr-val])
             (dissoc ent :db/id))
       (transact conn user-id)
       :tx-data
       count
       (+ -2)))

(defn find-by-id [db eid]
  (d/pull db '[*] eid))

(defn entity-history [db ent-id]
  (->>
   (d/q '[:find ?tx ?aname ?v ?added
          :in $ ?e
          :where
          [?e ?a ?v ?tx ?added]
          [?a :db/ident ?aname]]
        (d/history db)
        ent-id)
   (map (fn [[txid a v added?]]
          (let [tx (d/pull db '[:db/id :db/txInstant :tx/person] txid)]
            {:a a
             :v v
             :tx tx
             :added? added?})))
   (sort-by last)))

(defn tx-datoms [conn t]
  (let [db (d/db conn)]
    (->> (some-> (d/log conn) (d/tx-range t nil) first :data)
         (map (fn [datom]
                {:e (:e datom)
                 :a (d/ident db (:a datom))
                 :v (:v datom)
                 :added? (:added datom)}))
         (sort-by :added?))))

(defn last-txes
  ([conn]
   (last-txes conn 0))
  ([conn from-idx]
   (last-txes conn from-idx 50))
  ([conn from-idx n]
   (let [db (d/db conn)]
     (->> (some-> (d/log conn) (d/tx-range nil nil))
          reverse
          (filter #(> (count (:data %)) 1))
          (drop from-idx)
          (take n)
          (map (fn [row]
                 (-> (d/pull db '[*] (-> row :t d/t->tx))
                     (assoc :datom-count (count (:data row))))))))))

