(ns liskasys.endpoint.main
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [compojure.coercions :refer [as-int]]
            [compojure.core :refer :all]
            [datomic.api :as d]
            [environ.core :refer [env]]
            [liskasys.db :as db]
            [liskasys.db-queries :as db-queries]
            [liskasys.config :as config]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.validation :as validation]
            [liskasys.endpoint.main-hiccup :as main-hiccup]
            [liskasys.endpoint.main-service :as main-service]
            [liskasys.hiccup :as hiccup]
            [liskasys.qr-code :as qr-code]
            [liskasys.util :as util]
            [ring.util.response :as response]
            [taoensso.timbre :as timbre]
            [liskasys.cljc.util :as cljc.util])
  (:import java.io.ByteArrayInputStream))

(defn- make-date-sets [str-date-seq]
  (->> str-date-seq
       (map #(time/from-format % time/ddMMyyyy))
       (set)))

(defn- upload-dir [server-name]
  (or (:upload-dir env) "./uploads/" (get config/dbs server-name) "/"))

(defn- user-children-data [db user-id selected-id]
  (let [user-children (->> (db-queries/find-active-children-by-person-id db user-id)
                           (util/sort-by-locale cljc.util/person-fullname))
        sid (or selected-id (:db/id (first user-children)))]
    {:user-children user-children
     :selected-child (some #(when (= (:db/id %) sid) %) user-children)
     :selected-id sid}))

(defn main-endpoint [{{conns :conns} :datomic}]
  (routes
   (context "" {{{roles :-roles :as user} :user} :session flash-msg :flash server-name :server-name}
     (GET "/" {:keys [params]}
       (if-not (contains? roles "parent")
         (response/redirect "/jidelni-listek")
         (let [db (d/db (get conns server-name))
               ucd (user-children-data db (:db/id user) (:child-id params))
               child-daily-plans (db-queries/find-next-person-daily-plans db (:selected-id ucd))]
           (main-hiccup/liskasys-frame
            user
            (main-hiccup/cancellation-page ucd child-daily-plans)
            flash-msg))))

     (POST "/" {:keys [params]}
       (let [excuses (->> (:excuse params)
                          (keep (fn [[k v]]
                                  (when-not (str/blank? v)
                                    [(time/from-format k time/ddMMyyyy) v])))
                          (into {}))
             cancel-dates (make-date-sets (:cancel-dates params))
             already-cancelled-dates (make-date-sets (:already-cancelled-dates params))
             out (main-service/transact-cancellations (conns server-name)
                                                      (:db/id user)
                                                      (:child-id params)
                                                      (set/difference cancel-dates already-cancelled-dates)
                                                      (set/difference already-cancelled-dates cancel-dates)
                                                      excuses)]
         (cond-> (response/redirect "/")
           (> out 0)
           (assoc :flash (str "Změny byly uloženy.")))))

     (GET "/nahrady" {:keys [params]}
       (let [db (d/db (conns server-name))
             ucd (user-children-data db (:db/id user) (:child-id params))
             substs (db-queries/find-person-substs db (:selected-id ucd))]
         (if-not (:-substs-page? user)
           (response/redirect "/jidelni-listek")
           (main-hiccup/liskasys-frame
            user
            (main-hiccup/substitutions ucd substs)))))

     (POST "/nahrady" {:keys [params]}
       (let [subst-req-date (-> params
                                :subst-request
                                ffirst
                                (time/from-format time/ddMMyyyy))
             subst-remove-id  (-> params
                                  :subst-remove
                                  ffirst
                                  edn/read-string)]
         (cond
           subst-remove-id
           (db/retract-entity (conns server-name) (:db/id user) subst-remove-id)
           subst-req-date
           (main-service/request-substitution (conns server-name)
                                              (:db/id user)
                                              (:child-id params)
                                              subst-req-date)
           :else
           (timbre/error "Invalid post to /nahrady without req-date or remove-id"))
         (response/redirect "/nahrady")))

     (GET "/platby" {:keys [params]}
          (if-not (:-person-bill-page? user)
            (response/redirect "/jidelni-listek")
            (let [db (d/db (conns server-name))
                  person-bills (db-queries/find-person-bills db (:db/id user))]
              (main-hiccup/liskasys-frame
               user
               (main-hiccup/person-bills person-bills
                                         #(db-queries/find-price-list db (:db/id %)))))))

     (GET "/qr-code" [id :<< as-int]
       (let [db (d/db (conns server-name))
             person-bill (first (db/find-by-type db :person-bill {:db/id id}))
             person (:person-bill/person person-bill)
             {:config/keys [org-name full-url]} (d/pull db '[*] :liskasys/config)
             qr-code-file (qr-code/save-qr-code (db-queries/find-bank-account db (:db/id person))
                                                (/ (:person-bill/total person-bill) 100)
                                                (str (:person/var-symbol person))
                                                org-name
                                                (str (cljc.util/person-fullname person) " "
                                                     (-> person-bill :person-bill/period cljc.util/period->text)))
             qr-code-bytes (util/file-to-byte-array qr-code-file)]
         (.delete qr-code-file)
         (-> (response/response (ByteArrayInputStream. qr-code-bytes))
             (response/content-type "image/png")
             (response/header "Content-Length" (count qr-code-bytes)))))

     (GET "/jidelni-listek" [history]
       (let [{:keys [lunch-menu previous? history]} (main-service/find-last-lunch-menu (d/db (conns server-name)) (edn/read-string history))]
         (main-hiccup/liskasys-frame
          user
          (main-hiccup/lunch-menu lunch-menu previous? history))))

     #_(GET "/jidelni-listek/:id" [id :<< as-int]
         (let [lunch-menu (first (jdbc-common/select db-spec :lunch-menu {:id id}))]
           (-> (response/file-response (str (upload-dir server-name) "lunch-menu/" (:id lunch-menu) ".dat") {:root "."})
               (response/content-type (:content-type lunch-menu))
               (response/header "Content-Disposition" (str "inline; filename=" (:orig-filename lunch-menu))))))

     (POST "/jidelni-listek" [menu upload]
       (let [id (db/transact-entity (conns server-name) (:db/id user) {:lunch-menu/text menu
                                                                       :lunch-menu/from (-> (t/today) tc/to-date)
                                                             ;; :orig-filename (not-empty (:filename upload))
                                                             ;; :content-type (when (not-empty (:filename upload))
                                                             ;;                 (:content-type upload))
})
             ;;server-file (str (upload-dir server-name) "lunch-menu/" id ".dat")
]
         #_(when (not-empty (:filename upload))
             (io/make-parents server-file)
             (io/copy (:tempfile upload) (io/file server-file))))
       (response/redirect "/jidelni-listek"))

     (GET "/login" []
       (hiccup/login-page main-hiccup/system-title))

     (POST "/login" [username pwd :as req]
       (try
         (let [db (d/db (conns server-name))
               person (main-service/login db username pwd)
               {:config/keys [org-name person-bill-page? substs-page?]} (d/pull db '[*] :liskasys/config)]
           (when-not person
             (throw (Exception. "Neplatné uživatelské jméno nebo heslo.")))
           (-> (response/redirect "/" :see-other)
               (assoc-in [:session :user]
                         (-> person
                             (select-keys [:db/id :person/lastname :person/firstname :person/email])
                             (assoc :-roles
                                    (cond-> (->> (str/split (str (:person/roles person)) #",")
                                                 (map str/trim)
                                                 (set))
                                      (some :person/active? (:person/_parent person))
                                      (conj "parent"))
                                    :-server-name server-name
                                    :-org-name org-name
                                    :-person-bill-page? person-bill-page?
                                    :-substs-page? substs-page?)))))
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
         (main-service/change-user-passwd (conns server-name) (:db/id user) (:person/email user) old-pwd new-pwd new-pwd2)
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
        (hiccup/user-profile-form (-> (db/find-by-id (d/db (conns server-name)) (:db/id user))
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
         (db/transact-entity (conns server-name) (:db/id user) {:db/id (:db/id user)
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
        (str/join "; "))))

   (context "/admin.app" {{user :user} :session server-name :server-name}
     (GET "/" []
         (if-not (some (:-roles user) ["admin" "inspektor"])
           (response/redirect "/")
           (hiccup/cljs-landing-page (str main-hiccup/system-title " Admin: " (:-org-name user)))))

     (POST "/api" [req-msg]
       (let [[msg-id ?data] req-msg
             ent-type (keyword (namespace msg-id))
             action (name msg-id)
             conn (conns server-name)]
         (when-not (some (:-roles user) ["admin" "inspektor"])
           (throw (Exception. "Not authorized")))
         (when-not (or (= msg-id :user/auth)
                       (= action "select")
                       (contains? (:-roles user) "admin"))
           (throw (Exception. "Not authorized")))
         (response/response
          (case action
            "select" (db/find-by-type (d/db conn) ent-type ?data)
            "save" (db/transact-entity conn (:db/id user) ?data)
            "delete" (db/retract-entity conn (:db/id user) ?data)
            (case msg-id
              :user/auth user
              :entity/retract (db/retract-entity conn (:db/id user) ?data)
              :entity/retract-attr (db/retract-attr conn (:db/id user) ?data)
              :entity/history (db/entity-history (d/db conn) ?data)
              :person-bill/generate (main-service/re-generate-person-bills conn (:db/id user) (:person-bill/period ?data))
              :person-bill/publish-all-bills (main-service/publish-all-bills conn (:db/id user) (:person-bill/period ?data))
              :person-bill/set-bill-as-paid (main-service/set-bill-as-paid conn (:db/id user) (:db/id ?data))
              :tx/datoms (db/tx-datoms conn ?data)
              :tx/range (db/last-txes conn (:from-idx ?data) (:n ?data))
              (throw (Exception. (str "Unknown msg-id: " msg-id)))))))))))
