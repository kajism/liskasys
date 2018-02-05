(ns liskasys.cljs.config
  (:require [clojure.string :as str]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.comp.history :as history]
            [liskasys.cljs.pages :as pages]
            [liskasys.cljs.util :as util]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]))

(defn page-configs []
  (let [configs (re-frame/subscribe [:entities :config])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Základní nastavení"]
        (when-not (seq @configs)
          [re-com/hyperlink-href :label [re-com/button :label "Vytvořit"] :href (str "#/config/e")])
        [data-table
         :table-id :configs
         :rows configs
         :colls [[[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :config])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/config/" (:db/id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      #_[buttons/delete-button :on-confirm #(re-frame/dispatch [:entity-delete :config (:db/id row)])]]])
                  :none]
                 ["Název organizace" :config/org-name]
                 ["Celé URL" :config/full-url]
                 ["Odesilatel automatických emalů" :config/automat-email]]]]])))

(defn page-config []
  (let [config (re-frame/subscribe [:entity-edit :config])]
    (fn []
      (let [item @config]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Základní nastavení"]
          [re-com/label :label "Název organizace"]
          [re-com/input-text
           :model (str (:config/org-name item))
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/org-name %])
           :width "200px"]
          [re-com/label :label "Odkaz na web tohoto systému (celé URL)"]
          [re-com/input-text
           :model (str (:config/full-url item))
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/full-url %])
           :width "200px"]
          [re-com/label :label "Odesilatel automatických emalů (když neexistuje uživatel s rolí koordinátor)"]
          [re-com/input-text
           :model (str (:config/automat-email item))
           :on-change #(re-frame/dispatch [:entity-change :config (:db/id item) :config/automat-email %])
           :width "200px"]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :config])]
            "nebo"
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/configs")]]]
          [history/view (:db/id item)]]]))))

(secretary/defroute "/configs" []
  (re-frame/dispatch [:set-current-page :configs]))
(pages/add-page :configs #'page-configs)

(secretary/defroute #"/config/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :config (cljc-util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :config]))
(pages/add-page :config #'page-config)
(common/add-kw-url :config "config")
