(ns liskasys.component.scheduler
  (:require [clojure.java.jdbc :as jdbc]
            [com.stuartsierra.component :as component]
            [postal.core :as postal]
            [taoensso.timbre :as timbre]
            [twarc.core :as twarc])
  (:import java.util.TimeZone))

(defn h2-dump-to-file [scheduler db-spec file]
  (timbre/info "Dumping DB to " file)
  (jdbc/query db-spec ["SCRIPT TO ?" file]))

(defn send-lunch-order [scheduler db-spec]
  (timbre/info "Sending lunch order"
               (postal/send-message {:from "daniela.chaloupkova@post.cz"
                                     :to "karel.miarka@seznam.cz"
                                     :subject "Objednávka obědů na zítřek"
                                     :body [{:type "text/plain; charset=utf-8"
                                             :content "Testovací objednávka ěščřžýáíúůň"}]})))

(defrecord Scheduler [db twarc-scheduler quartz-props]
  component/Lifecycle
  (start [component]
    (let [sched (-> (twarc/make-scheduler quartz-props)
                    twarc/start)]
      (timbre/info "Scheduling periodic tasks")
      ;; cron expression: sec min hour day-of-mon mon day-of-week ?year
      (twarc/schedule-job sched #'h2-dump-to-file [(:spec db) "./uploads/liskasys-db.sql"]
                          :trigger {:cron {:expression "0 24 3 * * ?" ;; at 03:24AM
                                           :misfire-handling :fire-and-process}})
      (twarc/schedule-job sched #'send-lunch-order [(:spec db)]
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
