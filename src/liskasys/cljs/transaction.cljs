(ns liskasys.cljs.transaction
  (:require [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.ratom :as ratom]
            [secretary.core :as secretary]))

(re-frame/reg-sub
 ::tx
 (fn [db [_]]
   (:tx db)))

(re-frame/reg-sub
 ::txes
 (fn [db [_]]
   (:txes db)))

(re-frame/reg-sub
 ::tx-datoms
 :<- [::tx]
 (fn [tx [_]]
   (:datoms tx)))

(re-frame/reg-event-fx
 ::load-tx
 common/debug-mw
 (fn [{:keys [db]} [_ tx-id]]
   {:server-call {:req-msg [:tx/datoms tx-id]
                  :resp-evt [::set-tx tx-id]}
    :db (assoc db :tx nil)}))

(re-frame/reg-event-db
 ::set-tx
 [common/debug-mw (re-frame/path :tx)]
 (fn [old-tx [_ tx-id datoms]]
   {:db/id tx-id
    :datoms datoms}))

(re-frame/reg-event-fx
 ::load-txes
 common/debug-mw
 (fn [{:keys [db]} [_]]
   {:server-call {:req-msg [:tx/range {:from-idx 0
                                       :n 4000}]
                  :resp-evt [::set-txes]}
    :db (assoc db :txes nil)}))

(re-frame/reg-event-db
 ::set-txes
 [common/debug-mw (re-frame/path :txes)]
 (fn [old-txes [_ txes]]
   txes))

(defn transactions []
  (let [txes (re-frame/subscribe [::txes])
        table-state (re-frame/subscribe [:table-state :transactions])
        persons (re-frame/subscribe [:entities :person])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Transakce"]
        [data-table
         :table-id :transactions
         :rows txes
         :desc? true
         :colls [[[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [::load-txes])]
                  (fn [row]
                    (when (= (:db/id row) (:selected-row-id @table-state))
                      [re-com/h-box :gap "5px" :justify :end
                       :children
                       [[re-com/hyperlink-href
                         :href (str "#/transaction/" (:db/id row) "e")
                         :label [re-com/md-icon-button
                                 :md-icon-name "zmdi-edit"
                                 :tooltip "Editovat"]]]]))
                  :none]
                 {:header "Kdy"
                  :val-fn :db/txInstant
                  :td-comp (fn [& {:keys [value]}]
                             [:td (time/to-format value time/ddMMyyyyHHmmss)])}
                 {:header "Kdo"
                  :val-fn #(some->> % :tx/person :db/id (get @persons) cljc.util/person-fullname)
                  :td-comp (fn [& {:keys [value row]}]
                             [:td
                              [:a {:href (str "#/person/" (-> row :tx/person :db/id))} value]])}
                 ["Počet změn" :datom-count]]]]])))

(defn get-value [& {:keys [row]}]
  (let [lunch-types (re-frame/subscribe [:entities :lunch-type])
        persons (re-frame/subscribe [:entities :person])
        billing-periods (re-frame/subscribe [:entities :billing-period])]
    (fn [& {:keys [row]}]
      [:td
       (let [{:keys [a v]} row]
         (case a
           :person/lunch-type
           (:lunch-type/label (get @lunch-types v))
           :person-bill/period
           (cljc.util/period->text (get @billing-periods v))
           (:person/parent :daily-plan/person :tx/person :person-bill/person)
           (cljc.util/person-fullname (get @persons v))
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
           [data-table
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
            :order-by 3]])))))

(secretary/defroute "/transactions" []
  (re-frame/dispatch [::load-txes])
  (re-frame/dispatch [:set-current-page :transactions]))
(pages/add-page :transactions #'transactions)

(secretary/defroute #"/transaction/(\d*)(e?)" [id]
  (re-frame/dispatch [::load-tx (cljc.util/parse-int id)])
  (re-frame/dispatch [:set-current-page :transaction]))
(pages/add-page :transaction #'transaction)
