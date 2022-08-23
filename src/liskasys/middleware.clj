(ns liskasys.middleware
  (:require [ring.util.response :as response]
            [taoensso.timbre :as timbre]
            [clojure.edn :as edn]))

(defn wrap-logging [handler]
  (fn [request]
    (timbre/debug (:request-method request) (:uri request))
    #_(timbre/debug request)
    (handler request)))

(defn wrap-exceptions [handler api-pattern]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (timbre/error e)
        (if-not (re-find api-pattern (:uri request))
          (throw e)
          {:status 500
           :headers {"Content-Type" "text/plain;charset=utf-8"}
           :body (.getMessage e)})))))

(def access-denied-response {:status 403
                             :headers {"Content-Type" "text/plain;charset=utf-8"}
                             :body "Přístup odmítnut. Aktualizujte stránku a přihlašte se."})

(def login-redirect (response/redirect "/login"))

(defn wrap-auth [handler api-pattern]
  (fn [request]
    (let [user (get-in request [:session :user])
          login? (= "/login" (:uri request))
          api-call? (re-find api-pattern (:uri request))]
      (if (or user login?)
        (handler request)
        (if api-call?
          access-denied-response
          login-redirect)))))

(defn wrap-child-id [handler]
  (fn [{:keys [session params] :as request}]
    (let [child-id (or (edn/read-string (:child-id params))
                       (:child-id session))
          response (-> request
                       (assoc-in [:params :child-id] child-id)
                       (handler))]
      (cond-> response
        (:child-id params)
        (assoc :session (assoc session :child-id child-id))))))

(defn wrap-check-server-name [conns handler]
  (fn [{:keys [server-name] :as request}]
    (if-not (contains? conns server-name)
      access-denied-response
      (handler request))))