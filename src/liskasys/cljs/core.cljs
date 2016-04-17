(ns liskasys.cljs.core
  (:require [clojure.string :as str]
            [liskasys.cljs.pages :as pages]
            [liskasys.cljs.ajax :refer [server-call]]
            [liskasys.cljs.util :as util]
            [goog.events :as events]
            [goog.history.EventType :as EventType]
            [re-frame.core :as re-frame]
            [re-com.core :as re-com]
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
 util/debug-mw
 (fn [db [_]]
   (server-call [:user/auth {}]
                [:set-auth-user])
   db))

(re-frame/register-handler
 :set-auth-user
 util/debug-mw
 (fn [db [_ auth-user]]
   (assoc db :auth-user auth-user)))

(re-frame/register-handler
 :set-current-page
 util/debug-mw
 (fn [db [_ current-page]]
   (assoc db :current-page current-page)))

(re-frame/register-handler
 :test/response
 util/debug-mw
 (fn [db [_ reply]]
   (timbre/debug "test reply" reply)
   db))

(re-frame/register-handler
 :init-app
 util/debug-mw
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
     [:button.navbar-toggle {:type "button" :data-toggle "collapse" :data-target "#masyst-navbar"}
      [:span.icon-bar]
      [:span.icon-bar]
      [:span.icon-bar]]
     [:a {:href "#"}
      [:img {:src "img/logo.svg" :alt "LISKASYS"}]]]
    [:div#masyst-navbar.collapse.navbar-collapse
     [:ul.nav.navbar-nav
      [:li
       [:a {:href "#/test"} "Test"]]
      [:li.dropdown
       [:a.dropdown-toggle  {:data-toggle "dropdown" :href "#"} "Plc simulátor" [:span.caret]]
       [:ul.dropdown-menu
        [:li [:a {:href "#/plc-sim/alarms"} "Alarmy"]]]]]
     [:ul.nav.navbar-nav.navbar-right
      [:li
       [:a
        {:href "#/users"} "Uživatelé"]]
      [:li
       [:a
        {:href "/logout"} "Odhlásit"]]]]]])

(defn page-main []
  [:div
   [:h3 "LISKASYS"]])

(pages/add-page :main #'page-main)
(secretary/defroute "/test" []
  (re-frame/dispatch [:set-current-page :test]))


(defn page-test []
  [:div
   [:h3 "Test"]])

(pages/add-page :test #'page-test)

(defn main-app-area []
  (let [user (re-frame/subscribe [:auth-user])]
    (fn []
      (if-not @user
        [re-com/throbber]
        [:div
         [menu @user]
         [:div.container-fluid
          [pages/page]]
         [:div.container
          [:img.img-responsive {:src "img/pata.svg" :alt "Nopova 3639/38  •  61500 Brno  •  info@maservis.cz"}]]]))))

(defn main []
  (hook-browser-navigation!)
  (if-let [node (.getElementById js/document "app")]
    (reagent/render [main-app-area] node)))

(main)
