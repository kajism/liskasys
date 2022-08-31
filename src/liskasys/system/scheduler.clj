(ns liskasys.system.scheduler
  (:require [liskasys.service :as service]
            [liskasys.cljc.time :as time]
            [liskasys.cljc.util :as cljc.util]
            [taoensso.timbre :as timbre]
            [twarc.core :as twarc]
            [datomic.api :as d]
            [clojure.string :as str])
  (:import java.util.TimeZone))

(twarc/defjob process-cancellation-closing-job [scheduler conn]
  (try
    (service/process-cancellation-closing conn)
    (catch Exception e
      (timbre/error "process-cancellation-closing-job error" e))))

(twarc/defjob process-lunch-order-and-substitutions-job [scheduler conn]
  (try
    (service/process-lunch-order-and-substitutions conn)
    (catch Exception e
      (timbre/error "process-lunch-order-and-substitutions-job error" e))))

(twarc/defjob process-monthly-lunch-orders-job [scheduler conn]
  (try
    (service/process-monthly-lunch-orders conn (-> (time/today)
                                                   (cljc.util/date-yyyymm)
                                                   (cljc.util/previous-yyyymm)))
    (catch Exception e
      (timbre/error "process-monthly-lunch-orders-job error" e))))

(defn start [{conns :datomic}]
  (let [scheduler (-> (twarc/make-scheduler {:threadPool.class "org.quartz.simpl.SimpleThreadPool"
                                             :threadPool.threadCount 1
                                             :plugin.triggHistory.class "org.quartz.plugins.history.LoggingTriggerHistoryPlugin"
                                             :plugin.jobHistory.class "org.quartz.plugins.history.LoggingJobHistoryPlugin"})
                  twarc/start)]
    (doseq [[db-key conn] conns
            :let [db (d/db conn)
                  {:config/keys [cancel-time order-time can-cancel-after-lunch-order?]} (d/pull db '[*] :liskasys/config)
                  [cancel-hour cancel-min] (str/split cancel-time #":")
                  [order-hour order-min] (str/split order-time #":")]]
      (when can-cancel-after-lunch-order?
        ;; cron expression: sec min hour day-of-mon mon day-of-week ?year
        (timbre/info db-key "process-cancellation-closing-job sched at " cancel-hour ":" cancel-min)
        (process-cancellation-closing-job scheduler [conn]
                                          :trigger {:cron {:expression (str "0 " cancel-min " " cancel-hour " * * ?")
                                                           :misfire-handling :fire-and-process
                                                           :time-zone (TimeZone/getTimeZone "Europe/Prague")}}))
      (timbre/info db-key "process-lunch-order-and-substitutions-job sched at " order-hour ":" order-min)
      (process-lunch-order-and-substitutions-job scheduler [conn]
                                                 :trigger {:cron {:expression (str "0 " order-min " " order-hour " * * ?")
                                                                  :misfire-handling :fire-and-process
                                                                  :time-zone (TimeZone/getTimeZone "Europe/Prague")}})

      (timbre/info db-key "process-monthly-lunch-orders-job scheduled")
      (process-monthly-lunch-orders-job scheduler [conn]
                                        :trigger {:cron {:expression (str "0 0 5 1 * ?")
                                                         :misfire-handling :fire-and-process
                                                         :time-zone (TimeZone/getTimeZone "Europe/Prague")}}))
    scheduler))

(defn stop [scheduler]
  (when scheduler
    (timbre/info "Stopping scheduler")
    (twarc/stop scheduler)
    nil))
