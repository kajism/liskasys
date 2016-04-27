(ns liskasys.endpoint.main
  (:require [clj-brnolib.hiccup :as hiccup]
            [clj-brnolib.jdbc-common :as jdbc-common]
            [clojure.pprint :refer [pprint]]
            [compojure.coercions :refer [as-int]]
            [compojure.core :refer :all]
            liskasys.db
            [liskasys.endpoint.main-hiccup :as main-hiccup]
            [ring.util.response :as response :refer [content-type resource-response]]
            [taoensso.timbre :as timbre]))

(defn main-endpoint [{{db :spec} :db}]
  (routes
   (context "" []
     (GET "/" {:keys [params]}
       (main-hiccup/cancellation-form params))
     (POST "/" {:keys [params]}
       (main-hiccup/cancellation-form params)))

   (context "/sprava" {{user :user} :session}
     (GET "/" []
       (hiccup/cljs-landing-page "Načítám LiškaSys ..."))
     (POST "/api" [req-msg]
       (timbre/debug req-msg)
       (let [[msg-id ?data] req-msg
             table-kw (keyword (namespace msg-id))
             action (name msg-id)]
         #_(when-not (get-in user [:-rights msg-id])
             (throw (Exception. "Not authorized")))
         (response/response
          (case action
            "select" (jdbc-common/select db table-kw {})
            "save" (jdbc-common/save! db table-kw ?data)
            "delete" (jdbc-common/delete! db table-kw ?data)
            (case msg-id
              :user/auth {}
              (throw (Exception. (str "Unknown msg-id: " msg-id)))))))))))
