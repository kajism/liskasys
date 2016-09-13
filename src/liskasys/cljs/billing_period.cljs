(ns liskasys.cljs.billing-period
  (:require [clj-brnolib.cljs.comp.buttons :as buttons]
            [clj-brnolib.cljs.comp.data-table :refer [data-table]]
            [clj-brnolib.cljs.util :as util]
            [liskasys.cljs.ajax :refer [server-call]]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]))

(re-frame/register-handler
 ::generate-person-bills
 common/debug-mw
 (fn [db [_ period-id]]
   (server-call [:person-bill/generate {:period-id period-id}]
                [:entities-set [:entities-where :person-bill {:period-id period-id}]])
   db))

(defn yyyymm->str [ym]
  (when ym
    (let [m (rem ym 100)]
      (str (quot ym 100) "/" (if (<= m 9) "0") m))))

(defn page-billing-periods []
  (let [billing-periods (re-frame/subscribe [:entities :billing-period])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Platební období"]
        [re-com/hyperlink-href :label [re-com/button :label "Nové"] :href (str "#/billing-period/e")]
        [data-table
         :table-id :billing-periods
         :rows @billing-periods
         :colls [["Od" (comp yyyymm->str :from-yyyymm)]
                 ["Do" (comp yyyymm->str :to-yyyymm)]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :billing-period])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/billing-period/" (:id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      [buttons/delete-button #(re-frame/dispatch [:entity-delete :billing-period (:id row)])]]])
                  :csv-export]]]]])))

(defn page-billing-period []
  (let [item-id (re-frame/subscribe [:entity-edit-id :billing-period])
        billing-period (re-frame/subscribe [:entity-edit :billing-period])
        person-bills (re-frame/subscribe [:entities-where :person-bill {:period-id @item-id}])
        persons (re-frame/subscribe [:entities :person])]
    (fn []
      (let [item @billing-period]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Platební období"]
          [re-com/label :label "Od - Do"]
          [re-com/h-box :gap "5px"
           :children
           [[re-com/input-text
             :model (str (:from-yyyymm item))
             :on-change #(re-frame/dispatch [:entity-change :billing-period (:id item) :from-yyyymm (util/parse-int %)])
             :validation-regex #"^\d{0,6}$"
             :width "120px"]
            "-"
            [re-com/input-text
             :model (str (:to-yyyymm item))
             :on-change #(re-frame/dispatch [:entity-change :billing-period (:id item) :to-yyyymm (util/parse-int %)])
             :validation-regex #"^\d{0,6}$"
             :width "120px"]
            "RRRRMM"]]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :billing-period])]
            "nebo"
            [re-com/hyperlink-href :label [re-com/button :label "Nové"] :href (str "#/billing-period/e")]
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/billing-periods")]]]
          (when (:id item)
            [re-com/v-box :gap "5px"
             :children
             [[:h4 "Předpisy plateb"]
              [re-com/button
               :label (str (if (pos? (count @person-bills)) "Přegenerovat nezaplacené" "Vygenerovat") " předpisy plateb")
               :class "btn-danger"
               :on-click #(re-frame/dispatch [::generate-person-bills (:id item)])]
              [data-table
               :rows @person-bills
               :colls [["Jméno" #(->> % :person-id (get @persons) :-fullname)]
                       ["Celkem Kč" (comp util/from-cents :total-cents)]
                       ["Zaplaceno?" :paid?]
                       ["Cena za docházku" (comp util/from-cents :att-price-cents)]
                       ["Obědy" :total-lunches]
                       ["Rozvrh docházky" #(when (not= (:att-pattern %) "0000000") (:att-pattern %))]
                       ["Rozvrh obědů" #(when (not= (:lunch-pattern %) "0000000") (:lunch-pattern %))]]]]])]]))))

(secretary/defroute "/billing-periods" []
  (re-frame/dispatch [:set-current-page :billing-periods]))
(pages/add-page :billing-periods #'page-billing-periods)

(secretary/defroute #"/billing-period/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :billing-period (util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :billing-period]))
(pages/add-page :billing-period #'page-billing-period)
(common/add-kw-url :billing-period "billing-period")
