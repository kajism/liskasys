(ns liskasys.cljc.util
  (:require [clojure.string :as str]
            #?@(:clj [[clojure.edn :as edn]
                      [clj-time.core :as t]]
                :cljs
                [[cljs-time.core :as t]
                 [cljs.tools.reader.edn :as edn]])))

#_(def max-children-per-day 19)

(defn person-fullname [{:keys [:person/lastname :person/firstname]}]
  (str lastname " " firstname))

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

(defn parse-int [s]
  (when s
    #?(:cljs
       (let [n (js/parseInt (remove-spaces s))]
         (if (js/isNaN n)
           nil
           n))
       :clj
       (let [n (edn/read-string (remove-spaces s))]
         (when (int? n)
           n)))))

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
  (when-not (str/blank? units)
    (* (parse-int units) 100)))

(defn zero-patterns? [{:keys [:person/lunch-pattern :person/att-pattern] :as person}]
  (and (or (str/blank? lunch-pattern) (= (set (seq lunch-pattern)) #{\0}))
       (or (str/blank? att-pattern) (= (set (seq att-pattern)) #{\0}))))

(defn yyyymm->text [ym]
  (when ym
    (let [m (rem ym 100)]
      (str (quot ym 100) "/" (if (<= m 9) "0") m))))

(defn period-start-end
  "Returns local dates with end exclusive!!"
  [{:keys [:billing-period/from-yyyymm :billing-period/to-yyyymm] :as period}]
  [(t/local-date (quot from-yyyymm 100) (rem from-yyyymm 100) 1)
   (t/plus (t/local-date (quot to-yyyymm 100) (rem to-yyyymm 100) 1)
           (t/months 1))])

(defn period->text [{:billing-period/keys [from-yyyymm to-yyyymm]}]
  (str (yyyymm->text from-yyyymm) " - " (yyyymm->text to-yyyymm)))

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
