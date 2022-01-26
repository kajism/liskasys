(defproject liskasys "1.0.127-SNAPSHOT"
  :description "Web information, attendance and lunch cancelation/ordering system for a forest kidergartens"
  :url "https://github.com/kajism/liskasys"
  :min-lein-version "2.0.0"
  :jvm-opts ["-Duser.timezone=UTC" "-XX:-OmitStackTraceInFastThrow"]
  :javac-options ["-target" "1.8" "-source" "1.8"]
  :dependencies [[cljs-ajax "0.8.4"]
                 [org.apache.httpcomponents/httpclient "4.5.13"]
                 [org.clojure/clojure "1.10.3"]
                 [org.clojure/clojurescript "1.10.891"]
                 #_[org.clojure/core.async "1.5.648"]
                 [org.clojure/core.cache "1.0.225"]
                 [org.clojure/core.memoize "1.0.253"]
                 [nrepl "0.9.0"]
                 [clj-time "0.15.2"] ;;tick?
                 [com.andrewmcveigh/cljs-time "0.5.2"];;tick?
                 [com.cognitect/transit-clj "1.0.324"]
                 [com.cognitect/transit-cljs "0.8.256"]
                 [com.datomic/datomic-free "0.9.5703.21"
                  :exclusions [com.google.guava/guava]]
                 [com.stuartsierra/component "1.0.0"]
                 [hiccup "1.0.5"]
                 [ring "1.9.4"]
                 [ring/ring-defaults "0.3.3"]
                 [re-com "2.13.2"]
                 [re-frame "1.2.0"]
                 [com.taoensso/timbre "5.1.2"]
                 #_[org.clojure/tools.namespace "0.3.0-alpha3"];;1.1.0 ???
                 [compojure "1.6.2"]
                 [duct "0.5.10"]
                 [environ "1.2.0"]
                 [meta-merge "1.0.0"]
                 [ring-jetty-component "0.3.1"]
                 [http-kit "2.5.3"]
                 [org.slf4j/slf4j-nop "1.7.30"]
                 [secretary "1.2.3"] ;;reitit
                 [ring-middleware-format "0.7.4"]
                 [crypto-password "0.3.0"]
                 [twarc "0.1.15"]
                 [com.draines/postal "2.0.4"]
                 [io.rkn/conformity "0.5.4"]
                 [cz.geek/geek-spayd "0.2.0"]]
  :plugins [[lein-environ "1.0.2"]
            [lein-gen "0.2.2"]
            [lein-cljsbuild "1.1.8"]];;cljs-shadow
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
                      :closure-defines {"goog.DEBUG" false}
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
   :project/dev   {:dependencies [[binaryage/devtools "1.0.3"]
                                  #_[cider/piggieback "0.5.2"]
                                  [duct/figwheel-component "0.3.4"]
                                  [eftest "0.5.9"]
                                  [figwheel "0.5.20"]
                                  [figwheel-sidecar "0.5.20"]
                                  #_[kerodon "0.9.1"]
                                  #_[org.clojure/test.check "0.9.0"]
                                  [org.clojure/tools.namespace "1.1.0"]
                                  [reloaded.repl "0.2.4"]
                                  #_[day8.re-frame/re-frame-10x "0.3.6"]]
                   :source-paths ["dev"]
                   :repl-options {:init-ns user
                                  :nrepl-middleware [cider.piggieback/wrap-cljs-repl]}
                   :env {:dev "true"
                         :port "3000"
                         :datomic-uri "datomic:free://localhost:4334/"
                         :app-domains "localhost jelinek"
                         :app-dbs     "liskasys  jelinek"}}
   :project/test  {}}
  :release-tasks
  [["vcs" "assert-committed"]
   ["change" "version" "leiningen.release/bump-version" "release"]
   ["vcs" "commit"]
   ["vcs" "tag" "--no-sign"]
   ["uberjar"]
   ["change" "version" "leiningen.release/bump-version"]
   ["vcs" "commit"]])
