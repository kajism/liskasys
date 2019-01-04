(ns liskasys.cljs.person-bill
  (:require [cljs-time.core :as t]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [secretary.core :as secretary]
            [liskasys.cljs.comp.history :as history]))

(re-frame/reg-event-fx
 ::after-delete-bill
 common/debug-mw
 (fn [{:keys [db]} [_ bill]]
   {:dispatch [:entities-load :person {:db/id (get-in bill [:person-bill/person :db/id])}]
    :db (update db :daily-plan #(reduce (fn [out [k v]]
                                          (if (= (:db/id bill) (get-in v [:daily-plan/bill :db/id]))
                                            (dissoc out k)
                                            out))
                                        % %))}))

(defn- row->person-fullname [row persons]
  (->> row :person-bill/person :db/id (get persons) cljc.util/person-fullname))

(defn- row->status [row]
  (case (get-in row [:person-bill/status :db/ident])
    :person-bill.status/new
    "nový"
    :person-bill.status/published
    [:span "zveřejněný, " [:b "nezaplacený"]]
    :person-bill.status/paid
    "zaplacený"
    ""))

(defn person-bills []
  (let [table-state (re-frame/subscribe [:table-state :person-bills])
        user (re-frame/subscribe [:auth-user])
        persons (re-frame/subscribe [:entities :person])
        billing-period-id (re-frame/subscribe [:entity-edit-id :billing-period])]
    (fn [person-bills]
      [re-com/v-box :gap "5px"
       :children
       [[:h4 "Rozpisy plateb"]
        [data-table
         :table-id :person-bills
         :rows person-bills
         :colls [[[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :person-bill {:person-bill/period @billing-period-id}])]
                  (fn [row]
                    (when (= (:db/id row) (:selected-row-id @table-state))
                      [re-com/h-box :gap "5px" :justify :end
                       :children
                       [(when (contains? (:-roles @user) "superadmin")
                          [re-com/hyperlink-href
                           :href (str "#/person-bill/" (get-in row [:person-bill/period :db/id]) "/" (:db/id row) "e")
                           :label [re-com/md-icon-button
                                   :md-icon-name "zmdi-edit"
                                   :tooltip "Editovat"
                                   :emphasise? true]])
                        [buttons/delete-button :on-confirm #(re-frame/dispatch [:entity-delete :person-bill (:db/id row) [::after-delete-bill row]])]]]))
                  :none]
                 {:header "Jméno"
                  :val-fn #(row->person-fullname % @persons)
                  :td-comp (fn [& {:keys [value row row-state]}]
                             [:td
                              (if (:selected? row-state)
                                [:a {:href (str "#/person/" (get-in row [:person-bill/person :db/id]) "e")}
                                 value]
                                value)])}
                 ["Var symbol" (comp str :person/var-symbol :person-bill/person)]
                 {:header "Stav"
                  :val-fn #(row->status %)
                  :td-comp (fn [& {:keys [value row row-state]}]
                             [:td
                              (if-not (and (:selected? row-state)
                                           (= :person-bill.status/published (get-in row [:person-bill/status :db/ident])))
                                value
                                [:div
                                 "zveřejněný, "
                                 [re-com/button
                                  :label "Zaplaceno!"
                                  :class "btn-danger btn-sm"
                                  :on-click #(re-frame/dispatch [:liskasys.cljs.billing-period/send-cmd (get-in row [:person-bill/period :db/id]) "set-bill-as-paid" (:db/id row)])]])])}
                 ["Celkem Kč" (comp cljc.util/from-cents :person-bill/total)]
                 ["Cena za docházku" (comp cljc.util/from-cents :person-bill/att-price)]
                 ["Cena za obědy" (fn [{:person-bill/keys [lunch-count] :keys [-lunch-price -total-lunch-price]}]
                                    (str lunch-count " x " (cljc.util/cents->text -lunch-price) " = " (cljc.util/from-cents -total-lunch-price)))]
                 ["Z předch. období" (comp cljc.util/from-cents :-from-previous)]
                 ["Rozvrh docházky" #(-> % :person-bill/person :person/att-pattern cljc.util/att-pattern->text)]
                 ["Rozvrh obědů" #(-> % :person-bill/person :person/lunch-pattern cljc.util/lunch-pattern->text)]]]]])))

(defn page-person-bill []
  (let [item-id (re-frame/subscribe [:entity-edit-id :person-bill])
        billing-period-id (re-frame/subscribe [:entity-edit-id :billing-period])
        billing-period (re-frame/subscribe [:entity-edit :billing-period])
        person-bills (re-frame/subscribe [:entities-where :person-bill {:person-bill/period @billing-period-id}])
        persons (re-frame/subscribe [:entities :person])]
    (fn []
      (let [item (get @person-bills @item-id)]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Rozpis platby"]
          [re-com/label :label "Období"]
          [:b (cljc.util/period->text @billing-period)]
          [re-com/label :label "Jméno"]
          [:b (row->person-fullname item @persons)]
          [re-com/label :label "Variabilní symbol"]
          [:b (-> item :person-bill/person :person/var-symbol)]
          [re-com/label :label "Stav"]
          [re-com/single-dropdown
           :model (some-> item :person-bill/status :db/ident)
           :on-change #(re-frame/dispatch [:entity-change :person-bill (:db/id item) :person-bill/status {:db/ident % :db/id %}])
           :choices [{:id :person-bill.status/new
                      :label "nový"}
                     {:id :person-bill.status/published
                      :label [:span "zveřejněný, " [:b "nezaplacený"]]}
                     {:id :person-bill.status/paid
                      :label "zaplacený"}]
           :width "250px"]
          [:b (row->status item)]
          [re-com/label :label "Celkem Kč"]
          [re-com/h-box :gap "5px"
           :children
           [[re-com/input-text
             :model (str (cljc.util/from-cents (:person-bill/total item)))
             :on-change #(re-frame/dispatch [:entity-change :person-bill (:db/id item) :person-bill/total (cljc.util/to-cents %)])
             :validation-regex #"^\d{0,4}$"]
            "Kč"]]
          [re-com/label :label "Cena za docházku"]
          [re-com/h-box :gap "5px"
           :children
           [[re-com/input-text
             :model (str (cljc.util/from-cents (:person-bill/att-price item)))
             :on-change #(re-frame/dispatch [:entity-change :person-bill (:db/id item) :person-bill/att-price (cljc.util/to-cents %)])
             :validation-regex #"^\d{0,4}$"]
            "Kč"]]
          [re-com/label :label "Počet obědů"]
          [re-com/input-text
           :model (str (:person-bill/lunch-count item))
           :on-change #(re-frame/dispatch [:entity-change :person-bill (:db/id item) :person-bill/lunch-count (cljc.util/parse-int %)])
           :validation-regex #"^[0-9]{0,2}$"
           :width "100px"]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :person-bill])]]]
          [history/view (:db/id item)]]]))))

(secretary/defroute #"/person-bill/(\d+)/(\d*)(e?)" [period-id bill-id edit?]
  (re-frame/dispatch [:entity-set-edit :billing-period (cljc.util/parse-int period-id) (not-empty edit?)])
  (re-frame/dispatch [:entity-set-edit :person-bill (cljc.util/parse-int bill-id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :person-bill]))
(pages/add-page :person-bill #'page-person-bill)
(common/add-kw-url :person-bill "person-bill")
