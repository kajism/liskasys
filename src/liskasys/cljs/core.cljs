(ns liskasys.cljs.core
  (:require [clojure.string :as str]
            [devtools.core :as devtools]
            [goog.events :as events]
            [goog.history.EventType :as EventType]
            [liskasys.cljs.ajax :refer [server-call]]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.group :as group]
            [liskasys.cljs.pages :as pages]
            [liskasys.cljs.bank-holiday]
            [liskasys.cljs.billing-period]
            [liskasys.cljs.class-register]
            [liskasys.cljs.daily-plan]
            [liskasys.cljs.lunch-menu]
            [liskasys.cljs.lunch-order]
            [liskasys.cljs.lunch-type]
            [liskasys.cljs.person]
            [liskasys.cljs.person-bill]
            [liskasys.cljs.price-list]
            [liskasys.cljs.school-holiday]
            [liskasys.cljs.transaction]
            [liskasys.cljs.config]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [reagent.ratom :as ratom]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre])
  (:import goog.History))

(devtools/install!)

(enable-console-print!)

(re-frame/reg-sub-raw
 :auth-user
 (fn [db [_]]
   (ratom/reaction (:auth-user @db))))

(re-frame/reg-event-db
 :load-auth-user
 common/debug-mw
 (fn [db [_]]
   (server-call [:user/auth {}]
                [:set-auth-user])
   db))

(re-frame/reg-event-db
 :set-auth-user
 common/debug-mw
 (fn [db [_ auth-user]]
   (assoc db :auth-user auth-user)))

(re-frame/reg-event-db
 :init-app
 common/debug-mw
 (fn [db [_]]
   (re-frame/dispatch [:load-auth-user])
   (merge db
          {} ;;init db
          )))

;; ---- Routes ---------------------------------------------------------------
(secretary/set-config! :prefix "#")

(secretary/defroute "/" []
  (re-frame/dispatch [:set-current-page :main]))

(defn hook-browser-navigation! []
  (doto (History.)
    (events/listen
     EventType/NAVIGATE
     (fn [event]
       (secretary/dispatch! (.-token event))))
    (.setEnabled true)))

(defn menu [user]
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
      [:li [:a {:href "#/persons"} "Lidé"]]
      [:li [:a {:href "#/daily-plans"} "Denní plány"]]
      [:li [:a {:href "#/class-registers"} "Třídní kniha"]]
      [:li [:a {:href "#/billing-periods"} "Platební období"]]
      [:li [:a {:href "#/lunch-menus"} "Jídelníček"]]
      [:li [:a {:href "#/lunch-orders"} "Oběd-návky"]]]
     [:ul.nav.navbar-nav.navbar-right
      #_[:li
       [:a {:target "_parent" :href "/obedy"} "Obědy"]]
      [:li.dropdown
       [:a.dropdown-toggle {:data-toggle "dropdown" :href "#"}
        "Nastavení" [:span.caret]]
       [:ul.dropdown-menu
        [:li [:a {:href "#/price-lists"} "Ceník a platba"]]
        [:li [:a {:href "#/lunch-types"} "Diety"]]
        [:li [:a {:href "#/school-holidays"} "Prázdniny"]]
        [:li [:a {:href "#/bank-holidays"} "Státní svátky"]]
        [:li [:a {:href "#/groups"} "Třídy"]]
        [:li [:a {:href "#/transactions"} "Transakce"]]
        [:li [:a {:href "#/configs"} "Základní nastavení"]]]]
      [:li
       [:a
        {:href "/logout"} "Odhlásit se"]]]]]])

(defn page-main []
  [:div
   [:h3 "LiškaSys"]])

(pages/add-page :main #'page-main)

(defn main-app-area []
  (let [user (re-frame/subscribe [:auth-user])]
    (re-frame/dispatch [:init-app])
    (fn []
      [:div
       [menu @user]
       [:div.container-fluid
        [pages/page]
        [:br]
        [:br]]])))

(defn main []
  (hook-browser-navigation!)
  (if-let [node (.getElementById js/document "app")]
    (reagent/render [main-app-area] node)))

(main)
