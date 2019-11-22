(ns liskasys.cljs.billing-period
  (:require [clojure.set :refer [intersection]]
            [clojure.string :as str]
            [cljs-time.core :as t]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.comp.buttons :as buttons]
            [liskasys.cljs.comp.data-table :refer [data-table]]
            [liskasys.cljs.pages :as pages]
            [liskasys.cljs.person-bill :as person-bill]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [secretary.core :as secretary]))

(re-frame/reg-event-fx
 ::send-cmd
 common/debug-mw
 (fn [_ [_ period-id cmd bill-id]]
   {:server-call {:req-msg [(keyword "person-bill" cmd) {:person-bill/period period-id
                                                         :db/id bill-id}]
                  :resp-evt [::cmd-results period-id bill-id]}}))

(re-frame/reg-event-db
 ::cmd-results
 common/debug-mw
 (fn [db [_ period-id bill-id results]]
   (if bill-id
     (let [bill (first results)]
       (re-frame/dispatch [:entities-load :person {:db/id (get-in bill [:person-bill/person :db/id])}])
       (re-frame/dispatch [:entities-load :daily-plan {:daily-plan/bill (:db/id bill)}])
       (assoc-in db [:person-bill (:db/id bill)] bill))
     (do (re-frame/dispatch [:entities-set :person-bill [:entities-where :person-bill {:person-bill/period period-id}] results])
         db))))

