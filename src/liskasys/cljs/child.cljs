(ns liskasys.cljs.child
  (:require [clj-brnolib.cljs.comp.buttons :as buttons]
            [clj-brnolib.cljs.comp.data-table :refer [data-table]]
            [clj-brnolib.cljs.util :as util]
            [clj-brnolib.validation :as validation]
            [cljs.pprint :refer [pprint]]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]
            [reagent.core :as reagent]
            [clojure.string :as str]
            [clj-brnolib.cljs.comp.input-text :refer [input-text]]))

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

(defn page-children []
  (let [children (re-frame/subscribe [:entities :child])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Děti"]
        [re-com/hyperlink-href :label [re-com/button :label "Nové"] :href (str "#/child/e")]
        [data-table
         :table-id :children
         :rows @children
         :colls [["Jméno" :firstname]
                 ["Příjmení" :lastname]
                 ["Variabilní symbol" :var-symbol]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :child])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/child/" (:id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      [buttons/delete-button #(re-frame/dispatch [:entity-delete :child (:id row)])]]])
                  :none]]]]])))

(defn page-child []
  (let [child (re-frame/subscribe [:entity-edit :child])
        validation-fn #(cond-> {}
                         (str/blank? (:firstname %))
                         (assoc :firstname "Vyplňte správně jméno")
                         (str/blank? (:lastname %))
                         (assoc :lastname "Vyplňte správně příjmení")
                         (not (:var-symbol %))
                         (assoc :var-symbol "Vyplňte správně celočíselný variabilní symbol")
                         true
                         timbre/spy)]
    (fn []
      (let [item @child
            errors (:-errors item)]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Dítě"]
          [re-com/label :label "Jméno"]
          [input-text item :child :firstname]
          [re-com/label :label "Příjmení"]
          [input-text item :child :lastname]
          [re-com/label :label "Variabilní symbol"]
          [input-text item :child :var-symbol util/parse-int]
          [re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :child validation-fn])]
          [:pre (with-out-str (pprint item))]]]))))

(secretary/defroute "/children" []
  (re-frame/dispatch [:set-current-page :children]))
(pages/add-page :children #'page-children)

(secretary/defroute #"/child/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :child (util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :child]))
(pages/add-page :child #'page-child)
(common/add-kw-url :child "child")
