(ns liskasys.cljs.lunch-menu
  (:require [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.comp.input-text :refer [input-text]]
            [liskasys.cljs.util :as util]
            [liskasys.cljc.time :as time]
            [cljs-time.coerce :as tc]
            [cljs-time.core :as t]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as str]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]))

(defn page-lunch-menus []
  (let [lunch-menus (re-frame/subscribe [:entities :lunch-menu])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Jídelníček"]
        [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/lunch-menu/e")]
        [data-table
         :table-id :lunch-menus
         :rows @lunch-menus
         :colls [["Platný od" :lunch-menu/from]
                 ["Text" #(str (subs (:lunch-menu/text %) 0 100) " ...") ]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :lunch-menu])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/lunch-menu/" (:db/id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      [buttons/delete-button #(re-frame/dispatch [:entity-delete :lunch-menu (:db/id row)])]]])
                  :csv-export]]
         :desc? true]]])))

(defn page-lunch-menu []
  (let [lunch-menu (re-frame/subscribe [:entity-edit :lunch-menu])]
    (fn []
      (let [item @lunch-menu
            errors (:-errors item)]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Jídelníček"]
          [re-com/label :label "Platný od"]
          [re-com/input-text
           :model (time/to-format (:lunch-menu/from item) time/ddMMyyyy)
           :on-change #(re-frame/dispatch [:entity-change :lunch-menu (:db/id item) :lunch-menu/from (time/from-dMyyyy %)])
           :validation-regex #"^\d{0,2}$|^\d{0,2}\.\d{0,2}$|^\d{0,2}\.\d{0,2}\.\d{0,4}$"
           :width "100px"]
          [re-com/label :label "Text"]
          [re-com/input-textarea
           :model (str (:lunch-menu/text item))
           :on-change #(re-frame/dispatch [:entity-change :lunch-menu (:db/id item) :lunch-menu/text %])
           :rows 20
           :width "800"]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :lunch-menu])]
            "nebo"
            [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/lunch-menu/e")]
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/lunch-menus")]]]]]))))

(secretary/defroute "/lunch-menus" []
  (re-frame/dispatch [:set-current-page :lunch-menus]))
(pages/add-page :lunch-menus #'page-lunch-menus)

(secretary/defroute #"/lunch-menu/(\d*)(e?)" [id edit?]
  (when-not (util/parse-int id)
    (re-frame/dispatch [:entity-new :lunch-menu {:lunch-menu/from (->> (t/today)
                                                                       (iterate #(t/plus % (t/days 1)))
                                                                       (drop-while #(not= (t/day-of-week %) 1))
                                                                       first
                                                                       tc/to-date)}]))
  (re-frame/dispatch [:entity-set-edit :lunch-menu (util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :lunch-menu]))
(pages/add-page :lunch-menu #'page-lunch-menu)
(common/add-kw-url :lunch-menu "lunch-menu")
