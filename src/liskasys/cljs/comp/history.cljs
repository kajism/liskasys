(ns liskasys.cljs.comp.history
  (:require [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.cljs.ajax :refer [server-call]]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.data-table :as data-table]
            [re-frame.core :as re-frame]
            [reagent.ratom :as ratom]))

(re-frame/reg-sub-raw
 ::entity-history
 (fn [db [_]]
   (ratom/reaction (:entity-history @db))))

(re-frame/reg-sub-raw
 ::entity-history-datoms
 (fn [db [_]]
   (let [history (re-frame/subscribe [::entity-history])]
     (ratom/reaction (:datoms @history)))))

(re-frame/reg-event-db
 ::load-entity-history
 common/debug-mw
 (fn [db [_ ent-id]]
   (server-call [:entity/history ent-id]
                [::set-history ent-id])
   db))

(re-frame/reg-event-db
 ::set-history
 common/debug-mw
 (fn [db [_ ent-id datoms]]
   (assoc db :entity-history {:db/id ent-id
                              :datoms datoms})))

(defn get-value [& {:keys [row]}]
  (let [lunch-types (re-frame/subscribe [:entities :lunch-type])
        persons (re-frame/subscribe [:entities :person])]
    (fn [& {:keys [row]}]
      [:td
       (case (:a row)
         :person/lunch-type
         (:lunch-type/label (get @lunch-types (:v row)))
         (:person/parent :daily-plan/person)
         (cljc.util/person-fullname (get @persons (:v row)))
         (if (inst? (:v row))
           (time/to-format (:v row) time/ddMMyyyyHHmmss)
           (str (:v row))))])))

(defn view [ent-id]
  (let [history (re-frame/subscribe [::entity-history])
        datoms(re-frame/subscribe [::entity-history-datoms])
        persons (re-frame/subscribe [:entities :person])
        user (re-frame/subscribe [:auth-user])]
    (fn [ent-id]
      (when (and ent-id (contains? (:-roles @user) "admin"))
        (if (not= ent-id (:db/id @history))
          [:div
           [:br]
           [:a {:on-click #(re-frame/dispatch [::load-entity-history ent-id])} "Zobrazit historii změn záznamu"]]
          [:div
           [:h4 "Historie úprav"]
           [data-table/data-table
            :table-id :history
            :colls [{:header "Kdy"
                     :val-fn (comp :db/txInstant :tx)
                     :td-comp (fn [& {:keys [value row]}]
                                [:td
                                 [:a {:href (str "#/transaction/" (-> row :tx :db/id))}
                                  (time/to-format value time/ddMMyyyyHHmmss)]])}
                    {:header "Kdo"
                     :val-fn #(->> % :tx :tx/person :db/id (get @persons) cljc.util/person-fullname)
                     :td-comp (fn [& {:keys [value row]}]
                                [:td
                                 [:a {:href (str "#/person/" (-> row :tx :tx/person :db/id))} value]])}
                    ["Atribut" :a]
                    {:header "Hodnota"
                     :val-fn :v
                     :td-comp get-value}
                    ["Smazano?" (complement :added?)]]
            :rows datoms
            :order-by 0
            :desc? true]])))))
