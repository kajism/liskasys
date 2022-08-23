(ns cljs.user
  (:require [devtools.core :as devtools]))

(js/console.info "Starting in development mode")

(devtools/install!)

(enable-console-print!)
