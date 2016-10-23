(ns liskasys.cljs.comp.history
  (:require [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.cljs.ajax :refer [server-call]]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.data-table :as data-table]
            [re-frame.core :as re-frame]
            [reagent.ratom :as ratom]))

(re-frame/register-sub
 ::entity-history
 (fn [db [_]]
   (ratom/reaction (:entity-history @db))))

(re-frame/register-sub
 ::entity-history-datoms
 (fn [db [_]]
   (let [history (re-frame/subscribe [::entity-history])]
     (ratom/reaction (:datoms @history)))))

(re-frame/register-handler
 ::load-entity-history
 common/debug-mw
 (fn [db [_ ent-id]]
   (server-call [:entity/history ent-id]
                [::set-history ent-id])
   db))

(re-frame/register-handler
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
       (let [[_ _ a v] row]
         (case a
           :person/lunch-type
           (:lunch-type/label (get @lunch-types v))
           (:person/parent :daily-plan/person)
           (cljc-util/person-fullname (get @persons v))
           (if (inst? v)
             (time/to-format v time/ddMMyyyyHHmmss)
             (str v))))])))

(defn view [ent-id]
  (let [history (re-frame/subscribe [::entity-history])
        datoms(re-frame/subscribe [::entity-history-datoms])
        persons (re-frame/subscribe [:entities :person])
        user (re-frame/subscribe [:auth-user])]
    (fn [ent-id]
      (when (and ent-id (contains? (:-roles @user) "superadmin"))
        (if (not= ent-id (:db/id @history))
          [:div
           [:br]
           [:a {:on-click #(re-frame/dispatch [::load-entity-history ent-id])} "Zobrazit historii"]]
          [:div
           [:h4 "Historie úprav"]
           [data-table/data-table
            :table-id :history
            :colls [{:header "Kdy"
                     :val-fn first
                     :td-comp (fn [& {:keys [value]}]
                                [:td
                                 (time/to-format value time/ddMMyyyyHHmmss)])}
                    ["Kdo" #(->> % second (get @persons) cljc-util/person-fullname)]
                    ["Atribut" #(nth % 2)]
                    {:header "Hodnota"
                     :val-fn (constantly nil)
                     :td-comp get-value}
                    ["Smazano?" #(not (nth % 4))]]
            :rows datoms
            :order-by 0
            :desc? true]])))))
