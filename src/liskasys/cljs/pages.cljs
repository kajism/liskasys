(ns liskasys.cljs.pages
  (:require [clojure.string :as str]
            [liskasys.cljs.common :as common]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.ratom :as ratom]))

(def pages (atom {}))

(defn add-page [kw comp-fn]
  (swap! pages assoc kw comp-fn))

(re-frame/register-sub
 :page-state
 (fn [db [_ page-id]]
   (ratom/reaction (get-in @db [:page-states page-id]))))

(re-frame/register-handler
 :page-state-set
 common/debug-mw
 (fn [db [_ page-id state]]
   (assoc-in db [:page-states page-id] state)))

(re-frame/register-handler
 :page-state-change
 common/debug-mw
 (fn [db [_ page-id key val]]
   ((if (fn? val) update-in assoc-in) db [:page-states page-id key] val)))

(re-frame/register-sub
 :current-page
 (fn [db _]
   (ratom/reaction (:current-page @db))))

(re-frame/register-handler
 :set-current-page
 common/debug-mw
 (fn [db [_ current-page]]
   (assoc db :current-page current-page)))

(re-frame/register-sub
 :msg
 (fn [db [_ kw]]
   (ratom/reaction (get-in @db [:msg kw]))))

(re-frame/register-handler
 :set-msg
 common/debug-mw
 (fn [db [_ kw msg rollback-db]]
   (when (and msg (= :info kw))
     (js/setTimeout #(re-frame/dispatch [:set-msg :info nil]) 1000))
   (let [db (or rollback-db db)]
     (assoc-in db [:msg kw] msg))))

(defn error-msg-popup [title msg on-close]
  [re-com/modal-panel
   :backdrop-on-click on-close
   :child [re-com/alert-box
           :alert-type :danger
           :closeable? true
           :heading title
           :body msg
           :on-close on-close]])

(defn page []
  (let [current-page (re-frame/subscribe [:current-page])
        error-msg (re-frame/subscribe [:msg :error])
        info-msg (re-frame/subscribe [:msg :info])
        user (re-frame/subscribe [:auth-user])]
    (fn []
      [:div
       (when-not (str/blank? @info-msg)
         [re-com/alert-box
          :alert-type :info
          :body @info-msg
          :style {:position "fixed" :top "70px"}])
       (when-not (str/blank? @error-msg)
         [error-msg-popup "Chyba" @error-msg #(re-frame/dispatch [:set-msg :error nil])])
       (if-not (and @current-page @user)
         [re-com/throbber]
         [(get @pages @current-page)])])))
