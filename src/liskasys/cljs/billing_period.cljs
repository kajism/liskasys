(ns liskasys.cljs.billing-period
  (:require [clj-brnolib.cljs.comp.buttons :as buttons]
            [clj-brnolib.cljs.comp.data-table :refer [data-table]]
            [clj-brnolib.cljs.util :as util]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.cljs.ajax :refer [server-call]]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]
            [cljs-time.core :as t]))

(re-frame/register-handler
 ::generate-person-bills
 common/debug-mw
 (fn [db [_ period-id]]
   (server-call [:person-bill/generate {:person-bill/period period-id}]
                [:entities-set [:entities-where :person-bill {:person-bill/period period-id}]])
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
         :colls [["Od" (comp yyyymm->str :billing-period/from-yyyymm)]
                 ["Do" (comp yyyymm->str :billing-period/to-yyyymm)]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :billing-period])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/billing-period/" (:db/id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      [buttons/delete-button #(re-frame/dispatch [:entity-delete :billing-period (:db/id row)])]]])
                  :csv-export]]]]])))

(defn page-billing-period []
  (let [item-id (re-frame/subscribe [:entity-edit-id :billing-period])
        billing-period (re-frame/subscribe [:entity-edit :billing-period])
        person-bills (re-frame/subscribe [:entities-where :person-bill {:person-bill/period @item-id}])
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
             :model (str (:billing-period/from-yyyymm item))
             :on-change #(re-frame/dispatch [:entity-change :billing-period (:db/id item) :billing-period/from-yyyymm (util/parse-int %)])
             :validation-regex #"^\d{0,6}$"
             :width "120px"]
            "-"
            [re-com/input-text
             :model (str (:billing-period/to-yyyymm item))
             :on-change #(re-frame/dispatch [:entity-change :billing-period (:db/id item) :billing-period/to-yyyymm (util/parse-int %)])
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
              [re-com/button
               :label (str (if (pos? (count @person-bills)) "Přegenerovat nezaplacené" "Vygenerovat") " rozpisy plateb")
               :class "btn-danger"
               :on-click #(re-frame/dispatch [::generate-person-bills (:db/id item)])]
              [data-table
               :table-id :person-bills
               :rows @person-bills
               :colls [["Jméno" (fn [row]
                                  [re-com/h-box :gap "5px"
                                   :children
                                   [(->> row :person-bill/person :db/id (get @persons) cljc-util/person-fullname)
                                    [re-com/hyperlink-href
                                     :href (str "#/person/" (get-in row [:person-bill/person :db/id]) "e")
                                     :label [re-com/md-icon-button
                                             :md-icon-name "zmdi-edit"
                                             :tooltip "Editovat"]]]])]
                       ["Var symbol" :person/var-symbol]
                       ["Celkem Kč" (comp util/from-cents :person-bill/total)]
                       ["Zaplaceno?" :person-bill/paid?]
                       ["Cena za docházku" (comp util/from-cents :person-bill/att-price)]
                       ["Obědy" :person-bill/lunch-count]
                       ["Rozvrh docházky" #(when (not= (:person/att-pattern %) "0000000") (:person/att-pattern %))]
                       ["Rozvrh obědů" #(when (not= (:person/lunch-pattern %) "0000000") (:person/lunch-pattern %))]]]]])]]))))

(secretary/defroute "/billing-periods" []
  (re-frame/dispatch [:set-current-page :billing-periods]))
(pages/add-page :billing-periods #'page-billing-periods)

(secretary/defroute #"/billing-period/(\d*)(e?)" [id edit?]
  (when-not (util/parse-int id)
    (let [[_ from to] (iterate #(t/plus % (t/months 1)) (t/today))]
      (re-frame/dispatch [:entity-new :billing-period {:billing-period/from-yyyymm (+ (* (t/year from) 100) (t/month from))
                                                       :billing-period/to-yyyymm (+ (* (t/year to) 100) (t/month to))}])))
  (re-frame/dispatch [:entity-set-edit :billing-period (util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :billing-period]))
(pages/add-page :billing-period #'page-billing-period)
(common/add-kw-url :billing-period "billing-period")
