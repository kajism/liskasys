(ns liskasys.db-queries-test
  (:require [clojure.test :refer [deftest is]]
            [datomic.api :as d]
            [liskasys.db-queries :as sut]))

(defn test-db []
  (d/as-of (user/db "localhost") #inst "2022-09-01"))

(deftest find-school-year-previous-periods-test
  (is (= (sut/find-school-year-previous-periods (test-db) #inst "2022-06-01")
         [{:billing-period/from-yyyymm 202205
           :billing-period/to-yyyymm 202205
           :db/id 17592186107018}
          {:billing-period/from-yyyymm 202204
           :billing-period/to-yyyymm 202204
           :db/id 17592186106094}
          {:billing-period/from-yyyymm 202203
           :billing-period/to-yyyymm 202203
           :db/id 17592186105005}
          {:billing-period/from-yyyymm 202202
           :billing-period/to-yyyymm 202202
           :db/id 17592186104031}
          {:billing-period/from-yyyymm 202201
           :billing-period/to-yyyymm 202201
           :db/id 17592186103040}
          {:billing-period/from-yyyymm 202112
           :billing-period/to-yyyymm 202112
           :db/id 17592186101862}
          {:billing-period/from-yyyymm 202110
           :billing-period/to-yyyymm 202111
           :db/id 17592186100206}
          {:billing-period/from-yyyymm 202109
           :billing-period/to-yyyymm 202109
           :db/id 17592186098957}]))
  (is (= (sut/find-school-year-previous-periods (test-db) #inst "2022-07-01")
         []))
  (is (= (sut/find-school-year-previous-periods (test-db) #inst "2022-08-01")
         []))
  (is (= (sut/find-school-year-previous-periods (test-db) #inst "2022-09-01")
         [])))
