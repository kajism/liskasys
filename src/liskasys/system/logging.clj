(ns liskasys.system.logging
  (:require [clojure.string :as str]
            [environ.core :refer [env]]
            [taoensso.encore :as enc]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.3rd-party.rotor :as rotor]
            [taoensso.timbre.appenders.core :as appenders]))

(defn- edn-output-fn [{:keys [level timestamp_ ?ns-str ?line vargs msg_]}]
  (let [out {:ts (force timestamp_)
             :l level
             :th (.getName (Thread/currentThread))
             :s (str ?ns-str ":" ?line)}]
    (pr-str
      (if (map? (first vargs))
        (merge out (first vargs))
        (assoc out :msg (force msg_))))))

(defn- liskasys-output-fn
  "Default (fn [data]) -> string output fn.
    Use`(partial default-output-fn <opts-map>)` to modify default opts."
  ([     data] (liskasys-output-fn nil data))
  ([opts data] ; For partials
   (let [{:keys [no-stacktrace?]} opts
         {:keys [level ?err msg_ ?ns-str ?file timestamp_ ?line]} data]
     (str
       (when-let [ts (force timestamp_)] (str ts " "))
       (str/upper-case (name level))  " "
       "[" (or ?ns-str ?file "?") ":" (or ?line "?") "] - "
       (force msg_)
       (when-not no-stacktrace?
         (when-let [err ?err]
           (str enc/system-newline (timbre/stacktrace err opts))))
       "  @" (.getName (Thread/currentThread))))))

(defn- dev-console-output-fn
  "Shorter output. Omits timestamp, log level, 'liskasys' ns, thread"
  ([     data] (dev-console-output-fn nil data))
  ([opts data] ; For partials
   (let [{:keys [no-stacktrace?]} opts
         {:keys [level ?err msg_ ?ns-str ?file timestamp_ ?line]} data]
     (str
       "[" (or (str/replace-first ?ns-str "liskasys." "") ?file "?") ":" (or ?line "?") "] - "
       (force msg_)
       (when-not no-stacktrace?
         (when-let [err ?err]
           (str enc/system-newline (timbre/stacktrace err opts))))))))

(def dev-rotor (rotor/rotor-appender
                 {:path "log/liskasys.log"
                  :max-size (* 8 1024 1024)
                  :backlog 10}))

(timbre/set-config!
 (if (:dev env)
   {:min-level [[#{"liskasys.*"} :debug]
                [#{"*"} :warn]]
    :output-fn liskasys-output-fn
    :appenders {:println
                (-> (appenders/println-appender)
                    (assoc :output-fn dev-console-output-fn)
                    (assoc :min-level (if (#{"kajism" "peter"} (:user-name env)) :warn nil)))
                :rotor dev-rotor}}
   ;; production logging config
   {:min-level [[#{"liskasys.*"} :info]
                [#{"*"} :warn]]
    :output-fn liskasys-output-fn
    :appenders {:println (-> (appenders/println-appender)
                             (assoc :min-level :warn)) ;; less printing to console
                :rotor (rotor/rotor-appender
                        {:path "log/liskasys.log"
                         :max-size (* 32 1024 1024) ;; more logging history
                         :backlog 20})}}))
