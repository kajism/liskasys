(ns liskasys.cljs.group
  (:require [clojure.string :as str]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.comp.history :as history]
            [liskasys.cljs.comp.input-text :refer [input-text]]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]))

(defn page-groups []
  (let [groups (re-frame/subscribe [:entities :group])
        table-state (re-frame/subscribe [:table-state :groups])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Třída"]
        [data-table
         :table-id :groups
         :rows groups
         :colls [[[re-com/h-box :gap "5px" :justify :end
                   :children
                   [[re-com/md-icon-button
                     :md-icon-name "zmdi-plus-square"
                     :tooltip "Přidat"
                     :on-click #(set! js/window.location.hash "#/group/e")]
                    [re-com/md-icon-button
                     :md-icon-name "zmdi-refresh"
                     :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :group])]]]
                  (fn [row]
                    (when (= (:db/id row) (:selected-row-id @table-state))
                      [re-com/h-box :gap "5px" :justify :end
                       :children
                       [[re-com/hyperlink-href
                         :href (str "#/group/" (:db/id row) "e")
                         :label [re-com/md-icon-button
                                 :md-icon-name "zmdi-edit"
                                 :tooltip "Editovat"]]
                        [buttons/delete-button #(re-frame/dispatch [:entity-delete :group (:db/id row)])]]]))
                  :none]
                 ["Název" :group/label]
                 #_["Barva" :group/color]]]]])))

(defn page-group []
  (let [group (re-frame/subscribe [:entity-edit :group])
        validation-fn #(cond-> {}
                         (str/blank? (:group/label %))
                         (assoc :group/label "Vyplňte název")
                         ;;(str/blank? (:group/color %))
                         ;;(assoc :group/color "Vyplňte barvu")
                         )]
    (fn []
      (let [item @group
            errors (:-errors item)]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Třídy"]
          [re-com/label :label "Název"]
          [input-text item :group :group/label]
          #_[re-com/label :label "Barva"]
          #_[input-text item :group :group/color]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :group validation-fn])]
            "nebo"
            (when (:db/id item)
              [re-com/hyperlink-href :label [re-com/button :label "Nová"] :href (str "#/group/e")])
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/groups")]]]
          [history/view (:db/id item)]]]))))

(secretary/defroute "/groups" []
  (re-frame/dispatch [:set-current-page :groups]))
(pages/add-page :groups #'page-groups)

(secretary/defroute #"/group/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :group (cljc-util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :group]))
(pages/add-page :group #'page-group)
(common/add-kw-url :group "group")