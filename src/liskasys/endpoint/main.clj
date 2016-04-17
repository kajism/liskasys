(ns liskasys.endpoint.main
  (:require [clojure.pprint :refer [pprint]]
            [compojure.coercions :refer [as-int]]
            [compojure.core :refer :all]
            [liskasys.endpoint.hiccup :as hiccup]
            [ring.util.response :as response :refer [content-type resource-response]]
            [taoensso.timbre :as timbre]))

(defn main-endpoint [{{db :spec} :db}]
  (routes
   (context "" []
     (GET "/" []
       (hiccup/cljs-landing-page)))

   (context "/api" {{user :user} :session}
     (POST "/" [req-msg]
       (timbre/debug req-msg)
       (let [[msg-id ?data] req-msg]
         #_(when-not (get-in user [:-rights msg-id])
             (throw (Exception. "Not authorized")))
         (response/response
          (case msg-id
            :user/auth {}
            (throw (Exception. (str "Unknown msg-id: " msg-id))))))))))
