(ns liskasys.cljs.branch
  (:require [clojure.string :as str]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.comp.history :as history]
            [liskasys.cljs.comp.input-text :refer [input-text]]
            [liskasys.cljs.pages :as pages]
            [liskasys.cljs.util :as util]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]))

(defn page-branches []
  (let [branches (re-frame/subscribe [:entities :branch])
        table-state (re-frame/subscribe [:table-state :branches])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Pobočky"]
        [data-table
         :table-id :branches
         :rows branches
         :colls [[[re-com/h-box :gap "5px" :justify :end
                   :children
                   [[re-com/md-icon-button
                     :md-icon-name "zmdi-plus-square"
                     :tooltip "Přidat"
                     :on-click #(set! js/window.location.hash "#/branch/e")]
                    [re-com/md-icon-button
                     :md-icon-name "zmdi-refresh"
                     :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :branch])]]]
                  (fn [row]
                    (when (= (:db/id row) (:selected-row-id @table-state))
                      [re-com/h-box :gap "5px" :justify :end
                       :children
                       [[re-com/hyperlink-href
                         :href (str "#/branch/" (:db/id row) "e")
                         :label [re-com/md-icon-button
                                 :md-icon-name "zmdi-edit"
                                 :tooltip "Editovat"]]
                        [buttons/delete-button :on-confirm #(re-frame/dispatch [:entity-delete :branch (:db/id row)])]]]))
                  :none]
                 ["Název" :branch/label]
                 ["Adresa" :branch/address]
                 ["Email dodavatele obědů" :branch/lunch-order-email-addr]]]]])))

(defn page-branch []
  (let [branch (re-frame/subscribe [:entity-edit :branch])
        branches (re-frame/subscribe [:entities :branch])]
    (fn []
      (let [item @branch
            errors (:-errors item)]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Pobočka"]
          [re-com/label :label "Název"]
          [input-text item :branch :branch/label]
          [re-com/label :label "Adresa"]
          [input-text item :branch :branch/address]
          [re-com/label :label "Email dodavatele obědů pro pobočku (sem se pošle objednávka pro pobočku)"]
          [input-text item :branch :branch/lunch-order-email-addr]
          [:br]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :branch])]
            "nebo"
            (when (:db/id item)
              [re-com/hyperlink-href :label [re-com/button :label "Nová"] :href (str "#/branch/e")])
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/branches")]]]
          [history/view (:db/id item)]]]))))

(secretary/defroute "/branches" []
  (re-frame/dispatch [:set-current-page :branches]))
(pages/add-page :branches #'page-branches)

(secretary/defroute #"/branch/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :branch (cljc.util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :branch]))
(pages/add-page :branch #'page-branch)
(common/add-kw-url :branch "branch")
