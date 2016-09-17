(ns liskasys.endpoint.main
  (:require [clj-brnolib.hiccup :as hiccup]
            [clj-brnolib.jdbc-common :as jdbc-common]
            [clj-brnolib.time :as time]
            [clj-brnolib.validation :as validation]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clojure.edn :as edn]
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
            [taoensso.truss :as truss]))

(defn- make-date-sets [str-date-seq]
  (when str-date-seq
    (let [yesterday (time/to-date (t/yesterday))]
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

     (GET "/jidelni-listek" [history]
       (let [history (or (edn/read-string history) 0)
             {:keys [lunch-menu previous? history]} (service/find-last-lunch-menu (d/db conn) history)]
         (main-hiccup/liskasys-frame
          user
          (main-hiccup/lunch-menu lunch-menu previous? history))))

     #_(GET "/jidelni-listek/:id" [id :<< as-int]
       (let [lunch-menu (first (jdbc-common/select db-spec :lunch-menu {:id id}))]
         (-> (response/file-response (str (upload-dir) "lunch-menu/" (:id lunch-menu) ".dat") {:root "."})
             (response/content-type (:content-type lunch-menu))
             (response/header "Content-Disposition" (str "inline; filename=" (:orig-filename lunch-menu))))))

     (POST "/jidelni-listek" [menu upload]
       (let [id (service/transact-entity conn (:db/id user) {:lunch-menu/text menu
                                                             :lunch-menu/from (-> (t/today) tc/to-date)
                                                             ;; :orig-filename (not-empty (:filename upload))
                                                             ;; :content-type (when (not-empty (:filename upload))
                                                             ;;                 (:content-type upload))
                                                             })
             ;;server-file (str (upload-dir) "lunch-menu/" id ".dat")
             ]
         #_(when (not-empty (:filename upload))
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
                             (select-keys [:db/id :person/lastname :person/firstname :person/email])
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
         (service/change-user-passwd conn (:db/id user) (:person/email user) old-pwd new-pwd new-pwd2)
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
        (hiccup/user-profile-form (-> (service/find-by-id (d/db conn) (:db/id user))
                                      (set/rename-keys {:person/firstname :firstname
                                                        :person/lastname :lastname
                                                        :person/email :email
                                                        :person/phone :phone})) nil)))

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
         (service/transact-entity conn (:db/id user) {:db/id (:db/id user)
                                                      :person/firstname firstname
                                                      :person/lastname lastname
                                                      :person/email email
                                                      :person/phone phone})
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
             ent-type (keyword (namespace msg-id))
             action (name msg-id)]
         (when-not ((:-roles user) "admin")
             (throw (Exception. "Not authorized")))
         (response/response
          (case action
            "select" (service/find-by-type (d/db conn) ent-type ?data)
            "save" (service/transact-entity conn (:db/id user) ?data)
            "delete" (service/retract-entity conn (:db/id user) ?data)
            (case msg-id
              :user/auth {}
              :entity/retract (service/retract-entity conn (:db/id user) ?data)
              :entity/retract-attr (service/retract-attr conn (:db/id user) ?data)
              :person-bill/generate (service/re-generate-person-bills conn (:db/id user) (:person-bill/period ?data))
              :person-bill/all-period-bills-paid (service/all-period-bills-paid conn (:db/id user) (:person-bill/period ?data))
              (throw (Exception. (str "Unknown msg-id: " msg-id)))))))))))
