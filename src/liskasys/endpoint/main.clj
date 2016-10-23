(ns liskasys.endpoint.main
  (:require [liskasys.hiccup :as hiccup]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.validation :as validation]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [compojure.coercions :refer [as-int]]
            [compojure.core :refer :all]
            [datomic.api :as d]
            [environ.core :refer [env]]
            [liskasys.endpoint.main-hiccup :as main-hiccup]
            [liskasys.service :as service]
            [ring.util.response :as response]
            [taoensso.timbre :as timbre]))

(defn- make-date-sets [str-date-seq]
  (when str-date-seq
    (let [yesterday (time/to-date (t/yesterday))]
      (->> str-date-seq
           (map #(time/from-format % time/ddMMyyyy))
           set))))

(defn- upload-dir []
  (or (:upload-dir env) "./uploads/"))

(defn main-endpoint [{{conn :conn} :datomic}]
  (routes
   (context "" {{{roles :-roles :as user} :user} :session}
     (GET "/" {:keys [params]}
       (if-not (roles "parent")
         (response/redirect "/jidelni-listek")
         (let [db (d/db conn)
               child-id (edn/read-string (:child-id params))
               parents-children (service/find-children-by-person-id db (:db/id user))
               selected-person-id (or child-id (:db/id (first parents-children)))
               child-daily-plans (service/find-next-weeks-person-daily-plans db selected-person-id 3)]
           (main-hiccup/liskasys-frame
            user
            (main-hiccup/cancellation-page parents-children selected-person-id child-daily-plans)))))

     (POST "/" {:keys [params]}
       (let [cancel-dates (make-date-sets (:cancel-dates params))
             already-cancelled-dates (make-date-sets (:already-cancelled-dates params))
             child-id (edn/read-string (:child-id params))]
         (service/transact-cancellations conn
                                         (:db/id user)
                                         child-id
                                         (set/difference cancel-dates already-cancelled-dates)
                                         (set/difference already-cancelled-dates cancel-dates))
         (response/redirect (str (when child-id "/?child-id=") child-id))))

     (GET "/platby" {:keys [params]}
       (let [person-bills (service/find-person-bills (d/db conn) (:db/id user))]
         (main-hiccup/liskasys-frame
          user
          (main-hiccup/person-bills person-bills))))

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

     (GET "/login" []
       (hiccup/login-page main-hiccup/system-title))

     (POST "/login" [username pwd :as req]
       (try
         (let [person (service/login (d/db conn) username pwd)]
           (when-not person
             (throw (Exception. "Neplatné uživatelské jméno nebo heslo.")))
           (-> (response/redirect "/" :see-other)
               (assoc-in [:session :user]
                         (-> person
                             (select-keys [:db/id :person/lastname :person/firstname :person/email])
                             (assoc :-roles
                                    (cond-> (->> (str/split (str (:person/roles person)) #",")
                                                 (map str/trim)
                                                 set)
                                      (pos? (count (:person/_parent person)))
                                      (conj "parent")))))))
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
                                                      :person/email (str/trim email)
                                                      :person/phone phone})
         (main-hiccup/liskasys-frame
          user
          (hiccup/user-profile-form params {:type :success :msg "Změny byly uloženy"}))
         (catch Exception e
           (main-hiccup/liskasys-frame
            user
            (hiccup/user-profile-form params {:type :danger :msg (.getMessage (timbre/spy e))})))))

     (GET "/version-info" []
       (->>
        (-> (io/resource "META-INF/maven/liskasys/liskasys/pom.properties")
            slurp
            (str/split #"\n")
            (subvec 1 3))
        (str/join "; " ))))

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
              :user/auth user
              :entity/retract (service/retract-entity conn (:db/id user) ?data)
              :entity/retract-attr (service/retract-attr conn (:db/id user) ?data)
              :entity/history (service/entity-history (d/db conn) ?data)
              :person-bill/generate (service/re-generate-person-bills conn (:db/id user) (:person-bill/period ?data))
              :person-bill/publish-all-bills (service/publish-all-bills conn (:db/id user) (:person-bill/period ?data))
              :person-bill/set-bill-as-paid (service/set-bill-as-paid conn (:db/id user) (:db/id ?data))
              (throw (Exception. (str "Unknown msg-id: " msg-id)))))))))))
