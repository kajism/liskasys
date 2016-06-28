(ns liskasys.endpoint.main-hiccup
  (:require [clj-brnolib.hiccup :as hiccup]
            [clj-brnolib.jdbc-common :as jdbc-common]
            [clj-brnolib.time :as time]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.periodic :as tp]
            [clojure.pprint :refer [pprint]]
            [liskasys.db :as db]
            [taoensso.timbre :as timbre])
  (:import java.text.Collator
           [java.util Date Locale]))

(def system-title "LiškaSys")

(defn liskasys-frame [user body]
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
         (when (pos? (:-children-count user))
           [:li
            [:a {:href "/"} "Omluvenky"]])
         [:li
          [:a {:href "/jidelni-listek"} "Jídelní lístek"]]
         (when (or ((:-roles user) "admin")
                   ((:-roles user) "obedy"))
           [:li
            [:a {:href "/obedy"} "Obědy"]])
         (when ((:-roles user) "admin")
           [:li
            [:a {:href "/odhlasene-obedy"} "Odhlášené obědy"]])]
        [:ul.nav.navbar-nav.navbar-right
         [:li
          [:a {:href "/profile"} (:-fullname user)]]
         (when ((:-roles user) "admin")
           [:li
            [:a {:target "admin" :href "/admin.app"} "Admin"]])
         [:li
          [:a {:href "/passwd"} "Změna hesla"]]
         [:li
          [:a
           {:href "/logout"} "Odhlásit se"]]]]]]
     body])))

(defn cancellation-page [db-spec user {:keys [child-id] :as params}]
  (liskasys-frame
   user
   (let [children (db/select-children-by-user-id db-spec (:id user))
         child-id (or child-id (:id (first children)))
         attendance-days (db/select-next-attendance-weeks db-spec child-id 2)]
     [:div.container
      [:h3 "Omluvenky"]
      [:form {:method "post"
              :role "form"}
       [:div.form-group
        [:label {:for "child"} "Dítě"]
        [:select#child.form-control {:name "child-id"}
         (for [child children]
           [:option {:value (:id child)
                     :selected (= child-id (:id child))} (:-fullname child)])]]
       [:div.form-group
        [:label {:for "from"} "Docházka bude (nebo již je) omluvena v označených dnech"]
        [:table.table.table-striped
         [:tbody
          (for [[date att] attendance-days
                :let [date-str (time/to-format date time/ddMMyyyy)
                      cancellation (:cancellation att)]]
            [:tr
             [:td
              [:label
               (when cancellation
                 [:input {:type "hidden" :name "already-cancelled-dates[]" :value date-str}])
               [:input {:type "checkbox" :name "cancel-dates[]"
                        :value (time/to-format date time/ddMMyyyy)
                        :checked (boolean cancellation)}] " "
               (get time/week-days (:day-of-week att)) " "
               (time/to-format date time/dMyyyy)
               (when (:lunch-cancelled? cancellation)
                 " včetně oběda")]]])]]]
       #_(anti-forgery/anti-forgery-field)
       [:button.btn.btn-danger {:type "submit"} "Uložit"]]
      #_[:pre (with-out-str
                (pprint user)
                (pprint params))]])))

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
   (let [days (iterate (fn [date]
                         (t/plus date (t/days 1)))
                       (t/today))
         work-days (keep (fn [date]
                           (when (<= (t/day-of-week date) 5)
                             date))
                         days)
         lunch-types (jdbc-common/select db-spec :lunch-type {})
         have-lunch?-fn #(and (:lunch? %) (not (:lunch-cancelled? %)))]
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
        (for [date (->> work-days
                        (take 2))
              :let [day-of-week (t/day-of-week date)
                    atts (db/select-attendance-day db-spec (time/to-date date) day-of-week)
                    atts-with-lunch (filter have-lunch?-fn atts)
                    atts-by-lunch-type (group-by (comp :lunch-type-id) atts-with-lunch)]]
          [:tr
           [:td (get time/week-days day-of-week) " " (-> date time/to-date (time/to-format time/dMyyyy))]
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
            (list-of-kids user (remove have-lunch?-fn atts))]
           [:td (count (remove #(some? (:lunch-cancelled? %)) atts))]])]]])))

(defn lunch-menu [db-spec user {:keys [history delete-id new?] :as params}]
  (when delete-id
    (jdbc-common/delete! db-spec :lunch-menu {:id delete-id}))
  (liskasys-frame
   user
   (let [history (or (some-> history Integer.) 0)
         [lunch-menu previous] (db/select-last-two-lunch-menus db-spec history)]
     [:div.container
      [:h3 "Jídelní lístek"]
      (if new?
        [:form {:method "post"
                :role "form"
                :enctype "multipart/form-data"}
         [:div.form-group
          [:textarea.form-control {:name "menu" :rows "30" :cols "50"}]]
         [:div.form-group
          [:input {:type "file" :name "upload"}]]
         [:button.btn.btn-success {:type "submit"} "Uložit"]]
        [:div
         (when ((:-roles user) "admin")
           [:div.row
            [:div.col-md-6
             [:a {:href "?new?=true"}
              [:button.btn.btn-default "Nový"]]] " "
            [:div.col-md-6.text-right
             [:a {:href (str "?delete-id=" (:id lunch-menu) "&history=" history)}
              [:button.btn.btn-danger "Smazat"]]]
            [:br][:br]])
         (when lunch-menu
           [:div
            (cond
              (nil? (:content-type lunch-menu))
              [:pre (:text lunch-menu)]
              (= "image/" (subs (:content-type lunch-menu) 0 6))
              [:div
               [:img {:src (str "/jidelni-listek/" (:id lunch-menu))}]
               [:br][:br]]
              :else
              [:div
               [:a {:target "_blank" :href (str "/jidelni-listek/" (:id lunch-menu))} "Stáhnout"]
               [:br][:br]])
            [:div.row
             [:div.col-md-6
              (when previous
                [:a {:href (str "?history=" (inc history))}
                 [:button.btn.btn-default "Předchozí"]])]
             [:div.col-md-6.text-right
              (when (pos? history)
                [:a {:href (str "?history=" (dec history))}
                 [:button.btn.btn-default "Následující"]])]]])])])))

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
         cancellations (->> (jdbc-common/select db-spec :cancellation {})
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
