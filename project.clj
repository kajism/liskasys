(defproject liskasys "1.0.125-SNAPSHOT"
  :description "Web information, attendance and lunch cancelation/ordering system for a forest kidergartens"
  :url "https://github.com/kajism/liskasys"
  :min-lein-version "2.0.0"
  :jvm-opts ["-Duser.timezone=UTC" "-XX:-OmitStackTraceInFastThrow"]
  :javac-options ["-target" "1.8" "-source" "1.8"]
  :dependencies [[cljs-ajax "0.5.2"]
                 [org.apache.httpcomponents/httpclient "4.5.1"]
                 [org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.439"]
                 [org.clojure/core.async "0.4.490"]
                 [org.clojure/core.cache "0.6.5"]
                 [org.clojure/core.memoize "0.5.9"]
                 [nrepl "0.6.0"]
                 [clj-time "0.12.0"]
                 [com.andrewmcveigh/cljs-time "0.5.2"]
                 [com.cognitect/transit-clj "0.8.313"]
                 [com.cognitect/transit-cljs "0.8.256"]
                 [com.datomic/datomic-free "0.9.5703.21"
                  :exclusions [com.google.guava/guava]]
                 [com.stuartsierra/component "0.3.1"]
                 [hiccup "1.0.5"]
                 [ring "1.5.0"]
                 [ring/ring-defaults "0.2.1"]
                 [re-com "2.4.0" :exclusions [reagent]]
                 [re-frame "0.10.6"]
                 [com.taoensso/timbre "4.7.4"]
                 #_[org.clojure/tools.namespace "0.3.0-alpha3"]
                 [compojure "1.5.0"]
                 [duct "0.5.10"]
                 [environ "1.0.2"]
                 [meta-merge "0.1.1"]
                 [ring-jetty-component "0.3.1"]
                 [http-kit "2.3.0"] ;; older version figwheel dependency causes problems
                 [org.slf4j/slf4j-nop "1.7.14"]
                 [secretary "1.2.3"]
                 [ring-middleware-format "0.7.4"]
                 [crypto-password "0.2.0"]
                 [twarc "0.1.9"]
                 [prismatic/plumbing "0.5.5"] ;; old used by twarc causing problems
                 [com.draines/postal "2.0.1"]
                 [io.rkn/conformity "0.4.0"]
                 [cz.geek/geek-spayd "0.2.0"]]
  :plugins [[lein-environ "1.0.2"]
            [lein-gen "0.2.2"]
            [lein-cljsbuild "1.1.5"]]
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
   :project/dev   {:dependencies [[binaryage/devtools "0.9.10"]
                                  [cider/piggieback "0.4.0"]
                                  [duct/figwheel-component "0.3.4"]
                                  #_[eftest "0.1.1"]
                                  [figwheel "0.5.14"]
                                  #_[kerodon "0.7.0"]
                                  #_[org.clojure/test.check "0.9.0"]
                                  [org.clojure/tools.namespace "0.2.11"]
                                  [reloaded.repl "0.2.3"]
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
