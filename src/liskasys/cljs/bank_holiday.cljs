(ns liskasys.cljs.bank-holiday
  (:require [clj-brnolib.cljs.comp.buttons :as buttons]
            [clj-brnolib.cljs.comp.data-table :refer [data-table]]
            [clj-brnolib.cljs.util :as util]
            [clj-brnolib.validation :as validation]
            [cljs.pprint :refer [pprint]]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]
            [reagent.core :as reagent]
            [clojure.string :as str]
            [clj-brnolib.cljs.comp.input-text :refer [input-text]]))

(defn page-bank-holidays []
  (let [bank-holidays (re-frame/subscribe [:entities :bank-holiday])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Státní svátek"]
        [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/bank-holiday/e")]
        [data-table
         :table-id :bank-holidays
         :rows @bank-holidays
         :colls [["Měsíc" :month]
                 ["Den" :day]
                 ["+- od Velikonoc" :easter-delta]
                 ["Název" :label]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :bank-holiday])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/bank-holiday/" (:id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      [buttons/delete-button #(re-frame/dispatch [:entity-delete :bank-holiday (:id row)])]]])
                  :csv-export]]]]])))

(defn page-bank-holiday []
  (let [bank-holiday (re-frame/subscribe [:entity-edit :bank-holiday])
        validation-fn #(cond-> {}
                         (str/blank? (:label %))
                         (assoc :label "Vyplňte název")
                         true
                         timbre/spy)]
    (fn []
      (let [item @bank-holiday
            errors (:-errors item)]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Státní svátky"]
          [re-com/label :label "Název"]
          [re-com/input-text
           :model (str (:label item))
           :on-change #(re-frame/dispatch [:entity-change :bank-holiday (:id item) :label %])
           :width "400px"]
          [re-com/label :label "Měsíc"]
          [re-com/input-text
           :model (str (:month item))
           :on-change #(re-frame/dispatch [:entity-change :bank-holiday (:id item) :month (util/parse-int %)])
           :validation-regex #"^\d{0,2}$"
           :width "60px"]
          [re-com/label :label "Den"]
          [re-com/input-text
           :model (str (:day item))
           :on-change #(re-frame/dispatch [:entity-change :bank-holiday (:id item) :day (util/parse-int %)])
           :validation-regex #"^\d{0,2}$"
           :width "60px"]
          [re-com/label :label "+- dnů od Velikonoc"]
          [re-com/input-text
           :model (str (:easter-delta item))
           :on-change #(re-frame/dispatch [:entity-change :bank-holiday (:id item) :easter-delta (util/parse-int %)])
           :validation-regex #"^[-\d]{0,2}$"
           :width "60px"]
          [re-com/label :label "Platný od roku"]
          [re-com/input-text
           :model (str (:valid-from-year item))
           :on-change #(re-frame/dispatch [:entity-change :bank-holiday (:id item) :valid-from-year (util/parse-int %)])
           :validation-regex #"^\d{0,4}$"
           :width "60px"]
          [re-com/label :label "Platný do roku"]
          [re-com/input-text
           :model (str (:valid-to-year item))
           :on-change #(re-frame/dispatch [:entity-change :bank-holiday (:id item) :valid-to-year (util/parse-int %)])
           :validation-regex #"^\d{0,4}$"
           :width "60px"]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :bank-holiday validation-fn])]
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
