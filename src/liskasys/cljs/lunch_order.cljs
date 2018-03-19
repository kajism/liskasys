(ns liskasys.cljs.lunch-order
  (:require [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.comp.history :as history]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]))

(defn page-lunch-orders []
  (let [lunch-orders (re-frame/subscribe [:entities :lunch-order])
        table-state (re-frame/subscribe [:table-state :lunch-orders])
        user (re-frame/subscribe [:auth-user])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Objednávky obědů"]
        [data-table
         :table-id :lunch-orders
         :rows lunch-orders
         :colls [[[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :lunch-order])]
                  (fn [row]
                    (when (= (:db/id row) (:selected-row-id @table-state))
                      [re-com/h-box :gap "5px" :justify :end
                       :children
                       [[re-com/hyperlink-href
                         :href (str "#/lunch-order/" (:db/id row) "e")
                         :label [re-com/md-icon-button
                                 :md-icon-name "zmdi-edit"
                                 :tooltip "Editovat"]]
                        (when (contains? (:-roles @user) "superadmin")
                          [buttons/delete-button :on-confirm #(re-frame/dispatch [:entity-delete :lunch-order (:db/id row)]) :emphasise? true])]]))
                  :none]
                 ["Datum" :lunch-order/date]
                 ["Počet obědů" :lunch-order/total]]
         :desc? true]]])))

(defn page-lunch-order []
  (let [lunch-order (re-frame/subscribe [:entity-edit :lunch-order])
        noop-fn #()
        user (re-frame/subscribe [:auth-user])]
    (fn []
      (let [item @lunch-order
            errors (:-errors item)]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Objednávka obědů"]
          [re-com/label :label "Datum"]
          [re-com/input-text
           :model (time/to-format (:lunch-order/date item) time/ddMMyyyy)
           :disabled? true
           :on-change noop-fn]
          [re-com/label :label "Počet obědů"]
          [re-com/input-text
           :model (str (:lunch-order/total item))
           :disabled? (not (contains? (:-roles @user) "superadmin"))
           :on-change noop-fn]
          (if (contains? (:-roles @user) "superadmin")
            [re-com/h-box :align :center :gap "5px"
             :children
             [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :lunch-order])]
              "nebo"
              [re-com/hyperlink-href :label [re-com/button :label "Nová"] :href (str "#/lunch-order/e")]
              [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/lunch-orders")]]]
            [re-com/h-box :align :center :gap "5px"
             :children
             [[re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/lunch-orders")]]])
          [history/view (:db/id item)]]]))))

(secretary/defroute "/lunch-orders" []
  (re-frame/dispatch [:set-current-page :lunch-orders]))
(pages/add-page :lunch-orders #'page-lunch-orders)

(secretary/defroute #"/lunch-order/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :lunch-order (cljc-util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :lunch-order]))
(pages/add-page :lunch-order #'page-lunch-order)
(common/add-kw-url :lunch-order "lunch-order")
