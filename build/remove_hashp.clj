(ns remove-hashp
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn file-name [f]
  (-> f
      (.toPath)
      (.getFileName)
      (str)))

(defn remove-hashp [dirs]
  (doseq [dir dirs]
    (->> (file-seq (io/file dir))
         (map (fn [f]
                [f (file-name f)]))
         (filter (fn [[f fname]]
                   (and (not= fname "remove_hashp.clj")
                        (or (str/ends-with? fname ".clj")
                            (str/ends-with? fname ".cljc")
                            (str/ends-with? fname ".cljs")))))
         (run! (fn [[f fname]]
                 (let [src (slurp f)]
                   (when (or (str/includes? src "#p")
                             (str/includes? src "[hashp.core]"))
                     (println "Removing hashp from" fname)
                     (->> (str/replace src #"#p\ *|\s*\(:require \[hashp.core\]\)|\s+\[hashp.core\]" "")
                          (spit f)))))))))
