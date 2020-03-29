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

(defn- send-message [org-name msg]
  (timbre/info org-name ": sending email" msg)
  (let [result (postal/send-message msg)]
    (if (zero? (:code result))
      (timbre/info org-name ": email sent" result)
      (timbre/error org-name ": failed to send email" result)))
  msg)

(defn lunch-counts-by-diet-label [diet-labels-by-id plans-with-lunches]
  (->> plans-with-lunches
       (group-by (comp :db/id :person/lunch-type :daily-plan/person))
       (map (fn [[diet-id dps]]
              [(get diet-labels-by-id diet-id) (reduce + 0 (keep :daily-plan/lunch-req dps))]))
       (util/sort-by-locale first)
       (into {})))

(defn all-diet-labels-by-id [lunch-types]
  (->> lunch-types
       (into [{:db/id nil :lunch-type/label "běžná"}])
       (map (juxt :db/id :lunch-type/label))
       (into {})))

(defn lunch-order-msg [from tos org-name diet-labels-by-id date plans-with-lunches]
  (let [subj (str org-name ": Objednávka obědů na " (time/format-day-date date))
        plans-by-child? (group-by #(boolean (or (get-in % [:daily-plan/person :person/child?])
                                                (get-in % [:daily-plan/person :person/child-portion?]))) plans-with-lunches)]
    {:from from
     :to tos
     :subject subj
     :body [{:type content-type
             :content (str subj "\n"
                           "-------------------------------------------------\n\n"
                           (when-let [plans (get plans-by-child? true)]
                             (str
                              "* DĚTI\n"
                              (str/join "\n"
                                        (for [[t c] (lunch-counts-by-diet-label diet-labels-by-id plans)]
                                          (str "  " t ": " c)))))
                           (when-let [plans (get plans-by-child? false)]
                             (str
                              "\n\n* DOSPĚLÍ\n"
                              (str/join "\n"
                                        (for [[t c] (lunch-counts-by-diet-label diet-labels-by-id plans)]
                                          (str "  " t ": " c)))))
                           "\n-------------------------------------------------\n"
                           "CELKEM: " (->> plans-with-lunches
                                           (keep :daily-plan/lunch-req)
                                           (reduce + 0))
                           "\n\n")}]}))

