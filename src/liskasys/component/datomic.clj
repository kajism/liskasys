(ns liskasys.component.datomic
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [io.rkn.conformity :as conformity]
            [liskasys.service :as service]
            [taoensso.timbre :as timbre]))

(defrecord Datomic [uri conn]
  component/Lifecycle
  (start [component]
    (let [_ (d/create-database uri)
          conn (d/connect uri)
          norms-map (conformity/read-resource "liskasys/datomic_schema.edn")]
      (timbre/info "Connected to datomic, going to run conformity")
      (conformity/ensure-conforms conn norms-map)
      (when-let [person-bills (not-empty (d/q '[:find [(pull ?e [:db/id :person-bill/paid?]) ...]
                                                :where
                                                [?e :person-bill/total]
                                                (not [?e :person-bill/status])]
                                          (d/db conn)))]
        (timbre/info "Adding billing status")
        (->> person-bills
             (mapv (fn [{:keys [:db/id :person-bill/paid?]}]
                     [:db/add id :person-bill/status (if paid? :person-bill.status/paid :person-bill.status/new)]))
             (service/transact conn nil)))
      (assoc component :conn conn)))
  (stop [component]
    (when conn
      (timbre/info "Releasing datomic connection")
      (d/release conn))
    (assoc component :conn nil)))

(defn datomic [config]
  (map->Datomic config))
