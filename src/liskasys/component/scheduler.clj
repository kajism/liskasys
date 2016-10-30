(ns liskasys.component.scheduler
  (:require [com.stuartsierra.component :as component]
            [liskasys.cljc.time :as time]
            [liskasys.service :as service]
            [taoensso.timbre :as timbre]
            [twarc.core :as twarc])
  (:import java.util.TimeZone))

(twarc/defjob close-lunch-order-job [scheduler conn]
  (service/close-lunch-order conn (time/tomorrow)))

(twarc/defjob process-substitutions-job [scheduler conn]
  (service/process-substitutions conn (time/tomorrow)))

(twarc/defjob process-lunch-order-job [scheduler conn]
  (service/process-lunch-order conn (time/tomorrow)))

(defrecord Scheduler [datomic twarc-scheduler quartz-props]
  component/Lifecycle
  (start [component]
    (let [sched (-> (twarc/make-scheduler quartz-props)
                    twarc/start)]
      (timbre/info "Scheduling periodic tasks")
      ;; cron expression: sec min hour day-of-mon mon day-of-week ?year
      (close-lunch-order-job sched
                             [(:conn datomic)]
                             :trigger {:cron {:expression "0 1 10 * * ?" ;; at 10:01:00 AM
                                              :misfire-handling :fire-and-process
                                              :time-zone (TimeZone/getTimeZone "Europe/Prague")}})

      (process-substitutions-job sched
                             [(:conn datomic)]
                             :trigger {:cron {:expression "30 1 10 * * ?" ;; at 10:01:30 AM
                                              :misfire-handling :fire-and-process
                                              :time-zone (TimeZone/getTimeZone "Europe/Prague")}})

      (process-lunch-order-job sched
                            [(:conn datomic)]
                            :trigger {:cron {:expression "0 2 10 * * ?" ;; at 10:02:00 AM
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
