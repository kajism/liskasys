(ns liskasys.emailing
  (:require [datomic.api :as d]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.db :as db]
            [liskasys.db-queries :as db-queries]
            [postal.core :as postal]
            [taoensso.timbre :as timbre]))

(def content-type "text/plain; charset=utf-8")
(def footer-text "\n\nToto je automaticky generovaný email ze systému ")

(defn send-daily-summary [db date group-results]
  (let [{:config/keys [org-name full-url]} (d/pull db '[*] :liskasys/config)
        subj (str org-name ": Denní souhrn na " (time/format-day-date date) " (" (->> group-results (map :count) (reduce +)) " dětí)")
        summary-msg {:from (db-queries/find-auto-sender-email db)
                     :to (mapv :person/email (db-queries/find-persons-with-role db "průvodce"))
                     :subject subj
                     :body [{:type content-type
                             :content (str subj "\n\n"
                                           (->> group-results (map :text) (reduce str))
                                           footer-text full-url)}]}]
    (timbre/info org-name ": sending summary msg" summary-msg)
    (timbre/info (postal/send-message summary-msg))))

(defn send-today-child-counts [db today-atts]
  (let [{:config/keys [org-name full-url closing-msg-role]} (d/pull db '[*] :liskasys/config)]
    (let [groups (db/find-by-type db :group {})
          atts-by-group-id (group-by (comp :db/id :person/group :daily-plan/person) today-atts)
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