(defn page-billing-periods []
  (let [billing-periods (re-frame/subscribe [:entities :billing-period])
        table-state (re-frame/subscribe [:table-state :billing-periods])
        user (re-frame/subscribe [:auth-user])]
    (fn []
      [re-com/v-box
       :children
       [[:h3 "Platební období"]
        [data-table
         :table-id :billing-periods
         :rows billing-periods
         :colls [[[re-com/h-box :gap "5px" :justify :end
                   :children
                   [[re-com/md-icon-button
                     :md-icon-name "zmdi-plus-square"
                     :tooltip "Přidat"
                     :on-click #(set! js/window.location.hash "#/billing-period/e")]
                    [re-com/md-icon-button
                     :md-icon-name "zmdi-refresh"
                     :tooltip "Přenačíst ze serveru"
                     :on-click #(re-frame/dispatch [:entities-load :billing-period])]]]
                  (fn [row]
                    (when (= (:db/id row) (:selected-row-id @table-state))
                      [re-com/h-box :gap "5px" :justify :end
                       :children
                       [[re-com/hyperlink-href
                         :href (str "#/billing-period/" (:db/id row) "e")
                         :label [re-com/md-icon-button
                                 :md-icon-name "zmdi-edit"
                                 :tooltip "Editovat"]]
                        (when (contains? (:-roles @user) "superadmin")
                          [buttons/delete-button :on-confirm #(re-frame/dispatch [:entity-delete :billing-period (:db/id row)]) :emphasise? true])]]))
                  :none]
                 ["Od" (comp cljc.util/yyyymm->text :billing-period/from-yyyymm)]
                 ["Do" (comp cljc.util/yyyymm->text :billing-period/to-yyyymm)]]
         :desc? true]]])))

(defn read-and-process-csv-file [js-file form-data published-bills bill-amount-fn result-key]
  (let [reader (js/FileReader.)]
    (set! (.. reader -onload)
          (fn [e]
            (let [lines (-> e .-target .-result (str/split #"\n"))
                  quotes? (str/starts-with? (first lines) "\"")
                  separator (if quotes?
                              #"\";\""
                              #";")
                  lines (cond->> lines
                          quotes?
                          (map #(subs % 1))
                          :always
                          (map #(str/trim %))
                          quotes?
                          (map #(subs % 0 (dec (count %))))
                          :always
                          (map #(str/split % separator)))
                  find-idx #(->> (first lines)
                                 (map-indexed vector)
                                 (some (fn [[idx column-name]]
                                         (when (str/index-of column-name %)
                                           idx))))
                  amount-idx (or (find-idx "Objem")
                                 (find-idx "stka v m")
                                 #_(find-idx (:amount-column-name @form-data)))
                  _ (println "amount-idx" amount-idx)
                  vs-idx (or (find-idx "VS")
                             (find-idx "Variabiln")
                             #_(find-idx (:vs-column-name @form-data)))
                  _ (println "vs-idx" vs-idx)
                  vs-amounts (->> lines
                                  (drop 1)
                                  (map (juxt #(some-> (get % vs-idx) (str/replace #"^0+" ""))
                                             #(get % amount-idx)))
                                  (set))
                  _ (println "vs-amounts" vs-amounts)
                  matched-bill-ids (->> published-bills
                                        (keep (fn [bill]
                                                (when (contains?
                                                       vs-amounts
                                                       [(str (some-> bill :person-bill/person :person/vs (str/replace #"^0+" "")))
                                                        (str (/ (bill-amount-fn bill) 100))])
                                                  (:db/id bill))))
                                        (set))]
              (swap! form-data assoc result-key matched-bill-ids))))
    (.readAsText reader js-file)))

(defn csv-import-dialog-markup [form-data published-bills process-ok process-cancel]
  (let [{:keys [matched-bill-ids matched-att-price-bill-ids matched-lunch-price-bill-ids]} @form-data
        matched-ids (or matched-bill-ids
                        (intersection matched-att-price-bill-ids matched-lunch-price-bill-ids))]
    [re-com/border
     :border "1px solid #eee"
     :child  [re-com/v-box
              :padding  "10px"
              :style    {:background-color "cornsilk"}
              :children [[re-com/title :label "Import CSV souborů s platbami" :level :level1]
                         [re-com/v-box
                          :class    "form-group"
                          :children [;; [:label {:for "amount-column-name"} "Název sloupce s částkou"]
                                     ;; [re-com/input-text
                                     ;;  :model       (:amount-column-name @form-data)
                                     ;;  :on-change   #(swap! form-data assoc :amount-column-name %)
                                     ;;  :class       "form-control"
                                     ;;  :attr        {:id "amount-column-name"}]
                                     ;; [:label {:for "vs-column-name"} "Název sloupce s variabilním symbolem"]
                                     ;; [re-com/input-text
                                     ;;  :model       (:vs-column-name @form-data)
                                     ;;  :on-change   #(swap! form-data assoc :vs-column-name %)
                                     ;;  :class       "form-control"
                                     ;;  :attr        {:id "vs-column-name"}]
                                     [:label {:for "csv-file"} "CSV soubor s celkovými platbami"]
                                     [:input {:id "csv-file"
                                              :type "file"
                                              :class "form-control"
                                              :on-change #(-> % .-target .-files (aget 0)
                                                              (read-and-process-csv-file form-data published-bills :person-bill/total :matched-bill-ids))}]
                                     [:label "----------------------- nebo oddělené platby za docházku a obědy -----------------------"]
                                     [:label {:for "csv-file1"} "CSV soubor s platbami za docházku"]
                                     [:input {:id "csv-file1"
                                              :type "file"
                                              :class "form-control"
                                              :on-change #(-> % .-target .-files (aget 0)
                                                              (read-and-process-csv-file form-data published-bills :person-bill/att-price :matched-att-price-bill-ids))}]
                                     [:label {:for "csv-file1"} "CSV soubor s platbami za obědy"]
                                     [:input {:id "csv-file2"
                                              :type "file"
                                              :class "form-control"
                                              :on-change #(-> % .-target .-files (aget 0)
                                                              (read-and-process-csv-file form-data published-bills (fn [{:person-bill/keys [total att-price]}] (- total att-price)) :matched-lunch-price-bill-ids))}]]]
                         [re-com/line :color "#ddd" :style {:margin "10px 0 10px"}]
                         (when (coll? matched-ids)
                           [re-com/title
                            :label (if (zero? (count matched-ids))
                                     "Dle částky a VS nespárovány žádné platby..."
                                     (str "Dle částky a VS spárováno " (count matched-ids) " plateb."))
                            :level :level3])
                         #_[:pre {:style {:width "600px"}} (pr-str @form-data)]
                         [re-com/h-box
                          :gap      "12px"
                          :children [[re-com/button
                                      :label    (str "Označit " (count matched-ids) " zaplacené!")
                                      :class    "btn-danger"
                                      :on-click #(process-ok matched-ids)
                                      :disabled? (zero? (count matched-ids))]
                                     [re-com/button
                                      :label    "Zrušit"
                                      :on-click process-cancel]]]]]]))

(defn select-paid-by-csv-dialog []
  (let [form-data (reagent/atom nil)
        _  (add-watch form-data :key (fn [k r os ns] (print k r os ns)))
        default-form-data {:show? true
                           :amount-column-name "Objem"
                           :vs-column-name "VS"}
        process-ok (fn [ids]
                     (doseq [db-id ids]
                       (re-frame/dispatch [::send-cmd nil "set-bill-as-paid" db-id]))
                     (reset! form-data nil)
                     false) ;; Prevent default "GET" form submission (if used)
        process-cancel (fn [event]
                         (reset! form-data nil)
                         false)]
    (fn [published-bills]
      [re-com/v-box
       :children [[re-com/button
                   :label "Zaplaceno dle CSV"
                   :class "btn-primary"
                   :on-click #(reset! form-data default-form-data)]
                  (when (:show? @form-data)
                    [re-com/modal-panel
                     :backdrop-color   "grey"
                     :backdrop-opacity 0.4
                     :child            [csv-import-dialog-markup
                                        form-data
                                        published-bills
                                        process-ok
                                        process-cancel]])]])))

(defn page-billing-period []
  (let [item-id (re-frame/subscribe [:entity-edit-id :billing-period])
        billing-period (re-frame/subscribe [:entity-edit :billing-period])
        person-bills (re-frame/subscribe [:entities-where :person-bill {:person-bill/period @item-id}])
        configs (re-frame/subscribe [:entities :config])]
    (fn []
      (let [item @billing-period]
        [re-com/v-box :gap "5px"
         :children
         [[:h3 "Platební období"]
          [re-com/label :label "Od - Do"]
          [re-com/h-box :gap "5px"
           :children
           [[re-com/input-text
             :model (str (:billing-period/from-yyyymm item))
             :on-change #(re-frame/dispatch [:entity-change :billing-period (:db/id item) :billing-period/from-yyyymm (cljc.util/parse-int %)])
             :validation-regex #"^\d{0,6}$"
             :width "120px"]
            "-"
            [re-com/input-text
             :model (str (:billing-period/to-yyyymm item))
             :on-change #(re-frame/dispatch [:entity-change :billing-period (:db/id item) :billing-period/to-yyyymm (cljc.util/parse-int %)])
             :validation-regex #"^\d{0,6}$"
             :width "120px"]
            "RRRRMM"]]
          [re-com/h-box :align :center :gap "5px"
           :children
           [[re-com/button :label "Uložit" :class "btn-success" :on-click #(re-frame/dispatch [:entity-save :billing-period])]
            "nebo"
            (when (:db/id item)
              [re-com/hyperlink-href :label [re-com/button :label "Nové"] :href (str "#/billing-period/e")])
            [re-com/hyperlink-href :label [re-com/button :label "Seznam"] :href (str "#/billing-periods")]]]
          (when (:db/id item)
            [:div
             [re-com/h-box :gap "5px"
              :children
              [[re-com/button
                :label "Vygenerovat rozpisy"
                :class "btn-danger"
                :on-click #(re-frame/dispatch [::send-cmd (:db/id item) "generate"])]
               (when (some #(= (get-in % [:person-bill/status :db/ident]) :person-bill.status/new) (vals @person-bills))
                 (let [{:config/keys [person-bill-email?]} (first (vals @configs))]
                   [re-com/button
                    :label (if person-bill-email?
                             "Zveřejnit nové a poslat emaily"
                             "Zveřejnit nové (emaily vypnuty)")
                    :class "btn-danger"
                    :on-click #(re-frame/dispatch [::send-cmd (:db/id item) "publish-all-bills"])]))
               (when (some #(= (get-in % [:person-bill/status :db/ident]) :person-bill.status/published) (vals @person-bills))
                 [select-paid-by-csv-dialog (->> @person-bills
                                                 (vals)
                                                 (filter #(= (get-in % [:person-bill/status :db/ident]) :person-bill.status/published)))])]]
             [person-bill/person-bills person-bills]])]]))))

(secretary/defroute "/billing-periods" []
  (re-frame/dispatch [:set-current-page :billing-periods]))
(pages/add-page :billing-periods #'page-billing-periods)

(secretary/defroute #"/billing-period/(\d*)(e?)" [id edit?]
  (re-frame/dispatch [:entity-set-edit :billing-period (cljc.util/parse-int id) (not-empty edit?)])
  (re-frame/dispatch [:set-current-page :billing-period]))
(pages/add-page :billing-period #'page-billing-period)
(common/add-kw-url :billing-period "billing-period")
