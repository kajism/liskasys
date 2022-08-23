(ns liskasys.main
  (:gen-class)
  (:require [liskasys.system :refer [config]]
            [integrant.core :as ig]))

(def system (atom nil))

(defn -main [& args]
  (reset! system (ig/init config))
  (let [^Runnable stop-fn #(ig/halt! @system)]
    (.addShutdownHook (Runtime/getRuntime) (Thread. stop-fn))))
