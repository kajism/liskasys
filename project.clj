(defproject liskasys "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :min-lein-version "2.0.0"
  :jvm-opts ["-Duser.timezone=UTC"]
  :dependencies [[cljs-ajax "0.5.2"]
                 [org.apache.httpcomponents/httpclient "4.5.1"]
                 #_[com.andrewmcveigh/cljs-time "0.3.14"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.8.40"]
                 #_[org.clojure/tools.nrepl "0.2.12"]
                 [clj-brnolib "0.1.0-SNAPSHOT"]
                 [com.stuartsierra/component "0.3.1"]
                 [compojure "1.5.0"]
                 [duct "0.5.10"]
                 [environ "1.0.2"]
                 [meta-merge "0.1.1"]
                 [ring-jetty-component "0.3.1"]
                 [ring-webjars "0.1.1"]
                 [org.slf4j/slf4j-nop "1.7.14"]
                 [duct/hikaricp-component "0.1.0"]
                 [com.h2database/h2 "1.4.191"]
                 [duct/ragtime-component "0.1.3"]
                 #_[re-frame "0.7.0"]
                 [re-com "0.8.0"]
                 [secretary "1.2.3"]
                 #_[com.cognitect/transit-clj "0.8.285"]
                 #_[com.cognitect/transit-cljs "0.8.237"]
                 [ring-middleware-format "0.7.0"]
                 [crypto-password "0.2.0"]
                 #_[com.taoensso/timbre "4.3.1"]
                 #_[prismatic/schema "1.1.0"]
                 [crypto-password "0.2.0"]]
  :plugins [[lein-environ "1.0.2"]
            [lein-gen "0.2.2"]
            [lein-cljsbuild "1.1.2"]]
  :generators [[duct/generators "0.5.10"]]
  :duct {:ns-prefix liskasys}
  :main ^:skip-aot liskasys.main
  :target-path "target/%s/"
  :resource-paths ["resources" "target/cljsbuild"]
  :prep-tasks [["javac"] ["cljsbuild" "once"] ["compile"]]
  :cljsbuild
  {:builds
   {:main {:jar true
           :source-paths ["src"]
           :compiler {:output-to "target/cljsbuild/liskasys/public/js/main.js"
                      :closure-defines {:goog.DEBUG false}
                      :optimizations :advanced}}}}
  :aliases {"gen"   ["generate"]
            "setup" ["do" ["generate" "locals"]]}
  :profiles
  {:dev  [:project/dev  :profiles/dev]
   :test [:project/test :profiles/test]
   :repl {:resource-paths ^:replace ["resources" "target/figwheel"]
          :prep-tasks     ^:replace [["javac"] ["compile"]]}
   :uberjar {:aot :all}
   :profiles/dev  {}
   :profiles/test {}
   :project/dev   {:dependencies [[com.cemerick/piggieback "0.2.1"]
                                  [com.datomic/datomic-free "0.9.5350"]
                                  [duct/figwheel-component "0.3.2"]
                                  [eftest "0.1.1"]
                                  [figwheel "0.5.0-6"]
                                  [kerodon "0.7.0"]
                                  [org.clojure/tools.namespace "0.2.11"]
                                  [reloaded.repl "0.2.1"]]
                   :source-paths ["dev"]
                   :repl-options {:init-ns user
                                  :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                   :env {:dev true
                         :port "3000"
                         :database-url "jdbc:h2:./liskasys-dev.db"}}
   :project/test  {}})
