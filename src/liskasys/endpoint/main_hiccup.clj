(ns liskasys.endpoint.main-hiccup
  (:require [clj-brnolib.hiccup :as hiccup]
            [clj-brnolib.time :as time]
            [clj-time.core :as clj-time]
            [clojure.pprint :refer [pprint]])
  (:import java.util.Date))

(defn cancellation-form [{:keys [user-email var-symbol from to] :as params}]
  (hiccup/hiccup-response
   (hiccup/hiccup-frame
    (let [from (or (time/from-format from time/ddMMyyyy)
                   (time/to-date (clj-time/plus (clj-time/now) (clj-time/days 1))))
          to (time/from-format to time/ddMMyyyy)]
      [:div.container.login
       [:h3 "Zrušení docházky dítěte"]
       [:form {:method "post"
               :role "form"}
        [:div.form-group
         [:label {:for "user-email"} "Kontaktní email rodiče"]
         [:input#user-name.form-control {:name "user-email" :type "text" :value user-email}]]
        [:div.form-group
         [:label {:for "var-symbol"} "Variabilní symbol dítěte"]
         [:input#var-symbol.form-control {:name "var-symbol" :type "text" :value var-symbol}]]
        [:div.form-group
         [:label {:for "from"} "Od"]
         [:input#from.form-control {:name "from" :type "text" :value (time/to-format from time/ddMMyyyy)}]]
        [:div.form-group
         [:label {:for "to"} "Do"]
         [:input#from.form-control {:name "to" :type "text" :value (time/to-format to time/ddMMyyyy)}]]
        #_(anti-forgery/anti-forgery-field)
        [:button.btn.btn-default {:type "submit"}
         "Odeslat"]]
       [:pre (with-out-str (pprint params))]]))))
