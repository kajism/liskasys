(ns liskasys.emailing
  (:require [clojure.string :as str]
            [datomic.api :as d]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.db :as db]
            [liskasys.db-queries :as db-queries]
            [liskasys.util :as util]
            [postal.core :as postal]
            [taoensso.timbre :as timbre]))

(def content-type "text/plain; charset=utf-8")
(def footer-text "\n\nToto je automaticky generovaný email ze systému ")

(defn- lunch-counts-by-diet-label [lunch-types plans-with-lunches]
  (->> plans-with-lunches
       (group-by (comp :db/id :person/lunch-type :daily-plan/person))
       (map (fn [[k v]]
              [(get lunch-types k) (reduce + 0 (keep :daily-plan/lunch-req v))]))
       (util/sort-by-locale first)))

(defn- all-lunch-type-labels-by-id [lunch-types]
  (->> lunch-types
       (into [{:db/id nil :lunch-type/label "běžná"}])
       (map (juxt :db/id :lunch-type/label))
       (into {})))

(defn send-lunch-order-email [db date plans-with-lunches]
  (let [org-name (:config/org-name (d/pull db '[*] :liskasys/config))
        subject (str org-name ": Objednávka obědů na " (time/format-day-date date))
        plans-by-child? (group-by #(boolean (get-in % [:daily-plan/person :person/child?])) plans-with-lunches)

        lunch-type-labels-by-id (-> (db/find-by-type db :lunch-type {})
                                    (all-lunch-type-labels-by-id))
        msg {:from (db-queries/find-auto-sender-email db)
             :to (mapv :person/email (db-queries/find-persons-with-role db "obědy"))
             :subject subject
             :body [{:type content-type
                     :content (str subject "\n"
                                   "-------------------------------------------------\n\n"
                                   (when-let [plans (get plans-by-child? true)]
                                     (str
                                      "* DĚTI\n"
                                      (str/join "\n"
                                                (for [[t c] (lunch-counts-by-diet-label lunch-type-labels-by-id plans)]
                                                  (str "  " t ": " c)))))
                                   (when-let [plans (get plans-by-child? false)]
                                     (str
                                      "\n\n* DOSPĚLÍ\n"
                                      (str/join "\n"
                                                (for [[t c] (lunch-counts-by-diet-label lunch-type-labels-by-id plans)]
                                                  (str "  " t ": " c)))))
                                   "\n-------------------------------------------------\n"
                                   "CELKEM: " (reduce + 0 (keep :daily-plan/lunch-req plans-with-lunches)) "\n\n")}]}]
    (timbre/info org-name ": sending " (:subject msg) "to" (:to msg))
    (let [result (postal/send-message msg)]
      (if (zero? (:code result))
        (timbre/info org-name ": lunch order has been sent" result)
        (timbre/error org-name ": failed to send email" result)))))

(defn- name-with-att-lunch-types-str [dp]
  (str (cljc.util/person-fullname (:daily-plan/person dp))
       (when (= (:daily-plan/child-att dp) 2)
         ", půldenní")
       (if-not (cljc.util/daily-plan-lunch? dp)
         ", bez oběda"
         (when-let [type (some-> dp :daily-plan/person :person/lunch-type :lunch-type/label)]
           (str ", strava " type)))))

(defn- name-with-excuse-str [dp]
  (str (cljc.util/person-fullname (:daily-plan/person dp))
       (when-not (str/blank? (:daily-plan/excuse dp))
         (str ", " (:daily-plan/excuse dp)))))

(defn- group-summary-text [{:keys [group going not-going cancelled other-lunches]}]
  (str (:group/label group) " (" (count going) ")"
       "\n===========================================\n\n"
       (when-let [xs (not-empty (->> going
                                     (remove :daily-plan/subst-req-on)
                                     (map name-with-att-lunch-types-str)
                                     (util/sort-by-locale)))]
         (str "Docházka (" (count xs) ") ------------------------------\n" (str/join "\n" xs)))
       (when-let [xs (not-empty (->> going
                                     (filter :daily-plan/subst-req-on)
                                     (map name-with-att-lunch-types-str)
                                     (util/sort-by-locale)))]
         (str "\n\nNáhradnící (" (count xs) ") ------------------------\n" (str/join "\n" xs)))
       (when-let [xs (not-empty (->> other-lunches
                                     (map name-with-att-lunch-types-str)
                                     (util/sort-by-locale)))]
         (str "\n\nOstatní obědy (" (count xs) ") ---------------------\n" (str/join "\n" xs)))
       (when-let [xs (not-empty (->> cancelled
                                     (map name-with-excuse-str)
                                     (util/sort-by-locale)))]
         (str "\n\nOmluvenky (" (count xs) ") ---------------------------\n" (str/join "\n" xs)))
       (when-let [xs (not-empty (->> not-going
                                     (map #(cljc.util/person-fullname (:daily-plan/person %)))
                                     (util/sort-by-locale)))]
         (str "\n\nNáhradníci, kteří se nevešli (" (count xs) ") ------\n" (str/join "\n" xs)))
       "\n\n"))

(defn send-daily-summary [db date group-results]
  (let [{:config/keys [org-name full-url]} (d/pull db '[*] :liskasys/config)
        subj (str org-name ": Denní souhrn na " (time/format-day-date date) " ("
                  (->> group-results (map #(count (:going %))) (reduce +)) " dětí)")
        msg {:from (db-queries/find-auto-sender-email db)
             :to (mapv :person/email (db-queries/find-persons-with-role db "průvodce"))
             :subject subj
             :body [{:type content-type
                     :content (str subj "\n\n"
                                   (->> group-results
                                        (map group-summary-text)
                                        (reduce str))
                                   footer-text full-url)}]}]
    (timbre/info org-name ": sending summary msg" msg)
    (timbre/info (postal/send-message msg))))

(defn send-today-child-counts [db today-atts]
  (let [{:config/keys [org-name full-url closing-msg-role]} (d/pull db '[*] :liskasys/config)]
    (let [groups (db/find-by-type db :group {})
          atts-by-group-id (group-by #(get-in % [:daily-plan/group :db/id]) today-atts)
          subj (str org-name ": Celkový dnešní počet dětí je " (count today-atts))
          msg {:from (db-queries/find-auto-sender-email db)
               :to (mapv :person/email (db-queries/find-persons-with-role db closing-msg-role))
               :subject subj
               :body [{:type content-type
                       :content (str subj "\n\n"
                                     (reduce (fn [out group]
                                               (str out (:group/label group) ": "
                                                    (count (get atts-by-group-id (:db/id group))) "\n"))
                                             ""
                                             groups)
                                     footer-text full-url)}]}]
      (timbre/info org-name ": sending cancellation closing msg" msg)
      (timbre/info (postal/send-message msg)))))

(defn make-bill-published-sender [db]
  (let [{:config/keys [org-name full-url]} (d/pull db '[*] :liskasys/config)
        from (db-queries/find-auto-sender-email db)
        bank-account (db-queries/find-bank-account db)]
    (fn [bill]
      (let [subject (str org-name ": Platba školkovného a obědů na období " (-> bill :person-bill/period cljc.util/period->text))
            msg {:from from
                 :to (or (-> bill :person-bill/person :person/email)
                         (mapv :person/email (-> bill :person-bill/person :person/parent)))
                 :subject subject
                 :body [{:type content-type
                         :content (str subject "\n"
                                       "---------------------------------------------------------------------------------\n\n"
                                       "Číslo účtu: " bank-account "\n"
                                       "Částka: " (/ (:person-bill/total bill) 100) " Kč\n"
                                       "Variabilní symbol: " (-> bill :person-bill/person :person/var-symbol) "\n"
                                       "Do poznámky: " (-> bill :person-bill/person cljc.util/person-fullname) " "
                                       (-> bill :person-bill/period cljc.util/period->text) "\n"
                                       "Splatnost do: " (or (:price-list/payment-due-date (db-queries/find-price-list db)) "20. dne tohoto měsíce") "\n\n"
                                       "Pro QR platbu přejděte na " full-url " menu Platby"
                                       footer-text full-url)}]}]
        (timbre/info org-name ": sending info about published payment" msg)
        (timbre/info (postal/send-message msg))))))

(defn make-substitution-result-sender [db]
  (let [{:config/keys [org-name full-url]} (d/pull db '[*] :liskasys/config)
    from (db-queries/find-auto-sender-email db)]
   (fn [dp going?]
     (let [msg {:from from
                :to (map :person/email (-> dp :daily-plan/person :person/parent))
                :subject (str org-name ": " (if going?
                                              "Zítřejsí náhrada platí!"
                                              "Zítřejší náhrada bohužel není možná"))
                :body [{:type content-type
                        :content (str (-> dp :daily-plan/person cljc.util/person-fullname)
                                      (if going?
                                        (str (-> dp :daily-plan/person cljc.util/person-fullname)
                                             " má zítra ve školce nahradní "
                                             (cljc.util/child-att->str (:daily-plan/child-att dp))
                                             " docházku "
                                             (if (cljc.util/daily-plan-lunch? dp)
                                               "včetně oběda."
                                               "bez oběda."))
                                        " si bohužel zítra nemůže nahradit docházku z důvodu nedostatku volných míst.")
                                      footer-text full-url)}]}]
       (timbre/info org-name ": sending to" (when-not going? "not") "going" msg)
       (timbre/info (postal/send-message msg))))))

