(ns liskasys.cljs.daily-plan
  (:require [clj-brnolib.cljs.comp.buttons :as buttons]
            [clj-brnolib.cljs.comp.data-table :refer [data-table]]
            [clj-brnolib.cljs.comp.input-text :refer [input-text]]
            [clj-brnolib.cljs.util :as util]
            [clj-brnolib.time :as time]
            [cljs-time.coerce :as tc]
            [cljs-time.core :as t]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as str]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]))

(defn page-daily-plans []
  (let [daily-plans (re-frame/subscribe [:entities :daily-plan])
        persons (re-frame/subscribe [:entities :person])
        selected-row (reagent/atom nil)]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Denní plány"]
        [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/daily-plan/e")]
        [data-table
         :table-id :daily-plans
         :selected-row selected-row
         :rows @daily-plans
         :colls [["Datum" :daily-plan/date]
                 ["Jméno" (fn [row]
                            (let [label (->> row :daily-plan/person :db/id (get @persons) cljc-util/person-fullname)]
                              (if (identical? row @selected-row)
                                [re-com/hyperlink-href
                                 :href (str "#/person/" (get-in row [:daily-plan/person :db/id]) "e")
                                 :label label]
                                label)))]
                 ["Docházka" #(or (:daily-plan/child-att %) 0)]
                 ["Docházka zrušena" (comp util/boolean->text :daily-plan/att-cancelled?)]
                 ["Oběd požadavek" #(or (:daily-plan/lunch-req %) 0)]
                 ["Oběd objednáno" #(or (:daily-plan/lunch-ord %) 0)]
                 ["Oběd zrušen" (comp util/boolean->text :daily-plan/lunch-cancelled?)]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :daily-plan])]
                  (fn [row]
                    (when (identical? row @selected-row)
                      [re-com/hyperlink-href
                       :href (str "#/daily-plan/" (:db/id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]))
                  :csv-export]]
         :desc? true]]])))

(defn page-daily-plan []
  (let [daily-plan (re-frame/subscribe [:entity-edit :daily-plan])
        persons (re-frame/subscribe [:entities :person])]
    (fn []
      (let [item @daily-plan
            errors (:-errors item)]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Denní plán osoby"]
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
          [re-com/label :label "Druh docházky"]
          [re-com/input-text
           :model (str (:daily-plan/child-att item))
           :on-change #(re-frame/dispatch [:entity-change :daily-plan (:db/id item) :daily-plan/child-att (util/parse-int %)])
           :validation-regex #"^[0-2]{0,1}$"
           :width "100px"]
          [re-com/checkbox
           :label "docházka zrušena?"
           :model (:daily-plan/att-cancelled? item)
           :on-change #(re-frame/dispatch [:entity-change :daily-plan (:db/id item) :daily-plan/att-cancelled? %])]
          [re-com/label :label "Požadovaný počet obědů"]
          [re-com/input-text
           :model (str (:daily-plan/lunch-req item))
           :on-change #(re-frame/dispatch [:entity-change :daily-plan (:db/id item) :daily-plan/lunch-req (util/parse-int %)])
           :validation-regex #"^[0-9]{0,1}$"
           :width "100px"]
          [re-com/label :label "Objednaný počet obědů"]
          [re-com/input-text
           :model (str (:daily-plan/lunch-ord item))
           :on-change #() ;;#(re-frame/dispatch [:entity-change :daily-plan (:db/id item) :daily-plan/lunch-ord (util/parse-int %)])
           :disabled? true
           :width "100px"]
          [re-com/checkbox
           :label "oběd zrušen?"
           :model (:daily-plan/lunch-cancelled? item)
           :on-change #(re-frame/dispatch [:entity-change :daily-plan (:db/id item) :daily-plan/lunch-cancelled? %])]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :daily-plan])]
            "nebo"
            [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/daily-plan/e")]
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/daily-plans")]]]]]))))

(secretary/defroute "/daily-plans" []
  (re-frame/dispatch [:set-current-page :daily-plans]))
(pages/add-page :daily-plans #'page-daily-plans)

(secretary/defroute #"/daily-plan/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :daily-plan (util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :daily-plan]))
(pages/add-page :daily-plan #'page-daily-plan)
(common/add-kw-url :daily-plan "daily-plan")
