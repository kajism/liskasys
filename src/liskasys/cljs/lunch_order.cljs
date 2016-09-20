(ns liskasys.cljs.lunch-order
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
            [clj-brnolib.cljs.comp.input-text :refer [input-text]]
            [clj-brnolib.time :as time]))

(defn page-lunch-orders []
  (let [lunch-orders (re-frame/subscribe [:entities :lunch-order])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Dieta"]
        [data-table
         :table-id :lunch-orders
         :rows @lunch-orders
         :colls [["Datum" :lunch-order/date]
                 ["Počet obědů" :lunch-order/total]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :lunch-order])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/lunch-order/" (:db/id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      [buttons/delete-button #(re-frame/dispatch [:entity-delete :lunch-order (:db/id row)])]]])
                  :csv-export]]
         :desc? true]]])))

(defn page-lunch-order []
  (let [lunch-order (re-frame/subscribe [:entity-edit :lunch-order])
        noop-fn #()]
    (fn []
      (let [item @lunch-order
            errors (:-errors item)]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Diety"]
          [re-com/label :label "Datum"]
          [re-com/input-text
           :model (time/to-format (:lunch-order/date item) time/ddMMyyyy)
           :disabled? true
           :on-change noop-fn]
          [re-com/label :label "Počet obědů"]
          [re-com/input-text
           :model (str (:lunch-order/total item))
           :disabled? true
           :on-change noop-fn]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/lunch-orders")]]]]]))))

(secretary/defroute "/lunch-orders" []
  (re-frame/dispatch [:set-current-page :lunch-orders]))
(pages/add-page :lunch-orders #'page-lunch-orders)

(secretary/defroute #"/lunch-order/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :lunch-order (util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :lunch-order]))
(pages/add-page :lunch-order #'page-lunch-order)
(common/add-kw-url :lunch-order "lunch-order")