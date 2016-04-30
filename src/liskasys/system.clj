(ns liskasys.system
  (:require [clj-brnolib.middleware :as middleware]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [com.stuartsierra.component :as component]
            [duct.component.endpoint :refer [endpoint-component]]
            [duct.component.handler :refer [handler-component]]
            [duct.component.hikaricp :refer [hikaricp]]
            [duct.component.ragtime :refer [ragtime]]
            [duct.middleware.not-found :refer [wrap-not-found]]
            [duct.middleware.route-aliases :refer [wrap-route-aliases]]
            [liskasys.endpoint.main :refer [main-endpoint]]
            [meta-merge.core :refer [meta-merge]]
            [ring.component.jetty :refer [jetty-server]]
            [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
            [ring.middleware.format :refer [wrap-restful-format]]
            [ring.middleware.webjars :refer [wrap-webjars]]
            [ring.util.response :as response]))

(def base-config
  {:app {:middleware [wrap-restful-format
                      [middleware/wrap-auth :api-routes-pattern]
                      [middleware/wrap-exceptions :api-routes-pattern]
                      [wrap-not-found :not-found]
                      [wrap-webjars]
                      [wrap-defaults :defaults]
                      [wrap-route-aliases :aliases]]
         :api-routes-pattern #"/api"
         :not-found  (io/resource "liskasys/errors/404.html")
         :defaults   (meta-merge site-defaults {:static {:resources "liskasys/public"}
                                                :security {:anti-forgery false}})
         :aliases    {}}
   :ragtime {:resource-path "liskasys/migrations"}})

(defn new-system [config]
  (let [config (meta-merge base-config config)]
    (-> (component/system-map
         :app  (handler-component (:app config))
         :http (jetty-server (:http config))
         :db   (hikaricp (:db config))
         :ragtime (ragtime (:ragtime config))
         :main (endpoint-component main-endpoint))
        (component/system-using
         {:http [:app]
          :app  [:main]
          :ragtime [:db]
          :main [:db]}))))
