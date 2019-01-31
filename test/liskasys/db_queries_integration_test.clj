(ns liskasys.db-queries-integration-test
  (:require [clojure.test :refer :all]
            [liskasys.db-queries :refer :all]
            [datomic.api :as d]
            [clj-time.core :as t]))

(def db (d/as-of (d/db (d/connect "datomic:free://localhost:4334/liskasys"))
                 #inst "2019-02-01"))

(deftest make-holiday?-fn-test
  (testing "higher school"
    (let [holiday-ld? (make-holiday?-fn db true)]
      (is (some? (holiday-ld? (t/local-date 2019 02 11)))) ;; higher only
      (is (some? (holiday-ld? (t/local-date 2019 1 1)))) ;; bank
      (is (some? (holiday-ld? (t/local-date 2017 12 30)))) ;; all schools
      ))

  (testing "kindergarten"
    (let [holiday-ld? (make-holiday?-fn db)]
      (is (some? (holiday-ld? (t/local-date 2019 1 1))))
      (is (nil? (holiday-ld? (t/local-date 2018 1 2))))

      ;; easter
      (is (some? (holiday-ld? (t/local-date 2019 4 22))))
      (is (nil? (holiday-ld? (t/local-date 2018 4 22))))
      (is (some? (holiday-ld? (t/local-date 2018 4 2))))

      ;; schools only
      (is (some? (holiday-ld? (t/local-date 2017 12 30))))

      ;; higher schools only
      (is (nil? (holiday-ld? (t/local-date 2019 02 11)))))))

(deftest find-person-substs-test
  (let [substs (find-person-substs db 17592186065839)]
    (is (= {:db/id 17592186055183, :group/label "Doupě", :group/max-capacity 15, :group/mandatory-excuse? true}
           (:group substs)))

    (is (= [{:daily-plan/date #inst "2018-11-30T00:00:00.000-00:00", :daily-plan/lunch-cancelled? true, :daily-plan/excuse "Rýma a kašel.", :daily-plan/child-att 1, :daily-plan/lunch-req 1, :db/id 17592186068235, :daily-plan/person #:db{:id 17592186065839}, :daily-plan/group #:db{:id 17592186055183}, :daily-plan/bill #:db{:id 17592186067364}, :daily-plan/att-cancelled? true}]
           (:substable-dps substs)))

    (is (false? (:can-subst? substs)))

    (is (= 9 (count (:dp-gap-days substs))))))
