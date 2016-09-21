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
        persons (re-frame/subscribe [:entities :person])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Denní plány"]
        [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/daily-plan/e")]
        [data-table
         :table-id :daily-plans
         :rows @daily-plans
         :colls [["Datum" :daily-plan/date]
                 ["Osoba" #(->> [:daily-plan/person :db/id]
                                (get-in %)
                                (get @persons)
                                cljc-util/person-fullname)]
                 ["Docházka" :daily-plan/child-att]
                 ["Docházka zrušena" (comp util/boolean->text :daily-plan/att-cancelled?)]
                 ["Oběd požadavek" :daily-plan/lunch-req]
                 ["Oběd objednáno" :daily-plan/lunch-ord]
                 ["Oběd zrušen" (comp util/boolean->text :daily-plan/lunch-cancelled?)]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :daily-plan])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/daily-plan/" (:db/id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      [buttons/delete-button #(re-frame/dispatch [:entity-delete :daily-plan (:db/id row)])]]])
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
          [re-com/input-text
           :model (->> [:daily-plan/person :db/id]
                       (get-in item)
                       (get @persons)
                       cljc-util/person-fullname)
           :on-change #()
           :disabled? true]
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
           :on-change #(re-frame/dispatch [:entity-change :daily-plan (:db/id item) :daily-plan/lunch-ord (util/parse-int %)])
           :disabled? true
           :width "100px"]
          [re-com/checkbox
           :label "Oběd zrušen?"
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
  (when-not (util/parse-int id)
    (re-frame/dispatch [:entity-new :daily-plan {:daily-plan/from (->> (t/today)
                                                                       (iterate #(t/plus % (t/days 1)))
                                                                       (drop-while #(not= (t/day-of-week %) 1))
                                                                       first
                                                                       tc/to-date)}]))
  (re-frame/dispatch [:entity-set-edit :daily-plan (util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :daily-plan]))
(pages/add-page :daily-plan #'page-daily-plan)
(common/add-kw-url :daily-plan "daily-plan")
