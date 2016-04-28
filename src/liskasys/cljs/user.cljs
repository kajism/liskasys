(ns liskasys.cljs.user
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

(defn page-users []
  (let [users (re-frame/subscribe [:entities :user])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Uživatelé"]
        [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/user/e")]
        [data-table
         :table-id :users
         :rows @users
         :colls [["Příjmení" :lastname]
                 ["Jméno" :firstname]
                 ["Email" :email]
                 ["Telefon" :phone]
                 [[re-com/md-icon-button
                   :md-icon-name "zmdi-refresh"
                   :tooltip "Přenačíst ze serveru"
                   :on-click #(re-frame/dispatch [:entities-load :user])]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/user/" (:id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      [buttons/delete-button #(re-frame/dispatch [:entity-delete :user (:id row)])]]])
                  :none]]]]])))

(defn page-user []
  (let [user (re-frame/subscribe [:entity-edit :user])
        validation-fn #(cond-> {}
                         (str/blank? (:firstname %))
                         (assoc :firstname "Vyplňte správně jméno")
                         (str/blank? (:lastname %))
                         (assoc :lastname "Vyplňte správně příjmení")
                         (not (validation/valid-email? (:email %)))
                         (assoc :email "Vyplňte správně emailovou adresu")
                         true
                         timbre/spy)]
    (fn []
      (let [item @user
            errors (:-errors item)]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Uživatel"]
          [re-com/label :label "Příjmení"]
          [input-text item :user :lastname]
          [re-com/label :label "Jméno"]
          [input-text item :user :firstname]
          [re-com/label :label "Email"]
          [input-text item :user :email]
          [re-com/label :label "Telefon"]
          [input-text item :user :phone]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :user validation-fn])]
            "nebo"
            [re-com/hyperlink-href :label [re-com/button :label "Nový"] :href (str "#/user/e")]
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/users")]]]]]))))

(secretary/defroute "/users" []
  (re-frame/dispatch [:set-current-page :users]))
(pages/add-page :users #'page-users)

(secretary/defroute #"/user/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :user (util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :user]))
(pages/add-page :user #'page-user)
(common/add-kw-url :user "user")
