(ns liskasys.system
  (:require [clj-brnolib.component.nrepl-server :refer [nrepl-server]]
            [clj-brnolib.middleware :as middleware]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [com.stuartsierra.component :as component]
            [duct.component.endpoint :refer [endpoint-component]]
            [duct.component.handler :refer [handler-component]]
            [duct.component.hikaricp :refer [hikaricp]]
            [duct.component.ragtime :refer [ragtime]]
            [duct.middleware.not-found :refer [wrap-not-found]]
            [duct.middleware.route-aliases :refer [wrap-route-aliases]]
            [environ.core :refer [env]]
            [liskasys.component.datomic :refer [datomic]]
            [liskasys.component.scheduler :refer [scheduler]]
            [liskasys.endpoint.main :refer [main-endpoint]]
            [meta-merge.core :refer [meta-merge]]
            [ring.component.jetty :refer [jetty-server]]
            [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
            [ring.middleware.format :refer [wrap-restful-format]]
            [ring.middleware.session.cookie :as cookie]
            [ring.util.response :as response]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.3rd-party.rotor :refer [rotor-appender]]
            [taoensso.timbre.appenders.core :refer [println-appender]]))

(def base-config
  {:app {:middleware [middleware/wrap-logging
                      wrap-restful-format
                      [middleware/wrap-auth :api-routes-pattern]
                      [middleware/wrap-exceptions :api-routes-pattern]
                      [wrap-not-found :not-found]
                      [wrap-defaults :defaults]
                      [wrap-route-aliases :aliases]]
         :api-routes-pattern #"/api"
         :not-found  (io/resource "liskasys/errors/404.html")
         :defaults   (meta-merge site-defaults (cond-> {:static {:resources "liskasys/public"}
                                                        :security {:anti-forgery false}
                                                        :proxy true}
                                                 (:dev env)
                                                 (assoc :session {:store (cookie/cookie-store {:key "StursovaListicka"})})))
         :aliases    {}}
   :ragtime {:resource-path "liskasys/migrations"}})

(defn new-system [config]
  (timbre/set-config!
   {:level     (if (:dev env) :debug :info)
    :appenders {:println (println-appender)
                :rotor (rotor-appender
                        {:path "log/liskasys.log"
                         :max-size (* 2 1024 1024)
                         :backlog 10})}})

  (timbre/info "Installing default exception handler")
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread ex]
       (timbre/error ex "Uncaught exception on" (.getName thread)))))

  (let [config (meta-merge base-config config)]
    (-> (component/system-map
         :nrepl (nrepl-server (:nrepl-port config))
         :app  (handler-component (:app config))
         :http (jetty-server (:http config))
         :db   (hikaricp (:db config))
         :datomic (datomic (:datomic config))
         :scheduler (scheduler)
         :ragtime (ragtime (:ragtime config))
         :main (endpoint-component main-endpoint))
        (component/system-using
         {:http [:app]
          :app  [:main]
          :ragtime [:db]
          :scheduler [:db :datomic]
          :main [:db :datomic]}))))
