(ns liskasys.cljs.user
  (:require [clj-brnolib.cljs.comp.buttons :as buttons]
            [clj-brnolib.cljs.comp.data-table :refer [data-table]]
            [clj-brnolib.cljs.util :as util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.pages :as pages]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [secretary.core :as secretary]
            [taoensso.timbre :as timbre]
            [cljs.pprint :refer [pprint]]))

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
         :colls [["Jméno" :firstname]
                 ["Příjmení" :lastname]
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
  (let [user (re-frame/subscribe [:entity-edit :user])]
    (fn []
      (let [item @user]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Uživatel"]
          [re-com/label :label "Jméno"]
          [re-com/input-text
           :model (str (:firstname item))
           :on-change #(re-frame/dispatch [:entity-change :user (:id item) :firstname %])]
          [re-com/label :label "Příjmení"]
          [re-com/input-text
           :model (str (:lastname item))
           :on-change #(re-frame/dispatch [:entity-change :user (:id item) :lastname %])]
          [re-com/label :label "Email"]
          [re-com/input-text
           :model (str (:email item))
           :on-change #(re-frame/dispatch [:entity-change :user (:id item) :email %])]
          [re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :user])]
          [:pre (with-out-str (pprint item))]]]))))

(secretary/defroute "/users" []
  (re-frame/dispatch [:set-current-page :users]))
(pages/add-page :users #'page-users)

(secretary/defroute #"/user/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :user (util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :user]))
(pages/add-page :user #'page-user)
(common/add-kw-url :user "user")
