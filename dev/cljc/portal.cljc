(ns cljc.portal
  (:require #?@(:cljs [[portal.web :as p]]
                :clj  [[portal.api :as p]])))

(defonce portal-client-ref (atom nil))

(defn open []
  (reset! portal-client-ref (p/open))
  (add-tap #'p/submit))

(defn close []
  (remove-tap #'p/submit)
  (p/close)
  (reset! portal-client-ref nil))

(defn supports-metadata? [x]
  #?(:clj  (instance? clojure.lang.IMeta x)
     :cljs (satisfies? IWithMeta x)))

(defn >>p
  "Print to portal and return x (good inside ->> macro or where value must be returned)
   Optional debug message or data can be put into metadata to distinguish results in portal."
  ([x]
   (tap> x)
   x)
  ([debug-metadata x]
   (tap> (with-meta (if (supports-metadata? x)
                      x
                      {:wrapped-value x})
                    {:debug debug-metadata}))
   x))

(defn >p
  "Print to portal and return x (good inside -> macro or where value must be returned)
   Optional debug message or data can be put into metadata to distinguish results in portal."
  ([x debug-metadata]
   (>>p debug-metadata x)
   x)
  ([x]
   (>>p x)))

(defn pp
  "Print to portal and return true (good for REPL usage to avoid huge data printed in the REPL).
  Optional debug message or data can be put into metadata to distinguish results in portal."
  ([debug-metadata x & xs]
   (->> (into [x] xs)
        (map-indexed vector)
        (run! (fn [[idx x]]
                (>>p (str debug-metadata idx) x))))
   true)
  ([debug-metadata x]
   (>>p debug-metadata x)
   true)
  ([x]
   (>>p x)
   true))

(comment
  (open)
  (pp "nil value" nil)
  (pp "my data" {:my "data" :a 1})
  (pp :no-debug-metadata)
  (-> {:a 1 :b 2}
      (>p)
      (update-vals inc)
      (>p "2nd inside ->"))

  (->> [1 2 3]
       (>>p "inside ->>")
       (map inc)
       (pp))
  @@portal-client-ref
  (close)
  )
