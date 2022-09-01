(ns user
  (:require [cljc.portal]
            [clojure.java.io :as io]
            [clojure.repl]
            [clojure.tools.namespace.repl]
            [shadow.cljs.devtools.api :as shadow]
            [shadow.cljs.devtools.server :as shadow-server]
            [datomic.api :as d]
            [liskasys.system :as system]
            [integrant.repl :as ig-repl :refer [halt]]
            [integrant.repl.state :as ig-state]))

(clojure.tools.namespace.repl/set-refresh-dirs "src" "test" "build")

(defonce cljs-started? (atom false))

(defn reset-cljs-build []
  (shadow-server/stop!)
  (shadow-server/start!)
  (shadow/watch :app {:autobuild false})
  (reset! cljs-started? true))

(defn reset []
  (ig-repl/set-prep! (fn [] system/config))
  (ig-repl/reset)
  (when-not @cljs-started?
    (reset-cljs-build))
  (shadow/watch-compile! :app))

(comment
  (shadow-server/reload!)
  (shadow/active-builds)
  (shadow/repl :app)
  (shadow/compile :app)
  (shadow/watch :app)
  (shadow/watch :app {:autobuild false})
  (shadow/watch-compile! :app)
  (shadow/watch-compile-all!)

  (reset)
  (halt)

  (remove-ns 'user)

  (clojure.tools.namespace.repl/clear)
  (clojure.tools.namespace.repl/refresh-all)

  (:app/scheduler ig-state/system)
  )

(when (io/resource "local.clj")
  (load "local"))

(defn conns []
  (:datomic/conns ig-state/system))

(defn conn [server-name]
  (get (conns) server-name))

(defn db [server-name]
  (d/db (conn server-name)))
