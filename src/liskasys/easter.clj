(ns liskasys.easter
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as t]))

;; Source: https://gist.github.com/werand/2387286
(defn- easter-sunday-for-year-ld [year]
  (let [golden-year (+ 1 (mod year 19))
        div (fn div [& more] (Math/floor (apply / more)))
        century (+ (div year 100) 1)
        skipped-leap-years (- (div (* 3 century) 4) 12)
        correction (- (div (+ (* 8 century) 5) 25) 5)
        d (- (div (* 5 year) 4) skipped-leap-years 10)
        epac (let [h (mod (- (+ (* 11 golden-year) 20 correction)
                             skipped-leap-years) 30)]
               (if (or (and (= h 25) (> golden-year 11)) (= h 24))
                 (inc h) h))
        m (let [t (- 44 epac)]
            (if (< t 21) (+ 30 t) t))
        n (- (+ m 7) (mod (+ d m) 7))
        day (if (> n 31) (- n 31) n)
        month (if (> n 31) 4 3)]
    (t/local-date year (int month) (int day))))

(defn- easter-monday-for-year-ld* [year]
  (t/plus (easter-sunday-for-year-ld year) (t/days 1)))

(def easter-monday-for-year-ld (memoize easter-monday-for-year-ld*))

(defn easter-monday-for-year [year]
  (tc/to-date (easter-monday-for-year-ld year)))
