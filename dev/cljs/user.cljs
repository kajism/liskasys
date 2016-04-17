(ns cljs.user
  (:require [figwheel.client :as figwheel]
            liskasys.cljs.core))

(js/console.info "Starting in development mode")

(enable-console-print!)

(figwheel/start {:websocket-url (str "ws://" (.-hostname js/location) ":3449/figwheel-ws")})
