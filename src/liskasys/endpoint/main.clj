(ns liskasys.endpoint.main
  (:require [clj-brnolib.hiccup :as hiccup]
            [clj-brnolib.jdbc-common :as jdbc-common]
            [clj-brnolib.time :as time]
            [clj-brnolib.validation :as validation]
            [clj-time.core :as clj-time]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as str]
            [compojure.coercions :refer [as-int]]
            [compojure.core :refer :all]
            [datomic.api :as d]
            [environ.core :refer [env]]
            [liskasys.db :as db]
            [liskasys.endpoint.main-hiccup :as main-hiccup]
            [liskasys.service :as service]
            [ring.util.response :as response]
            [taoensso.timbre :as timbre]
            [taoensso.truss :as truss])
  (:import java.io.StringReader
           java.util.Date))

(defn- make-date-sets [str-date-seq]
  (when str-date-seq
    (let [yesterday (time/to-date (clj-time/yesterday))]
      (->> (truss/have sequential? str-date-seq)
           (map #(truss/have! (fn [d] (.before yesterday d))
                              (time/from-format % time/ddMMyyyy)))
           set))))

(defn- upload-dir []
  (or (:upload-dir env) "./uploads/"))

(defn main-endpoint [{{db-spec :spec} :db {conn :conn} :datomic}]
  (routes
   (context "" {{{children-count :-children-count roles :-roles :as user} :user} :session}
     (GET "/" {:keys [params]}
       (if-not (or (pos? children-count) (roles "admin"))
         (response/redirect "/obedy")
         (let [parents-children (db/select-children-by-user-id db-spec (:id user))
               selected-child-id (or (:child-id params) (:id (first parents-children)))
               child-att-days (service/find-next-attendance-weeks db-spec selected-child-id 2)]
           (main-hiccup/liskasys-frame
            user
            (main-hiccup/cancellation-page parents-children selected-child-id child-att-days)))))

     (POST "/" {:keys [params]}
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

     (GET "/jidelni-listek" {:keys [params]}
       (main-hiccup/lunch-menu db-spec user params))

     (GET "/jidelni-listek/:id" [id :<< as-int]
       (let [lunch-menu (first (jdbc-common/select db-spec :lunch-menu {:id id}))]
         (-> (response/file-response (str (upload-dir) "lunch-menu/" (:id lunch-menu) ".dat") {:root "."})
             (response/content-type (:content-type lunch-menu))
             (response/header "Content-Disposition" (str "inline; filename=" (:orig-filename lunch-menu))))))

     (POST "/jidelni-listek" [menu upload]
       (let [id (jdbc-common/insert! db-spec :lunch-menu {:text menu
                                                          :orig-filename (not-empty (:filename upload))
                                                          :content-type (when (not-empty (:filename upload))
                                                                          (:content-type upload))})
             server-file (str (upload-dir) "lunch-menu/" id ".dat")]
         (when (not-empty (:filename upload))
           (io/make-parents server-file)
           (io/copy (:tempfile upload) (io/file server-file))))
       (response/redirect "/jidelni-listek"))

     (GET "/obedy" {:keys [params]}
       (if-not (or ((:-roles user) "admin")
                   ((:-roles user) "obedy"))
         (response/redirect "/")
         (main-hiccup/lunches db-spec user params)))

     (GET "/odhlasene-obedy" {:keys [params]}
       (main-hiccup/cancelled-lunches db-spec user))

     (GET "/login" []
       (hiccup/login-page main-hiccup/system-title))

     (POST "/login" [username pwd :as req]
       (try
         (let [person (service/login (d/db conn) username pwd)]
           (when-not person
             (throw (Exception. "Neplatné uživatelské jméno nebo heslo.")))
           (timbre/info "User" username "just logged in.")
           (-> (response/redirect "/" :see-other)
               (assoc-in [:session :user]
                         (-> person
                             (select-keys [:db/id :-fullname :email])
                             (assoc :-roles (->> (str/split (str (:person/roles person)) #",")
                                                 (map str/trim)
                                                 set))
                             (assoc :-children-count (count (:person/_parent person)))))))
         (catch Exception e
           (hiccup/login-page main-hiccup/system-title (.getMessage e)))))

     (GET "/logout" []
       (-> (response/redirect "/" :see-other)
           (assoc :session {})))

     (GET "/passwd" []
       (main-hiccup/liskasys-frame
        user
        (hiccup/passwd-form nil)))

     (POST "/passwd" [old-pwd new-pwd new-pwd2]
       (try
         (let [user (first (jdbc-common/select db-spec :user {:id (:id user)}))]
           (when-not (= new-pwd new-pwd2)
             (throw (Exception. "Zadaná hesla se neshodují.")))
           (when (or (str/blank? new-pwd) (< (count (str/trim new-pwd)) 6))
             (throw (Exception. "Nové heslo je příliš krátké.")))
           (when-not (service/check-person-password user old-pwd)
             (throw (Exception. "Chybně zadané původní heslo."))))
         #_(jdbc-common/save! db-spec :user {:id (:id user) :passwd (scrypt/encrypt new-pwd)})
         (main-hiccup/liskasys-frame
          user
          (hiccup/passwd-form {:type :success :msg "Heslo bylo změněno"}))
         (catch Exception e
           (main-hiccup/liskasys-frame
            user
            (hiccup/passwd-form {:type :danger :msg (.getMessage (timbre/spy e))})))))

     (GET "/profile" []
       (main-hiccup/liskasys-frame
        user
        (hiccup/user-profile-form (first (jdbc-common/select db-spec :user {:id (:id user)})) nil)))

     (POST "/profile" {{:keys [firstname lastname email phone] :as params} :params}
       (try
         (when (str/blank? firstname)
           (throw (Exception. "Vyplňte své jméno")))
         (when (str/blank? lastname)
           (throw (Exception. "Vyplňte své příjmení")))
         (when-not (validation/valid-email? email)
           (throw (Exception. "Vyplňte správně kontaktní emailovou adresu")))
         (when-not (validation/valid-phone? phone)
           (throw (Exception. "Vyplňte správně kontaktní telefonní číslo")))
         (jdbc-common/save! db-spec :user {:id (:id user) :firstname firstname :lastname lastname :email email :phone phone})
         (main-hiccup/liskasys-frame
          user
          (hiccup/user-profile-form params {:type :success :msg "Změny byly uloženy"}))
         (catch Exception e
           (main-hiccup/liskasys-frame
            user
            (hiccup/user-profile-form params {:type :danger :msg (.getMessage (timbre/spy e))}))))))

   (context "/admin.app" {{user :user} :session}
     (GET "/" []
       (if-not ((:-roles user) "admin")
         (response/redirect "/")
         (hiccup/cljs-landing-page (str main-hiccup/system-title " Admin"))))

     (POST "/api" [req-msg]
       (let [[msg-id ?data] req-msg
             table-kw (keyword (namespace msg-id))
             action (name msg-id)]
         (when-not ((:-roles user) "admin")
             (throw (Exception. "Not authorized")))
         (response/response
          (case action
            "select" (service/find-all (d/db conn) table-kw ?data)
            "save" (service/transact-entity conn (:db/id user) ?data)
            "delete" (service/retract-entity conn (:db/id user) ?data)
            (case msg-id
              :user/auth {}
              :entity/retract (service/retract-entity conn (:db/id user) ?data)
              :entity/retract-attr (service/retract-attr conn (:db/id user) ?data)
              :person-bill/generate (service/re-generate-person-bills db-spec (:period-id ?data))
              (throw (Exception. (str "Unknown msg-id: " msg-id))))))))

     (GET "/save-sql-backup" []
       (jdbc/query db-spec ["SCRIPT TO ?"
                            (str "./db-dump/liskasys-db" (time/to-format (Date.) time/yyyyMMdd-HHmm) ".sql")])
       ;; restore: (jdbc/execute! (user/db-spec) ["RUNSCRIPT FROM './liskasys-db.sql' "])
       (main-hiccup/liskasys-frame
        user
        [:div "Ok"])))))
