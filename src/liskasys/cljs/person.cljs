(ns liskasys.cljs.person
  (:require [clj-brnolib.cljs.comp.buttons :as buttons]
            [clj-brnolib.cljs.comp.data-table :refer [data-table]]
            [clj-brnolib.cljs.util :as util]
            [clj-brnolib.validation :as validation]
            [cljs.pprint :refer [pprint]]
            [liskasys.cljs.ajax :refer [server-call]]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]
            [reagent.core :as reagent]
            [clojure.string :as str]
            [clj-brnolib.cljs.comp.input-text :refer [input-text]]))

(re-frame/register-handler
 :user-child/save
 common/debug-mw
 (fn [db [_ user-id child-id]]
   (server-call [:user-child/save {:user-id user-id
                                   :child-id child-id}]
                nil
                [:entity-saved :user-child])))

(defn page-persons []
  (let [persons (re-frame/subscribe [:entities :person])
        lunch-types (re-frame/subscribe [:entities :lunch-type])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Osoby"]
        [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/person/e")]
        [data-table
         :table-id :persons
         :rows @persons
         :colls [["Příjmení" :lastname]
                 ["Jméno" :firstname]
                 ["Variabilní symbol" :var-symbol]
                 ["Dieta" #(:label (get @lunch-types (:lunch-type-id %)))]
                 ["Rozvrh docházky" #(when (not= (:att-pattern %) "0000000") (:att-pattern %))]
                 ["Rozvrh obědů" #(when (not= (:lunch-pattern %) "0000000") (:lunch-pattern %))]
                 ["Email" :email]
                 ["Mobilní telefon" :phone]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :person])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/person/" (:id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      [buttons/delete-button #(re-frame/dispatch [:entity-delete :person (:id row)])]]])
                  :none]]]]])))

(defn page-person []
  (let [person (re-frame/subscribe [:entity-edit :person])
        lunch-types (re-frame/subscribe [:entities :lunch-type])
        parent-childs (re-frame/subscribe [:entities :parent-child])
        persons (re-frame/subscribe [:entities :person])]
    (fn []
      (if-not (and @persons @parent-childs @lunch-types)
        [re-com/throbber]
        (let [item @person
              errors (:-errors item)
              child-parents (->> @parent-childs
                               vals
                               (keep #(when (= (:id item) (:child-id %))
                                        (assoc % :-fullname (:-fullname (get @persons (:parent-id %))))))
                               (util/sort-by-locale :-fullname))
              sorted-users (->> (apply dissoc @persons (map :parent-id child-parents))
                                vals
                                (util/sort-by-locale :-fullname))]
          [re-com/v-box :gap "5px"
           :children
           [[:h3 "Osoba"]
            [re-com/label :label "Příjmení"]
            [input-text item :person :lastname]
            [re-com/label :label "Jméno"]
            [input-text item :person :firstname]
            [re-com/checkbox
             :label "aktivní člen?"
             :model (:active? item)
             :on-change #(re-frame/dispatch [:entity-change :person (:id item) :active? %])]
            [re-com/label :label "Variabilní symbol"]
            [input-text item :child :var-symbol util/parse-int]
            [re-com/label :label "Dieta"]
            [re-com/single-dropdown
             :model (:lunch-type-id item)
             :on-change #(re-frame/dispatch [:entity-change :child (:id item) :lunch-type-id %])
             :choices (conj (util/sort-by-locale :label (vals @lunch-types)) {:id nil :label "běžná"})
             :placeholder "běžná"
             :width "250px"]
            [re-com/label :label "Rozvrh obědů"]
            [re-com/h-box :gap "5px"
             :children
             [[input-text item :person :lunch-pattern]
              [re-com/checkbox
               :label "obědy zdarma?"
               :model (:free-lunches? item)
               :on-change #(re-frame/dispatch [:entity-change :person (:id item) :free-lunches? %])]]]
            [re-com/checkbox
             :label "dítě?"
             :model (:child? item)
             :on-change #(re-frame/dispatch [:entity-change :person (:id item) :child? %])]
            (if (:child? item)
              [re-com/v-box
               :children
               [[re-com/label :label "Rozvrh docházky"]
                [re-com/h-box :gap "5px"
                 :children
                 [[input-text item :person :att-pattern]
                  [re-com/checkbox
                   :label "docházka zdarma?"
                   :model (:free-att? item)
                   :on-change #(re-frame/dispatch [:entity-change :person (:id item) :free-att? %])]]]
                (when (:id item)
                  [re-com/v-box
                   :children
                   [[:h3 "Rodiče"]
                    [:table
                     [:tbody
                      [:tr
                       (doall
                        (for [parent child-parents]
                          ^{:key (:id parent)}
                          [:td
                           [re-com/label :label (:-fullname parent)]
                           [buttons/delete-button #(re-frame/dispatch [:entity-delete :user-child (:id parent)]) :below-center]]))]
                      [:tr
                       [:td
                        [re-com/single-dropdown
                         :model nil
                         :on-change #(re-frame/dispatch [:user-child/save % (:id item)])
                         :choices sorted-users
                         :label-fn :-fullname
                         :placeholder "Přidat rodiče..."
                         :filter-box? true
                         :width "250px"]]]]]]])]]
              [re-com/v-box
               :children
               [[re-com/label :label "Email"]
                [input-text item :person :email]
                [re-com/label :label "Telefon"]
                [input-text item :person :phone]
                [re-com/label :label "Role"]
                [re-com/h-box :align :center :gap "5px"
                 :children
                 [[input-text item :person :roles]
                  "admin, obedy"]]]])
            [re-com/h-box :align :center :gap "5px"
             :children
             [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :person])]
              "nebo"
              [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/person/e")]
              [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/persons")]]]]])))))

(secretary/defroute "/persons" []
  (re-frame/dispatch [:set-current-page :persons]))
(pages/add-page :persons #'page-persons)

(secretary/defroute #"/person/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :person (util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :person]))
(pages/add-page :person #'page-person)
(common/add-kw-url :person "person")


