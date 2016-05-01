(ns liskasys.endpoint.main
  (:require [clj-brnolib.hiccup :as hiccup]
            [clj-brnolib.jdbc-common :as jdbc-common]
            [clj-brnolib.time :as time]
            [clj-time.core :as clj-time]
            [clojure.set :as set]
            [clojure.string :as str]
            [compojure.core :refer :all]
            [crypto.password.scrypt :as scrypt]
            [liskasys.db :as db]
            [liskasys.endpoint.main-hiccup :as main-hiccup]
            [ring.util.response :as response]
            [taoensso.timbre :as timbre]
            [taoensso.truss :as truss]))

(defn- make-date-sets [str-date-seq]
  (when str-date-seq
    (let [yesterday (time/to-date (clj-time/yesterday))]
      (->> (truss/have sequential? str-date-seq)
           (map #(truss/have! (fn [d] (.before yesterday d))
                              (time/from-format % time/ddMMyyyy)))
           set))))

(defn- check-password [db-spec user pwd]
  (if (:passwd user)
    (scrypt/check pwd (:passwd user))
    (->> (db/select-children-by-user-id db-spec (:id user))
         (filter #(= pwd (str (:var-symbol %))))
         not-empty)))

(defn main-endpoint [{{db-spec :spec} :db}]
  (routes
   (context "" {{user :user} :session}
     (GET "/" {:keys [params]}
       (timbre/debug "GET /")
       (main-hiccup/cancellation-form db-spec user params))

     (POST "/" {:keys [params]}
       (timbre/debug "POST /")
       (let [cancel-dates (make-date-sets (:cancel-dates params))
             already-cancelled-dates (make-date-sets (:already-cancelled-dates params))]
         (doseq [cancel-date (set/difference cancel-dates already-cancelled-dates)]
           (jdbc-common/save! db-spec :cancellation {:child-id (:child-id params)
                                                     :user-id (:id user)
                                                     :date cancel-date}))
         (doseq [uncancel-date (set/difference already-cancelled-dates cancel-dates)]
           (jdbc-common/delete! db-spec :cancellation {:child-id (:child-id params)
                                                       :date uncancel-date}))
         (response/redirect "")
         #_(main-hiccup/cancellation-form db user params)))

     (GET "/login" []
       (timbre/debug "GET /login")
       (hiccup/login-page main-hiccup/system-title))

     (POST "/login" [user-name pwd :as req]
       (timbre/debug "POST /login")
       (try
         (let [user (first (jdbc-common/select db-spec :user {:email user-name}))]
           (when-not (and user (check-password db-spec user pwd))
             (throw (Exception. "Neplatné uživatelské jméno nebo heslo.")))
           (-> (response/redirect "/" :see-other)
               (assoc-in [:session :user] (select-keys user [:id :-fullname :roles]))))
         (catch Exception e
           (hiccup/login-page main-hiccup/system-title (.getMessage e)))))

     (GET "/logout" []
       (timbre/debug "GET /logout")
       (-> (response/redirect "/" :see-other)
           (assoc :session {})))

     (GET "/passwd" []
       (timbre/debug "GET /passwd")
       (hiccup/passwd-page main-hiccup/system-title))

     (POST "/passwd" [old-pwd new-pwd new-pwd2]
       (timbre/debug "POST /passwd")
       (try
         (let [user (first (jdbc-common/select db-spec :user {:id (:id user)}))]
           (when-not (check-password db-spec user old-pwd)
             (throw (Exception. "Chybně zadané původní heslo.")))
           (when-not (= new-pwd new-pwd2)
             (throw (Exception. "Zadaná hesla se neshodují.")))
           (when (or (str/blank? new-pwd) (< (count (str/trim new-pwd)) 6))
             (throw (Exception. "Nové heslo je příliš krátké.")))
           (jdbc-common/save! db-spec :user {:id (:id user) :passwd (scrypt/encrypt new-pwd)})
           (-> (response/redirect "/" :see-other)))
         (catch Exception e
           (hiccup/passwd-page main-hiccup/system-title (.getMessage (timbre/spy e)))))))

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
            "select" (jdbc-common/select db-spec table-kw {})
            "save" (jdbc-common/save! db-spec table-kw ?data)
            "delete" (jdbc-common/delete! db-spec table-kw {:id ?data})
            (case msg-id
              :user/auth {}
              (throw (Exception. (str "Unknown msg-id: " msg-id)))))))))))
