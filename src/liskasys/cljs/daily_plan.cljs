(ns liskasys.cljs.daily-plan
  (:require [cljs-time.coerce :as tc]
            [cljs-time.core :as t]
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
            [reagent.core :as reagent]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]))

(defn child-att->str [child-att]
  (case child-att
    1 "celodenní"
    2 "půldenní"
    "-"))

(defn page-daily-plans []
  (let [daily-plans (re-frame/subscribe [:entities :daily-plan])
        persons (re-frame/subscribe [:entities :person])
        lunch-types (re-frame/subscribe [:entities :lunch-type])
        table-state (re-frame/subscribe [:table-state :daily-plans])
        user (re-frame/subscribe [:auth-user])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Denní plány"]
        [data-table
         :table-id :daily-plans
         :rows daily-plans
         :colls [[[re-com/h-box :gap "5px" :justify :end
                   :children
                   [[re-com/md-icon-button
                     :md-icon-name "zmdi-plus-square"
                     :tooltip "Přidat"
                     :on-click #(do (re-frame/dispatch [:entity-new :daily-plan {}])
                                    (set! js/window.location.hash "#/daily-plan/e"))]
                    [re-com/md-icon-button
                     :md-icon-name "zmdi-refresh"
                     :tooltip "Přenačíst ze serveru"
                     :on-click #(re-frame/dispatch [:entities-load :daily-plan])]]]
                  (fn [row]
                     (when (and (= (:db/id row) (:selected-row-id @table-state)))
                              [re-com/h-box :gap "5px" :justify :end
                               :children
                               [(when (or (-> row :daily-plan/date tc/to-local-date (t/after? (t/today)))
                                          (contains? (:-roles @user) "superadmin"))
                                  [re-com/hyperlink-href
                                   :href (str "#/daily-plan/" (:db/id row) "e")
                                   :label [re-com/md-icon-button
                                           :md-icon-name "zmdi-edit"
                                           :tooltip "Editovat"]])
                                (when (contains? (:-roles @user) "superadmin")
                                  [buttons/delete-button #(re-frame/dispatch [:entity-delete :daily-plan (:db/id row)])])]]))
                  :none]
                 ["Datum" :daily-plan/date]
                 {:header "Jméno"
                  :val-fn #(some->> % :daily-plan/person :db/id (get @persons) cljc-util/person-fullname)
                  :td-comp (fn [& {:keys [value row row-state]}]
                             [:td
                              (if (:selected? row-state)
                                [re-com/hyperlink-href
                                 :href (str "#/person/" (get-in row [:daily-plan/person :db/id]) "e")
                                 :label value]
                                value)])}
                 ["Docházka" #(cljc-util/child-att->str (:daily-plan/child-att %))]
                 ["Omluvena?" (fn [row]
                                (if (->> row :daily-plan/person :db/id (get @persons) :person/child?)
                                  (-> row  :daily-plan/att-cancelled? cljc-util/boolean->text)
                                  "-"))]
                 ["Obědy: Požadováno ks" #(or (:daily-plan/lunch-req %) 0)]
                 ["Dieta" #(some->> % :daily-plan/person :db/id (get @persons) :person/lunch-type :db/id (get @lunch-types) :lunch-type/label)]
                 ["Odhlášen?" (comp cljc-util/boolean->text :daily-plan/lunch-cancelled?)]
                 ["Objednáno ks" #(or (:daily-plan/lunch-ord %) 0)]
                 {:header "Nahrada zapsána"
                  :val-fn :daily-plan/subst-req-on
                  :td-comp (fn [& {:keys [value]}]
                             [:td (time/to-format value time/ddMMyyyyHHmmss)])}
                 ["Nahrazováno" #(some-> % :daily-plan/substituted-by :db/id (@daily-plans) :daily-plan/date)]]
         :desc? true]]])))

(defn page-daily-plan []
  (let [daily-plan (re-frame/subscribe [:entity-edit :daily-plan])
        persons (re-frame/subscribe [:entities :person])
        daily-plans (re-frame/subscribe [:entities :daily-plan])
        user (re-frame/subscribe [:auth-user])]
    (fn []
      (let [item @daily-plan
            errors (:-errors item)]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Denní plán osoby"]
          (when (:daily-plan/subst-req-on item)
            [re-com/h-box :gap "5px"
             :children
             [[re-com/label :label "Náhrada zapsána "]
              [:label (time/to-format (:daily-plan/subst-req-on item) time/ddMMyyyyHHmmss)]
              [re-com/label :label " za "]
              (let [subst-id (-> item :daily-plan/_substituted-by first :db/id)]
                [:label [:a {:href (str "#/daily-plan/" subst-id)}
                         (some-> (get @daily-plans subst-id)
                                 :daily-plan/date
                                 (time/to-format time/ddMMyyyy))]])]])
          [re-com/label :label "Den"]
          [re-com/input-text
           :model (time/to-format (:daily-plan/date item) time/ddMMyyyy)
           :on-change #(re-frame/dispatch [:entity-change :daily-plan (:db/id item) :daily-plan/date (time/from-dMyyyy %)])
           :validation-regex #"^\d{0,2}$|^\d{0,2}\.\d{0,2}$|^\d{0,2}\.\d{0,2}\.\d{0,4}$"
           :width "100px"]
          [re-com/label :label "Osoba"]
          [re-com/single-dropdown
           :model (get-in item [:daily-plan/person :db/id])
           :on-change #(re-frame/dispatch [:entity-change :daily-plan (:db/id item) :daily-plan/person {:db/id %}])
           :choices (->> @persons
                         vals
                         (filter :person/active?)
                         (util/sort-by-locale cljc-util/person-fullname))
           :id-fn :db/id
           :label-fn cljc-util/person-fullname
           :filter-box? true
           :width "250px"]
          (when (->> item :daily-plan/person :db/id (get @persons) :person/child?)
            (re-com/v-box
             :gap "5px"
             :children
             [[re-com/label :label "Druh docházky"]
              [re-com/h-box :gap "15px" :align :center
               :children
               [[re-com/single-dropdown
                 :model (:daily-plan/child-att item)
                 :on-change #(re-frame/dispatch [:entity-change :daily-plan (:db/id item) :daily-plan/child-att (cljc-util/parse-int %)])
                 :choices [{:id nil :label "žádná"}
                           {:id 1 :label "celodenní"}
                           {:id 2 :label "půldenní"}]
                 :placeholder "žádná"
                 :width "100px"]
                [re-com/checkbox
                 :label "docházka omluvena?"
                 :model (:daily-plan/att-cancelled? item)
                 :on-change #(re-frame/dispatch [:entity-change :daily-plan (:db/id item) :daily-plan/att-cancelled? %])]
                (when (:daily-plan/substituted-by item)
                  [re-com/label :label "Nahrazováno:"])
                (when (:daily-plan/substituted-by item)
                  (let [subst-id (get-in item [:daily-plan/substituted-by :db/id])]
                    [:label [:a {:href (str "#/daily-plan/" subst-id)}
                             (some-> (get @daily-plans subst-id)
                                     :daily-plan/date
                                     (time/to-format time/ddMMyyyy))]]))]]]))
          [re-com/label :label "Požadováno obědů"]
          [re-com/h-box :gap "15px" :align :center
           :children
           [[re-com/input-text
             :model (str (:daily-plan/lunch-req item))
             :on-change #(re-frame/dispatch [:entity-change :daily-plan (:db/id item) :daily-plan/lunch-req (cljc-util/parse-int %)])
             :validation-regex #"^[0-9]{0,1}$"
             :width "100px"]
            [re-com/checkbox
             :label "oběd odhlášen?"
             :model (:daily-plan/lunch-cancelled? item)
             :on-change #(re-frame/dispatch [:entity-change :daily-plan (:db/id item) :daily-plan/lunch-cancelled? %])]]]
          [re-com/label :label "Objednáno obědů"]
          [re-com/input-text
           :model (str (:daily-plan/lunch-ord item))
           :on-change #(re-frame/dispatch [:entity-change :daily-plan (:db/id item) :daily-plan/lunch-ord (cljc-util/parse-int %)])
           :disabled? (not (contains? (:-roles @user) "superadmin"))
           :width "100px"]
          [re-com/h-box :gap "5px" :align :center
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :daily-plan])]
            "nebo"
            (when (:db/id item)
              [re-com/hyperlink-href
               :href (str "#/daily-plan/e")
               :label [re-com/button :label "Nový" :on-click #(re-frame/dispatch [:entity-new :daily-plan {:daily-plan/person (:daily-plan/person item)}])]])
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/daily-plans")]]]
          [history/view (:db/id item)]]]))))

(secretary/defroute "/daily-plans" []
  (re-frame/dispatch [:set-current-page :daily-plans]))
(pages/add-page :daily-plans #'page-daily-plans)

(secretary/defroute #"/daily-plan/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :daily-plan (cljc-util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :daily-plan]))
(pages/add-page :daily-plan #'page-daily-plan)
(common/add-kw-url :daily-plan "daily-plan")
