(ns liskasys.cljs.billing-period
  (:require [cljs-time.core :as t]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.cljs.ajax :refer [server-call]]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.pages :as pages]
            [liskasys.cljs.person-bill :as person-bill]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [secretary.core :as secretary]))

(re-frame/register-handler
 ::send-cmd
 common/debug-mw
 (fn [db [_ period-id cmd bill-id]]
   (server-call [(keyword "person-bill" cmd) {:person-bill/period period-id
                                              :db/id bill-id}]
                [::cmd-results period-id bill-id])
   db))

(re-frame/register-handler
 ::cmd-results
 common/debug-mw
 (fn [db [_ period-id bill-id results]]
   (if bill-id
     (let [bill (first results)]
       (re-frame/dispatch [:entities-load :person {:db/id (get-in bill [:person-bill/person :db/id])}])
       (re-frame/dispatch [:entities-load :daily-plan {:daily-plan/bill (:db/id bill)}])
       (assoc-in db [:person-bill (:db/id bill)] bill))
     (do (re-frame/dispatch [:entities-set :person-bill [:entities-where :person-bill {:person-bill/period period-id}] results])
         db))))

(defn page-billing-periods []
  (let [billing-periods (re-frame/subscribe [:entities :billing-period])
        table-state (re-frame/subscribe [:table-state :billing-periods])
        user (re-frame/subscribe [:auth-user])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Platební období"]
        [data-table
         :table-id :billing-periods
         :rows billing-periods
         :colls [[[re-com/h-box :gap "5px" :justify :end
                   :children
                   [[re-com/md-icon-button
                     :md-icon-name "zmdi-plus-square"
                     :tooltip "Přidat"
                     :on-click #(set! js/window.location.hash "#/billing-period/e")]
                    [re-com/md-icon-button
                     :md-icon-name "zmdi-refresh"
                     :tooltip "Přenačíst ze serveru"
                     :on-click #(re-frame/dispatch [:entities-load :billing-period])]]]
                  (fn [row]
                    (when (= (:db/id row) (:selected-row-id @table-state))
                      [re-com/h-box :gap "5px" :justify :end
                       :children
                       [[re-com/hyperlink-href
                         :href (str "#/billing-period/" (:db/id row) "e")
                         :label [re-com/md-icon-button
                                 :md-icon-name "zmdi-edit"
                                 :tooltip "Editovat"]]
                        (when (contains? (:-roles @user) "superadmin")
                          [buttons/delete-button :on-confirm #(re-frame/dispatch [:entity-delete :billing-period (:db/id row)]) :emphasise? true])]]))
                  :none]
                 ["Od" (comp cljc-util/yyyymm->text :billing-period/from-yyyymm)]
                 ["Do" (comp cljc-util/yyyymm->text :billing-period/to-yyyymm)]]
         :desc? true]]])))

(defn page-billing-period []
  (let [item-id (re-frame/subscribe [:entity-edit-id :billing-period])
        billing-period (re-frame/subscribe [:entity-edit :billing-period])
        person-bills (re-frame/subscribe [:entities-where :person-bill {:person-bill/period @item-id}])]
    (fn []
      (let [item @billing-period]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Platební období"]
          [re-com/label :label "Od - Do"]
          [re-com/h-box :gap "5px"
           :children
           [[re-com/input-text
             :model (str (:billing-period/from-yyyymm item))
             :on-change #(re-frame/dispatch [:entity-change :billing-period (:db/id item) :billing-period/from-yyyymm (cljc-util/parse-int %)])
             :validation-regex #"^\d{0,6}$"
             :width "120px"]
            "-"
            [re-com/input-text
             :model (str (:billing-period/to-yyyymm item))
             :on-change #(re-frame/dispatch [:entity-change :billing-period (:db/id item) :billing-period/to-yyyymm (cljc-util/parse-int %)])
             :validation-regex #"^\d{0,6}$"
             :width "120px"]
            "RRRRMM"]]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :billing-period])]
            "nebo"
            (when (:db/id item)
              [re-com/hyperlink-href :label [re-com/button :label "Nové"] :href (str "#/billing-period/e")])
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/billing-periods")]]]
          (when (:db/id item)
            [:div
             [re-com/h-box :gap "5px"
              :children
              [[re-com/button
                :label "Vygenerovat rozpisy"
                :class "btn-danger"
                :on-click #(re-frame/dispatch [::send-cmd (:db/id item) "generate"])]
               (when (some #(= (get-in % [:person-bill/status :db/ident]) :person-bill.status/new) (vals @person-bills))
                 [re-com/button
                  :label "Zveřejnit nové a poslat emaily"
                  :class "btn-danger"
                  :on-click #(re-frame/dispatch [::send-cmd (:db/id item) "publish-all-bills"])])]]
             [person-bill/person-bills person-bills]])]]))))

(secretary/defroute "/billing-periods" []
  (re-frame/dispatch [:set-current-page :billing-periods]))
(pages/add-page :billing-periods #'page-billing-periods)

(secretary/defroute #"/billing-period/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :billing-period (cljc-util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :billing-period]))
(pages/add-page :billing-period #'page-billing-period)
(common/add-kw-url :billing-period "billing-period")
