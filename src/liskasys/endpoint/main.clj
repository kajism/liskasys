(ns liskasys.endpoint.main
  (:require [clj-brnolib.hiccup :as hiccup]
            [clj-brnolib.jdbc-common :as jdbc-common]
            [clojure.pprint :refer [pprint]]
            [compojure.coercions :refer [as-int]]
            [compojure.core :refer :all]
            liskasys.db
            [liskasys.endpoint.main-hiccup :as main-hiccup]
            [ring.util.response :as response :refer [content-type resource-response]]
            [taoensso.timbre :as timbre]
            [clj-brnolib.time :as time]
            [taoensso.truss :as truss]
            [clj-time.core :as clj-time]))

(defn- make-date-sets [str-date-seq]
  (let [yesterday (time/to-date (clj-time/yesterday))]
    (->> (truss/have sequential? str-date-seq)
         (map #(truss/have! (fn [d] (.before yesterday d))
                            (time/from-format % time/ddMMyyyy)))
         set)))

(defn main-endpoint [{{db :spec} :db}]
  (routes
   (context "" {{user :user} :session}
     (GET "/" {:keys [params]}
       (timbre/debug "GET /")
       (main-hiccup/cancellation-form db user params))
     (POST "/" {:keys [params]}
       (timbre/debug "POST /")
       (let [cancel-dates (make-date-sets (:cancel-dates params))
             already-cancelled-dates (make-date-sets (:already-cancelled-dates params))]
         (doseq [cancel-date cancel-dates
                 :when (not (contains? already-cancelled-dates cancel-date))]
           (jdbc-common/save! db :cancellation {:child-id (:child-id params)
                                                :user-id (:id user)
                                                :date cancel-date}))
         (doseq [cancelled-date already-cancelled-dates
                 :when (not (contains? cancel-dates cancelled-date))]
           (jdbc-common/delete! db :cancellation {:child-id (:child-id params)
                                                  :date cancelled-date}))
         (main-hiccup/cancellation-form db user params)))

     (GET "/login" []
       (timbre/debug "GET /login")
       (hiccup/login-page main-hiccup/system-title))
     (POST "/login" [user-name pwd :as req]
       (timbre/debug "POST /login")
       (try
         (when-let [user (first (jdbc-common/select db :user {:email user-name}))]
           ;;TODO check pwd
           (-> (response/redirect "/" :see-other)
               (assoc-in [:session :user] (select-keys user [:id :-fullname]))))
         (catch Exception e
           (hiccup/login-page main-hiccup/system-title (.getMessage e)))))
     (GET "/logout" []
       (timbre/debug "GET /logout")
       (-> (response/redirect "/" :see-other)
           (assoc :session {}))))

   (context "/admin.app" {{user :user} :session}
     (GET "/" []
       (timbre/debug "GET /admin.app/")
       (hiccup/cljs-landing-page main-hiccup/system-title))
     (POST "/api" [req-msg]
       (timbre/debug "POST /admin.app/api request" req-msg)
       (let [[msg-id ?data] req-msg
             table-kw (keyword (namespace msg-id))
             action (name msg-id)]
         #_(when-not (get-in user [:-rights msg-id])
             (throw (Exception. "Not authorized")))
         (response/response
          (case action
            "select" (jdbc-common/select db table-kw {})
            "save" (jdbc-common/save! db table-kw ?data)
            "delete" (jdbc-common/delete! db table-kw {:id ?data})
            (case msg-id
              :user/auth {}
              (throw (Exception. (str "Unknown msg-id: " msg-id)))))))))))
