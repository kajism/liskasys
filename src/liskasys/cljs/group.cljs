(ns liskasys.cljs.group
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

(defn page-groups []
  (let [groups (re-frame/subscribe [:entities :group])
        branches (re-frame/subscribe [:entities :branch])
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
                        [buttons/delete-button :on-confirm #(re-frame/dispatch [:entity-delete :group (:db/id row)])]]]))
                  :none]
                 ["Název" :group/label]
                 ["Pobočka" #(:branch/label (get @branches (some-> % :group/branch :db/id)))]
                 ["Kapacita" :group/max-capacity]
                 ["Povinný důvod omluvenky?" :group/mandatory-excuse?]]]]])))

(defn page-group []
  (let [group (re-frame/subscribe [:entity-edit :group])
        groups (re-frame/subscribe [:entities :group])
        branches (re-frame/subscribe [:entities :branch])
        validation-fn #(cond-> {}
                         (str/blank? (:group/label %))
                         (assoc :group/label "Vyplňte název")
                         (str/blank? (:group/max-capacity %))
                         (assoc :group/max-capacity "Vyplňte maximální počet dětí ve třídě")
                         )]
    (fn []
      (let [item @group
            errors (:-errors item)]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Třídy"]
          [re-com/label :label "Název"]
          [input-text item :group :group/label]
          [re-com/label :label "Pobočka"]
          [re-com/single-dropdown
           :model (get-in item [:group/branch :db/id])
           :on-change #(re-frame/dispatch [:entity-change :group (:db/id item) :group/branch {:db/id %}])
           :choices (->> @branches
                         (vals)
                         (util/sort-by-locale :branch/label))
           :id-fn :db/id
           :label-fn :branch/label
           :filter-box? true
           :width "250px"]
          [re-com/label :label "Kapacita"]
          (let [error-msg (some-> item :-errors :group/max-capacity)]
            [re-com/input-text
             :model (str (:group/max-capacity item))
             :on-change #(re-frame/dispatch [:entity-change :group (:db/id item) :group/max-capacity (cljc.util/parse-int %)])
             :validation-regex #"^\d{0,2}$"
             :width "70px"
             :status (when error-msg :warning)
             :status-icon? true
             :status-tooltip error-msg])
          [re-com/checkbox
           :label "povinný důvod omluvenky?"
           :model (:group/mandatory-excuse? item)
           :on-change #(re-frame/dispatch [:entity-change :group (:db/id item) :group/mandatory-excuse? %])]
          [re-com/label :label "Fungování skupiny ve dnech"]
          [re-com/h-box :gap "5px"
           :children
           [[re-com/input-text
             :model (str (:group/pattern item))
             :on-change #(re-frame/dispatch [:entity-change :group (:db/id item) :group/pattern %])
             :validation-regex #"^[0-2]{0,5}$"]
            "poútstčtpá: 1 = skupina funguje, 0 = skupina v tomto dni není"]]
          [re-com/label :label "Zástupná skupina ve dnech nefungování skupiny"]
          [re-com/single-dropdown
           :model (get-in item [:group/subst-group :db/id])
           :on-change #(re-frame/dispatch [:entity-change :group (:db/id item) :group/subst-group {:db/id %}])
           :choices (->> @groups
                         (vals)
                         (remove #(= (:db/id %) (:db/id item)))
                         (util/sort-by-locale :group/label))
           :id-fn :db/id
           :label-fn :group/label
           :filter-box? true
           :width "250px"]
          [:br]
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
  (re-frame/dispatch [:entity-set-edit :group (cljc.util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :group]))
(pages/add-page :group #'page-group)
(common/add-kw-url :group "group")
