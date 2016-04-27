(ns liskasys.cljs.cancellation
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

(defn page-cancellations []
  (let [cancellations (re-frame/subscribe [:entities :cancellation])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Omluvenky"]
        [re-com/hyperlink-href :label [re-com/button :label "Nová"] :href (str "#/cancellation/e")]
        [data-table
         :table-id :cancellations
         :rows @cancellations
         :colls [["Datum" :date]
                 ["Dítě" :-child-fullname]
                 ["Včetně oběda?" :lunch-cancelled?]
                 ["Kdy a kdo" #(str (time/to-format (:created %) time/ddMMyyyyHHmm) ", " (:-user-fullname %))]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :cancellation])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/cancellation/" (:id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      [buttons/delete-button #(re-frame/dispatch [:entity-delete :cancellation (:id row)])]]])
                  :none]]]]])))

(defn page-cancellation []
  (let [cancellation (re-frame/subscribe [:entity-edit :cancellation])
        children (re-frame/subscribe [:entities :child])
        validation-fn #(cond-> {}
                         (not (:date %))
                         (assoc :date "Vyberte datum")
                         (not (:child-id %))
                         (assoc :child-id "Vyberte dítě")
                         true
                         timbre/spy)]
    (fn []
      (if-not @children
        [re-com/throbber]
        (let [item @cancellation
              errors (:-errors item)
              sorted-children (->> @children
                                vals
                                (util/sort-by-locale :-fullname))]
          [re-com/v-box :gap "5px"
           :children
           [[:h3 "Omluvenka"]
            [:table
             [:tbody
              [:tr
               [:td [re-com/label :label "Dítě"]]
               [:td [re-com/label :label "Datum"]]]
              [:tr
               [:td [re-com/single-dropdown
                     :model (:child-id item)
                     :on-change #(re-frame/dispatch [:entity-change :cancellation (:id item) :child-id %])
                     :choices sorted-children
                     :label-fn :-fullname
                     :placeholder "Vyberte dítě"
                     :filter-box? true
                     :width "250px"]]
               [:td [re-com/datepicker-dropdown
                     :model (time/from-date (:date item))
                     :on-change #(re-frame/dispatch [:entity-change :cancellation (:id item) :date (time/to-date %)])
                     :format "dd.MM.yyyy"
                     :show-today? true]]]]]
            [re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :cancellation validation-fn])]
            [:pre (with-out-str (pprint item))]]])))))

(secretary/defroute "/cancellations" []
  (re-frame/dispatch [:set-current-page :cancellations]))
(pages/add-page :cancellations #'page-cancellations)

(secretary/defroute #"/cancellation/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :cancellation (util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :cancellation]))
(pages/add-page :cancellation #'page-cancellation)
(common/add-kw-url :cancellation "cancellation")
