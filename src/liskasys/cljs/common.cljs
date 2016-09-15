(ns liskasys.cljs.common
  (:require [liskasys.cljc.schema :as schema]
            [liskasys.cljs.ajax :refer [server-call]]
            [clj-brnolib.cljs.util :as util]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.ratom :as ratom]
            [taoensso.timbre :as timbre]
            [schema.core :as s]))

(def debug-mw [(when ^boolean goog.DEBUG re-frame/debug)
               (when ^boolean goog.DEBUG (re-frame/after
                                          #(if-let [res (s/check schema/AppDb %)]
                                             (.error js/console (str "schema problem: " res)))))])

(defonce kw->url (atom {}))

(defn add-kw-url [kw url]
  (swap! kw->url assoc kw url))

;;---- Subscriptions--------------------------------------------
(re-frame/register-sub
 :entities
 (fn [db [_ kw]]
   (let [out (ratom/reaction (get @db kw))]
     (when (nil? @out)
       (re-frame/dispatch [:entities-load kw]))
     out)))

(re-frame/register-sub
 :entities-where
 (fn [db [_ kw where-m]]
   (let [out (ratom/reaction (get-in @db [:entities-where kw where-m]))]
     (when (nil? @out)
       (re-frame/dispatch [:entities-load kw where-m]))
     out)))

(re-frame/register-sub
 :entity-edit-id
 (fn [db [_ kw]]
   (ratom/reaction (get-in @db [:entity-edit kw :db/id]))))

(re-frame/register-sub
 :entity-edit
 (fn [db [_ kw]]
   (let [id (re-frame/subscribe [:entity-edit-id kw])
         ents (re-frame/subscribe [:entities kw])]
     (ratom/reaction (get @ents @id)))))

(re-frame/register-sub
 :entity-edit?
 (fn [db [_ kw]]
   (ratom/reaction (get-in @db [:entity-edit kw :edit?]))))

;;---- Handlers -----------------------------------------------
(re-frame/register-handler
 :entities-load
 debug-mw
 (fn [db [_ kw where-m]]
   (server-call [(keyword (name kw) "select") (or where-m {})]
                [:entities-set (if-not where-m
                                 [kw]
                                 [:entities-where kw where-m])])
   db))

(re-frame/register-handler
 :entities-set
 debug-mw
 (fn [db [_ path v]]
   (assoc-in db path (into {} (map (juxt :db/id identity)
                                   v)))))

(re-frame/register-handler
 :entity-set-edit
 debug-mw
 (fn [db [_ kw id edit?]]
   (assoc-in db [:entity-edit kw] {:db/id id
                                   :edit? (boolean edit?)})))

(re-frame/register-handler
 :entity-new
 debug-mw
 (fn [db [_ kw new-ent]]
   (if new-ent
     (assoc-in db [kw nil] new-ent)
     (update db kw dissoc nil))))

(re-frame/register-handler
 :entity-change
 debug-mw
 (fn [db [_ kw id attr val]]
   (if (fn? val)
     (update-in db [kw id attr] val)
     (assoc-in db [kw id attr] val))))

(re-frame/register-handler
 :entity-save
 debug-mw
 (fn [db [_ kw validation-fn ent-id]]
   (let [id (or ent-id (get-in db [:entity-edit kw :db/id]))
         ent (get-in db [kw id])
         errors (when validation-fn (validation-fn ent))
         file (:-file ent)]
     (if (empty? errors)
       (server-call (timbre/spy [(keyword (name kw) "save") (util/dissoc-temp-keys ent)])
                    file
                    [:entity-saved kw])
       (timbre/debug "validation errors" errors))
     (assoc-in db [kw id :-errors] errors))))

(re-frame/register-handler
 :entity-saved
 debug-mw
 (fn [db [_ kw new-ent]]
   (re-frame/dispatch [:set-msg :info "Záznam byl uložen"])
   (when (get @kw->url kw)
     (set! js/window.location.hash (str "#/" (get @kw->url kw) "/" (:db/id new-ent) "e")))
   (-> db
       (assoc-in [kw (:db/id new-ent)] new-ent)
       (update kw #(dissoc % nil)))))

(re-frame/register-handler
 :entity-delete
 debug-mw
 (fn [db [_ kw id]]
   (when id
     (server-call [(keyword (name kw) "delete") id] nil nil db))
   (update db kw #(dissoc % id))))

(re-frame/register-handler
 :file-delete
 debug-mw
 (fn [db [_ kw parent-id file-id]]
   (when file-id
     (server-call [(keyword (name kw) "delete") file-id] nil nil db))
   (update-in db [kw parent-id :file/_parent] #(filterv (fn [file] (not= file-id (:db/id file))) %))))
