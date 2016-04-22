(ns liskasys.cljs.common
  (:require [liskasys.cljc.schema :as schema]
            [re-frame.core :as re-frame]
            [schema.core :as s]))

(def debug-mw [(when ^boolean goog.DEBUG re-frame/debug)
               (when ^boolean goog.DEBUG (re-frame/after
                                          #(if-let [res (s/check schema/AppDb %)]
                                             (.error js/console (str "schema problem: " res)))))])
