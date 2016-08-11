(ns clj-brnolib.middleware
  (:require [ring.util.response :as response]
            [taoensso.timbre :as timbre]))

(defn wrap-logging [handler]
  (fn [request]
    (timbre/debug (:request-method request) (:uri request))
    #_(timbre/debug request)
    (handler request)))

(defn wrap-exceptions [handler api-pattern]
  (fn [request]
    (if-not (re-find api-pattern (:uri request))
      (handler request)
      (try
        (handler request)
        (catch Exception e
          (timbre/info e)
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
