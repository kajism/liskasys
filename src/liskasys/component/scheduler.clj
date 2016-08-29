(ns liskasys.component.scheduler
  (:require [com.stuartsierra.component :as component]
            [liskasys.db :as db]
            [liskasys.service :as service]
            [taoensso.timbre :as timbre]
            [twarc.core :as twarc])
  (:import java.util.TimeZone))

(twarc/defjob h2-dump-to-file-job [scheduler db-spec file]
  (db/h2-dump-to-file db-spec file))

(twarc/defjob close-lunch-order-job [scheduler db-spec]
  (service/close-lunch-order db-spec (db/tomorrow) 0))

(twarc/defjob send-lunch-order-job [scheduler db-spec]
  (service/send-lunch-order db-spec (db/tomorrow)))

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
      (close-lunch-order-job sched
                             [(:spec db)]
                             :trigger {:cron {:expression "0 1 10 * * ?" ;; at 10:01 AM
                                              :misfire-handling :fire-and-process
                                              :time-zone (TimeZone/getTimeZone "Europe/Prague")}})
      (send-lunch-order-job sched
                            [(:spec db)]
                            :trigger {:cron {:expression "0 2 10 * * ?" ;; at 10:02 AM
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
