(ns liskasys.cljs.user
  (:require [liskasys.cljs.common :as common]
            [liskasys.cljs.pages :as pages]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]))

(re-frame/register-handler
 ::save
 common/debug-mw
 (fn [db [_ reply]]
   (timbre/debug "test reply" reply)
   db))

(re-frame/register-handler
 ::saved
 common/debug-mw
 (fn [db [_ reply]]
   (timbre/debug "saved" reply)
   db))

(defn page-users []
  [:div
   [:h3 "Uživatelé"]])

(secretary/defroute "/users" []
  (re-frame/dispatch [:set-current-page :users]))

(pages/add-page :users #'page-users)
