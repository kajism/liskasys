(ns liskasys.cljc.util-test
  (:require [clojure.test :refer [deftest is testing]]
            [liskasys.cljc.util :as sut]))

(deftest start-of-school-year-test
  (is (= (sut/start-of-school-year 202112)
         202109))
  (is (= (sut/start-of-school-year 202201)
         202109))
  (is (= (sut/start-of-school-year 202206)
         202109))
  (is (= (sut/start-of-school-year 202206)
         202109))
  (is (= (sut/start-of-school-year 202207)
         202209))
  (is (= (sut/start-of-school-year 202208)
         202209))
  (is (= (sut/start-of-school-year 202209)
         202209))
  (is (= (sut/start-of-school-year 202210)
         202209)))
