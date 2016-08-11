(ns clj-brnolib.validation
  (:require [clojure.string :as str]))

(defn valid-name? [name] ;http://stackoverflow.com/questions/3617797/regex-to-match-only-letters
  (and (not (str/blank? name))
       (re-matches #"^[A-Z\u00C0-\u017F][a-zA-Z\u00C0-\u017F]+$" name)))

(defn valid-email? [email] ;http://stackoverflow.com/questions/33736473/how-to-validate-email-in-clojure
  (let [pattern #"[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)+[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"]
    (and (not (str/blank? email))
         (re-matches pattern email))))

(defn valid-phone? [phone]
  (and (not (str/blank? phone))
       (re-matches #"^\+?\d{9,14}$" (str/replace phone #"\s" ""))))
