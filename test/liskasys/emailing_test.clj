(ns liskasys.emailing-test
  (:require [clojure.test :refer :all]
            [liskasys.emailing :refer :all]))

(def diet-labels-by-id {nil "běžná", 2 "lactose free", 3 "gluten free"})

(def plans-with-lunches [{:daily-plan/lunch-req 1 :daily-plan/person {:person/lunch-type {:db/id 2} :person/child? true}}
                         {:daily-plan/lunch-req 1 :daily-plan/person {:person/lunch-type {:db/id 2}}}
                         {:daily-plan/lunch-req 1 :daily-plan/person {:person/lunch-type {:db/id 3}}}
                         {:daily-plan/lunch-req 4}
                         {:daily-plan/lunch-req 1 :daily-plan/person {:person/child-portion? true}}
                         {:daily-plan/lunch-req 2 :daily-plan/person {:person/child? true}}
                         {}])

(def config {:config/org-name "Test school" :config/full-url "http://testschool.org/liskasys"})

(deftest all-diet-labels-by-id-test
  (is (= diet-labels-by-id
         (all-diet-labels-by-id [{:db/id 2 :lunch-type/label "lactose free"}
                                 {:db/id 3 :lunch-type/label "gluten free"}]))))

(deftest lunch-counts-by-diet-label-test
  (is (= {"běžná" 7 "lactose free" 2 "gluten free" 1}
         (lunch-counts-by-diet-label diet-labels-by-id
                                     plans-with-lunches))))

