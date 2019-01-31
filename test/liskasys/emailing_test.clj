(ns liskasys.emailing-test
  (:require [clojure.test :refer :all]
            [liskasys.emailing :refer :all]))

(deftest all-diet-labels-by-id-test
  (is (= {nil "běžná", 2 "lactose free", 3 "gluten free"}
         (all-diet-labels-by-id [{:db/id 2 :lunch-type/label "lactose free"}
                                 {:db/id 3 :lunch-type/label "gluten free"}]))))

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
                                 {:config/org-name "Test school" :config/full-url "http://testschool.org/liskasys"}
                                 [{:db/id 1 :group/label "first"}
                                  {:db/id 2 :group/label "second"}]
                                 [{:daily-plan/group {:db/id 1}}
                                  {:daily-plan/group {:db/id 1}}
                                  {:daily-plan/group {:db/id 2}}]))))

