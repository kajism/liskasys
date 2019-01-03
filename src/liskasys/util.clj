(ns liskasys.util
  (:import java.io.FileInputStream
           java.text.Collator
           java.util.Locale))

(def cs-collator (Collator/getInstance (Locale. "CS")))

(comment
  (sort-by-locale ["zdar" "dřevo" "ahoj" "babička" "drát" "čáp" "chci"]))

(defn sort-by-locale
  ([coll]
   (sort-by-locale identity coll))
  ([key-fn coll]
   (sort-by key-fn cs-collator coll)))

(defn file-to-byte-array [f]
  (let [ary (byte-array (.length f))
        is (FileInputStream. f)]
    (.read is ary)
    (.close is)
    ary))
