(ns liskasys.cljs.audit
  (:require [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.comp.input-text :refer [input-text]]
            [liskasys.cljs.util :as util]
            [liskasys.cljc.time :as time]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as str]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [reagent.ratom :as ratom]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]))

(re-frame/register-sub
 ::audits
 (fn [db [_]]
   (let [table-state (re-frame/subscribe [:table-state :audits])]
     (ratom/reaction
      @(re-frame/subscribe [:entities-where :audit @table-state])))))

(defn page-audits []
  (let [audits (re-frame/subscribe [::audits])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Transakce"]
        [data-table
         :table-id :audits
         :rows @audits
         :colls [["Kdy" :db/txInstant]
                 ["Kdo" :tx/person]
                 ["Co"]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :audit])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/audit/" (:db/id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      [buttons/delete-button #(re-frame/dispatch [:entity-delete :audit (:db/id row)])]]])
                  :csv-export]]
         :desc? true]]])))

(defn page-audit []
  (let [audit (re-frame/subscribe [:entity-edit :audit])
        noop-fn #()]
    (fn []
      (let [item @audit
            errors (:-errors item)]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Detail transakce"]
          [re-com/label :label "Datum"]
          [re-com/input-text
           :model (time/to-format (:audit/date item) time/ddMMyyyy)
           :disabled? true
           :on-change noop-fn]
          [re-com/label :label "Počet obědů"]
          [re-com/input-text
           :model (str (:audit/total item))
           :disabled? true
           :on-change noop-fn]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/audits")]]]]]))))

(secretary/defroute "/audits" []
  (re-frame/dispatch [:set-current-page :audits]))
(pages/add-page :audits #'page-audits)

(secretary/defroute #"/audit/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :audit (util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :audit]))
(pages/add-page :audit #'page-audit)
(common/add-kw-url :audit "audit")
