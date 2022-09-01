(ns liskasys.cljc.util
  (:require [clojure.string :as str]
            #?@(:clj [[clojure.edn :as edn]
                      [clj-time.core :as t]
                      [clj-time.coerce :as tc]]
                :cljs
                [[cljs-time.core :as t]
                 [cljs-time.coerce :as tc]
                 [cljs.tools.reader.edn :as edn]])))

#_(def max-children-per-day 19)

(def att-payment-choices (->> [{:id 1 :label "měsíčně (každý měsíc)"}
                               {:id 3 :label "čtvrtletně bez prázdnin (3m 3m 3m 1m)"}
                               {:id 4 :label "čtvrtletně včetně prázdnin (4 x 3m)"}
                               {:id 5 :label "půlročně bez prázdnin (6m 4m)"}
                               {:id 6 :label "půlročně včetně prázdnin (6m 6m)"}
                               {:id 10 :label "ročně bez prázdnin (10 měsíců v platbě na září)"}
                               {:id 12 :label "ročně včetně prázdnin (12 měsíců v platbě na září)"}]
                              (map (juxt :id identity))
                              (into (array-map))))

(defn person-fullname [{:keys [:person/lastname :person/firstname]}]
  (let [out (str lastname " " firstname)]
    (if (re-find #"^\s*$" out)
      "<no name>"
      out)))

(defn dissoc-temp-keys [m]
  (into {} (remove (fn [[k v]]
                     (or (str/starts-with? (name k) "-")
                         (and (str/starts-with? (name k) "_")
                              (sequential? v))))
                   m)))

(defn remove-spaces [s]
  (-> s
      str
      (str/replace #"\s+" "")))

(defn remove-leading-zeros [s]
  (-> s
      str
      (str/replace #"^0+(\d)" "$1")))

(defn parse-int [s]
  (when-let [s (not-empty (remove-leading-zeros (remove-spaces s)))]
    #?(:cljs
       (let [n (js/parseInt s)]
         (if (js/isNaN n)
           nil
           n))
       :clj
       (let [n (edn/read-string s)]
         (when (number? n)
           (long n))))))

(defn boolean->text [b]
  (if b "Ano" "Ne"))

(defn money->text [n]
  (let [[i d] (-> n
                  str
                  (str/replace "." ",")
                  (str/split #"\,"))]
    (str (->> i
              reverse
              (partition-all 3)
              (map #(apply str %))
              (str/join " ")
              str/reverse)
         (when d
           (str "," d)))))

(defn file-size->text [n]
  (cond
    (nil? n) ""
    (neg? n) "-"
    (zero? n) "0"
    :else
    (reduce (fn [div label]
              (let [q (quot n div)]
                (if (pos? q)
                  (reduced (str (.toFixed (/ n div) 1) " " label))
                  (/ div 1000))))
            1000000000000
            ["TB" "GB" "MB" "kB" "B"])))

(defn from-cents [cents]
  (when cents
    (quot cents 100)))

(defn to-cents [units]
  (if-not (str/blank? units)
    (* (parse-int units) 100)
    0))

(defn zero-patterns? [{:keys [:person/lunch-pattern :person/att-pattern] :as person}]
  (and (or (str/blank? lunch-pattern) (= (set (seq lunch-pattern)) #{\0}))
       (or (str/blank? att-pattern) (= (set (seq att-pattern)) #{\0}))))

(defn yyyymm->text [ym]
  (when ym
    (let [m (rem ym 100)]
      (str (quot ym 100) "/" (if (<= m 9) "0") m))))

(defn yyyymm-start-ld [ym]
  (t/local-date (quot ym 100) (rem ym 100) 1))

(defn period-start-end-lds
  "Returns local dates with end exclusive!!"
  [{:billing-period/keys [from-yyyymm to-yyyymm]}]
  [(yyyymm-start-ld from-yyyymm)
   (t/plus (t/local-date (quot to-yyyymm 100) (rem to-yyyymm 100) 1)
           (t/months 1))])

(defn period->text [{:billing-period/keys [from-yyyymm to-yyyymm]}]
  (str (yyyymm->text from-yyyymm)
       (when (not= from-yyyymm to-yyyymm)
         (str " - " (yyyymm->text to-yyyymm)))))

(def days ["po" "út" "st" "čt" "pá"])

(defn att-pattern->text [pattern]
  (->> pattern
       (keep-indexed (fn [idx ch]
                       (case ch
                         \0 "_"
                         \1 (get days idx)
                         \2 (str (get days idx) "*"))))
       (str/join " ")))

(defn lunch-pattern->text [pattern]
  (->> pattern
       #_(keep-indexed (fn [idx ch]
                       (case ch
                         \0 "_"
                         ch)))
       (str/join " ")))

(defn cents->text [x]
  (-> x
      from-cents
      money->text))

(defn child-att->str [child-att]
  (case child-att
    1 "celodenní"
    2 "půldenní"
    "-"))

(defn date-yyyymm [date]
  (let [ld (tc/to-local-date date)]
    (+ (* (t/year ld) 100)
       (t/month ld))))

(defn date-from-yyyymm [yyyymm]
  (tc/to-date (t/local-date (quot yyyymm 100) (rem yyyymm 100) 1)))

(defn previous-yyyymm [yyyymm]
  (if-not (= (rem yyyymm 100) 1)
    (dec yyyymm)
    (+ (* (dec (quot yyyymm 100)) 100) 12)))

(defn next-yyyymm [yyyymm]
  (if-not (= (rem yyyymm 100) 12)
    (inc yyyymm)
    (+ (* (inc (quot yyyymm 100)) 100) 1)))

(defn start-of-school-year [yyyymm]
  (let [y (quot yyyymm 100)
        m (rem yyyymm 100)]
    (+ (* (if (>= m 7)
            y
            (dec y))
          100)
       9)))

(comment
  (def price-list {:price-list/lunch 50 :price-list/lunch-adult 100})
  (= 50 (person-lunch-price {:person/child? true} price-list))
  (= 100 (person-lunch-price {:person/child? false} price-list)))

(defn person-lunch-price [{:person/keys [child?]} {:price-list/keys [lunch lunch-adult]}]
  (if child?
    lunch
    (or lunch-adult lunch)))

(defn period-local-dates
  "Returns all local-dates except holidays from - to (exclusive)."
  [holiday?-fn from-ld to-ld]
  (->> from-ld
       (iterate (fn [ld]
                  (t/plus ld (t/days 1))))
       (take-while (fn [ld]
                     (t/before? ld to-ld)))
       (remove holiday?-fn)))

(defn daily-plan-lunch? [{:daily-plan/keys [lunch-req lunch-cancelled?]}]
  (and lunch-req (pos? lunch-req)
       (not lunch-cancelled?)))

(defn daily-plan-attendance? [{:daily-plan/keys [child-att att-cancelled?]}]
  (and child-att (pos? child-att)
       (not att-cancelled?)))

(defn select-group-for-subst
  "Decides substitution in another group if no group dps doesn't exist"
  [plans person groups]
  (let [plans-by-group-id (group-by #(get-in % [:daily-plan/group :db/id]) plans)
        person-group-id (get-in person [:person/group :db/id])
        result-group-id (if (pos-int? (count (remove :daily-plan/subst-req-on (get plans-by-group-id person-group-id))))
                          person-group-id
                          (first (disj (set (keys plans-by-group-id)) person-group-id)))]
    {:group (some #(when (= result-group-id (:db/id %))
                     %)
                  groups)
     :group-plans (get plans-by-group-id result-group-id)}))

(comment
  (= {:group {:db/id 1 :group/name "Jednicka"}
      :group-plans [{:daily-plan/group {:db/id 1}}]}
     (select-group-for-subst [{:daily-plan/group {:db/id 1}} {:daily-plan/group {:db/id 2}}] {:person/group {:db/id 1}} [{:db/id 1 :group/name "Jednicka"} {:db/id 2 :group/name "Dvojka"}]))
  (= {:group {:db/id 2 :group/name "Dvojka"}
      :group-plans [{:daily-plan/group {:db/id 2}}]}
     (select-group-for-subst [{:daily-plan/group {:db/id 1}} {:daily-plan/group {:db/id 2}}] {:person/group {:db/id 2}} [{:db/id 1 :group/name "Jednicka"} {:db/id 2 :group/name "Dvojka"}]))
  (= {:group {:db/id 1 :group/name "Jednicka"}
      :group-plans [{:daily-plan/group {:db/id 1}}]}
     (select-group-for-subst [{:daily-plan/group {:db/id 1}}] {:person/group {:db/id 2}} [{:db/id 1 :group/name "Jednicka"} {:db/id 2 :group/name "Dvojka"}]))
  (= {:group {:db/id 2 :group/name "Dvojka"}
      :group-plans [{:daily-plan/group {:db/id 2}}]}
     (select-group-for-subst [{:daily-plan/group {:db/id 2}}] {:person/group {:db/id 1}} [{:db/id 1 :group/name "Jednicka"} {:db/id 2 :group/name "Dvojka"}])))
