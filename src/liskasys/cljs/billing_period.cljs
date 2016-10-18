(ns liskasys.cljs.billing-period
  (:require [cljs-time.core :as t]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.cljs.ajax :refer [server-call]]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.pages :as pages]
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
                [:entities-set [:entities-where :person-bill {:person-bill/period period-id}]])
   db))

(defn page-billing-periods []
  (let [billing-periods (re-frame/subscribe [:entities :billing-period])
        table-state (re-frame/subscribe [:table-state :billing-periods])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Platební období"]
        [re-com/hyperlink-href :label [re-com/button :label "Nové"] :href (str "#/billing-period/e")]
        [data-table
         :table-id :billing-periods
         :rows billing-periods
         :colls [["Od" (comp cljc-util/yyyymm->text :billing-period/from-yyyymm)]
                 ["Do" (comp cljc-util/yyyymm->text :billing-period/to-yyyymm)]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :billing-period])]
                  (fn [row]
                    (when (= (:db/id row) (:selected-row-id @table-state))
                      [re-com/h-box
                       :gap "5px"
                       :children
                       [[re-com/hyperlink-href
                         :href (str "#/billing-period/" (:db/id row) "e")
                         :label [re-com/md-icon-button
                                 :md-icon-name "zmdi-edit"
                                 :tooltip "Editovat"]]
                        #_[buttons/delete-button #(re-frame/dispatch [:entity-delete :billing-period (:db/id row)])]]]))
                  :csv-export]]
         :desc? true]]])))

(defn page-billing-period []
  (let [item-id (re-frame/subscribe [:entity-edit-id :billing-period])
        billing-period (re-frame/subscribe [:entity-edit :billing-period])
        person-bills (re-frame/subscribe [:entities-where :person-bill {:person-bill/period @item-id}])
        persons (re-frame/subscribe [:entities :person])
        table-state (re-frame/subscribe [:table-state :person-bills])]
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
            [re-com/hyperlink-href :label [re-com/button :label "Nové"] :href (str "#/billing-period/e")]
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/billing-periods")]]]
          (when (:db/id item)
            [re-com/v-box :gap "5px"
             :children
             [[:h4 "Rozpisy plateb"]
              [re-com/h-box :gap "5px"
               :children
               [(when (or (empty? @person-bills)
                          (some #(not= (get-in % [:person-bill/status :db/ident]) :person-bill.status/paid) (vals @person-bills)))
                  [re-com/button
                   :label (str (if (seq @person-bills)
                                 "Přegenerovat nezaplacené"
                                 "Vygenerovat")
                               " rozpisy")
                   :class "btn-danger"
                   :on-click #(re-frame/dispatch [::send-cmd (:db/id item) "generate"])])
                (when (some #(= (get-in % [:person-bill/status :db/ident]) :person-bill.status/new) (vals @person-bills))
                  [re-com/button
                   :label "Zveřejnit rozpisy"
                   :class "btn-danger"
                   :on-click #(re-frame/dispatch [::send-cmd (:db/id item) "publish-all-bills"])])]]
              [data-table
               :table-id :person-bills
               :rows person-bills
               :colls [["Jméno" (fn [row]
                                  (let [label (->> row :person-bill/person :db/id (get @persons) cljc-util/person-fullname)]
                                    (if (= (:db/id row) (:selected-row-id @table-state))
                                      [re-com/hyperlink-href
                                       :href (str "#/person/" (get-in row [:person-bill/person :db/id]) "e")
                                       :label label]
                                      label)))]
                       ["Var symbol" (comp :person/var-symbol :person-bill/person)]
                       ["Stav" (fn [row]
                                 (case (get-in row [:person-bill/status :db/ident])
                                   :person-bill.status/new
                                   "nový"
                                   :person-bill.status/published
                                   [:div "zveřejněný"
                                    (when (= (:db/id row) (:selected-row-id @table-state))
                                      [re-com/button
                                       :label "Zaplacený"
                                       :class "btn-danger btn-xs"
                                       :on-click #(re-frame/dispatch [::send-cmd (:db/id item) "set-bill-as-paid" (:db/id row)])])]
                                   :person-bill.status/paid "zaplacený"
                                   ""))]
                       ["Celkem Kč" (comp cljc-util/from-cents :person-bill/total)]
                       ["Cena za docházku" (comp cljc-util/from-cents :person-bill/att-price)]
                       ["Cena za obědy" (fn [{:person-bill/keys [lunch-count] :keys [_lunch-price _total-lunch-price]}]
                                          (str lunch-count " x " (cljc-util/cents->text _lunch-price) " = " (cljc-util/from-cents _total-lunch-price)))]
                       ["Z předch. období" (comp cljc-util/from-cents :_from-previous)]
                       ["Rozvrh docházky" #(-> % :person-bill/person :person/att-pattern cljc-util/att-pattern->text)]
                       ["Rozvrh obědů" #(-> % :person-bill/person :person/lunch-pattern cljc-util/lunch-pattern->text)]]]]])]]))))

(secretary/defroute "/billing-periods" []
  (re-frame/dispatch [:set-current-page :billing-periods]))
(pages/add-page :billing-periods #'page-billing-periods)

(secretary/defroute #"/billing-period/(\d*)(e?)" [id edit?]
  (when-not (cljc-util/parse-int id)
    (let [[_ from to] (iterate #(t/plus % (t/months 1)) (t/today))]
      (re-frame/dispatch [:entity-new :billing-period {:billing-period/from-yyyymm (+ (* (t/year from) 100) (t/month from))
                                                       :billing-period/to-yyyymm (+ (* (t/year to) 100) (t/month to))}])))
  (re-frame/dispatch [:entity-set-edit :billing-period (cljc-util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :billing-period]))
(pages/add-page :billing-period #'page-billing-period)
(common/add-kw-url :billing-period "billing-period")
