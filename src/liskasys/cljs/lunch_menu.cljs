(ns liskasys.cljs.lunch-menu
  (:require [cljs-time.coerce :as tc]
            [cljs-time.core :as t]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]
            [liskasys.cljs.comp.history :as history]))

(defn page-lunch-menus []
  (let [lunch-menus (re-frame/subscribe [:entities :lunch-menu])
        table-state (re-frame/subscribe [:table-state :lunch-menus])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Týdenní jídelníčky"]
        [data-table
         :table-id :lunch-menus
         :rows lunch-menus
         :colls [[[re-com/h-box :gap "5px" :justify :end
                   :children
                   [[re-com/md-icon-button
                     :md-icon-name "zmdi-plus-square"
                     :tooltip "Přidat"
                     :on-click #(set! js/window.location.hash "#/lunch-menu/e")]
                    [re-com/md-icon-button
                     :md-icon-name "zmdi-refresh"
                     :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :lunch-menu])]]]
                  (fn [row]
                    (when (= (:db/id row) (:selected-row-id @table-state))
                      [re-com/h-box :gap "5px" :justify :end
                       :children
                       [[re-com/hyperlink-href
                         :href (str "#/lunch-menu/" (:db/id row) "e")
                         :label [re-com/md-icon-button
                                 :md-icon-name "zmdi-edit"
                                 :tooltip "Editovat"]]
                        [buttons/delete-button :on-confirm #(re-frame/dispatch [:entity-delete :lunch-menu (:db/id row)])]]]))
                  :none]
                 ["Platný od" :lunch-menu/from]
                 ["Text" #(str (subs (:lunch-menu/text %) 0 100) " ...") ]]
         :desc? true]]])))

(defn page-lunch-menu []
  (let [lunch-menu (re-frame/subscribe [:entity-edit :lunch-menu])]
    (fn []
      (let [item @lunch-menu
            errors (:-errors item)]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Týdenní jídelníček"]
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
            (when (:db/id item)
              [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/lunch-menu/e")])
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/lunch-menus")]]]
          [history/view (:db/id item)]]]))))

(secretary/defroute "/lunch-menus" []
  (re-frame/dispatch [:set-current-page :lunch-menus]))
(pages/add-page :lunch-menus #'page-lunch-menus)

(secretary/defroute #"/lunch-menu/(\d*)(e?)" [id edit?]
  (when-not (cljc.util/parse-int id)
    (re-frame/dispatch [:entity-new :lunch-menu {:lunch-menu/from (->> (t/today)
                                                                       (iterate #(t/plus % (t/days 1)))
                                                                       (drop-while #(not= (t/day-of-week %) 1))
                                                                       first
                                                                       tc/to-date)}]))
  (re-frame/dispatch [:entity-set-edit :lunch-menu (cljc.util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :lunch-menu]))
(pages/add-page :lunch-menu #'page-lunch-menu)
(common/add-kw-url :lunch-menu "lunch-menu")
