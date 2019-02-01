(ns cljs.user
  (:require [devtools.core :as devtools]
            [figwheel.client :as figwheel]
            [liskasys.cljs.core]))

(js/console.info "Starting in development mode")

(devtools/install!)

(enable-console-print!)

(figwheel/start {:websocket-url (str "ws://" (.-hostname js/location) ":2449/figwheel-ws")})
