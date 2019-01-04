(ns liskasys.cljs.class-register
  (:require [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.comp.history :as history]
            [liskasys.cljs.pages :as pages]
            [liskasys.cljs.util :as util]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]
            [reagent.ratom :as ratom]))

(re-frame/reg-sub
 ::reg-date-dps
 :<- [:entities :daily-plan]
 :<- [:entities :person]
 :<- [:entity-edit :class-register]
 (fn [[daily-plans persons class-register] _]
   (let [date (:class-register/date class-register)
         group-id (get-in class-register [:class-register/group :db/id])]
     (if-not (and date group-id (pos-int? (count daily-plans)))
       nil
       (->> (vals daily-plans)
            (filter #(and
                      (= date (:daily-plan/date %))
                      (= group-id (some->> % :daily-plan/group :db/id))))
            (sort-by #(some->> % :daily-plan/person :db/id (get persons) (cljc.util/person-fullname))))))))

(defn page-class-registers []
  (let [class-registers (re-frame/subscribe [:entities :class-register])
        groups (re-frame/subscribe [:entities :group])
        table-state (re-frame/subscribe [:table-state :class-registers])
        user (re-frame/subscribe [:auth-user])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Třídní kniha"]
        [data-table
         :table-id :class-registers
         :rows class-registers
         :colls [[[re-com/h-box :gap "5px" :justify :end
                   :children
                   [[re-com/md-icon-button
                     :md-icon-name "zmdi-plus-square"
                     :tooltip "Přidat"
                     :on-click #(do (re-frame/dispatch [:entity-new :class-register {:class-register/date (time/today)
                                                                                     :class-register/group (first (vals @groups))}])
                                    (set! js/window.location.hash "#/class-register/e"))]
                    [re-com/md-icon-button
                     :md-icon-name "zmdi-refresh"
                     :tooltip "Přenačíst ze serveru"
                     :on-click #(re-frame/dispatch [:entities-load :class-register])]
                    ]]
                  (fn [row]
                    (when (= (:db/id row) (:selected-row-id @table-state))
                      [re-com/h-box :gap "5px" :justify :end
                       :children
                       [[re-com/hyperlink-href
                         :href (str "#/class-register/" (:db/id row) "e")
                         :label [re-com/md-icon-button
                                 :md-icon-name "zmdi-edit"
                                 :tooltip "Editovat"]]
                        (when (contains? (:-roles @user) "superadmin")
                          [buttons/delete-button :on-confirm #(re-frame/dispatch [:entity-delete :class-register (:db/id row)]) :emphasise? true])]]))
                  :none]
                 ["Datum" :class-register/date]
                 ["Třída" #(some->> % :class-register/group :db/id (get @groups) :group/label)]
                 ["Popis dne" :class-register/descr]]
         :desc? true]]])))

(defn page-class-register []
  (let [class-register (re-frame/subscribe [:entity-edit :class-register])
        persons (re-frame/subscribe [:entities :person])
        groups (re-frame/subscribe [:entities :group])
        user (re-frame/subscribe [:auth-user])
        reg-date-dps (re-frame/subscribe [::reg-date-dps])
        noop-fn #()]
    (fn []
      (if-not (and @groups @persons)
        [re-com/throbber]
        (let [item @class-register
              errors (:-errors item)]
          [re-com/v-box :gap "5px"
           :children
           [[:h3 "Třídní kniha"]
            [re-com/label :label "Datum"]
            [re-com/input-text
             :model (time/to-format (:class-register/date item) time/ddMMyyyy)
             :on-change #(re-frame/dispatch [:entity-change :class-register (:db/id item) :class-register/date (time/from-dMyyyy %)])
             :validation-regex #"^\d{0,2}$|^\d{0,2}\.\d{0,2}$|^\d{0,2}\.\d{0,2}\.\d{0,4}$"
             :width "100px"]
            [re-com/label :label "Třída"]
            [re-com/single-dropdown
             :model (some-> item :class-register/group :db/id)
             :on-change #(re-frame/dispatch [:entity-change :class-register (:db/id item) :class-register/group {:db/id %}])
             :choices (conj (util/sort-by-locale :group/label (vals @groups)) {:db/id nil :group/label "nevybráno"})
             :id-fn :db/id
             :label-fn :group/label
             :placeholder "nevybráno"
             :width "250px"]
            [re-com/label :label "Popis dne"]
            [re-com/input-textarea
             :model (str (:class-register/descr item))
             :rows 10
             :width "600px"
             :on-change #(re-frame/dispatch [:entity-change :class-register (:db/id item) :class-register/descr %])]
            [re-com/h-box :align :center :gap "5px"
             :children
             [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :class-register])]
              "nebo"
              (when (:db/id item)
                [re-com/hyperlink-href
                 :href (str "#/class-register/e")
                 :label [re-com/button :label "Nový" :on-click #(re-frame/dispatch [:entity-new :class-register
                                                                                    (-> item
                                                                                        (select-keys [:class-register/group :class-register/date])
                                                                                        (update :class-register/date time/next-working-day))])]])
              [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/class-registers")]]]
            (if-not @reg-date-dps
              [re-com/throbber]
              [re-com/v-box :children
               [[:h4 "Docházka: přítomno " (->> @reg-date-dps (remove (some-fn :daily-plan/att-cancelled? :daily-plan/absence?)) (count)) ", "
                 [:span.cancelled "omluveno " (->> @reg-date-dps (filter :daily-plan/att-cancelled?) (count))] ", "
                 [:span.absence "absence " (->> @reg-date-dps (filter :daily-plan/absence?) (count))]]
                (into [:ul] (map #(-> [:li {:class (util/dp-class %)}
                                       (some->> % :daily-plan/person :db/id (get @persons) (cljc.util/person-fullname))
                                       (when-some [excuse (:daily-plan/excuse %)]
                                         (str ", " excuse))
                                       [re-com/hyperlink-href
                                        :href (str "#/daily-plan/" (:db/id %) "e")
                                        :label [re-com/md-icon-button
                                                :md-icon-name "zmdi-edit"
                                                :tooltip "Editovat"]]])
                                 @reg-date-dps))]])
            [history/view (:db/id item)]]])))))

(secretary/defroute "/class-registers" []
  (re-frame/dispatch [:set-current-page :class-registers]))
(pages/add-page :class-registers #'page-class-registers)

(secretary/defroute #"/class-register/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :class-register (cljc.util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :class-register]))
(pages/add-page :class-register #'page-class-register)
(common/add-kw-url :class-register "class-register")
