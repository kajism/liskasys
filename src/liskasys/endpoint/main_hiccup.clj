(ns liskasys.endpoint.main-hiccup
  (:require [clj-brnolib.hiccup :as hiccup]
            [clj-brnolib.time :as time]
            [clj-time.core :as clj-time]
            [clojure.pprint :refer [pprint]]
            [liskasys.db :as db]
            [taoensso.timbre :as timbre])
  (:import java.util.Date))

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
         [:li
          [:a {:href "/"} "Omluvenky"]]
         (when (or ((:-roles user) "admin")
                   ((:-roles user) "obedy"))
           [:li
            [:a {:href "/obedy"} "Obědy"]])]
        [:ul.nav.navbar-nav.navbar-right
         [:li
          [:a {:href ""} (:-fullname user)]]
         (when ((:-roles user) "admin")
           [:li
            [:a {:href "/admin.app"} "Admin"]])
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

(defn lunches [db-spec user params]
  (liskasys-frame
   user
   (let [_ 1]
     [:div.container
      [:h3 "Obědy"]])))
