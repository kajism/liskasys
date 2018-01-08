(defproject liskasys "1.0.38-SNAPSHOT"
  :description "Web information, attendance and lunch cancelation/ordering system for a forest kidergarten"
  :url "http://obedy.listicka.org"
  :min-lein-version "2.0.0"
  :jvm-opts ["-Duser.timezone=UTC"]
  :dependencies [[cljs-ajax "0.5.2"]
                 [org.apache.httpcomponents/httpclient "4.5.1"]
                 [org.clojure/clojure "1.9.0-alpha13"]
                 [org.clojure/clojurescript "1.9.229"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [clj-time "0.12.0"]
                 [com.andrewmcveigh/cljs-time "0.4.0"]
                 [com.cognitect/transit-clj "0.8.285"]
                 [com.cognitect/transit-cljs "0.8.237"]
                 [com.datomic/datomic-free "0.9.5394"
                  :exclusions [com.google.guava/guava]]
                 [com.stuartsierra/component "0.3.1"]
                 [hiccup "1.0.5"]
                 [ring "1.4.0"]
                 [ring/ring-defaults "0.2.0"]
                 [prismatic/schema "1.1.0"]
                 [re-com "0.8.1"]
                 [re-frame "0.7.0"]
                 [com.taoensso/timbre "4.3.1"]
                 [org.clojure/tools.namespace "0.3.0-alpha3"]
                 [compojure "1.5.0"]
                 [duct "0.5.10"]
                 [environ "1.0.2"]
                 [meta-merge "0.1.1"]
                 [ring-jetty-component "0.3.1"]
                 [org.slf4j/slf4j-nop "1.7.14"]
                 [secretary "1.2.3"]
                 [ring-middleware-format "0.7.0"]
                 [crypto-password "0.2.0"]
                 [twarc "0.1.9"]
                 [com.draines/postal "2.0.1"]
                 [io.rkn/conformity "0.4.0"]
                 [cz.geek/geek-spayd "0.2.0"]]
  :plugins [[lein-environ "1.0.2"]
            [lein-gen "0.2.2"]
            [lein-cljsbuild "1.1.4"]]
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
   :project/dev   {:dependencies [[com.cemerick/piggieback "0.2.1"]
                                  [com.datomic/datomic-free "0.9.5350"]
                                  [duct/figwheel-component "0.3.2"]
                                  [eftest "0.1.1"]
                                  [figwheel "0.5.0-6"]
                                  [kerodon "0.7.0"]
                                  [org.clojure/test.check "0.9.0"]
                                  [org.clojure/tools.namespace "0.2.11"]
                                  [reloaded.repl "0.2.1"]]
                   :source-paths ["dev"]
                   :repl-options {:init-ns user
                                  :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                   :env {:dev "true"
                         :port "3000"
                         :datomic-uri "datomic:free://localhost:4334/liskasys"}}
   :project/test  {}}
  :release-tasks
  [["vcs" "assert-committed"]
   ["change" "version" "leiningen.release/bump-version" "release"]
   ["vcs" "commit"]
   ["vcs" "tag" "--no-sign"]
   ["uberjar"]
   ["change" "version" "leiningen.release/bump-version"]
   ["vcs" "commit"]])