(defn send-lunch-order-emails [db date plans-with-lunches]
  (let [org-name (:config/org-name (d/pull db '[*] :liskasys/config))
        branches-by-id (->> (db/find-by-type db :branch {})
                            (map (juxt :db/id identity))
                            (into {}))]
    (doall
     (for [[branch-id plans] (group-by #(some-> % :daily-plan/person :person/group :group/branch :db/id)
                                       plans-with-lunches)
           :let [{:branch/keys [label address lunch-order-email-addr] :as branch} (get branches-by-id branch-id)
                 msg (lunch-order-msg (db-queries/find-auto-sender-email db)
                                      (cond-> (mapv :person/email (db-queries/find-persons-with-role db "obědy"))
                                        (not (str/blank? lunch-order-email-addr))
                                        (conj lunch-order-email-addr))
                                      (if-not branch
                                        org-name
                                        (str label ", " address))
                                      (-> (db/find-by-type db :lunch-type {})
                                          (all-diet-labels-by-id))
                                      date
                                      plans)]]
       (send-message org-name msg)))))

(defn name-att-diet-str [{:daily-plan/keys [person child-att] :as dp}]
  (str (cljc.util/person-fullname person)
       (when (= child-att 2)
         ", půldenní")
       (if-not (cljc.util/daily-plan-lunch? dp)
         ", bez oběda"
         (when-let [diet-label (some-> person :person/lunch-type :lunch-type/label)]
           (str ", strava " diet-label)))))

(defn name-excuse-str [{:daily-plan/keys [person excuse]}]
  (str (cljc.util/person-fullname person)
       (when-not (str/blank? excuse)
         (str ", " excuse))))

(defn group-daily-summary-text [{:keys [group going not-going cancelled other-lunches]}]
  (str (:group/label group) " (" (count going) ")"
       "\n===========================================\n\n"
       (when-let [xs (not-empty (->> going
                                     (remove :daily-plan/subst-req-on)
                                     (map name-att-diet-str)
                                     (util/sort-by-locale)))]
         (str "Docházka (" (count xs) ") ------------------------------\n" (str/join "\n" xs)))
       (when-let [xs (not-empty (->> going
                                     (filter :daily-plan/subst-req-on)
                                     (map name-att-diet-str)
                                     (util/sort-by-locale)))]
         (str "\n\nNáhradnící (" (count xs) ") ------------------------\n" (str/join "\n" xs)))
       (when-let [xs (not-empty (->> other-lunches
                                     (map name-att-diet-str)
                                     (util/sort-by-locale)))]
         (str "\n\nOstatní obědy (" (count xs) ") ---------------------\n" (str/join "\n" xs)))
       (when-let [xs (not-empty (->> cancelled
                                     (map name-excuse-str)
                                     (util/sort-by-locale)))]
         (str "\n\nOmluvenky (" (count xs) ") ---------------------------\n" (str/join "\n" xs)))
       (when-let [xs (not-empty (->> not-going
                                     (map #(cljc.util/person-fullname (:daily-plan/person %)))
                                     (util/sort-by-locale)))]
         (str "\n\nNáhradníci, kteří se nevešli (" (count xs) ") ------\n" (str/join "\n" xs)))
       "\n\n"))

(defn daily-summary-msg [from tos {:config/keys [org-name full-url]} date daily-summaries]
  (let [subj (str org-name ": Denní souhrn na " (time/format-day-date date) " ("
                  (->> daily-summaries (map #(count (:going %))) (reduce +)) " dětí)")]
    {:from from
     :to tos
     :subject subj
     :body [{:type content-type
             :content (str subj "\n\n"
                           (->> daily-summaries
                                (map group-daily-summary-text)
                                (reduce str))
                           footer-text full-url)}]}))

(defn send-daily-summary [db date daily-summaries]
  (let [{:config/keys [org-name] :as config} (d/pull db '[*] :liskasys/config)
        msg (daily-summary-msg (db-queries/find-auto-sender-email db)
                               (mapv :person/email (db-queries/find-persons-with-role db "průvodce"))
                               config
                               date
                               daily-summaries)]
    (send-message org-name msg)))

(defn today-child-counts-msg [from tos {:config/keys [org-name full-url]} groups today-att-dps]
  (let [subj (str org-name ": Celkový dnešní počet dětí je " (count today-att-dps))
        atts-by-group-id (group-by #(get-in % [:daily-plan/group :db/id]) today-att-dps)]
    {:from from
     :to tos
     :subject subj
     :body [{:type content-type
             :content (str subj "\n\n"
                           (reduce (fn [out group]
                                     (str out (:group/label group) ": "
                                          (count (get atts-by-group-id (:db/id group))) "\n"))
                                   ""
                                   groups)
                           footer-text full-url)}]}))

(defn send-today-child-counts [db today-att-dps]
  (let [{:config/keys [org-name closing-msg-role] :as config} (d/pull db '[*] :liskasys/config)
        msg (today-child-counts-msg (db-queries/find-auto-sender-email db)
                                    (mapv :person/email (db-queries/find-persons-with-role db closing-msg-role))
                                    config
                                    (db/find-by-type db :group {})
                                    today-att-dps)]
    (send-message org-name msg)))


(defn monthly-lunch-order-totals-msg [from tos {:config/keys [org-name full-url]} yyyymm person-totals]
  (let [subj (str org-name ": Objednávky obědů na osobu za uplynulý měsíc (" yyyymm ")")]
    {:from from
     :to tos
     :subject subj
     :body [{:type content-type
             :content (str subj "\n\n"
                           (reduce (fn [out [person total]]
                                     (str out (cljc.util/person-fullname person) ": " total "\n"))
                                   ""
                                   person-totals)
                           footer-text full-url)}]}))

(defn send-monthly-lunch-order-totals-per-person [db yyyymm person-totals]
  (let [{:config/keys [org-name lunch-totals-role] :as config} (d/pull db '[*] :liskasys/config)
        msg (monthly-lunch-order-totals-msg (db-queries/find-auto-sender-email db)
                                            (mapv :person/email (db-queries/find-persons-with-role db lunch-totals-role))
                                            config
                                            yyyymm
                                            person-totals)]
    (send-message org-name msg)))

(defn monthly-lunch-fund-totals-msg [from tos {:config/keys [org-name full-url]} yyyymm {:keys [total-portions adult-portions child-portions total-lunch-fund-cents total-lunch-down-payment-cents next-total-lunch-down-payment-cents lunch-total-cents]}]
  (let [subj (str org-name ": Objednávky obědů za uplynulý měsíc (" (cljc.util/yyyymm->text yyyymm) ")")]
    {:from from
     :to tos
     :subject subj
     :body [{:type content-type
             :content (str subj "\n\n"
                           "Dětské porce: " child-portions "\n"
                           "Dospělé porce: " adult-portions "\n"
                           "Celková cena porcí: " (quot lunch-total-cents 100) " Kč\n"
                           (when total-lunch-down-payment-cents
                             (str "Zaplacené zálohy na obědy " (cljc.util/yyyymm->text yyyymm) ": " (quot total-lunch-down-payment-cents 100) " Kč\n"))
                           (when next-total-lunch-down-payment-cents
                             (str "Zaplacené zálohy na obědy " (cljc.util/yyyymm->text (cljc.util/next-yyyymm yyyymm)) ": " (quot next-total-lunch-down-payment-cents 100) " Kč\n"))
                           "Zbývá ve fondu: " (quot total-lunch-fund-cents 100) " Kč\n"
                           footer-text full-url)}]}))

(defn send-monthly-lunch-fund-totals [db yyyymm fund-totals]
  (let [{:config/keys [org-name lunch-fund-totals-role] :as config} (d/pull db '[*] :liskasys/config)
        msg (monthly-lunch-fund-totals-msg (db-queries/find-auto-sender-email db)
                                            (mapv :person/email (db-queries/find-persons-with-role db lunch-fund-totals-role))
                                            config
                                            yyyymm
                                            fund-totals)]
    (send-message org-name msg)))

(defn bill-published-msg [from {:config/keys [org-name full-url]} {:price-list/keys [bank-account bank-account-lunches]} payment-due-to {:person-bill/keys [total att-price person period]}]
  (let [period-text (cljc.util/period->text period)
        subj (str org-name ": Platba školkovného a obědů na období " period-text)
        separate-lunches? (not (str/blank? bank-account-lunches))]
    {:from from
     :to (or (:person/email person)
             (mapv :person/email (:person/parent person)))
     :subject subj
     :body [{:type content-type
             :content (str subj "\n"
                           "---" (when separate-lunches? " Školkovné ")
                           "------------------------------------------------------------------------------\n\n"
                           "Číslo účtu: " bank-account "\n"
                           "Částka: " (/ (if separate-lunches? att-price total) 100.0) " Kč\n"
                           "Variabilní symbol: " (:person/vs person) "\n"
                           "Do poznámky: " (cljc.util/person-fullname person) " " period-text "\n"
                           "Splatnost do: " payment-due-to "\n\n"
                           (when-not separate-lunches?
                             (str "Pro QR platbu přejděte na " full-url " menu Platby"))
                           (when separate-lunches?
                             (str "--- Obědy ----------------------------------------------------------------------------------\n\n"
                                  "Číslo účtu: " bank-account-lunches "\n"
                                  "Částka: " (/ (- total att-price) 100.0) " Kč\n"
                                  "Variabilní symbol: " (:person/vs person) "\n"
                                  "Do poznámky: " (cljc.util/person-fullname person) " " period-text "\n"
                                  "Splatnost do: " payment-due-to))
                           footer-text full-url)}]}))

(defn make-bill-published-sender [db]
  (let [{:config/keys [org-name person-bill-email?] :as config} (d/pull db '[*] :liskasys/config)
        from (db-queries/find-auto-sender-email db)
        price-lists (db-queries/find-price-lists db)]
    (fn [bill]
      (when person-bill-email?
        (let [price-list (get price-lists (get-in bill [:person-bill/person :person/price-list :db/id]))
              payment-due-to (or (:price-list/payment-due-date price-list)
                                 "20. dne tohoto měsíce")
              msg (bill-published-msg from
                                      config
                                      price-list
                                      payment-due-to
                                      bill)]
          (send-message org-name msg))))))

(defn substitution-result-msg [from {:config/keys [org-name full-url]} {:daily-plan/keys [person child-att] :as dp} going?]
  {:from from
   :to (mapv :person/email (:person/parent person))
   :subject (str org-name ": " (if going?
                                 "Zítřejsí náhrada platí!"
                                 "Zítřejší náhrada bohužel není možná"))
   :body [{:type content-type
           :content (str (cljc.util/person-fullname person)
                         (if going?
                           (str " má zítra ve školce nahradní "
                                (cljc.util/child-att->str child-att)
                                " docházku "
                                (if (cljc.util/daily-plan-lunch? dp)
                                  "včetně oběda."
                                  "bez oběda."))
                           " si bohužel zítra nemůže nahradit docházku z důvodu nedostatku volných míst.")
                         footer-text full-url)}]})

(defn make-substitution-result-sender [db]
  (let [from (db-queries/find-auto-sender-email db)
        {:config/keys [org-name] :as config} (d/pull db '[*] :liskasys/config)]
    (fn [dp going?]
      (let [msg (substitution-result-msg from
                                         config
                                         dp
                                         going?)]
        (send-message org-name msg)))))
