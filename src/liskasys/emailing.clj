(ns liskasys.emailing
  (:require [datomic.api :as d]
            [liskasys.cljc.util :as cljc.util]
            [liskasys.db-queries :as db-queries]
            [postal.core :as postal]
            [taoensso.timbre :as timbre]))

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
                 :body [{:type "text/plain; charset=utf-8"
                         :content (str subject "\n"
                                       "---------------------------------------------------------------------------------\n\n"
                                       "Číslo účtu: " bank-account "\n"
                                       "Částka: " (/ (:person-bill/total bill) 100) " Kč\n"
                                       "Variabilní symbol: " (-> bill :person-bill/person :person/var-symbol) "\n"
                                       "Do poznámky: " (-> bill :person-bill/person cljc.util/person-fullname) " "
                                       (-> bill :person-bill/period cljc.util/period->text) "\n"
                                       "Splatnost do: " (or (:price-list/payment-due-date (db-queries/find-price-list db)) "20. dne tohoto měsíce") "\n\n"
                                       "Pro QR platbu přejděte na " full-url " menu Platby"
                                       "\n\nToto je automaticky generovaný email ze systému " full-url)}]}]
        (timbre/info "Sending info about published payment" msg)
        (timbre/info (postal/send-message msg))))))
