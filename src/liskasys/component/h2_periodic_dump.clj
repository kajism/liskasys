(ns liskasys.component.h2-periodic-dump
  (:require [clj-brnolib.time :as time]
            [clojure.java.jdbc :as jdbc]
            [com.stuartsierra.component :as component])
  (:import [java.util Timer TimerTask]))

(defn fixed-rate-timer
  ([f period-ms] (fixed-rate-timer f 0 period-ms))
  ([f delay-ms period-ms]
   (let [tt (proxy [TimerTask] []
              (run []
                (f)))]
     (.scheduleAtFixedRate (Timer.) tt delay-ms period-ms)
     #(.cancel tt))))

(defrecord H2PeriodicDump [db dump-hours cancel-fn]
  component/Lifecycle
  (start [component]
    (let [dump-hours (or dump-hours 12)
          dump-fn #(jdbc/query (:spec db) ["SCRIPT TO ?"
                                           (str "./uploads/liskasys-db.sql")])]
      (assoc component :cancel-fn (fixed-rate-timer dump-fn (* 10 1000) (* dump-hours 60 60 1000)))))
  (stop [component]
    (when cancel-fn
      (cancel-fn))
    (assoc component :cancel-fn nil)))

(defn h2-periodic-dump [{:keys [dump-hours] :as config}]
  (map->H2PeriodicDump {:dump-hours dump-hours}))
