(ns clj-brnolib.cljs.comp.data-table
  (:require [clj-brnolib.time :as time]
            [clj-brnolib.cljs.util :as util]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [re-com.core :as re-com]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [reagent.ratom :as ratom]
            [taoensso.timbre :as timbre]))

(re-frame/register-sub
 :table-state
 (fn [db [_ table-id]]
   (ratom/reaction (get-in @db [:table-states table-id]))))

(re-frame/register-handler
 :table-state-set
 re-frame/debug
 (fn [db [_ table-id state]]
   (assoc-in db [:table-states table-id] state)))

(re-frame/register-handler
 :table-state-change
 re-frame/debug
 (fn [db [_ table-id key val]]
   ((if (fn? val) update-in assoc-in) db [:table-states table-id key] val)))

(defn checked-ids [db table-id]
  (keep (fn [[id {ch? :checked?}]]
          (when ch? id))
        (get-in db [:table-states table-id :row-states])))

(defn deref-or-value
  [val-or-atom]
  (if (satisfies? IDeref val-or-atom) @val-or-atom val-or-atom))

(defn make-csv [rows colls]
  (let [colls (->> (vals colls)
                   (remove (fn [[label f modifier]]
                             (#{:none :csv-export} modifier))))]
    (str (str/join ";" (map first colls)) "\n"
         (apply str
                (for [row rows]
                  (str (str/join ";" (map #(% row)
                                          (map second colls))) "\n"))))))

(defn data-table [& {:keys [table-id order-by desc? rows-per-page row-checkboxes?] :as args}]
  (let [order-by (or order-by 0)
        init-state {:order-by order-by
                    :desc? (or desc? false)
                    :search-all ""
                    :search-colls {}
                    :rows-per-page (or rows-per-page 50)
                    :page-no 0}
        state (if table-id
                (re-frame/subscribe [:table-state table-id])
                (reagent/atom init-state))
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
        on-change-rows-per-page #(do
                                   (change-state-fn :rows-per-page %)
                                   (change-state-fn :page-no 0))
        on-change-search-all (fn [evt]
                               (change-state-fn :search-all (-> evt .-target .-value))
                               (change-state-fn :page-no 0))
        on-change-search-colls (fn [coll-key evt]
                                 (let [value (-> evt .-target .-value)]
                                   (change-state-fn :search-colls #(assoc % coll-key value))
                                   (change-state-fn :page-no 0)))]
    (when-not @state
      (re-frame/dispatch [:table-state-set table-id init-state]))
    (fn data-table-render [& {:keys [rows colls] :as args}]
      (if-not @state
        [re-com/throbber]
        (let [colls (into {} (->> colls
                                  (keep identity)
                                  (map-indexed vector)))
              rows (deref-or-value rows)
              rows (cond->> rows
                     (map? rows)
                     vals
                     (map? (first rows))
                     (map-indexed #(assoc %2 :-idx %1)))
              sort-key-fn (nth (get colls (:order-by @state)) 1)
              sort-key-fn (cond->> sort-key-fn
                            (transit/bigdec? (-> rows first sort-key-fn))
                            (comp util/parse-float #(when % (.-rep %))))
              sort-fn (if (string? (-> rows first sort-key-fn))
                        util/sort-by-locale
                        sort-by)
              sorted-rows (cond-> (sort-fn sort-key-fn rows)
                            (:desc? @state) reverse)
              filtered-by-colls (reduce
                                 (fn [rows coll-idx]
                                   (if (str/blank? (get (:search-colls @state) coll-idx))
                                     rows
                                     (filter
                                      (fn [row]
                                        (> (.indexOf (str/lower-case (str ((nth (get colls coll-idx) 1) row)))
                                                     (str/lower-case (get (:search-colls @state) coll-idx)))
                                           -1))
                                      rows)))
                                 sorted-rows
                                 (keys colls))
              filtered-rows (if (empty? (:search-all @state))
                              filtered-by-colls
                              (filter #(> (.indexOf (clojure.string/join "#" (vals %)) (:search-all @state)) -1) filtered-by-colls))
              row-from (* (or (:rows-per-page @state) 0) (:page-no @state))
              row-to (if (:rows-per-page @state)
                       (min (+ row-from (:rows-per-page @state)) (count filtered-rows))
                       (count filtered-rows))
              final-rows (subvec (vec filtered-rows) row-from row-to)]
          [:div.data-table-component
           [:table.table.tree-table.table-hover.table-striped
            [:thead
             [:tr
              (when row-checkboxes?
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
                    [:input.form-control {:type "text"
                             :value (get (:search-colls @state) coll-idx)
                                          :on-change #(on-change-search-colls coll-idx %)}])
                  (when (= :sum header-modifier)
                    [:div.suma [:span {:dangerously-set-inner-HTML {:__html "&Sigma; "}}]
                     (util/money->text
                      (->> filtered-rows
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
                                       (set! (.-href anchor) (str "data:text/plain;charset=utf-8," (js/encodeURIComponent (make-csv filtered-rows colls))))
                                       (set! (.-download anchor) (str table-name ".csv"))))]])]
                    [:a {:on-click #(on-click-order-by coll-idx)}
                     [:span label]
                     [:span (if (not= (:order-by @state) coll-idx)
                              ""
                              (if (:desc? @state)
                                [re-com/md-icon-button :md-icon-name "zmdi-chevron-up" :tooltip "seřadit opačně" :size :smaller]
                                [re-com/md-icon-button :md-icon-name "zmdi-chevron-down" :tooltip "seřadit opačně" :size :smaller]))]])

]))]]
            [:tbody
             (doall
              (map-indexed
               (fn [idx row]
                 ^{:key (or (:db/id row) idx)}
                 [:tr
                  (when row-checkboxes?
                    [:td [re-com/checkbox
                          :model (get-in @state [:row-states (:db/id row) :checked?])
                          :on-change #(change-state-fn :row-states
                                                       (fn [row-states]
                                                         (update row-states (:db/id row) update :checked? not)))]])
                  (doall
                   (for [[coll-idx [_ f _]] colls
                         :let [value (f row)]]
                     ^{:key (str (or (:db/id row) idx) "-" coll-idx)}
                     [:td {:class (str #_"text-nowrap" (when (or (number? value) (transit/bigdec? value)) " text-right"))}
                      (cond
                        (or (string? value) (vector? value)) value
                        (= js/Date (type value)) (time/to-format value time/ddMMyyyyHHmm)
                        (number? value) (util/money->text value)
                        (transit/bigdec? value) (util/money->text (util/parse-int (.-rep value)))
                        (= js/Boolean (type value)) (util/boolean->text value)
                        :else (str value))]))])
               final-rows))]]
           (when (> (count rows) 5)
             [re-com/h-box :gap "5px" :align :center
              :children
              [[re-com/box :child (str "Zobrazuji " (inc row-from) " - " row-to " z " (count filtered-rows) " záznamů"
                                       (if (< (count filtered-rows) (count rows)) (str " (vyfiltrováno z celkem " (count rows) " záznamů)")))]
               [re-com/box :child ". Maximální počet řádků na stránce je "]
               [re-com/single-dropdown
                :model (:rows-per-page @state)
                :on-change on-change-rows-per-page
                :placeholder "vše"
                :choices (into [{:id nil :label "vše"}]
                               (map (fn [n] {:id n :label (str n)})
                                    [5 10 15 25 50 100]))
                :width "70px"]]
              ;;          [:div.dataTables_filter
              ;;           [:label "Search"
              ;;           [:input {:type "text" :value (:search-all @state) :on-change #(on-change-search-all %)}]]]
              ])
           (when (> (count filtered-rows) (count final-rows))
             [:ul.pager
              [:li.previous
               [:a {:class (str "" (when (= row-from 0) "btn disabled"))
                    :on-click #(change-state-fn :page-no dec)}
                "Předchozí"]]
              [:li.next
               [:a {:class (str "" (when (= row-to (count filtered-rows)) "btn disabled"))
                    :on-click #(change-state-fn :page-no inc)}
                "Následující"]]])])))))
