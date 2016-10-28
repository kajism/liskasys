(ns liskasys.endpoint.main-hiccup
  (:require [liskasys.hiccup :as hiccup]
            [liskasys.cljc.time :as time]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [clj-time.periodic :as tp]
            [clojure.pprint :refer [pprint]]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.service :as service]
            [taoensso.timbre :as timbre])
  (:import java.text.Collator
           [java.util Date Locale]))

(def system-title "LiškaSys")

(defn liskasys-frame [{roles :-roles :as user} body-hiccup]
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
         (when (roles "parent")
           [:li [:a {:href "/"} "Omluvenky"]])
         [:li [:a {:href "/jidelni-listek"} "Jídelníček"]]
         [:li [:a {:href "/platby"} "Platby"]]
         #_[:li [:a {:href "/nahrady"} "Náhrady"]]]
        [:ul.nav.navbar-nav.navbar-right
         [:li
          [:a {:href "/profile"} (cljc-util/person-fullname user)]]
         (when (roles "admin")
           [:li [:a {:target "admin" :href "/admin.app"} "Admin"]])
         [:li [:a {:href "/passwd"} "Změna hesla"]]
         [:li [:a {:href "/logout"} "Odhlásit se"]]]]]]
     body-hiccup])))

(defn cancellation-page [user-children-data child-daily-plans]
  [:div.container
   [:h3 "Omluvenky"]
   [:div
    [:div.form-group
     [:label {:for "child"} "Dítě"]
     [:form {:method "get"
             :role "form"}
      [:select#child.form-control {:name "child-id"
                                   :onchange "this.form.submit()"}
       (for [person (:user-children user-children-data)]
         [:option {:value (:db/id person)
                   :selected (= (:selected-id user-children-data) (:db/id person))} (cljc-util/person-fullname person)])]]]
    [:form {:method "post"
            :role "form"}
     [:input {:type "hidden" :name "child-id" :value (:selected-id user-children-data)}]
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

(defn substitutions [user-children-data {:keys [canc-plans subst-plans dates substs-by-date]}]
  [:div.container
   [:h3 "Náhrady"]
   [:div
    [:div.form-group
     [:label {:for "child"} "Dítě"]
     [:form {:method "get"
             :role "form"}
      [:select#child.form-control {:name "child-id"
                                   :onchange "this.form.submit()"}
       (for [person (:user-children user-children-data)]
         [:option {:value (:db/id person)
                   :selected (= (:selected-id user-children-data) (:db/id person))} (cljc-util/person-fullname person)])]]]
    [:form {:method "post"
            :role "form"}
     [:input {:type "hidden" :name "child-id" :value (:selected-id user-children-data)}]
     [:table.table.table-striped
      [:thead
       [:tr
        [:th "Omluvených dnů"]
        [:th "Nahrazených dnů"]]]
      [:tbody
       [:tr
        [:td (count canc-plans)]
        [:td (count subst-plans)]]]]
     [:div.form-group
      [:label {:for "from"} "Označte dny, kdy máte zájem nahradit docházku"]
      [:table.table.table-striped
       [:tbody
        (for [{:keys [date subst? lunch?]} dates
              :let [date-str (time/to-format date time/ddMMyyyy)]]
          [:tr
           [:td
            [:label
             (when subst?
               [:input {:type "hidden" :name "already-subst-dates[]" :value date-str}])
             [:input {:type "checkbox" :name "subst-dates[]"
                      :value date-str
                      :checked (boolean subst?)}] " "
             (service/format-day-date date)]]])]]]
     ;;(anti-forgery/anti-forgery-field)
     [:button.btn.btn-danger {:type "submit"} "Uložit"]]]])

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

(defn person-bills [person-bills]
  [:div.container
   [:h3 "Rozpisy plateb"]
   [:table.table.table-striped
    [:thead
     [:tr
      [:th "Období"]
      [:th "Jméno"]
      [:th "Variabilní symbol"]
      [:th "Celkem Kč"]
      [:th "Cena za docházku"]
      [:th "Cena za obědy"]
      [:th "Z předch. období"]
      [:th "Rozvrh docházky"]
      [:th "Rozvrh obědů"]]]
    [:tbody
     (for [{:person-bill/keys [period person total att-price lunch-count] :keys [_lunch-price _total-lunch-price _from-previous]} person-bills]
       [:tr
        [:td (cljc-util/period->text period)]
        [:td (cljc-util/person-fullname person)]
        [:td.right (:person/var-symbol person)]
        [:td.right [:b (cljc-util/cents->text total)]]
        [:td.right (cljc-util/cents->text att-price)]
        [:td.right (str lunch-count " x " (cljc-util/from-cents _lunch-price) " = " (cljc-util/from-cents _total-lunch-price))]
        [:td.right (cljc-util/cents->text _from-previous)]
        [:td (cljc-util/att-pattern->text (:person/att-pattern person))]
        [:td (cljc-util/lunch-pattern->text (:person/lunch-pattern person))]])]]])
