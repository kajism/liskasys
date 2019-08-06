(ns liskasys.cljs.price-list
  (:require [clojure.string :as str]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc.util]
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

(def empty-price-list {:price-list/days-5 0
                       :price-list/days-4 0
                       :price-list/days-3 0
                       :price-list/days-2 0
                       :price-list/days-1 0
                       :price-list/half-day 0
                       :price-list/lunch 0
                       :price-list/lunch-adult 0})

(defn page-price-lists []
  (let [price-lists (re-frame/subscribe [:entities :price-list])
        user (re-frame/subscribe [:auth-user])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Ceník a platba"]
        [data-table
         :table-id :price-lists
         :rows price-lists
         :colls [[[re-com/h-box :gap "5px" :justify :end
                   :children
                   [[re-com/md-icon-button
                     :md-icon-name "zmdi-plus-square"
                     :tooltip "Přidat"
                     :on-click #(do
                                  (re-frame/dispatch [:entity-new :price-list empty-price-list])
                                  (set! js/window.location.hash "#/price-list/e"))]
                    [re-com/md-icon-button
                     :md-icon-name "zmdi-refresh"
                     :tooltip "Přenačíst ze serveru"
                     :on-click #(re-frame/dispatch [:entities-load :price-list])]]]
                  (fn [row]
                    [re-com/h-box
                     :gap "5px"
                     :children
                     [[re-com/hyperlink-href
                       :href (str "#/price-list/" (:db/id row) "e")
                       :label [re-com/md-icon-button
                               :md-icon-name "zmdi-edit"
                               :tooltip "Editovat"]]
                      (when (contains? (:-roles @user) "superadmin")
                        [buttons/delete-button :on-confirm #(re-frame/dispatch [:entity-delete :price-list (:db/id row)])])]])
                  :none]
                 ["Název" :price-list/label]
                 ["5 dní" (comp cljc.util/from-cents :price-list/days-5)]
                 ["4 dny" (comp cljc.util/from-cents :price-list/days-4)]
                 ["3 dny" (comp cljc.util/from-cents :price-list/days-3)]
                 ["2 dny" (comp cljc.util/from-cents :price-list/days-2)]
                 ["1 den" (comp cljc.util/from-cents :price-list/days-1)]
                 ["Půlden" (comp cljc.util/from-cents :price-list/half-day)]
                 ["Oběd dětský" (comp cljc.util/from-cents :price-list/lunch)]
                 ["Oběd dospělý" (comp cljc.util/from-cents :price-list/lunch-adult)]
                 ["Číslo účtu" :price-list/bank-account]
                 ["Splatnost do" :price-list/payment-due-date]
                 ["Číslo účtu pro obědy" :price-list/bank-account-lunches]]]]])))

(defn- from-cents [cents]
  (str (cljc.util/from-cents cents)))

(defn page-price-list []
  (let [price-list (re-frame/subscribe [:entity-edit :price-list])]
    (fn []
      (let [item @price-list]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Ceník a platba"]
          [re-com/label :label "Název ceníku"]
          [re-com/input-text
           :model (str (:price-list/label item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/label %])
           :width "200px"]
          [re-com/label :label "Měsíčně Kč za celotýdenní"]
          [re-com/input-text
           :model (from-cents (:price-list/days-5 item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/days-5 (cljc.util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "Měsíčně Kč za 4 dny z týdne"]
          [re-com/input-text
           :model (from-cents (:price-list/days-4 item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/days-4 (cljc.util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "Měsíčně Kč za 3 dny z týdne"]
          [re-com/input-text
           :model (from-cents (:price-list/days-3 item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/days-3 (cljc.util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "Měsíčně Kč za 2 dny z týdne"]
          [re-com/input-text
           :model (from-cents (:price-list/days-2 item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/days-2 (cljc.util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "Měsíčně Kč za 1 den z týdne"]
          [re-com/input-text
           :model (from-cents (:price-list/days-1 item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/days-1 (cljc.util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "1 půlden"]
          [re-com/input-text
           :model (from-cents (:price-list/half-day item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/half-day (cljc.util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "1 oběd dětský"]
          [re-com/input-text
           :model (from-cents (:price-list/lunch item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/lunch (cljc.util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "1 oběd dospělý"]
          [re-com/input-text
           :model (from-cents (:price-list/lunch-adult item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/lunch-adult (cljc.util/to-cents %)])
           :validation-regex #"^\d{0,4}$"
           :width "120px"]
          [re-com/label :label "Číslo účtu"]
          [re-com/input-text
           :model (str (:price-list/bank-account item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/bank-account %])
           :width "200px"]
          [re-com/label :label "Splatnost do (text v emailu např: 10 dnů nebo 20. dne tohoto měsíce)"]
          [re-com/input-text
           :model (str (:price-list/payment-due-date item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/payment-due-date %])
           :width "200px"]
          [re-com/label :label "Číslo účtu pro obědy (vyplňte jen když se liší od účtu pro platbu za docházku)"]
          [re-com/input-text
           :model (str (:price-list/bank-account-lunches item))
           :on-change #(re-frame/dispatch [:entity-change :price-list (:db/id item) :price-list/bank-account-lunches %])
           :width "200px"]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :price-list])]
            "nebo"
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/price-lists")]]]
          [history/view (:db/id item)]]]))))

(secretary/defroute "/price-lists" []
  (re-frame/dispatch [:set-current-page :price-lists]))
(pages/add-page :price-lists #'page-price-lists)

(secretary/defroute #"/price-list/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :price-list (cljc.util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :price-list]))
(pages/add-page :price-list #'page-price-list)
(common/add-kw-url :price-list "price-list")
