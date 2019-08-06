(ns liskasys.endpoint.main-hiccup
  (:require [liskasys.hiccup :as hiccup]
            [liskasys.cljc.time :as time]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [clj-time.periodic :as tp]
            [clojure.string :as str]
            [liskasys.cljc.util :as cljc.util]
            [taoensso.timbre :as timbre])
  (:import java.text.Collator
           [java.util Date Locale]))

(def system-title "LiškaSys")

(defn liskasys-frame
  ([user body-hiccup]
   (liskasys-frame user body-hiccup nil))
  ([{roles :-roles :as user} body-hiccup flash-msg]
   (hiccup/hiccup-response
    (hiccup/hiccup-frame
     (str system-title ": " (:-org-name user))
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
          (when (roles "parent")
            [:li [:a {:href "/nahrady"} "Náhrady"]])]
         [:ul.nav.navbar-nav.navbar-right
          [:li
           [:a {:href "/profile"} (cljc.util/person-fullname user)]]
          (when (some roles ["admin" "inspektor"])
            [:li [:a {:target "admin" :href "/admin.app"} "Admin"]])
          [:li [:a {:href "/passwd"} "Změna hesla"]]
          [:li [:a {:href "/logout"} "Odhlásit se"]]]]]]
      (when flash-msg
        [:div.container
         [:div.alert.alert-success flash-msg]])
      body-hiccup]))))

(defn cancellation-page [user-children-data child-daily-plans]
  [:div.container
   [:script
    "console.log(\"Fn declaration\");
function validateExcuses() {
var xs = document.getElementsByName(\"cancel-dates[]\");
console.log(\"Validating \" + xs.length + \" elements\");
for (var i=0; i < xs.length; i++) {
  var exc = document.getElementsByName(\"excuse[\" + xs[i].value  + \"]\")[0].value;
  if (xs[i].checked && (!exc || /^\\s*$/.test(exc))) {
    alert(\"Vyplňte prosím důvody nepřítomnosti.\");
    return false;
  }
}
console.log(\"Ok\");
return true;
}"]
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
                   :selected (= (:selected-id user-children-data) (:db/id person))} (cljc.util/person-fullname person)])]]]
    [:form (merge {:method "post"
                   :role "form"}
                  (when (some-> user-children-data :selected-child :person/group :group/mandatory-excuse?)
                    {:onsubmit "return validateExcuses()"}))
     [:div.form-group
      [:input.form-control {:type "hidden" :name "child-id" :value (:selected-id user-children-data)}]
      #_[:label {:for "from"} "Docházka bude (nebo již je) omluvena v označených dnech"]
      [:table.table.table-striped
       [:thead
        [:tr
         [:th "Datum"]
         [:th "Omluvit?"]
         [:th "Důvod nepřítomnosti"]]]
       [:tbody
        (for [{:daily-plan/keys [date att-cancelled? lunch-ord excuse]} child-daily-plans
              :let [date-str (time/to-format date time/ddMMyyyy)]]
          [:tr
           [:td
            [:label.nowrap (time/format-day-date date)]
            (when (some-> lunch-ord (> 0))
              [:span.label.label-danger "Objednané obědy: "lunch-ord])]
           [:td
            (when att-cancelled?
              [:input {:type "hidden" :name "already-cancelled-dates[]" :value date-str}])
            [:input.form-control.input-sm {:type "checkbox" :name "cancel-dates[]"
                                           :value date-str
                                           :checked (boolean att-cancelled?)}]
            #_[:label.nowrap
               " "
               (when lunch-cancelled?
                 "(oběd odhlášen)")]]
           [:td
            [:input.form-control {:type "text"
                                  :name (str "excuse[" date-str  "]")
                                  :size "30"
                                  :value excuse}]]])]]]
     #_(anti-forgery/anti-forgery-field)
     (when (seq child-daily-plans)
       [:button.btn.btn-danger {:type "submit"} "Uložit"])
     [:br]
     [:br]]]])

(defn substitutions [user-children-data {:keys [dp-gap-days can-subst? substable-dps person groups]}]
  [:div.container
   [:h3 "Náhrady"]
   #_[:label "Omluvenky z předchozího školního roku nelze nahrazovat v novém. Náhrady pro nový školni rok budou zprovozněny zhruba do poloviny září, po úpravách systému zohledňujících zařazení dětí do tříd."]]
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
                   :selected (= (:selected-id user-children-data) (:db/id person))} (cljc.util/person-fullname person)])]]]
    [:form {:method "post"
            :role "form"}
     [:input {:type "hidden" :name "child-id" :value (:selected-id user-children-data)}]
     (if-not (seq dp-gap-days)
       [:h3 "Nebyla nalezena žádná možnost náhrady (nebo den, kdy není řádná docházka)."]
       [:div
        [:label "Ve dnech, kdy projevíte zájem nahradit docházku, budete zařazeni do pořadníku. Účast bude potvrzena emailem (a oběd objednán) den předem dopoledne."]
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
                                    (filter #(= (:selected-id user-children-data)
                                                (get-in % [:daily-plan/person :db/id])))
                                    (first))
                      {:keys [group group-plans]} (cljc.util/select-group-for-subst plans person groups)]]
            [:tr
             [:td [:label (time/format-day-date date)]]
             [:td (- (or (:group/max-capacity group) 0) (count group-plans))]
             [:td (let [substs (->> group-plans
                                    (filter :daily-plan/subst-req-on)
                                    (sort-by :daily-plan/subst-req-on))]
                    (str (when my-subst
;;                           (clojure.pprint/pprint group)
;;                           (clojure.pprint/pprint group-plans)
;;                           (clojure.pprint/pprint substs)
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
            [:br] [:br]]
           :else
           [:div
            [:a {:target "_blank" :href (str "/jidelni-listek/" (:db/id lunch-menu))} "Stáhnout"]
            [:br] [:br]])
       [:div.row
        [:div.col-md-6
         (when previous?
           [:a {:href (str "?history=" (inc history))}
            [:button.btn.btn-default "Předchozí"]])]
        [:div.col-md-6.text-right
         (when (pos? history)
           [:a {:href (str "?history=" (dec history))}
            [:button.btn.btn-default "Následující"]])]]])]])

(defn person-bills [person-bills price-list-fn]
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
         [:td (cljc.util/period->text period)]
         [:td (cljc.util/person-fullname person)]
         [:td.right (:person/var-symbol person)]
         [:td.right [:b (cljc.util/cents->text total)]]
         [:td.right (cljc.util/cents->text att-price)]
         [:td.right (str lunch-count " x " (cljc.util/from-cents -lunch-price) " = " (cljc.util/from-cents -total-lunch-price))]
         [:td.right (cljc.util/cents->text -from-previous)]
         [:td (cljc.util/att-pattern->text (:person/att-pattern person))]
         [:td (cljc.util/lunch-pattern->text (:person/lunch-pattern person))]]
        (let [{:keys [bank-account bank-account-lunches]} (price-list-fn person)
              show-qr? (re-find #"^[-0-9/]+$" bank-account)
              separate-lunches? (not (str/blank? bank-account-lunches))]
          (when (and (not -paid?)
                     show-qr?
                     (not separate-lunches?))
            [:tr
             [:td {:col-span 9} [:img {:src (str "/qr-code?id=" id)}]]]))))]]])
