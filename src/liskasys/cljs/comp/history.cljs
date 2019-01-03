(ns liskasys.cljs.comp.history
  (:require [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.data-table :as data-table]
            [re-frame.core :as re-frame]
            [reagent.ratom :as ratom]))

(re-frame/reg-sub
 ::entity-history
 (fn [db [_]]
   (:entity-history db)))

(re-frame/reg-sub
 ::entity-history-datoms
 :<- [::entity-history]
 (fn [history [_]]
   (:datoms history)))

(re-frame/reg-event-fx
 ::load-entity-history
 common/debug-mw
 (fn [_ [_ ent-id]]
   {:server-call {:req-msg [:entity/history ent-id]
                  :resp-evt [::set-history ent-id]}}))

(re-frame/reg-event-db
 ::set-history
 [common/debug-mw (re-frame/path :entity-history)]
 (fn [_ [_ ent-id datoms]]
   {:db/id ent-id
    :datoms datoms}))

(defn disp-value [& {:keys [row]}]
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
                     :td-comp disp-value}
                    ["Smazano?" (complement :added?)]]
            :rows datoms
            :order-by 0
            :desc? true]])))))
