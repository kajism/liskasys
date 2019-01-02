(ns liskasys.cljs.pages
  (:require [clojure.string :as str]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.cljs.common :as common]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.ratom :as ratom]))

(def pages (atom {}))

(defn add-page [kw comp-fn]
  (swap! pages assoc kw comp-fn))

(re-frame/reg-sub
 :page-state
 (fn [db [_ page-id]]
   (get-in db [:page-states page-id])))

(re-frame/reg-event-db
 :page-state-set
 [common/debug-mw (re-frame/path :page-states)]
 (fn [page-states [_ page-id state]]
   (assoc page-states page-id state)))

(re-frame/reg-event-db
 :page-state-change
 [common/debug-mw (re-frame/path :page-states)]
 (fn [page-states [_ page-id key val]]
   ((if (fn? val) update-in assoc-in) page-states [page-id key] val)))

(re-frame/reg-sub
 :current-page
 (fn [db _]
   (:current-page db)))

(re-frame/reg-event-db
 :set-current-page
 [common/debug-mw (re-frame/path :current-page)]
 (fn [old-current-page [_ current-page]]
   current-page))

(re-frame/reg-sub
 :msg
 (fn [db [_ kw]]
   (get-in db [:msg kw])))

(re-frame/reg-event-db
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
