(ns liskasys.endpoint.main-hiccup
  (:require [liskasys.hiccup :as hiccup]
            [liskasys.cljc.time :as time]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [clj-time.periodic :as tp]
            [clojure.pprint :refer [pprint]]
            [liskasys.cljc.util :as cljc-util]
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
         [:li [:a {:href "/nahrady"} "Náhrady"]]]
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
             (time/format-day-date date)
             " "
             (when lunch-cancelled?
               "(oběd odhlášen)")]]])]]]
     #_(anti-forgery/anti-forgery-field)
     [:button.btn.btn-danger {:type "submit"} "Uložit"]]]])

(defn substitutions [user-children-data {:keys [dp-gap-days  can-subst? substable-dps]}]
  [:div.container
   [:h3 "Náhrady"]
   [:label "Omluvenky z předchozího školního roku nelze nahrazovat v novém. Náhrady pro nový školni rok budou zprovozněny zhruba do poloviny září, po úpravách systému zohledňujících zařazení dětí do tříd."]]
  #_[:div.container
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
     #_[:table.table.table-striped
        [:thead
         [:tr
          [:th "Omluvených dnů"]
          [:th "Nahrazených dnů"]]]
        [:tbody
         [:tr
          [:td (count canc-plans)]
          [:td (count subst-plans)]]]]
     (if-not (seq dp-gap-days)
       [:h3 "Nebyla nalezena žádná možnost náhrady (nebo den, kdy není řádná docházka)."]
       [:div
        [:label "Ve dnech, kdy projevíte zájem nahradit docházku, budete zařazeni do pořadníku. Účast bude potvrzena emailem (a oběd objednán) den předem po 10. hodině."]
        [:table.table.table-striped
         [:thead
          [:tr
           [:th "Datum"]
           [:th "Počet volných míst"]
           [:th "Náhradníci"]]]
         [:tbody
          (for [[date plans] dp-gap-days
                :let [date-str (time/to-format date time/ddMMyyyy)
                      my-subst (->> plans
                                    (filter #(= (:selected-id user-children-data) (get-in % [:daily-plan/person :db/id])))
                                    first)]]
            [:tr
             [:td [:label (time/format-day-date date)]]
             [:td (- cljc-util/max-children-per-day (count plans))]
             [:td (let [substs (->> plans
                                    (filter #(:daily-plan/subst-req-on %))
                                    (sort-by :daily-plan/subst-req-on))]
                    (str (when my-subst
                           (str (inc (reduce (fn [_ [idx x]]
                                               (when (= x my-subst)
                                                 (reduced idx)))
                                             nil
                                             (map-indexed vector substs)))
                                ". z celkem "))
                         (count substs)))]
             [:td (if my-subst
                    [:input.btn.btn-danger.btn-xs
                     {:type "submit"
                      :name (str "subst-remove[" (:db/id my-subst) "]")
                      :value "Zrušit zájem"}]
                    (when can-subst?
                      [:input.btn.btn-success.btn-xs
                       {:type "submit"
                        :name (str "subst-request[" date-str "]")
                        :value "Mám zájem"}]))]])]]
        [:label "Zbývající počet náhrad: " (count substable-dps)]])
     #_(anti-forgery/anti-forgery-field)]]])

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
     (for [{:person-bill/keys [period person total att-price lunch-count] :keys [:db/id -lunch-price -total-lunch-price -from-previous -paid?]} person-bills]
       (list
        [:tr
         [:td (cljc-util/period->text period)]
         [:td (cljc-util/person-fullname person)]
         [:td.right (:person/var-symbol person)]
         [:td.right [:b (cljc-util/cents->text total)]]
         [:td.right (cljc-util/cents->text att-price)]
         [:td.right (str lunch-count " x " (cljc-util/from-cents -lunch-price) " = " (cljc-util/from-cents -total-lunch-price))]
         [:td.right (cljc-util/cents->text -from-previous)]
         [:td (cljc-util/att-pattern->text (:person/att-pattern person))]
         [:td (cljc-util/lunch-pattern->text (:person/lunch-pattern person))]]
        (when-not -paid?
          [:tr
           [:td {:col-span 9} [:img {:src (str "/qr-code?id=" id)}]]])))]]])
