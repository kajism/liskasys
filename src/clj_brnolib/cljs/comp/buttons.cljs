(ns clj-brnolib.cljs.comp.buttons
  (:require [reagent.core :as reagent]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [taoensso.timbre :as timbre]))

(defn button-with-confirmation [label confirm-query yes-evt position]
  (let [showing? (reagent/atom false)]
    (fn []
      [re-com/popover-anchor-wrapper
       :showing? showing?
       :position position
       :anchor [re-com/button
                :label label
                :on-click #(reset! showing? true)]
       :popover [re-com/popover-content-wrapper
                 :showing? showing?
                 :position position
                 :on-cancel #(reset! showing? false)
                 :body [re-com/v-box
                        :gap "10px"
                        :children [confirm-query
                                   [re-com/h-box
                                    :gap "5px"
                                    :children [[re-com/button
                                                :label "Ano"
                                                :on-click #(do
                                                             (re-frame/dispatch yes-evt)
                                                             (reset! showing? false))]
                                               [re-com/button
                                                :label "Ne"
                                                :on-click #(reset! showing? false)]]]]]]])))

(defn delete-button
  "Delete button with confirmation"
  [delete-evt position]
  (let [showing? (reagent/atom false)
        position (or position :below-left)]
    (fn []
      [re-com/popover-anchor-wrapper
       :showing? showing?
       :position position
       :anchor [re-com/md-icon-button
                :md-icon-name "zmdi-delete"
                :tooltip "Smazat"
                :on-click #(reset! showing? true)]
       :popover [re-com/popover-content-wrapper
                 :showing? showing?
                 :position position
                 :on-cancel #(reset! showing? false)
                 :body [re-com/v-box
                        :gap "10px"
                        :children ["Opravdu smazat tuto položku?"
                                   [re-com/h-box
                                    :gap "5px"
                                    :align :end
                                    :children [[re-com/button
                                                :label "Ano"
                                                :on-click #(do
                                                             (delete-evt)
                                                             (reset! showing? false))]
                                               [re-com/button
                                                :label "Ne"
                                                :on-click #(reset! showing? false)]]]]]]])))
