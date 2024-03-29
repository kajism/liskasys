(ns liskasys.cljs.core
  (:require [goog.events :as events]
            [goog.history.EventType :as EventType]
            [liskasys.cljs.ajax]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.pages :as pages]
            [liskasys.cljs.bank-holiday]
            [liskasys.cljs.billing-period]
            [liskasys.cljs.branch]
            [liskasys.cljs.class-register]
            [liskasys.cljs.daily-plan]
            [liskasys.cljs.group]
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
            [reagent.dom :as reagent-dom]
            [secretary.core :as secretary])
  (:import goog.History))

(re-frame/reg-sub
 :auth-user
 (fn [db [_]]
   (:auth-user db)))

(re-frame/reg-event-fx
 :load-auth-user
 common/debug-mw
 (fn [_ _]
   {:server-call {:req-msg [:user/auth {}]
                  :resp-evt [:set-auth-user]}}))

(re-frame/reg-event-db
 :set-auth-user
 [common/debug-mw (re-frame/path :auth-user)]
 (fn [_ [_ auth-user]]
   auth-user))

(re-frame/reg-event-fx
 :init-app
 common/debug-mw
 (fn [{:keys [db]} [_]]
   {:dispatch [:load-auth-user]
    :db (merge db
               {} ;;init db
               )}))

;; ---- Routes ---------------------------------------------------------------
(secretary/set-config! :prefix "#")

(secretary/defroute "/" []
  (re-frame/dispatch [:set-current-page :main]))

(defn hook-browser-navigation! []
  (doto (History.)
    (events/listen
     EventType/NAVIGATE
     (fn [event]
       (secretary/dispatch! (.-token ^js event))))
    (.setEnabled true)))

(defn menu [{roles :-roles styling :-styling :as user}]
  [:nav.navbar.navbar-default
   [:div.container-fluid
    [:div.navbar-header
     [:button.navbar-toggle {:type "button" :data-toggle "collapse" :data-target "#liskasys-navbar"}
      [:span.icon-bar]
      [:span.icon-bar]
      [:span.icon-bar]]
     (.log js/console styling)
     [:a {:href "#"}
      [:img {:src (str "/img/" (or (not-empty (:logo styling)) "logo_background.jpg")) :alt "LiškaSys" :height "60"}]]]
    [:div#liskasys-navbar.collapse.navbar-collapse
     [:ul.nav.navbar-nav
      [:li [:a {:href "#/persons"} "Lidé"]]
      [:li [:a {:href "#/daily-plans"} "Denní plány"]]
      #_[:li [:a {:href "#/class-registers"} "Třídní kniha"]]
      [:li [:a {:href "#/billing-periods"} "Platební období"]]
      [:li [:a {:href "#/lunch-menus"} "Jídelníček"]]
      [:li [:a {:href "#/lunch-orders"} "Oběd-návky"]]]
     [:ul.nav.navbar-nav.navbar-right
      (when (contains? roles "admin")
        [:li.dropdown
         [:a.dropdown-toggle {:data-toggle "dropdown" :href "#"}
          "Nastavení" [:span.caret]]
         [:ul.dropdown-menu
          [:li [:a {:href "#/price-lists"} "Ceník a platba"]]
          [:li [:a {:href "#/lunch-types"} "Diety"]]
          [:li [:a {:href "#/school-holidays"} "Prázdniny"]]
          [:li [:a {:href "#/bank-holidays"} "Státní svátky"]]
          [:li [:a {:href "#/branches"} "Pobočky"]]
          [:li [:a {:href "#/groups"} "Třídy"]]
          [:li [:a {:href "#/transactions"} "Transakce"]]
          [:li [:a {:href "#/configs"} "Základní nastavení"]]]])
      [:li
       [:a
        {:href "/logout"} "Odhlásit se"]]]]]])

(defn page-main []
  [:div
   [:h3 "LiškaSys"]])

(pages/add-page :main #'page-main)

(defn main-app-area [shadow]
  (let [user (re-frame/subscribe [:auth-user])
        configs (re-frame/subscribe [:entities :config])
        current-page (re-frame/subscribe [:current-page])]
    (fn []
      (if-not @user
        [re-com/throbber]
        [:div
         (when (and (:config/temp-closure? (first (vals @configs)))
                    (not= @current-page :config))
           [:div.alert.alert-danger "Objednávání obědů je pozastaveno viz. stránka Základní nastavení!!!"])
         [menu @user]
         [:div.container-fluid
          [pages/page]
          [:br]
          [:br]]]))))

(defn render []
  (when-let [node (.getElementById js/document "app")]
    (reagent-dom/render [main-app-area {:shadow (js/Date.now)}] node)))

(defn main []
  (hook-browser-navigation!)
  (re-frame/dispatch [:init-app])
  (render))

