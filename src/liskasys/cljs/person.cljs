(ns liskasys.cljs.person
  (:require [liskasys.cljc.time :as time]
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
            [reagent.core :as reagent]
            [reagent.ratom :as ratom]
            [secretary.core :as secretary]
            [clojure.string :as str]
            [cljs-time.core :as t]))

(re-frame/reg-sub
 ::kids
 :<- [:entities :person]
 (fn [persons _]
   (->> (vals persons)
        (filter :person/parent)
        (group-by :person/parent)
        (reduce (fn [out [parent-ids kids]]
                  (reduce (fn [out {parent-id :db/id}]
                            (update out parent-id #(util/sort-by-locale cljc.util/person-fullname (into (or % []) kids))))
                          out
                          parent-ids))
                {}))))

(re-frame/reg-sub
 ::rows
 :<- [:entities :person]
 :<- [:page-state :persons]
 (fn [[persons page-state] _]
   (cond->> (vals persons)
     (some? (:active? page-state))
     (filter #(= (:active? page-state) (boolean (:person/active? %))))
     (some? (:child? page-state))
     (filter #(= (:child? page-state) (boolean (:person/child? %)))))))

(defn empty-person [price-list]
  {:person/active? true
   :person/child? true
   :person/price-list (select-keys price-list [:db/id])})

(re-frame/reg-sub
 ::person-dps-by-date
 :<- [:entities :daily-plan]
 :<- [:entity-edit :person]
 (fn [[daily-plans person] _]
   (->> (vals daily-plans)
        (filter #(= (:db/id person) (get-in % [:daily-plan/person :db/id])))
        (map (juxt :daily-plan/date identity))
        (into {}))))

(re-frame/reg-sub
 ::person-att-months
 :<- [::person-dps-by-date]
 (fn [person-dps-by-date _]
   (->> (keys person-dps-by-date)
        (map #(-> % (time/to-format time/yyyyMM) (cljc.util/parse-int)))
        (set)
        (sort)
        (reverse))))

(defn table [rows]
  (let [lunch-types (re-frame/subscribe [:entities :lunch-type])
        groups (re-frame/subscribe [:entities :group])
        table-state (re-frame/subscribe [:table-state :persons])
        persons (re-frame/subscribe [:entities :person])
        price-lists (re-frame/subscribe [:entities :price-list])
        user (re-frame/subscribe [:auth-user])
        parent-attrs-fn (fn [row kw]
                          [:div
                           (doall
                            (for [p (-> row :person/parent)
                                  :let [parent (-> p :db/id (@persons))]]
                              ^{:key (:db/id parent)}
                              [:div.nowrap (kw parent)]))])]
    (fn [rows]
      [data-table
       :table-id :persons
       :rows rows
       :colls [[[re-com/h-box :gap "5px" :justify :end
                 :children
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-plus-square"
                   :tooltip "Přidat"
                   :on-click #(do (re-frame/dispatch [:entity-new :person (empty-person (last (vals @price-lists)))])
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
                      (when (contains? (:-roles @user) "superadmin")
                        [buttons/delete-button :on-confirm #(re-frame/dispatch [:entity-delete :person (:db/id row)]) :emphasise? true])]]))
                :none]
               ["Příjmení" :person/lastname]
               ["Jméno" :person/firstname]
               ["Šablona docházky" (comp cljc.util/att-pattern->text :person/att-pattern)]
               ["Šablona obědů" (comp cljc.util/lunch-pattern->text :person/lunch-pattern)]
               ["Variabilní symbol" #(str (:person/vs %))]
               ["Email rodičů" #(or (:person/email %)
                                    (parent-attrs-fn % :person/email))]
               ["Telefon rodičů" #(or (:person/phone %)
                                      (parent-attrs-fn % :person/phone))]
               ["Třída" #(:group/label (get @groups (some-> % :person/group :db/id)))]
               ["Dieta" #(:lunch-type/label (get @lunch-types (some-> % :person/lunch-type :db/id)))]
               ["Fond obědů" #(some-> % :person/lunch-fund cljc.util/from-cents)]
               ["Role" :person/roles]
               #_["Aktivní?" :person/active?]
               #_["Dítě?" :person/child?]]])))

(defn daily-summary [rows]
  (let [kids-by-day (reduce (fn [out day-idx]
                              (assoc out day-idx (->> rows
                                                      (remove #(= "0" (get (:person/att-pattern %) day-idx "0")))
                                                      (util/sort-by-locale cljc.util/person-fullname))))
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
                :label (cljc.util/person-fullname kid)]]))]))]]]))

(defn daily-summary-per-group [rows]
  (let [groups (re-frame/subscribe [:entities :group])]
    (fn [rows]
      [re-com/v-box :gap "5px" :children
       (->> rows
            (group-by (comp :db/id :person/group))
            (mapcat  (fn [[group-id kids]]
                       [[:h3 (->> group-id (get @groups) :group/label)]
                        [daily-summary kids]]))
            (vec))])))

(defn emails-to-copy [rows]
  [re-com/input-textarea
   :model  (->> rows
                (keep :person/email)
                (sort)
                (str/join ", "))
   :on-change #()
   :width "500px"
   :height "500px"])

(defn page-persons []
  (let [page-state (re-frame/subscribe [:page-state :persons])
        rows (re-frame/subscribe [::rows])]
    (when-not @page-state
      (re-frame/dispatch [:page-state-set :persons {:active? true
                                                    :child? true
                                                    :daily-summary? false
                                                    :emails? false}]))
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
            (when (and (:active? @page-state) (:child? @page-state))
              [re-com/horizontal-bar-tabs
               :tabs [{:id false :label "Seznam"}
                      {:id true :label "Denní souhrn"}]
               :model (:daily-summary? @page-state)
               :on-change #(re-frame/dispatch [:page-state-change :persons :daily-summary? %])])
            (when (false? (:child? @page-state))
              [re-com/horizontal-bar-tabs
               :tabs [{:id false :label "Seznam"}
                      {:id true :label "Emaily"}]
               :model (:emails? @page-state)
               :on-change #(re-frame/dispatch [:page-state-change :persons :emails? %])])]]
          (cond
            (and (:child? @page-state) (:active? @page-state) (:daily-summary? @page-state))
            [daily-summary-per-group @rows]
            (and (false? (:child? @page-state)) (:emails? @page-state))
            [emails-to-copy @rows]
            :else
            [table rows])]]))))

(defn page-person []
  (let [person (re-frame/subscribe [:entity-edit :person])
        lunch-types (re-frame/subscribe [:entities :lunch-type])
        groups (re-frame/subscribe [:entities :group])
        price-lists (re-frame/subscribe [:entities :price-list])
        persons (re-frame/subscribe [:entities :person])
        kids (re-frame/subscribe [::kids])
        user (re-frame/subscribe [:auth-user])
        show-personal-info? (re-frame/subscribe [:liskasys.cljs.common/path-value [::show-personal-info?]])
        show-att-history? (re-frame/subscribe [:liskasys.cljs.common/path-value [::show-att-history?]])
        person-att-months (re-frame/subscribe [::person-att-months])
        person-dps-by-date (re-frame/subscribe [::person-dps-by-date])]
    (fn []
      (if-not (and @persons @lunch-types @groups)
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
            [re-com/input-text
             :model (str (:person/vs item))
             :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/vs %])
             :validation-regex #"^(\d{0,10})$"]
            [re-com/label :label "Ceník"]
            [re-com/single-dropdown
             :model (some-> item :person/price-list :db/id)
             :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/price-list {:db/id %}])
             :choices (util/sort-by-locale :price-list/label (vals @price-lists))
             :id-fn :db/id
             :label-fn :price-list/label
             :placeholder "vyberte ceník"
             :width "250px"]
            [re-com/label :label "Dieta"]
            [re-com/single-dropdown
             :model (some-> item :person/lunch-type :db/id)
             :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/lunch-type {:db/id %}])
             :choices (conj (util/sort-by-locale :lunch-type/label (vals @lunch-types)) {:db/id nil :lunch-type/label "běžná"})
             :id-fn :db/id
             :label-fn :lunch-type/label
             :placeholder "běžná"
             :width "250px"]
            [re-com/label :label "Šablona obědů pro generování plateb a denních plánů na další období"]
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
               :model (str (cljc.util/from-cents (:person/lunch-fund item)))
               :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/lunch-fund (cljc.util/to-cents %)])
               :validation-regex #"^\d{0,4}$"]
              "Kč"]]
            [re-com/checkbox
             :label "dítě?"
             :model (:person/child? item)
             :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/child? %])]
            (if (:person/child? item)
              [re-com/v-box
               :children
               [[re-com/label :label "Třída"]
                [re-com/single-dropdown
                 :model (some-> item :person/group :db/id)
                 :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/group {:db/id %}])
                 :choices (conj (util/sort-by-locale :group/label (vals @groups)) {:db/id nil :group/label "nezařazeno"})
                 :id-fn :db/id
                 :label-fn :group/label
                 :placeholder "nezařazeno"
                 :width "250px"]
                [re-com/label :label "Šablona docházky pro generování plateb a denních plánů na další období"]
                [re-com/h-box :gap "5px"
                 :children
                 [[re-com/input-text
                   :model (str (:person/att-pattern item))
                   :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/att-pattern %])
                   :validation-regex #"^[0-2]{0,5}$"]
                  "poútstčtpá: 0 = bez docházky, 1 = celodenní, 2 = půldenní"]]
                [re-com/label :label "Datum začátku docházky"]
                [re-com/input-text
                 :model (time/to-format (:person/start-date item) time/ddMMyyyy)
                 :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/start-date (time/from-dMyyyy %)])
                 :validation-regex #"^\d{0,2}$|^\d{0,2}\.\d{0,2}$|^\d{0,2}\.\d{0,2}\.\d{0,4}$"
                 :width "100px"]
                [re-com/hyperlink :on-click #(re-frame/dispatch [:liskasys.cljs.common/set-path-value [::show-personal-info?] not]) :label
                 [re-com/h-box :gap "5px" :align :center :children
                  [[re-com/md-icon-button
                    :md-icon-name (if @show-personal-info? "zmdi-zoom-out" "zmdi-zoom-in")
                    :tooltip (if @show-personal-info? "Skrýt" "Zobrazit")]
                   [:h4 "Osobní údaje"]]]]
                (when @show-personal-info?
                  [re-com/v-box
                   :children
                   [[re-com/label :label "Datum narození / RČ"]
                    [re-com/input-text
                     :model (str (:person/birth-no item))
                     :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/birth-no %])
                     :validation-regex #"^[0-9./]{0,11}$"]
                    [re-com/label :label "Adresa"]
                    [re-com/input-text
                     :model (str (:person/address item))
                     :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/address %])
                     :width "500px"]
                    [re-com/label :label "Zdravotní pojišťovna"]
                    [re-com/input-text
                     :model (str (:person/health-insurance-comp item))
                     :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/health-insurance-comp %])
                     :width "500px"]
                    [re-com/label :label "Zdravotní problémy, alergie, léky apod."]
                    [re-com/input-text
                     :model (str (:person/health-warnings item))
                     :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/health-warnings %])
                     :width "500px"]
                    [re-com/checkbox
                     :label "dítě je očkované?"
                     :model (:person/vaccination? item)
                     :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/vaccination? %])]
                    [re-com/checkbox
                     :label "souhlas se zveřejněním fotek dítěte pro účely propagace?"
                     :model (:person/photo-publishing? item)
                     :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/photo-publishing? %])]
                    [re-com/label :label "Poznámky"]
                    [re-com/input-textarea
                     :model (str (:person/notes item))
                     :rows 5
                     :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/notes (fn [x] %)])
                     :width "500px"]]])
                [re-com/hyperlink :on-click #(re-frame/dispatch [:liskasys.cljs.common/set-path-value [::show-att-history?] not]) :label
                 [re-com/h-box :gap "5px" :align :center :children
                  [[re-com/md-icon-button
                    :md-icon-name (if @show-att-history? "zmdi-zoom-out" "zmdi-zoom-in")
                    :tooltip (if @show-att-history? "Skrýt" "Zobrazit")]
                   [:h4 "Historie docházky"]]]]
                (when @show-att-history?
                  (let [days (->> (range 31)
                                  (map inc))]
                    [:table.table.tree-table.table-hover.table-striped
                     [:thead
                      (->> days
                           (map #(-> [:th %]))
                           (into [:tr [:th "Měsíc / dny"]]))]
                     (->> @person-att-months
                          (map (fn [yyyymm]
                                 (let [last-day (t/day (t/last-day-of-the-month (quot yyyymm 100) (rem yyyymm 100)))]
                                   (->> days
                                        (take-while #(<= % last-day))
                                        (map #(let [date (time/date-yyyymm-dd yyyymm %)
                                                    dp (get @person-dps-by-date date)]
                                                [:td {:class (util/dp-class dp)}
                                                 (cond
                                                   (nil? dp) "-"
                                                   (:daily-plan/absence? dp) "A"
                                                   (:daily-plan/att-cancelled? dp) "O"
                                                   :else "P")]))
                                        (into [:tr
                                               [:th (cljc.util/yyyymm->text yyyymm)]])))))
                          (into [:tbody]))]))
                (when (:db/id item)
                  [re-com/v-box
                   :children
                   [[:h4 "Rodiče"]
                    [:ul
                     (doall
                      (for [parent (->> (:person/parent item)
                                        (map (comp @persons :db/id))
                                        (util/sort-by-locale cljc.util/person-fullname))]
                        ^{:key (:db/id parent)}
                        [:li
                         [re-com/hyperlink-href
                          :href (str "#/person/" (:db/id parent) "e")
                          :label (cljc.util/person-fullname parent)]
                         [buttons/delete-button
                          :on-confirm #(re-frame/dispatch [:common/retract-ref-many :person {:db/id (:db/id item)
                                                                                             :person/parent (:db/id parent)}])
                          :position :below-center]]))]
                    [re-com/single-dropdown
                     :model nil
                     :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/parent (fn [v] (conj v {:db/id %}))])
                     :choices (->> (apply dissoc @persons (map :db/id (:person/parent item)))
                                   vals
                                   (filter :person/active?)
                                   (remove :person/child?)
                                   (util/sort-by-locale cljc.util/person-fullname))
                     :id-fn :db/id
                     :label-fn cljc.util/person-fullname
                     :placeholder "Přidat rodiče..."
                     :filter-box? true
                     :width "250px"]]])]]
              [re-com/v-box
               :children
               [[re-com/label :label "Email"]
                [input-text item :person :person/email]
                [re-com/label :label "Změna hesla"]
                [input-text item :person :person/passwd]
                [re-com/label :label "Telefon"]
                [input-text item :person :person/phone]
                [re-com/label :label "Role"]
                [re-com/h-box :align :center :gap "5px"
                 :children
                 [[input-text item :person :person/roles]
                  "možné role oddělené čárkou: admin, koordinátor, obědy, průvodce, inspektor (email s objednávkou obědů se posíla na emaily všech s rolí obědy; denní přehled se posílá všem s rolí průvodce; systém odesílá emaily z emailové adresy koordinátora)"]]
                [re-com/checkbox
                 :label "dětská porce oběda?"
                 :model (:person/child-portion? item)
                 :on-change #(re-frame/dispatch [:entity-change :person (:db/id item) :person/child-portion? %])]
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
                          :label (cljc.util/person-fullname kid)]]))]]])]])
            [re-com/h-box :align :center :gap "5px"
             :children
             [[re-com/button :label "Uložit" :class "btn-success"
               :on-click #(do
                            (when (nil? (:person/active? item))
                              ;; should not happen, but happens...
                              ;; entity is not recognized as person when :person/active? attribute is not set
                              (re-frame/dispatch [:entity-change :person (:db/id item) :person/active? true]))
                            (re-frame/dispatch [:entity-save :person]))]
              "nebo"
              (when (:db/id item)
                [re-com/hyperlink-href :href (str "#/person/e")
                 :label [re-com/button :label "Nový"
                         :on-click #(re-frame/dispatch [:entity-new :person (empty-person (first (vals @price-lists)))])]])
              [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/persons")]]]
            [history/view (:db/id item)]]])))))

(secretary/defroute "/persons" []
  (re-frame/dispatch [:set-current-page :persons]))
(pages/add-page :persons #'page-persons)

(secretary/defroute #"/person/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :person (cljc.util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :person]))
(pages/add-page :person #'page-person)
(common/add-kw-url :person "person")
