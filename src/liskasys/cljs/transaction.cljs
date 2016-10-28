(ns liskasys.cljs.transaction
  (:require [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.cljs.ajax :refer [server-call]]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.data-table :as data-table]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.ratom :as ratom]
            [secretary.core :as secretary]))

(re-frame/register-sub
 ::tx
 (fn [db [_]]
   (ratom/reaction (:tx @db))))

(re-frame/register-sub
 ::tx-datoms
 (fn [db [_]]
   (let [tx (re-frame/subscribe [::tx])]
     (ratom/reaction (:datoms @tx)))))

(re-frame/register-handler
 ::load-tx
 common/debug-mw
 (fn [db [_ tx-id]]
   (server-call [:tx/datoms tx-id]
                [::set-tx tx-id])
   (assoc db :tx nil)))

(re-frame/register-handler
 ::set-tx
 common/debug-mw
 (fn [db [_ tx-id datoms]]
   (assoc db :tx {:db/id tx-id
                  :datoms datoms})))

(defn transactions []
  [:div "TBD"])

(defn get-value [& {:keys [row]}]
  (let [lunch-types (re-frame/subscribe [:entities :lunch-type])
        persons (re-frame/subscribe [:entities :person])]
    (fn [& {:keys [row]}]
      [:td
       (let [{:keys [a v]} row]
         (case a
           :person/lunch-type
           (:lunch-type/label (get @lunch-types v))
           (:person/parent :daily-plan/person)
           (cljc-util/person-fullname (get @persons v))
           (if (inst? v)
             (time/to-format v time/ddMMyyyyHHmmss)
             (str v))))])))

(defn transaction []
  (let [tx (re-frame/subscribe [::tx])
        datoms(re-frame/subscribe [::tx-datoms])
        user (re-frame/subscribe [:auth-user])]
    (fn []
      (when (contains? (:-roles @user) "admin")
        (if-not @tx
          [re-com/throbber]
          [:div
           [:h4 "Detail transakce"]
           [:label "Kdo"][:br]
           [:label "Kdy"][:br]
           [data-table/data-table
            :table-id :transaction
            :colls [{:header "Entita"
                     :val-fn #(-> % :e str)
                     :td-comp (fn [& {:keys [row value]}]
                                [:td
                                 [:a {:href (str "#/" (namespace (:a row)) "/" value)} value]])}
                    ["Atribut" :a]
                    {:header "Hodnota"
                     :val-fn (constantly nil)
                     :td-comp get-value}
                    ["Smazano?" (complement :added?)]]
            :rows datoms
            :order-by 0
            :desc? true]])))))

(secretary/defroute "/transactions" []
  (re-frame/dispatch [:set-current-page :transactions]))
(pages/add-page :transactions #'transactions)

(secretary/defroute #"/transaction/(\d*)(e?)" [id]
  (re-frame/dispatch [::load-tx (cljc-util/parse-int id)])
  (re-frame/dispatch [:set-current-page :transaction]))
(pages/add-page :transaction #'transaction)
