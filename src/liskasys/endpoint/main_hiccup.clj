(ns liskasys.endpoint.main-hiccup
  (:require [clj-brnolib.hiccup :as hiccup]
            [clj-brnolib.jdbc-common :as jdbc-common]
            [clj-brnolib.time :as time]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [clj-time.periodic :as tp]
            [clojure.pprint :refer [pprint]]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.db :as db]
            [liskasys.service :as service]
            [taoensso.timbre :as timbre])
  (:import java.text.Collator
           [java.util Date Locale]))

(def system-title "LiškaSys")

(defn liskasys-frame [{children-count :-children-count roles :-roles :as user} body-hiccup]
  (hiccup/hiccup-response
   (hiccup/hiccup-frame
    system-title
    [:div
     [:nav.navbar.navbar-default
      [:div.container-fluid
       [:div.navbar-header
        [:button.navbar-toggle {:type "button" :data-toggle "collapse" :data-target "#liskasys-navbar"}
         [:span.icon-bar]
         [:span.icon-bar]
         [:span.icon-bar]]
        [:a {:href "#"}
         [:img {:src "/img/logo_background.jpg" :alt "LiškaSys" :height "60"}]]]
       [:div#liskasys-navbar.collapse.navbar-collapse
        [:ul.nav.navbar-nav
         (when (pos? children-count)
           [:li
            [:a {:href "/"} "Omluvenky"]])
         [:li
          [:a {:href "/jidelni-listek"} "Jídelníček"]]
         #_(when (or (roles "admin")
                   (roles "obedy"))
           [:li
            [:a {:href "/obedy"} "Obědy"]])
         #_(when (roles "admin")
           [:li
            [:a {:href "/odhlasene-obedy"} "Odhlášené obědy"]])]
        [:ul.nav.navbar-nav.navbar-right
         [:li
          [:a {:href "/profile"} (cljc-util/person-fullname user)]]
         (when (roles "admin")
           [:li
            [:a {:target "admin" :href "/admin.app"} "Admin"]])
         [:li
          [:a {:href "/passwd"} "Změna hesla"]]
         [:li
          [:a
           {:href "/logout"} "Odhlásit se"]]]]]]
     body-hiccup])))

(defn cancellation-page [users-children selected-child-id child-daily-plans]
  [:div.container
   [:h3 "Omluvenky"]
   [:div
    [:div.form-group
     [:label {:for "child"} "Dítě"]
     [:form {:method "get"
             :role "form"}
      [:select#child.form-control {:name "child-id"
                                   :onchange "this.form.submit()"}
       (for [person users-children]
         [:option {:value (:db/id person)
                   :selected (= selected-child-id (:db/id person))} (cljc-util/person-fullname person)])]]]
    [:form {:method "post"
            :role "form"}
     [:input {:type "hidden" :name "child-id" :value selected-child-id}]
     [:div.form-group
      [:label {:for "from"} "Docházka bude (nebo již je) omluvena v označených dnech"]
      [:table.table.table-striped
       [:tbody
        (for [{:keys [:daily-plan/date :daily-plan/att-cancelled? :daily-plan/lunch-cancelled?]} child-daily-plans
              :let [date-str (time/to-format date time/ddMMyyyy)]]
          [:tr
           [:td
            [:label
             (when att-cancelled?
               [:input {:type "hidden" :name "already-cancelled-dates[]" :value date-str}])
             [:input {:type "checkbox" :name "cancel-dates[]"
                      :value date-str
                      :checked (boolean att-cancelled?)}] " "
             (service/format-day-date date)
             " "
             (when lunch-cancelled?
               "(oběd odhlášen)")]]])]]]
     ;;(anti-forgery/anti-forgery-field)
     [:button.btn.btn-danger {:type "submit"} "Uložit"]]]])

(def cs-collator (Collator/getInstance (Locale. "CS")))

(defn- list-of-kids
  [user atts]
  (when ((:-roles user) "admin")
    (for [att (->> atts
                   (sort-by :-fullname cs-collator))]
      (if (some? (:lunch-cancelled? att))
        [:div [:strike (:-fullname att)]]
        [:div (:-fullname att)]))))