(deftest lunch-order-msg-test
  (is (= {:from "me@test-school.org",
          :to ["someone@yahoo.com" "anotherone@gmail.com"],
          :subject "Test school: Objednávka obědů na pá 1. února 2019",
          :body
          [{:type "text/plain; charset=utf-8",
            :content
            "Test school: Objednávka obědů na pá 1. února 2019
-------------------------------------------------

* DĚTI
  běžná: 3
  lactose free: 1

* DOSPĚLÍ
  běžná: 4
  gluten free: 1
  lactose free: 1
-------------------------------------------------
CELKEM: 10\n\n"}]}
         (lunch-order-msg "me@test-school.org"
                          ["someone@yahoo.com" "anotherone@gmail.com"]
                          "Test school"
                          diet-labels-by-id
                          #inst "2019-02-01"
                          plans-with-lunches))))

(deftest name-att-diet-str-test
  (is (= "Miarka Oliver, bez oběda"
         (name-att-diet-str {:daily-plan/person {:person/firstname "Oliver" :person/lastname "Miarka"}} )))
  (is (= "Miarka Oliver"
         (name-att-diet-str {:daily-plan/person {:person/firstname "Oliver" :person/lastname "Miarka"}
                             :daily-plan/lunch-req 1})))
  (is (= "Miarka Oliver, strava vege"
         (name-att-diet-str {:daily-plan/person {:person/firstname "Oliver" :person/lastname "Miarka" :person/lunch-type {:lunch-type/label "vege"}}
                             :daily-plan/lunch-req 1})))
  (is (= "Miarka Oliver, půldenní"
         (name-att-diet-str {:daily-plan/person {:person/firstname "Oliver" :person/lastname "Miarka"}
                             :daily-plan/child-att 2
                             :daily-plan/lunch-req 1}))))

(deftest name-excuse-str-test
  (is (= "Miarka Oliver"
         (name-excuse-str {:daily-plan/person {:person/firstname "Oliver" :person/lastname "Miarka"}})))
  (is (= "Miarka Oliver, skiing"
         (name-excuse-str {:daily-plan/person {:person/firstname "Oliver" :person/lastname "Miarka"}
                           :daily-plan/excuse "skiing"}))))

(deftest today-child-counts-msg-test
  (is (= {:from "me@test-school.org",
          :to ["someone@yahoo.com" "anotherone@gmail.com"],
          :subject "Test school: Celkový dnešní počet dětí je 3",
          :body
          [{:type "text/plain; charset=utf-8",
            :content
            "Test school: Celkový dnešní počet dětí je 3\n\nfirst: 2\nsecond: 1\n\n\nToto je automaticky generovaný email ze systému http://testschool.org/liskasys"}]}
         (today-child-counts-msg "me@test-school.org"
                                 ["someone@yahoo.com" "anotherone@gmail.com"]
                                 config
                                 [{:db/id 1 :group/label "first"}
                                  {:db/id 2 :group/label "second"}]
                                 [{:daily-plan/group {:db/id 1}}
                                  {:daily-plan/group {:db/id 1}}
                                  {:daily-plan/group {:db/id 2}}]))))

(deftest bill-published-msg-test
  (is (= {:from "me@test-school.org",
          :to ["someone@yahoo.com" "anotherone@gmail.com"],
          :subject "Test school: Platba školkovného a obědů na období 2019/01 - 2019/02",
          :body
          [{:type "text/plain; charset=utf-8",
            :content
            "Test school: Platba školkovného a obědů na období 2019/01 - 2019/02
---------------------------------------------------------------------------------

Číslo účtu: 123456/7890
Částka: 1000.0 Kč
Variabilní symbol: 222
Do poznámky: Miarka Oliver 2019/01 - 2019/02
Splatnost do: do konce roku

Pro QR platbu přejděte na http://testschool.org/liskasys menu Platby

Toto je automaticky generovaný email ze systému http://testschool.org/liskasys"}]}
         (bill-published-msg "me@test-school.org"
                             config
                             {:price-list/bank-account "123456/7890"}
                             "do konce roku"
                             {:person-bill/total 100000
                              :person-bill/person {:person/firstname "Oliver" :person/lastname "Miarka"
                                                   :person/vs "222"
                                                   :person/parent [{:person/email "someone@yahoo.com"}
                                                                   {:person/email "anotherone@gmail.com"}]}
                              :person-bill/period {:billing-period/from-yyyymm 201901
                                                   :billing-period/to-yyyymm 201902}}))))

(deftest bill-published-msg-test-lunches-account
  (is (= {:from "me@test-school.org",
          :to ["someone@yahoo.com" "anotherone@gmail.com"],
          :subject "Test school: Platba školkovného a obědů na období 2019/01 - 2019/02",
          :body
          [{:type "text/plain; charset=utf-8",
            :content
            "Test school: Platba školkovného a obědů na období 2019/01 - 2019/02
--- Školkovné ------------------------------------------------------------------------------

Číslo účtu: 123456/7890
Částka: 800.0 Kč
Variabilní symbol: 222
Do poznámky: Miarka Oliver 2019/01 - 2019/02
Splatnost do: do konce roku

--- Obědy ----------------------------------------------------------------------------------

Číslo účtu: 222222/4444
Částka: 200.0 Kč
Variabilní symbol: 222
Do poznámky: Miarka Oliver 2019/01 - 2019/02
Splatnost do: do konce roku

Toto je automaticky generovaný email ze systému http://testschool.org/liskasys"}]}
         (bill-published-msg "me@test-school.org"
                             config
                             {:price-list/bank-account "123456/7890"
                              :price-list/bank-account-lunches "222222/4444"}
                             "do konce roku"
                             {:person-bill/total 100000
                              :person-bill/att-price 80000
                              :person-bill/person {:person/firstname "Oliver" :person/lastname "Miarka"
                                                   :person/vs "222"
                                                   :person/parent [{:person/email "someone@yahoo.com"}
                                                                   {:person/email "anotherone@gmail.com"}]}
                              :person-bill/period {:billing-period/from-yyyymm 201901
                                                   :billing-period/to-yyyymm 201902}}))))

(deftest substitution-result-msg-test
  (is (= {:from "me@test-school.org",
          :to ["someone@yahoo.com" "anotherone@gmail.com"],
          :subject "Test school: Zítřejsí náhrada platí!",
          :body
          [{:type "text/plain; charset=utf-8",
            :content
            "Miarka Oliver má zítra ve školce nahradní celodenní docházku bez oběda.\n\nToto je automaticky generovaný email ze systému http://testschool.org/liskasys"}]}
         (substitution-result-msg "me@test-school.org"
                                  config
                                  {:daily-plan/person {:person/firstname "Oliver" :person/lastname "Miarka"
                                                       :person/parent [{:person/email "someone@yahoo.com"}
                                                                       {:person/email "anotherone@gmail.com"}]}
                                   :daily-plan/child-att 1}
                                  true)))

  (is (= {:from "me@test-school.org",
          :to ["someone@yahoo.com" "anotherone@gmail.com"],
          :subject "Test school: Zítřejsí náhrada platí!",
          :body
          [{:type "text/plain; charset=utf-8",
            :content
            "Miarka Oliver má zítra ve školce nahradní půldenní docházku včetně oběda.\n\nToto je automaticky generovaný email ze systému http://testschool.org/liskasys"}]}
         (substitution-result-msg "me@test-school.org"
                                  config
                                  {:daily-plan/person {:person/firstname "Oliver" :person/lastname "Miarka"
                                                       :person/parent [{:person/email "someone@yahoo.com"}
                                                                       {:person/email "anotherone@gmail.com"}]}
                                   :daily-plan/child-att 2
                                   :daily-plan/lunch-req 1}
                                  true)))

  (is (= {:from "me@test-school.org",
          :to ["someone@yahoo.com" "anotherone@gmail.com"],
          :subject "Test school: Zítřejší náhrada bohužel není možná",
          :body
          [{:type "text/plain; charset=utf-8",
            :content
            "Miarka Oliver si bohužel zítra nemůže nahradit docházku z důvodu nedostatku volných míst.\n\nToto je automaticky generovaný email ze systému http://testschool.org/liskasys"}]}
         (substitution-result-msg "me@test-school.org"
                                  config
                                  {:daily-plan/person {:person/firstname "Oliver" :person/lastname "Miarka"
                                                       :person/parent [{:person/email "someone@yahoo.com"}
                                                                       {:person/email "anotherone@gmail.com"}]}}
                                  false))))
