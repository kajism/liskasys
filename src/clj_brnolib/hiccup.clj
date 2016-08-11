(ns clj-brnolib.hiccup
  (:require [clojure.pprint :refer [pprint]]
            [hiccup.page :as hiccup]
            [ring.util.anti-forgery :as anti-forgery]
            [ring.util.response :as response]))

(defn hiccup-response
  [body]
  (-> (hiccup/html5 {:lang "cs"}
                    body)
      response/response
      (response/content-type "text/html")
      (response/charset "utf-8")))

(defn hiccup-pprint
  [data]
  [:pre (with-out-str (pprint data))])

(defn hiccup-frame
  ([title body]
   (hiccup-frame title body true))
  ([title body re-com-styles?]
   (list
    [:head
     [:meta {:charset "UTF-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title title]
     [:link {:rel "stylesheet" :href "/assets/css/bootstrap.css"}]
     #_[:link {:rel "stylesheet" :href "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.5/css/bootstrap.min.css" :crossorigin "anonymous"}]
     [:link {:rel "stylesheet" :href "/css/site.css"}]
     (when re-com-styles?
       (list
        [:link {:rel "stylesheet" :href "/assets/css/material-design-iconic-font.min.css"}]
        [:link {:rel "stylesheet" :href "/assets/css/re-com.css"}]
        [:link {:href "https://fonts.googleapis.com/css?family=Roboto:300,400,500,700,400italic"
                :rel "stylesheet" :type "text/css"}]
        [:link {:href "https://fonts.googleapis.com/css?family=Roboto+Condensed:400 ,300"
                :rel "stylesheet" :type "text/css"}]))]
    [:body
     body
     [:script {:src "https://ajax.googleapis.com/ajax/libs/jquery/1.12.0/jquery.min.js"}]
     [:script {:src "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.6/js/bootstrap.min.js"}]])))

(defn login-page
  ([title] (login-page title nil))
  ([title msg]
   (hiccup-response
    (hiccup-frame
     title
     [:div.container
      [:h3 "Přihlašovací formulář"]
      (when msg
        [:div.alert.alert-danger msg])
      [:form {:method "post" :role "form"}
       [:div.form-group
        [:label {:for "username"} "Uživatelské jméno"]
        [:input#user-name.form-control {:name "username" :type "text"}]]
       [:div.form-group
        [:label {:for "heslo"} "Heslo"]
        [:input#heslo.form-control {:name "pwd" :type "password"}]]
       (anti-forgery/anti-forgery-field)
       [:button.btn.btn-success {:type "submit"} "Přihlásit se"]]]))))

(defn cljs-landing-page
  ([title]
   (hiccup-response
    (hiccup-frame title
                  [:div
                   [:div#app "Načítám " title "..."]
                   (anti-forgery/anti-forgery-field)
                   [:script {:src "/js/main.js"}]]))))

(defn passwd-form [{:keys [type msg] :as alert}]
  [:div.container
   [:h3 "Změna hesla"]
   (when alert
     [:div {:class (str "alert alert-" (name type))} msg])
   [:form {:method "post" :role "form"}
    [:div.form-group
     [:label {:for "old-pwd"} "Staré heslo"]
     [:input#user-name.form-control {:name "old-pwd" :type "password"}]]
    [:div.form-group
     [:label {:for "new-pwd"} "Nové heslo"]
     [:input#heslo.form-control {:name "new-pwd" :type "password"}]]
    [:div.form-group
     [:label {:for "new-pwd2"} "Nové heslo znovu"]
     [:input#heslo.form-control {:name "new-pwd2" :type "password"}]]
    (anti-forgery/anti-forgery-field)
    [:button.btn.btn-danger {:type "submit"} "Změnit heslo"]]])

(defn user-profile-form [params {:keys [type msg] :as alert}]
  [:div.container
   [:h3 "Údaje o uživateli"]
   (when alert
     [:div.alert {:class (str "alert-" (name type))} msg])
   [:form {:method "post" :role "form"}
    [:div.form-group
     [:label {:for "firstname"} "Jméno"]
     [:input#user-name.form-control {:name "firstname" :type "text" :value (:firstname params)}]]
    [:div.form-group
     [:label {:for "lastname"} "Příjmení"]
     [:input#heslo.form-control {:name "lastname" :type "text" :value (:lastname params)}]]
    [:div.form-group
     [:label {:for "email"} "Email"]
     [:input#heslo.form-control {:name "email" :type "text" :value (:email params)}]]
    [:div.form-group
     [:label {:for "phone"} "Telefon"]
     [:input#heslo.form-control {:name "phone" :type "text" :value (:phone params)}]]
    (anti-forgery/anti-forgery-field)
    [:button.btn.btn-success {:type "submit"} "Uložit"]]])
