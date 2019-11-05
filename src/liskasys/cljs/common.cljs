(ns liskasys.cljs.common
  (:require [liskasys.cljc.util :as cljc.util]
            [liskasys.cljs.util :as util]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.ratom :as ratom]
            [taoensso.timbre :as timbre]
            [clojure.string :as str]))

(def debug-mw [(when ^boolean goog.DEBUG re-frame/debug)
               #_(when ^boolean goog.DEBUG (re-frame/after
                                          #(if-let [res (s/check schema/AppDb %)]
                                             (.error js/console (str "schema problem: " res)))))])

(timbre/set-level! (if ^boolean goog.DEBUG :debug :info))

(defonce kw->url (atom {}))

(defn add-kw-url [kw url]
  (swap! kw->url assoc kw url))

;;---- Subscriptions--------------------------------------------
(re-frame/reg-sub-raw
 :entities
 (fn [db [_ kw]]
   (let [out (ratom/reaction (get @db kw))]
     (when (nil? @out)
       (re-frame/dispatch [:entities-load kw {} true]))
     out)))

(re-frame/reg-sub-raw
 :entities-where
 (fn [db [_ kw where-m]]
   (let [ents (ratom/reaction (get @db kw))
         ids (ratom/reaction (get-in @db [:entities-where kw where-m]))]
     (when (nil? @ids)
       (re-frame/dispatch [:entities-load kw where-m true]))
     (ratom/reaction
      (select-keys @ents @ids)))))

(re-frame/reg-sub
 :entity-edit-id
 (fn [db [_ kw]]
   (get-in db [:entity-edit kw :db/id])))

(re-frame/reg-sub
 :new-ents
 (fn [db [_ kw]]
   (get-in db [:new-ents kw])))

(re-frame/reg-sub
 :entity-edit
 (fn [[_ kw] ]
   [(re-frame/subscribe [:entity-edit-id kw])
    (re-frame/subscribe [:entities kw])
    (re-frame/subscribe [:new-ents kw])])
 (fn [[id ents new-ent] [_ kw]]
   (if id
     (get ents id)
     new-ent)))

(re-frame/reg-sub
 :entity-edit?
 (fn [db [_ kw]]
   (get-in db [:entity-edit kw :edit?])))

;;---- Handlers -----------------------------------------------
(re-frame/reg-event-fx
 :entities-load
 debug-mw
 (fn [{:keys [db]} [_ kw where-m missing-only?]]
   (if (and missing-only?
            (get-in db (if (not-empty where-m)
                         [:entities-where kw where-m]
                         [kw])))
     {}
     {:server-call {:req-msg [(keyword (name kw) "select") (or where-m {})]
                    :resp-evt [:entities-set kw (if (not-empty where-m)
                                                  [:entities-where kw where-m]
                                                  [kw])]}
      :db (assoc-in db (if (not-empty where-m)
                         [:entities-where kw where-m]
                         [kw]) {})})))

(re-frame/reg-event-db
 :entities-set
 debug-mw
 (fn [db [_ kw path v]]
   (let [ent-by-id (into {} (map (juxt :db/id identity) v))]
     (if (= :entities-where (first path))
       (-> db
           (assoc-in path (set (map :db/id v)))
           (update kw #(merge % ent-by-id)))
       (assoc db kw ent-by-id)))))

(re-frame/reg-event-db
 :entity-set-edit
 debug-mw
 (fn [db [_ kw id edit?]]
   (assoc-in db [:entity-edit kw] {:db/id id
                                   :edit? (boolean edit?)})))

(re-frame/reg-event-db
 :entity-new
 debug-mw
 (fn [db [_ kw new-ent]]
   (if new-ent
     (assoc-in db [:new-ents kw] new-ent)
     (update db :new-ents dissoc kw))))

(re-frame/reg-event-db
 :entity-change
 debug-mw
 (fn [db [_ kw id attr val]]
   ((if (fn? val) update-in assoc-in)
    db
    (if id [kw id attr] [:new-ents kw attr])
    (cond-> val
      (string? val)
      (-> (str/replace #"\n" "#@")
          (str/trim)
          (str/replace #"#@" "\n"))))))

(re-frame/reg-event-fx
 :entity-save
 debug-mw
 (fn [{:keys [db]} [_ kw validation-fn ent-id]]
   (let [id (or ent-id (get-in db [:entity-edit kw :db/id]))
         ent (if id (get-in db [kw id]) (get-in db [:new-ents kw]))
         errors (when validation-fn (validation-fn ent))
         file (:-file ent)]
     (cond-> {:db (assoc-in db (if id [kw id :-errors] [:new-ents kw :-errors]) errors)}
       (empty? errors)
       (assoc :server-call {:req-msg [(keyword (name kw) "save") (cljc.util/dissoc-temp-keys ent)]
                            :file file
                            :resp-evt [:entity-saved kw]})))))

(re-frame/reg-event-fx
 :entity-saved
 debug-mw
 (fn [{:keys [db]} [_ kw new-ent]]
   (when (get @kw->url kw)
     (set! js/window.location.hash (str "#/" (get @kw->url kw) "/" (:db/id new-ent) "e")))
   {:db (-> db
            (assoc-in [kw (:db/id new-ent)] new-ent)
            (update :new-ents #(dissoc % kw)))
    :dispatch [:set-msg :info "Záznam byl uložen"]}))

(re-frame/reg-event-fx
 :entity-delete
 debug-mw
 (fn [{:keys [db]} [_ kw id after-delete]]
   (cond->
       {:db (-> db
                (update kw #(dissoc % id))
                (update-in [:entities-where kw] (fn [wm]
                                                  (reduce
                                                   (fn [out [k v]]
                                                     (assoc out k (disj v id)))
                                                   wm
                                                   wm))))}
     (some? id)
     (assoc :server-call {:req-msg [(keyword (name kw) "delete") id]
                          :resp-evt after-delete
                          :rollback-db db}))))

(re-frame/reg-event-fx
 :file-delete
 debug-mw
 (fn [{:keys [db]} [_ kw parent-id file-id]]
   (cond->
       {:db (update-in db [kw parent-id :file/_parent] #(filterv (fn [file] (not= file-id (:db/id file))) %))}
     (some? file-id)
     (assoc :server-call {:req-msg [(keyword (name kw) "delete") file-id]
                          :rollback-db db}))))

(re-frame/reg-event-fx
 :common/retract-ref-many
 debug-mw
 (fn [{:keys [db]} [_ kw retract-attr]]
   {:server-call {:req-msg [:entity/retract-attr retract-attr]
                  :rollback-db db}
    :db (update-in db [kw (:db/id retract-attr)]
                   (fn [ent]
                     (reduce (fn [ent [attr-kw attr-val]]
                               (cond-> ent
                                 (vector? (get ent attr-kw))
                                 (update attr-kw #(filterv (fn [x] (not (= (:db/id x) attr-val))) %))))
                             ent
                             (dissoc retract-attr :db/id))))}))

(re-frame/reg-sub
 ::path-value
 (fn [db [_ path]]
   (assert (seqable? path) "Path should be a vector!")
   (get-in db path)))

(re-frame/reg-event-db
 ::set-path-value
 debug-mw
 (fn [db [_ path value]]
   (assert (seqable? path) "Path should be a vector!")
   (if (and (fn? value)
            (not (keyword? value)))
     (update-in db path value)
     (assoc-in db path value))))
