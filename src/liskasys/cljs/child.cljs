(ns liskasys.cljs.child
  (:require [clj-brnolib.cljs.comp.buttons :as buttons]
            [clj-brnolib.cljs.comp.data-table :refer [data-table]]
            [clj-brnolib.cljs.comp.input-text :refer [input-text]]
            [clj-brnolib.cljs.util :as util]
            [clj-brnolib.time :as time]
            [clj-brnolib.validation :as validation]
            [cljs-time.core :as cljs-time]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as str]
            [liskasys.cljs.ajax :refer [server-call]]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]))

(re-frame/register-handler
 ::save
 common/debug-mw
 (fn [db [_ reply]]
   (timbre/debug "test reply" reply)
   db))

(re-frame/register-handler
 ::saved
 common/debug-mw
 (fn [db [_ reply]]
   (timbre/debug "saved" reply)
   db))

(defn page-children []
  (let [children (re-frame/subscribe [:entities :child])
        lunch-types (re-frame/subscribe [:entities :lunch-type])]
    (fn []
      (if-not (and @children @lunch-types)
        [re-com/throbber]
        [re-com/v-box
         :children
         [[:h3 "Děti"]
          [re-com/hyperlink-href :label [re-com/button :label "Nové"] :href (str "#/child/e")]
          [data-table
           :table-id :children
           :rows @children
           :colls [["Příjmení" :lastname]
                   ["Jméno" :firstname]
                   ["Variabilní symbol" :var-symbol]
                   ["Dieta" #(:label (get @lunch-types (:lunch-type-id %)))]
                   [[re-com/md-icon-button
                     :md-icon-name "zmdi-refresh"
                     :tooltip "Přenačíst ze serveru"
                     :on-click #(re-frame/dispatch [:entities-load :child])]
                    (fn [row]
                      [re-com/h-box
                       :gap "5px"
                       :children
                       [[re-com/hyperlink-href
                         :href (str "#/child/" (:id row) "e")
                         :label [re-com/md-icon-button
                                 :md-icon-name "zmdi-edit"
                                 :tooltip "Editovat"]]
                        [buttons/delete-button #(re-frame/dispatch [:entity-delete :child (:id row)])]]])
                    :none]]]]]))))

(defn page-child []
  (let [child (re-frame/subscribe [:entity-edit :child])
        user-childs (re-frame/subscribe [:entities :user-child])
        users (re-frame/subscribe [:entities :user])
        attendances (re-frame/subscribe [:entities :attendance])
        lunch-types (re-frame/subscribe [:entities :lunch-type])
        validation-fn #(cond-> {}
                         (str/blank? (:firstname %))
                         (assoc :firstname "Vyplňte správně jméno")
                         (str/blank? (:lastname %))
                         (assoc :lastname "Vyplňte správně příjmení")
                         (not (:var-symbol %))
                         (assoc :var-symbol "Vyplňte správně celočíselný variabilní symbol")
                         true
                         timbre/spy)
        att-validation-fn #(cond-> {}
                             (not (:valid-from %))
                             (assoc :valid-from "Zvolte začátek platnosti docházky"))]
    (fn []
      (if-not (and @users @user-childs @attendances @lunch-types)
        [re-com/throbber]
        (let [item @child
              errors (:-errors item)
              child-users (->> @user-childs
                               vals
                               (keep #(when (= (:id item) (:child-id %))
                                        (assoc % :-fullname (:-fullname (get @users (:user-id %))))))
                               (util/sort-by-locale :-fullname))
              sorted-users (->> (apply dissoc @users (map :user-id child-users))
                                vals
                                (util/sort-by-locale :-fullname))
              child-attendances (->> @attendances
                                     vals
                                     (filter #(= (:id item) (:child-id %)))
                                     (sort-by :valid-from))]
          [re-com/v-box :gap "5px"
           :children
           [[:h3 "Dítě"]
            [:table
             [:tbody
              [:tr
               [:td [re-com/label :label "Příjmení"]]
               [:td [re-com/label :label "Jméno"]]]
              [:tr
               [:td [input-text item :child :lastname]]
               [:td [input-text item :child :firstname]]]
              [:tr
               [:td [re-com/label :label "Variabilní symbol"]]
               [:td [re-com/label :label "Dieta"]]]
              [:tr
               [:td [input-text item :child :var-symbol util/parse-int]]
               [:td [re-com/single-dropdown
                     :model (:lunch-type-id item)
                     :on-change #(re-frame/dispatch [:entity-change :child (:id item) :lunch-type-id %])
                     :choices (conj (util/sort-by-locale :label (vals @lunch-types)) {:id nil :label "běžná"})
                     :placeholder "běžná"
                     :width "250px"]]]]]
            [re-com/h-box :align :center :gap "5px"
             :children
             [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :child validation-fn])]
              "nebo"
              [re-com/hyperlink-href :label [re-com/button :label "Nové"] :href (str "#/child/e")]
              [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/children")]]]
            (when (:id item)
              [re-com/v-box
               :children
               [[:h3 "Rodiče"]
                [:table
                 [:tbody
                  [:tr
                   (doall
                    (for [child-user child-users]
                      ^{:key (:id child-user)}
                      [:td
                       [re-com/label :label (:-fullname child-user)]
                       [buttons/delete-button #(re-frame/dispatch [:entity-delete :user-child (:id child-user)]) :below-center]]))]
                  [:tr
                   [:td
                    [re-com/single-dropdown
                     :model nil
                     :on-change #(server-call [:user-child/save {:user-id % :child-id (:id item)}]
                                              nil
                                              [:entity-saved :user-child])
                     :choices sorted-users
                     :label-fn :-fullname
                     :placeholder "Přidat rodiče..."
                     :filter-box? true
                     :width "250px"]]]]]
                [:h3 "Docházka"]
                [re-com/button :label "Nová"
                 :on-click #(re-frame/dispatch [:entity-new :attendance {:child-id (:id item)}])]
                [:table.table.tree-table.table-hover.table-striped
                 [:thead
                  [:tr
                   (doall
                    (for [[day-no day-label] (take 5 time/week-days)]
                      ^{:key day-no}
                      [:td day-label]))
                   [:td "Platná od - do"]]]
                 [:tbody
                  (doall
                   (for [att child-attendances]
                     ^{:key (str "a" (:id att))}
                     [:tr
                      (doall
                       (for [[day-no day-label] (take 5 time/week-days)]
                         ^{:key day-no}
                         [:td
                          [re-com/v-box
                           :children
                           [[re-com/radio-button
                             :model (get-in att [:days day-no :type])
                             :value nil
                             :label "-"
                             :on-change #(re-frame/dispatch [:entity-change :attendance (:id att) :days (fn [v] (assoc v day-no {:type nil
                                                                                                                                 :lunch? false}))])]
                            [re-com/radio-button
                             :model (get-in att [:days day-no :type])
                             :value 2
                             :label "půldenní"
                             :on-change #(re-frame/dispatch [:entity-change :attendance (:id att) :days (fn [v] (assoc-in v [day-no :type] 2))])]
                            [re-com/radio-button
                             :model (get-in att [:days day-no :type])
                             :value 1
                             :label "celodenní"
                             :on-change #(re-frame/dispatch [:entity-change :attendance (:id att) :days (fn [v] (assoc v day-no {:type 1
                                                                                                                                 :lunch? true}))])]
                            [re-com/checkbox
                             :label "oběd?"
                             :model (get-in att [:days day-no :lunch?])
                             :on-change #(re-frame/dispatch [:entity-change :attendance (:id att) :days (fn [v] (assoc-in v [day-no :lunch?] %))])]]]]))
                      [:td
                       [re-com/v-box :gap "5px"
                        :children
                        [[:div (when (get-in att [:-errors :valid-from]) {:style {:background-color "#f57c00"}})
                          [re-com/datepicker-dropdown
                                :model (time/from-date (:valid-from att))
                                :on-change #(re-frame/dispatch [:entity-change :attendance (:id att) :valid-from (time/to-date %)])
                                :format "dd.MM.yyyy"
                                :show-today? true
                           :selectable-fn (fn [date] (#{1 2 3 4 5} (cljs-time/day-of-week date)))]]
                         [:div
                          [re-com/datepicker-dropdown
                           :model (time/from-date (:valid-to att))
                           :on-change #(re-frame/dispatch [:entity-change :attendance (:id att) :valid-to (time/to-date %)])
                           :format "dd.MM.yyyy"
                           :show-today? true]]]]]
                      [:td
                       [re-com/button :label "Uložit" :class "btn-success"
                        :on-click #(re-frame/dispatch [:entity-save :attendance att-validation-fn (:id att)])]]]))]]]])]])))))

(secretary/defroute "/children" []
  (re-frame/dispatch [:set-current-page :children]))
(pages/add-page :children #'page-children)

(secretary/defroute #"/child/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :child (util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :child]))
(pages/add-page :child #'page-child)
(common/add-kw-url :child "child")
