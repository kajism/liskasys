(ns liskasys.cljs.person
  (:require [liskasys.cljc.util :as cljc-util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.comp.input-text :refer [input-text]]
            [liskasys.cljs.pages :as pages]
            [liskasys.cljs.util :as util]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [reagent.ratom :as ratom]
            [secretary.core :as secretary]))

(re-frame/register-sub
 ::kids
 (fn [db [_]]
   (let [persons (re-frame/subscribe [:entities :person])]
     (ratom/reaction
      (->> (vals @persons)
           (filter :person/parent)
           (group-by :person/parent)
           (reduce (fn [out [parent-ids kids]]
                     (reduce (fn [out {parent-id :db/id}]
                               (update out parent-id #(util/sort-by-locale cljc-util/person-fullname (into (or % []) kids))))
                             out
                             parent-ids))
                   {}))))))

(re-frame/register-sub
 ::rows
 (fn [db [_]]
   (let [persons (re-frame/subscribe [:entities :person])
         page-state (re-frame/subscribe [:page-state :persons])]
     (ratom/reaction
      (cond->> (vals @persons)
        (some? (:active? @page-state))
        (filter #(= (:active? @page-state) (boolean (:person/active? %))))
        (some? (:child? @page-state))
        (filter #(= (:child? @page-state) (boolean (:person/child? %)))))))))

(def empty-person {:person/active? true
                   :person/child? true})

(defn table [rows]
  (let [lunch-types (re-frame/subscribe [:entities :lunch-type])
        table-state (re-frame/subscribe [:table-state :persons])]
    (fn [rows]
      [data-table
       :table-id :persons
       :rows rows
       :colls [[[re-com/h-box :gap "5px" :justify :end
                 :children
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-plus-square"
                   :tooltip "Přidat"
                   :on-click #(do (re-frame/dispatch [:entity-new :person empty-person])
                                  (set! js/window.location.hash "#/person/e"))]
                  [re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :person])]]]
                (fn [row]
                  (when (= (:db/id row) (:selected-row-id @table-state))
                    [re-com/h-box :gap "5px" :justify :end
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/person/" (:db/id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      [buttons/delete-button #(re-frame/dispatch [:entity-delete :person (:db/id row)])]]]))
                :none]
               ["Příjmení" :person/lastname]
               ["Jméno" :person/firstname]
               ["Rozvrh docházky" (comp cljc-util/att-pattern->text :person/att-pattern)]
               ["Rozvrh obědů" (comp cljc-util/lunch-pattern->text :person/lunch-pattern)]
               ["Variabilní symbol" #(str (:person/var-symbol %))]
               ["Email" :person/email]
               ["Dieta" #(:lunch-type/label (get @lunch-types (some-> % :person/lunch-type :db/id)))]
               ["Fond obědů" #(some-> % :person/lunch-fund cljc-util/from-cents)]
               #_["Aktivní?" :person/active?]
               #_["Dítě?" :person/child?]
               #_["Mobilní telefon" :person/phone]]])))

(defn daily-summary [kids]
  (let [kids-by-day (reduce (fn [out day-idx]
                              (assoc out day-idx (->> @kids
                                                      (remove (fn [{att-pattern :person/att-pattern}]
                                                                (or (not att-pattern)
                                                                    (= "0" (nth att-pattern day-idx)))))
                                                      (util/sort-by-locale cljc-util/person-fullname))))
                            (sorted-map)
                            (range 5))]
    [:table.table.tree-table.table-hover.table-striped
     [:thead
      [:tr
       [:th "pondělí"]
       [:th "úterý"]
       [:th "středa"]
       [:th "čtvrtek"]
       [:th "pátek"]]]
     [:tbody
      [:tr
       (doall
        (for [[idx kids] kids-by-day]
          ^{:key idx}
          [:td (count kids)]))]
      [:tr
       (doall
        (for [[idx kids]  kids-by-day]
          ^{:key idx}
          [:td
           (doall
            (for [kid kids]
              ^{:key (:db/id kid)}
              [:div
               [re-com/hyperlink-href
                :href (str "#/person/" (:db/id kid) "e")
                :label (cljc-util/person-fullname kid)]]))]))]]]))

(defn page-persons []
  (let [page-state (re-frame/subscribe [:page-state :persons])
        rows (re-frame/subscribe [::rows])]
    (when-not @page-state
      (re-frame/dispatch [:page-state-set :persons {:active? true
                                                    :child? true
                                                    :daily-summary? false}]))
    (fn []
      (if-not @page-state
        [re-com/throbber]
        [re-com/v-box
         :children
         [[re-com/h-box :gap "20px" :align :center
           :children
           [[:h3 "Lidé"]
            [re-com/horizontal-bar-tabs
             :tabs [{:id true :label "Děti"}
                    {:id false :label "Dospělí"}
                    {:id nil :label "Všichni"}]
             :model (:child? @page-state)
             :on-change #(re-frame/dispatch [:page-state-change :persons :child? %])]
            [re-com/horizontal-bar-tabs
             :tabs [{:id true :label "Aktivní"}
                    {:id false :label "Neaktivní"}
                    {:id nil :label "Všichni"}]
             :model (:active? @page-state)
             :on-change #(re-frame/dispatch [:page-state-change :persons :active? %])]
            (when (and (:child? @page-state) (:active? @page-state))
              [re-com/horizontal-bar-tabs
               :tabs [{:id false :label "Seznam"}
                      {:id true :label "Denní souhrn"}]
               :model (:daily-summary? @page-state)
               :on-change #(re-frame/dispatch [:page-state-change :persons :daily-summary? %])])]]
          (if (and (:child? @page-state) (:active? @page-state) (:daily-summary? @page-state))
            [daily-summary rows]
            [table rows])]]))))

(defn page-person []
  (let [person (re-frame/subscribe [:entity-edit :person])
        lunch-types (re-frame/subscribe [:entities :lunch-type])
        persons (re-frame/subscribe [:entities :person])
        kids (re-frame/subscribe [::kids])
        user (re-frame/subscribe [:auth-user])]
    (fn []
      (if-not (and @persons @lunch-types)
        [re-com/throbber]
        (let [item @person
              errors (:-errors item)]
          [re-com/v-box :gap "5px"
           :children
           [[:h3 "Osoba"]
            [re-com/label :label "Příjmení"]
            [input-text item :person :person/lastname]
            [re-com/label :label "Jméno"]
            [input-text item :person :person/firstname]
            [re-com/checkbox
             :label "aktivní člen?"
             :model (:person/active? item)
             :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/active? %])]
            [re-com/label :label "Variabilní symbol"]
            [input-text item :person :person/var-symbol cljc-util/parse-int]
            [re-com/label :label "Dieta"]
            [re-com/single-dropdown
             :model (some-> item :person/lunch-type :db/id)
             :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/lunch-type {:db/id %}])
             :choices (conj (util/sort-by-locale :label (vals @lunch-types)) {:db/id nil :lunch-type/label "běžná"})
             :id-fn :db/id
             :label-fn :lunch-type/label
             :placeholder "běžná"
             :width "250px"]
            [re-com/label :label "Rozvrh obědů"]
            [re-com/h-box :gap "5px"
             :children
             [[re-com/input-text
               :model (str (:person/lunch-pattern item))
               :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/lunch-pattern %])
               :validation-regex #"^\d{0,5}$"]
              "poútstčtpá: 0 = bez oběda, 1-9 = požadovaný počet obědů"]]
            [re-com/label :label "Fond obědů"]
            [re-com/h-box :gap "5px"
             :children
             [[re-com/input-text
               :model (str (cljc-util/from-cents (:person/lunch-fund item)))
               :on-change #() ;; #(re-frame/dispatch [:entity-change :person (:db/id item) :person/lunch-fund (cljc-util/to-cents %)])
               :validation-regex #"^\d{0,4}$"
               :disabled? (if (contains? (:-roles @user) "superadmin") false (:db/id item))]
              "Kč"]]
            [re-com/checkbox
             :label "dítě?"
             :model (:person/child? item)
             :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/child? %])]
            (if (:person/child? item)
              [re-com/v-box
               :children
               [[re-com/label :label "Rozvrh docházky"]
                [re-com/h-box :gap "5px"
                 :children
                 [[re-com/input-text
                   :model (str (:person/att-pattern item))
                   :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/att-pattern %])
                   :validation-regex #"^[0-2]{0,5}$"]
                  "poútstčtpá: 0 = bez docházky, 1 = celodenní, 2 = půldenní"]]
                (when (:db/id item)
                  [re-com/v-box
                   :children
                   [[:h3 "Rodiče"]
                    [:ul
                     (doall
                      (for [parent (->> (:person/parent item)
                                        (map (comp @persons :db/id))
                                        (util/sort-by-locale cljc-util/person-fullname))]
                        ^{:key (:db/id parent)}
                        [:li
                         [re-com/hyperlink-href
                          :href (str "#/person/" (:db/id parent) "e")
                          :label (cljc-util/person-fullname parent)]
                         [buttons/delete-button
                          #(re-frame/dispatch [:common/retract-ref-many :person {:db/id (:db/id item)
                                                                                 :person/parent (:db/id parent)}])
                          :below-center]]))]
                    [re-com/single-dropdown
                     :model nil
                     :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/parent (fn [v] (conj v {:db/id %}))])
                     :choices (->> (apply dissoc @persons (map :db/id (:person/parent item)))
                                   vals
                                   (filter :person/active?)
                                   (remove :person/child?)
                                   (util/sort-by-locale cljc-util/person-fullname))
                     :id-fn :db/id
                     :label-fn cljc-util/person-fullname
                     :placeholder "Přidat rodiče..."
                     :filter-box? true
                     :width "250px"]]])]]
              [re-com/v-box
               :children
               [[re-com/label :label "Email"]
                [input-text item :person :person/email]
                [re-com/label :label "Telefon"]
                [input-text item :person :person/phone]
                [re-com/label :label "Role"]
                [re-com/h-box :align :center :gap "5px"
                 :children
                 [[input-text item :person :person/roles]
                  "admin, obedy"]]
                (when (seq (get @kids (:db/id item)))
                  [re-com/v-box
                   :children
                   [[:h3 "Děti"]
                    [:ul
                     (doall
                      (for [kid (get @kids (:db/id item))]
                        ^{:key (:db/id kid)}
                        [:li
                         [re-com/hyperlink-href
                          :href (str "#/person/" (:db/id kid) "e")
                          :label (cljc-util/person-fullname kid)]
                         ]))]]])]])
            [re-com/h-box :align :center :gap "5px"
             :children
             [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :person])]
              "nebo"
              [re-com/hyperlink-href :href (str "#/person/e")
               :label [re-com/button :label "Nový"
                       :on-click #(re-frame/dispatch [:entity-new :person empty-person])]]
              [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/persons")]]]]])))))

(secretary/defroute "/persons" []
  (re-frame/dispatch [:set-current-page :persons]))
(pages/add-page :persons #'page-persons)

(secretary/defroute #"/person/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :person (cljc-util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :person]))
(pages/add-page :person #'page-person)
(common/add-kw-url :person "person")
