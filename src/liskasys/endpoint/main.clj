(ns liskasys.endpoint.main
  (:require [compojure.core :refer :all]
            [liskasys.endpoint.hiccup :as hiccup]))

(defn main-endpoint [{{db :spec} :db}]
  (context "" []
    (GET "/" []
      (hiccup/cljs-landing-page))))