(defn lunches [db-spec user params]
  (liskasys-frame
   user
   (let [lunch-types (jdbc-common/select db-spec :lunch-type {})]
     [:div.container
      [:h3 "Obědy"]
      [:table.table.table-striped
       [:thead
        [:tr
         [:th "Den / Dieta:"]
         [:th "&Sigma; obědů"]
         [:th {:style "background-color: LemonChiffon"} "běžná"]
         (for [lunch-type lunch-types]
           [:th {:style (str "background-color: " (:color lunch-type))}
            (:label lunch-type)])
         [:th {:style "background-color: Tomato"} "bez obědu"]
         [:th "&Sigma; dětí"]]]
       [:tbody
        (for [date (->> (t/today)
                        service/all-work-days-since
                        (take 2))
              :let [atts (db/select-children-with-attendance-day db-spec (time/to-date date))
                    atts-with-lunch (filter service/att-day-with-lunch? atts)
                    atts-by-lunch-type (group-by (comp :lunch-type-id) atts-with-lunch)]]
          [:tr
           [:td (->> date tc/to-date-time (tf/unparse service/day-formatter))]
           [:td (count atts-with-lunch)]
           [:td
            (count (atts-by-lunch-type nil))
            (list-of-kids user (atts-by-lunch-type nil))]
           (for [lunch-type lunch-types]
             [:td
              (count (atts-by-lunch-type (:id lunch-type)))
              (list-of-kids user (atts-by-lunch-type (:id lunch-type)))])
           [:td
            (- (count atts) (count atts-with-lunch))
            (list-of-kids user (remove service/att-day-with-lunch? atts))]
           [:td (count (remove #(some? (:lunch-cancelled? %)) atts))]])]]])))

(defn lunch-menu [lunch-menu previous? history]
  [:div.container
   [:h3 "Jídelní lístek"]
   [:div
    (when lunch-menu
      [:div
       [:pre (:lunch-menu/text lunch-menu)]
       #_(cond
           (nil? (:content-type lunch-menu))
           [:pre (:lunch-menu/text lunch-menu)]
           (= "image/" (subs (:content-type lunch-menu) 0 6))
           [:div
            [:img {:src (str "/jidelni-listek/" (:db/id lunch-menu))}]
            [:br][:br]]
           :else
           [:div
            [:a {:target "_blank" :href (str "/jidelni-listek/" (:db/id lunch-menu))} "Stáhnout"]
            [:br][:br]])
       [:div.row
        [:div.col-md-6
         (when previous?
           [:a {:href (str "?history=" (inc history))}
            [:button.btn.btn-default "Předchozí"]])]
        [:div.col-md-6.text-right
         (when (pos? history)
           [:a {:href (str "?history=" (dec history))}
            [:button.btn.btn-default "Následující"]])]]])]])

(defn cancelled-lunches [db-spec user]
  (liskasys-frame
   user
   (let [children (->> (if ((:-roles user) "admin")
                         (jdbc-common/select db-spec :child {})
                         (db/select-children-by-user-id db-spec (:id user)))
                       (sort-by :-fullname cs-collator))
         start-month (t/date-midnight 2016 5 1)
         last-month (-> (db/select-last-cancellation-date db-spec)
                        tc/from-date
                        t/first-day-of-the-month)
         months (->> (tp/periodic-seq last-month (t/months -1))
                     (take-while #(not (t/before? % start-month)))
                     (take 4)
                     (map (juxt t/year t/month))
                     reverse)
         cancellations (->> (jdbc-common/select db-spec :cancellation {:lunch-cancelled? true})
                            (group-by (fn [c]
                                        (let [d (tc/from-date (:date c))]
                                          [(:child-id c) (t/year d) (t/month d)]))))]
     [:div.container
      [:h3 "Odhlášené obědy"]
      [:table.table.table-striped
       [:thead
        [:tr
         [:th "Dítě / Měsíc:"]
         (for [[y m] months]
           [:th (str m "/" y)])
         #_[:th "&Sigma; obědů"]]
        [:tbody
         (for [ch children]
           [:tr
            [:td (:-fullname ch)]
            (for [[y m] months]
              [:td (count (get cancellations [(:id ch) y m]))])])]]]])))
