(ns liskasys.cljs.comp.data-table
  (:require [clojure.string :as str]
            [cognitect.transit :as transit]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc-util]
            [liskasys.cljs.common :as common]
            [liskasys.cljs.util :as util]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [reagent.ratom :as ratom]
            [taoensso.timbre :as timbre]))

(re-frame/register-sub
 :table-state
 (fn [db [_ table-id]]
   (ratom/reaction (get-in @db [:table-states table-id]))))

(defn show-date [value]
  (time/to-format value time/ddMMyyyy)
  #_(let [s (time/to-format value time/ddMMyyyyHHmm)]
    (cond-> s
      (str/ends-with? s " 00:00")
      (subs 0 (- (count s) 6)))))

(re-frame/register-sub
 :table-rows
 (fn [db [_ table-id colls] [orig-rows]]
   (let [state (re-frame/subscribe [:table-state table-id])
         rows  (ratom/reaction
                ((if (map? orig-rows)
                   vals
                   identity)
                 orig-rows))
         sort-key-fn* (ratom/reaction
                       (nth (get colls (:order-by @state)) 1))
         sort-fns (ratom/reaction
                   (let [sort-key-fn (when @sort-key-fn*
                                       (cond->> @sort-key-fn*
                                         (transit/bigdec? (some-> @rows first (@sort-key-fn*)))
                                         (comp util/parse-float #(when % (.-rep %)))
                                         (vector? (some-> @rows first (@sort-key-fn*)))
                                         (comp util/hiccup->string)))]
                     [sort-key-fn
                      (if (and sort-key-fn (string? (some-> @rows first sort-key-fn)))
                        util/sort-by-locale
                        sort-by)]))
         sorted-rows (ratom/reaction
                      #_(timbre/debug "Sorting ...")
                      (let [[sort-key-fn sort-fn]  @sort-fns]
                        (if (and sort-fn sort-key-fn)
                          (sort-fn (comp util/href->str sort-key-fn) @rows)
                          @rows)))
         search-colls (ratom/reaction
                       (:search-colls @state))
         filtered-rows (ratom/reaction
                        #_(timbre/debug "Filtering ...")
                        (reduce
                         (fn [out coll-idx]
                           (let [s (some-> (get @search-colls coll-idx) str str/lower-case)
                                 f (nth (get colls coll-idx) 1)]
                             (if (str/blank? s)
                               out
                               (filter
                                (fn [row]
                                  (let [v (f row)
                                        v (cond
                                            (boolean? v)
                                            (cljc-util/boolean->text v)
                                            (= js/Date (type v))
                                            (show-date v)
                                            :else
                                            (str v))]
                                    (-> v str/lower-case (str/index-of s))))
                                out))))
                         @sorted-rows
                         (keys colls)))
         row-from (ratom/reaction
                   (* (or (:rows-per-page @state) 0) (:page-no @state)))
         row-to (ratom/reaction
                 (if (:rows-per-page @state)
                   (min (+ @row-from (:rows-per-page @state)) (count @filtered-rows))
                   (count @filtered-rows)))
         desc? (ratom/reaction
                (:desc? @state))
         final-rows (ratom/reaction
                     (->> (cond-> @filtered-rows
                            @desc?
                            reverse)
                          (drop @row-from)
                          (take (- @row-to @row-from))))]
     (ratom/reaction
      {:final-rows @final-rows
       :filtered-rows @filtered-rows
       :row-from @row-from
       :row-to @row-to}))))

(re-frame/register-handler
 :table-state-set
 common/debug-mw
 (fn [db [_ table-id state]]
   (assoc-in db [:table-states table-id] state)))

(re-frame/register-handler
 :table-state-change
 ;;common/debug-mw
 (fn [db [_ table-id key val]]
   ((if (fn? val) update-in assoc-in) db [:table-states table-id key] val)))

(defn checked-ids [db table-id]
  (keep (fn [[id {ch? :checked?}]]
          (when ch? id))
        (get-in db [:table-states table-id :row-states])))

(defn make-csv [rows colls]
  (let [colls (->> (vals colls)
                   (remove (fn [[label f modifier]]
                             (#{:none :csv-export} modifier))))]
    (str (str/join ";" (map first colls)) "\n"
         (apply str
                (for [row rows]
                  (str (str/join ";" (map #(% row)
                                          (map second colls))) "\n"))))))

(defn td-comp [value last?]
  [:td {:class (str #_"text-nowrap"
                    (when last? " buttons")
                    (when (or (number? value) (transit/bigdec? value)) " text-right"))}
   (cond
     (or (string? value) (vector? value)) value
     (= js/Date (type value)) (show-date value)
     (number? value) (cljc-util/money->text value)
     (transit/bigdec? value) (cljc-util/money->text (cljc-util/parse-int (.-rep value)))
     (= (type value) js/Boolean) (cljc-util/boolean->text value)
     :else (str value))])

(defn tr-comp [colls row change-state-fn]
  (let [on-enter #(change-state-fn :selected-row-id (:db/id row))
        last-idx (dec (count colls))]
    [:tr {:on-mouse-enter on-enter}
     #_(when row-checkboxes?
         [:td [re-com/checkbox
               :model (get-in @state [:row-states (:db/id row) :checked?])
               :on-change #(change-state-fn :row-states
                                            (fn [row-states]
                                              (update row-states (:db/id row) update :checked? not)))]])
     (doall
      (for [[coll-idx [_ f _]] colls
            :let [value (f row)]]
        ^{:key (str (:db/id row) "-" coll-idx)}
        [td-comp value (= coll-idx last-idx)]))]))

(defn data-table [& {:keys [table-id order-by desc? rows-per-page row-checkboxes? rows colls] :as args}]
  (let [order-by (or order-by 0)
        colls (into {} (->> colls
                            (keep identity)
                            (map-indexed vector)))
        init-state {:order-by order-by
                    :desc? (or desc? false)
                    :search-all ""
                    :search-colls {}
                    :rows-per-page (or rows-per-page 50)
                    :page-no 0}
        state (if table-id
                (re-frame/subscribe [:table-state table-id])
                (reagent/atom init-state))
        table-rows (re-frame/subscribe [:table-rows table-id colls] [rows])
        table-name (if table-id (name table-id) (str "data-table" (rand-int 1000)))
        all-checked? (reagent/atom false)
        change-state-fn (if table-id
                          (fn [key val] (re-frame/dispatch [:table-state-change table-id key val]))
                          (fn [key val] (swap! state (if (fn? val) update assoc) key val)))
        on-click-order-by #(do
                             (if (= (:order-by @state) %)
                               (change-state-fn :desc? not)
                               (do
                                 (change-state-fn :order-by %)
                                 (change-state-fn :desc? false)))
                             (change-state-fn :page-no 0))
        on-change-rows-per-page (fn [evt]
                                  (change-state-fn :rows-per-page (cljc-util/parse-int (-> evt .-target .-value)))
                                  (change-state-fn :page-no 0))
        on-change-search-all (fn [evt]
                               (change-state-fn :search-all (-> evt .-target .-value))
                               (change-state-fn :page-no 0))
        search-colls (reagent/atom {})
        on-change-search-colls (fn []
                                 (change-state-fn :search-colls #(merge % @search-colls))
                                 (change-state-fn :page-no 0))]
    (add-watch search-colls :search-colls
               (fn [_ _ _ new-state]
                 (js/setTimeout #(when (= new-state @search-colls)
                                   (on-change-search-colls))
                                250)))
    (if @state
      (reset! search-colls (:search-colls @state))
      (re-frame/dispatch [:table-state-set table-id init-state]))
    (fn data-table-render []
      (if-not @state
        [re-com/throbber]
        [:div.data-table-component
         [:table.table.tree-table.table-hover.table-striped
          [:thead
           [:tr
            #_(when row-checkboxes?
                [:th [re-com/checkbox
                      :model all-checked?
                      :on-change #(let [new-val (swap! all-checked? not)]
                                    (change-state-fn :row-states
                                                     (fn [row-states]
                                                       (into {} (map (fn [{id :db/id}]
                                                                       [id (assoc (get row-states id) :checked? new-val)])
                                                                     rows)))))]])
            (doall
             (for [[coll-idx [label f header-modifier]] colls]
               ^{:key coll-idx}
               [:th.text-nowrap
                (when (or (= :filter header-modifier) (not header-modifier))
                  [:input.form-control
                   {:type "text"
                    :value (str (get @search-colls coll-idx))
                    :on-change #(swap! search-colls assoc coll-idx (-> % .-target .-value))
                    :on-blur #(on-change-search-colls)
                    :on-key-press #(when (= (.-charCode %) 13)
                                     (on-change-search-colls))}])
                (when (= :sum header-modifier)
                  [:div.suma [:span {:dangerously-set-inner-HTML {:__html "&Sigma; "}}]
                   (cljc-util/money->text
                    (->> (:filtered-rows @table-rows)
                         (keep f)
                         (map #(if (transit/bigdec? %)
                                 (util/parse-float (.-rep %))
                                 %))
                         (apply +)
                         int)) " Kč" [:br]])
                (if (#{:none :csv-export} header-modifier)
                  [:span
                   label
                   (when (= :csv-export header-modifier)
                     [:a {:id (str "download-" table-name)}
                      [re-com/md-icon-button :md-icon-name "zmdi-download" :tooltip "Export do CSV"
                       :on-click (fn []
                                   (let [anchor (.getElementById js/document (str "download-" table-name))]
                                     (set! (.-href anchor) (str "data:text/plain;charset=utf-8," (js/encodeURIComponent (make-csv (:filtered-rows @table-rows) colls))))
                                     (set! (.-download anchor) (str table-name ".csv"))))]])]
                  [:a {:on-click #(on-click-order-by coll-idx)}
                   [:span label]
                   [:span (if (not= (:order-by @state) coll-idx)
                            ""
                            (if (:desc? @state)
                              [re-com/md-icon-button :md-icon-name "zmdi-chevron-up" :tooltip "seřadit opačně" :size :smaller]
                              [re-com/md-icon-button :md-icon-name "zmdi-chevron-down" :tooltip "seřadit opačně" :size :smaller]))]])]))]]
          [:tbody
           (doall
            (map-indexed
             (fn [idx row]
               ^{:key (or (:db/id row) idx)}
               [tr-comp colls row change-state-fn])
             (:final-rows @table-rows)))]]
         (when (> (count @rows) 5)
           [:div
            [:span (str "Zobrazuji " (inc (:row-from @table-rows)) " - " (:row-to @table-rows) " z "
                        (count (:filtered-rows @table-rows)) " záznamů")
             (if (< (count (:filtered-rows @table-rows)) (count @rows))
               (str " (vyfiltrováno z celkem " (count @rows) " záznamů)"))]
            [:span
             ". Maximální počet řádků na stránce je "
             [:select {:size 1 :value (str (:rows-per-page @state)) :on-change on-change-rows-per-page}
              (for [x ["vše" 5 10 15 25 50 100]]
                ^{:key x} [:option {:value (if (string? x) "" x)} x])]]
            ;;          [:div.dataTables_filter
            ;;           [:label "Search"
            ;;           [:input {:type "text" :value (:search-all @state) :on-change #(on-change-search-all %)}]]]
            ])
         (when (> (count (:filtered-rows @table-rows)) (count (:final-rows @table-rows)))
           [:ul.pager
            [:li.previous
             [:a {:class (str "" (when (= (:row-from @table-rows) 0) "btn disabled"))
                  :on-click #(change-state-fn :page-no dec)}
              "Předchozí"]]
            [:li.next
             [:a {:class (str "" (when (= (:row-to @table-rows) (count (:filtered-rows @table-rows))) "btn disabled"))
                  :on-click #(change-state-fn :page-no inc)}
              "Následující"]]])]))))
