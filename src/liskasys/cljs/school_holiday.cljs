(ns liskasys.cljs.school-holiday
  (:require [clojure.string :as str]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.pages :as pages]
            [liskasys.cljs.util :as util]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]))

(defn page-school-holidays []
  (let [school-holidays (re-frame/subscribe [:entities :school-holiday])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Prázdniny"]
        [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/school-holiday/e")]
        [data-table
         :table-id :school-holidays
         :rows @school-holidays
         :colls [["Název" :school-holiday/label]
                 ["Od" :school-holiday/from]
                 ["Do" :school-holiday/to]
                 ["Každoročně?" :school-holiday/every-year?]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :school-holiday])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/school-holiday/" (:db/id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      [buttons/delete-button #(re-frame/dispatch [:entity-delete :school-holiday (:db/id row)])]]])
                  :csv-export]]
         :order-by 1]]])))

(defn page-school-holiday []
  (let [school-holiday (re-frame/subscribe [:entity-edit :school-holiday])]
    (fn []
      (let [item @school-holiday]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Prázdniny"]
          [re-com/label :label "Název"]
          [re-com/input-text
           :model (str (:school-holiday/label item))
           :on-change #(re-frame/dispatch [:entity-change :school-holiday (:db/id item) :school-holiday/label %])
           :width "400px"]
          [re-com/label :label "Od"]
          [re-com/input-text
           :model (time/to-format (:school-holiday/from item) time/ddMMyyyy)
           :on-change #(re-frame/dispatch [:entity-change :school-holiday (:db/id item) :school-holiday/from (time/from-dMyyyy %)])
           :validation-regex #"^\d{0,2}$|^\d{0,2}\.\d{0,2}$|^\d{0,2}\.\d{0,2}\.\d{0,4}$"
           :width "100px"]
          [re-com/label :label "Do"]
          [re-com/input-text
           :model (time/to-format (:school-holiday/to item) time/ddMMyyyy)
           :on-change #(re-frame/dispatch [:entity-change :school-holiday (:db/id item) :school-holiday/to (time/from-dMyyyy %)])
           :validation-regex #"^\d{0,2}$|^\d{0,2}\.\d{0,2}$|^\d{0,2}\.\d{0,2}\.\d{0,4}$"
           :width "100px"]
          [re-com/label :label "Každoročně?"]
          [re-com/checkbox
           :model (:school-holiday/every-year? item)
           :on-change #(re-frame/dispatch [:entity-change :school-holiday (:db/id item) :school-holiday/every-year? %])]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :school-holiday])]
            "nebo"
            [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/school-holiday/e")]
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/school-holidays")]]]]]))))

(secretary/defroute "/school-holidays" []
  (re-frame/dispatch [:set-current-page :school-holidays]))
(pages/add-page :school-holidays #'page-school-holidays)

(secretary/defroute #"/school-holiday/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :school-holiday (cljc-util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :school-holiday]))
(pages/add-page :school-holiday #'page-school-holiday)
(common/add-kw-url :school-holiday "school-holiday")
