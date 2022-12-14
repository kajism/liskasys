(ns liskasys.endpoint.handler
  (:require [liskasys.endpoint.routes :as routes]
            [liskasys.middleware :as middleware]
            [ring.middleware.defaults :refer [secure-site-defaults wrap-defaults]]
            [ring.middleware.format :refer [wrap-restful-format]]
            [ring.middleware.session.cookie :as session-cookie]
            [ring.util.response :as response]
            [taoensso.timbre :as timbre])
  (:import [java.time Duration OffsetDateTime ZoneOffset]
           java.time.format.DateTimeFormatter))

(defn- middleware-fn [component middleware]
  (if (vector? middleware)
    (let [[f & keys] middleware
          arguments  (map #(get component %) keys)]
      #(apply f % arguments))
    middleware))

(defn- compose-middleware [{:keys [middleware] :as component}]
  (->> (reverse middleware)
       (map #(middleware-fn component %))
       (apply comp identity)))

(defn make-handler [{:keys [datomic] :as deps}]
  (let [routes (routes/api-routes deps)
        wrap-mw (compose-middleware {:middleware
                                     [[middleware/wrap-child-id]
                                      [middleware/wrap-logging]
                                      [wrap-restful-format]
                                      [middleware/wrap-exceptions :api-routes-pattern]
                                      [middleware/wrap-auth :api-routes-pattern]
                                      [(partial middleware/wrap-check-server-name datomic)]
                                      [wrap-defaults :defaults]]
                                     :api-routes-pattern #"/api"
                                     :defaults (merge
                                                 secure-site-defaults
                                                 {:static {:resources "liskasys/public"}
                                                  :security {:anti-forgery false}
                                                  :proxy true
                                                  :session {:store (session-cookie/cookie-store
                                                                     {:key (byte-array [14 -18 73 24 32 12 3 -34 67 -17 19 14 27 -2 71 -120])})
                                                            :cookie-attrs {:max-age (* 30 24 60 60)}}})})
        handler (wrap-mw routes)]
    handler))
