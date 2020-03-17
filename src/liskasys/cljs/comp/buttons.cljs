(ns liskasys.cljs.comp.buttons
  (:require [reagent.core :as reagent]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [taoensso.timbre :as timbre]))

(defn button-with-confirmation []
  (let [showing? (reagent/atom false)]
    (fn [label confirm-question yes-evt position]
      [re-com/popover-anchor-wrapper
       :showing? showing?
       :position position
       :anchor [re-com/button
                :label label
                :on-click #(reset! showing? true)]
       :popover [re-com/popover-content-wrapper
                 :on-cancel #(reset! showing? false)
                 :body [re-com/v-box
                        :gap "10px"
                        :children [confirm-question
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
  [& {:keys [on-confirm position emphasise?] :or {position :below-right}}]
  (let [showing? (reagent/atom false)
        user (re-frame/subscribe [:auth-user])]
    (fn []
      (when (contains? (:-roles @user) "admin")
        [re-com/popover-anchor-wrapper
         :showing? showing?
         :position position
         :anchor [re-com/md-icon-button
                  :md-icon-name "zmdi-delete"
                  :tooltip "Smazat"
                  :emphasise? emphasise?
                  :on-click #(reset! showing? true)]
         :popover [re-com/popover-content-wrapper
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
                                                               (on-confirm)
                                                               (reset! showing? false))]
                                                 [re-com/button
                                                  :label "Ne"
                                                  :on-click #(reset! showing? false)]]]]]]]))))
