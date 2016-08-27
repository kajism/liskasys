(ns liskasys.component.scheduler
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [com.stuartsierra.component :as component]
            [liskasys.db :as db]
            [liskasys.service :as service]
            [taoensso.timbre :as timbre]
            [twarc.core :as twarc])
  (:import java.util.TimeZone))

(twarc/defjob h2-dump-to-file-job [scheduler db-spec file]
  (db/h2-dump-to-file db-spec file))

(twarc/defjob send-lunch-order-job [scheduler db-spec]
  (service/send-lunch-order db-spec (-> (t/today)
                                        (t/plus (t/days 1))
                                        tc/to-date)))

(defrecord Scheduler [db twarc-scheduler quartz-props]
  component/Lifecycle
  (start [component]
    (let [sched (-> (twarc/make-scheduler quartz-props)
                    twarc/start)]
      (timbre/info "Scheduling periodic tasks")
      ;; cron expression: sec min hour day-of-mon mon day-of-week ?year
      (h2-dump-to-file-job sched
                           [(:spec db) "./uploads/liskasys-db.sql"]
                           :trigger {:cron {:expression "0 24 3 * * ?" ;; at 03:24AM
                                            :misfire-handling :fire-and-process}})
      (send-lunch-order-job sched
                            [(:spec db)]
                            :trigger {:cron {:expression "0 0 10 * * ?" ;; at 10:00 AM
                                             :misfire-handling :fire-and-process
                                             :time-zone (TimeZone/getTimeZone "Europe/Prague")}})
      (assoc component :twarc-scheduler sched)))
  (stop [component]
    (when twarc-scheduler
      (timbre/info "Stopping scheduler")
      (twarc/stop twarc-scheduler))
    (assoc component :twarc-scheduler nil)))

(defn scheduler []
  (map->Scheduler {:quartz-props
                   {:threadPool.class "org.quartz.simpl.SimpleThreadPool"
                    :threadPool.threadCount 1
                    :plugin.triggHistory.class "org.quartz.plugins.history.LoggingTriggerHistoryPlugin"
                    :plugin.jobHistory.class "org.quartz.plugins.history.LoggingJobHistoryPlugin"}}))
