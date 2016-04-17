(ns liskasys.endpoint.hiccup
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

(defn- hiccup-frame [body]
  (list
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "LiškaSys"]
    #_[:link {:rel "stylesheet" :href "assets/css/bootstrap.css"}]
    [:link {:rel "stylesheet" :href "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.5/css/bootstrap.min.css" :crossorigin "anonymous"}]
    [:link {:rel "stylesheet" :href "assets/css/material-design-iconic-font.min.css"}]
    [:link {:rel "stylesheet" :href "assets/css/re-com.css"}]
    [:link {:rel "stylesheet" :href "css/site.css"}]
    [:link {:href "https://fonts.googleapis.com/css?family=Roboto:300,400,500,700,400italic"
            :rel "stylesheet" :type "text/css"}]
    [:link {:href "https://fonts.googleapis.com/css?family=Roboto+Condensed:400 ,300"
            :rel "stylesheet" :type "text/css"}]]
   [:body
    body
    [:script {:src "https://ajax.googleapis.com/ajax/libs/jquery/1.12.0/jquery.min.js"}]
    [:script {:src "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.6/js/bootstrap.min.js"}]]))

(defn login-page
  ([] (login-page nil))
  ([msg]
   (hiccup-response
    (hiccup-frame
     [:div.container.login
      [:h3 "Vítejte v informačním systému LiškaSys"]
      [:p "Pro přihlášení zadejte své přihlašovací údaje"]
      (when msg
        [:div.alert.alert-danger msg])
      [:form.form-inline {:method "post"}
       [:div.form-group
        [:label {:for "user-name"} "Uživatelské jméno"]
        [:input#user-name.form-control {:name "user-name" :type "text"}]]
       [:div.form-group
        [:label {:for "heslo"} "Heslo"]
        [:input#heslo.form-control {:name "pwd" :type "password"}]]
       (anti-forgery/anti-forgery-field)
       #_[:input {:type "image" :src "img/login.svg"}]
       [:button.btn.btn-default {:type "submit"}
          [:span.glyphicon.glyphicon-log-in] " Přihlásit"]]]))))

(defn cljs-landing-page []
  (hiccup-response
   (hiccup-frame
    [:div
     [:div#app "Startuji LiškaSys ..."]
     (anti-forgery/anti-forgery-field)
     [:script {:src "js/main.js"}]])))
