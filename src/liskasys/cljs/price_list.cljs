(ns liskasys.cljs.price-list
  (:require [clj-brnolib.cljs.comp.buttons :as buttons]
            [clj-brnolib.cljs.comp.data-table :refer [data-table]]
            [clj-brnolib.cljs.util :as util]
            [clj-brnolib.time :as time]
            [clojure.string :as str]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]))

(re-frame/register-handler
 ::create-new-price-list
 common/debug-mw
 (fn [db [_]]
   (assoc-in db [:price-list nil] (-> (get-in db [:price-list (get-in db [:entity-edit :price-list :id])])
                                      (dissoc :id :valid-from)))))

(defn page-price-lists []
  (let [price-lists (re-frame/subscribe [:entities :price-list])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Ceníky"]
        (when-not (seq @price-lists)
          [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/price-list/e")])
        [data-table
         :table-id :price-lists
         :rows @price-lists
         :colls [["5 dní" (comp util/from-cents :days-5)]
                 ["4 dny" (comp util/from-cents :days-4)]
                 ["3 dny" (comp util/from-cents :days-3)]
                 ["2 dny" (comp util/from-cents :days-2)]
                 ["1 den" (comp util/from-cents :days-1)]
                 ["půlden" (comp util/from-cents :half-day)]
                 ["oběd" (comp util/from-cents :lunch)]
                 ["Platný od" :valid-from]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :price-list])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/price-list/" (:id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      [buttons/delete-button #(re-frame/dispatch [:entity-delete :price-list (:id row)])]]])
                  :csv-export]]]]])))

(defn- from-cents [cents]
  (str (util/from-cents cents)))

(defn page-price-list []
  (let [price-list (re-frame/subscribe [:entity-edit :price-list])]
    (fn []
      (let [item @price-list]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Ceník"]
          [re-com/label :label "Platný od"]
          [re-com/datepicker-dropdown
           :model (time/from-date (:valid-from item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:id item) :valid-from (time/to-date %)])
           :format "dd.MM.yyyy"
           :show-today? true]
          [re-com/label :label "5 dní"]
          [re-com/input-text
           :model (from-cents (:days-5 item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:id item) :days-5 (util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "4 dny"]
          [re-com/input-text
           :model (from-cents (:days-4 item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:id item) :days-4 (util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "3 dny"]
          [re-com/input-text
           :model (from-cents (:days-3 item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:id item) :days-3 (util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "2 dny"]
          [re-com/input-text
           :model (from-cents (:days-2 item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:id item) :days-2 (util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "1 den"]
          [re-com/input-text
           :model (from-cents (:days-1 item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:id item) :days-1 (util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "Půldenní"]
          [re-com/input-text
           :model (from-cents (:half-day item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:id item) :half-day (util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "Oběd"]
          [re-com/input-text
           :model (from-cents (:lunch item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:id item) :lunch (util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :price-list])]
            "nebo"
            [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/price-list/e")]
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/price-lists")]]]]]))))

(secretary/defroute "/price-lists" []
  (re-frame/dispatch [:set-current-page :price-lists]))
(pages/add-page :price-lists #'page-price-lists)

(secretary/defroute #"/price-list/(\d*)(e?)" [id edit?]
  (when-not (util/parse-int id)
    (re-frame/dispatch [::create-new-price-list]))
  (re-frame/dispatch [:entity-set-edit :price-list (util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :price-list]))
(pages/add-page :price-list #'page-price-list)
(common/add-kw-url :price-list "price-list")
