(ns liskasys.cljs.bank-holiday
  (:require [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.util :as util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]))

(defn page-bank-holidays []
  (let [bank-holidays (re-frame/subscribe [:entities :bank-holiday])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Státní svátky"]
        [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/bank-holiday/e")]
        [data-table
         :table-id :bank-holidays
         :rows @bank-holidays
         :colls [["Název" :bank-holiday/label]
                 ["Měsíc" :bank-holiday/month]
                 ["Den" :bank-holiday/day]
                 ["+/- dnů od Velikonoc" :bank-holiday/easter-delta]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :bank-holiday])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/bank-holiday/" (:db/id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      [buttons/delete-button #(re-frame/dispatch [:entity-delete :bank-holiday (:db/id row)])]]])
                  :csv-export]]
         :order-by 1]]])))

(defn page-bank-holiday []
  (let [bank-holiday (re-frame/subscribe [:entity-edit :bank-holiday])]
    (fn []
      (let [item @bank-holiday]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Státní svátek"]
          [re-com/label :label "Název"]
          [re-com/input-text
           :model (str (:bank-holiday/label item))
           :on-change #(re-frame/dispatch [:entity-change :bank-holiday (:db/id item) :bank-holiday/label %])
           :width "400px"]
          [re-com/label :label "Měsíc"]
          [re-com/input-text
           :model (str (:bank-holiday/month item))
           :on-change #(re-frame/dispatch [:entity-change :bank-holiday (:db/id item) :bank-holiday/month (util/parse-int %)])
           :validation-regex #"^\d{0,2}$"
           :width "60px"]
          [re-com/label :label "Den"]
          [re-com/input-text
           :model (str (:bank-holiday/day item))
           :on-change #(re-frame/dispatch [:entity-change :bank-holiday (:db/id item) :bank-holiday/day (util/parse-int %)])
           :validation-regex #"^\d{0,2}$"
           :width "60px"]
          [re-com/label :label "+/- dnů od Velikonoc"]
          [re-com/input-text
           :model (str (:bank-holiday/easter-delta item))
           :on-change #(re-frame/dispatch [:entity-change :bank-holiday (:db/id item) :bank-holiday/easter-delta (util/parse-int %)])
           :validation-regex #"^[-\d]{0,2}$"
           :width "60px"]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :bank-holiday])]
            "nebo"
            [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/bank-holiday/e")]
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/bank-holidays")]]]]]))))

(secretary/defroute "/bank-holidays" []
  (re-frame/dispatch [:set-current-page :bank-holidays]))
(pages/add-page :bank-holidays #'page-bank-holidays)

(secretary/defroute #"/bank-holiday/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :bank-holiday (util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :bank-holiday]))
(pages/add-page :bank-holiday #'page-bank-holiday)
(common/add-kw-url :bank-holiday "bank-holiday")
