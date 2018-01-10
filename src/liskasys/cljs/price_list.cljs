(ns liskasys.cljs.price-list
  (:require [clojure.string :as str]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.comp.history :as history]
            [liskasys.cljs.pages :as pages]
            [liskasys.cljs.util :as util]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]))

(defn page-price-lists []
  (let [price-lists (re-frame/subscribe [:entities :price-list])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Ceník"]
        (when-not (seq @price-lists)
          [re-com/hyperlink-href :label [re-com/button :label "Vytvořit"] :href (str "#/price-list/e")])
        [data-table
         :table-id :price-lists
         :rows price-lists
         :colls [[[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :price-list])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/price-list/" (:db/id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      #_[buttons/delete-button #(re-frame/dispatch [:entity-delete :price-list (:db/id row)])]]])
                  :none]
                 ["Číslo účtu" :price-list/bank-account]
                 ["5 dní" (comp cljc-util/from-cents :price-list/days-5)]
                 ["4 dny" (comp cljc-util/from-cents :price-list/days-4)]
                 ["3 dny" (comp cljc-util/from-cents :price-list/days-3)]
                 ["2 dny" (comp cljc-util/from-cents :price-list/days-2)]
                 ["1 den" (comp cljc-util/from-cents :price-list/days-1)]
                 ["Půlden" (comp cljc-util/from-cents :price-list/half-day)]
                 ["Oběd dětský" (comp cljc-util/from-cents :price-list/lunch)]
                 ["Oběd dospělý" (comp cljc-util/from-cents :price-list/lunch-adult)]]]]])))

(defn- from-cents [cents]
  (str (cljc-util/from-cents cents)))

(defn page-price-list []
  (let [price-list (re-frame/subscribe [:entity-edit :price-list])]
    (fn []
      (let [item @price-list]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Ceník"]
          [re-com/label :label "Číslo účtu"]
          [re-com/input-text
           :model (str (:price-list/bank-account item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/bank-account %])
           :width "200px"]
          [re-com/label :label "5 dní"]
          [re-com/input-text
           :model (from-cents (:price-list/days-5 item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/days-5 (cljc-util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "4 dny"]
          [re-com/input-text
           :model (from-cents (:price-list/days-4 item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/days-4 (cljc-util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "3 dny"]
          [re-com/input-text
           :model (from-cents (:price-list/days-3 item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/days-3 (cljc-util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "2 dny"]
          [re-com/input-text
           :model (from-cents (:price-list/days-2 item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/days-2 (cljc-util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "1 den"]
          [re-com/input-text
           :model (from-cents (:price-list/days-1 item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/days-1 (cljc-util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "Půldenní"]
          [re-com/input-text
           :model (from-cents (:price-list/half-day item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/half-day (cljc-util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "Oběd dětský"]
          [re-com/input-text
           :model (from-cents (:price-list/lunch item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/lunch (cljc-util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "Oběd dospělý"]
          [re-com/input-text
           :model (from-cents (:price-list/lunch-adult item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/lunch-adult (cljc-util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :price-list])]
            "nebo"
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/price-lists")]]]
          [history/view (:db/id item)]]]))))

(secretary/defroute "/price-lists" []
  (re-frame/dispatch [:set-current-page :price-lists]))
(pages/add-page :price-lists #'page-price-lists)

(secretary/defroute #"/price-list/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :price-list (cljc-util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :price-list]))
(pages/add-page :price-list #'page-price-list)
(common/add-kw-url :price-list "price-list")
