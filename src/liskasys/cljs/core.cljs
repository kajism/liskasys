(ns liskasys.cljs.core
  (:require [clojure.string :as str]
            [goog.events :as events]
            [goog.history.EventType :as EventType]
            [liskasys.cljs.ajax :refer [server-call]]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.pages :as pages]
            liskasys.cljs.cancellation
            liskasys.cljs.child
            liskasys.cljs.lunch-type
            liskasys.cljs.user
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [reagent.ratom :as ratom]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre])
  (:import goog.History))

(enable-console-print!)

(re-frame/register-sub
 :current-page
 (fn [db _]
   (ratom/reaction (:current-page @db))))

(re-frame/register-sub
 :auth-user
 (fn [db [_]]
   (ratom/reaction (:auth-user @db))))

(re-frame/register-handler
 :load-auth-user
 common/debug-mw
 (fn [db [_]]
   (server-call [:user/auth {}]
                [:set-auth-user])
   db))

(re-frame/register-handler
 :set-auth-user
 common/debug-mw
 (fn [db [_ auth-user]]
   (assoc db :auth-user auth-user)))

(re-frame/register-handler
 :set-current-page
 common/debug-mw
 (fn [db [_ current-page]]
   (assoc db :current-page current-page)))

(re-frame/register-handler
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
      [:li
       [:a {:href "#/children"} "Děti"]]
      [:li
       [:a {:href "#/cancellations"} "Omluvenky"]]]
     [:ul.nav.navbar-nav.navbar-right
      #_[:li
       [:a {:target "_parent" :href "/obedy"} "Obědy"]]
      [:li.dropdown
       [:a.dropdown-toggle {:data-toggle "dropdown" :href "#"}
        "Nastavení" [:span.caret]]
       [:ul.dropdown-menu
        [:li
         [:a {:href "#/users"} "Uživatelé"]]
        [:li
         [:a {:href "#/price-lists"} "Ceník"]]
        [:li
         [:a {:href "#/lunch-types"} "Diety"]]]]
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
