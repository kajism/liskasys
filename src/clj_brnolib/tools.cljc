(ns clj-brnolib.tools
  (:require [clojure.string :as str]))

(def transliteration-table {
                            "á" "a"
                            "č" "c"
                            "ď" "d"
                            "d" "d"
                            "é" "e"
                            "ě" "e"
                            "í" "i"
                            "ň" "n"
                            "ó" "o"
                            "ř" "r"
                            "š" "s"
                            "ť" "t"
                            "ú" "u"
                            "ů" "u"
                            "ý" "y"
                            "ž" "z"})

(defn transliteration-text
  "Funkce odstrani z ceskych znaku diakritiku"
  [text]
  (if (nil? text)
    nil
    (str/join ""
              (map (fn [letter] (get   transliteration-table (str letter) letter)) text))))

(defn replace-illegal-char
  "Funkce nahradi nepovolene znaky za podtrzitka"
  [filename]
  (if (nil? filename)
    nil
    (str/replace filename #"[^a-z0-9-\.]" "_")))

(defn replace-multiple-underscores
  "Funkce nahradi vice podtrzitek podtrzitkem jednim"
  [filename]
  (if (nil? filename)
    nil
    (str/replace filename #"[_]+" "_")))

(defn sanitize-filename [filename]
  "Funkce prevede nazev souboru do tvaru bezpecneho k ulozeni"
  (if (or (nil? filename) (str/blank? filename))
    nil
    (-> filename
        str/lower-case
        transliteration-text
        replace-illegal-char
        replace-multiple-underscores)))

(defn ffilter [pred coll]
  (reduce (fn [out e] (when (pred e) (reduced e))) nil coll))

