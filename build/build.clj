(ns build
  (:require [clojure.tools.build.api :as b]
            [remove-hashp :refer [remove-hashp]]))

(def lib 'liskasys/liskasys)
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def deps-edn "deps.edn")
(def basis (b/create-basis {:project deps-edn}))
(def jar-file (format "target/%s.jar" (name lib)))
(def uber-file (format "target/uberjar/%s-%s-standalone.jar" (name lib) version))
(def src-dirs ["src"])
(def resource-dirs ["resources"])

(defn clean []
      (b/delete {:path "target"})
      (b/delete {:path "resources/liskasys/public/cljs/"}))

(defn cljs-release []
      (b/process {:command-args ["npm" "install"]})
      (b/process {:command-args ["npx" "shadow-cljs" "release" "app"]}))

(defn prepare []
      (clean)

      (remove-hashp src-dirs)

      (cljs-release)

      (b/write-pom {:class-dir class-dir
                    :lib lib
                    :version version
                    :basis basis
                    :src-dirs src-dirs})

      ;; copy resources
      (b/copy-dir {:src-dirs resource-dirs
                   :target-dir class-dir})
      (b/copy-file {:src deps-edn
                    :target (str class-dir "/" deps-edn)})
      (b/copy-dir {:src-dirs ["test"]
                   :target-dir (str class-dir "/test")})

      ;; AOT compilation
      (b/compile-clj {:basis basis
                      :src-dirs src-dirs
                      :class-dir class-dir}))

(defn jar [_]
      (prepare)

      (b/jar {:class-dir class-dir
              :jar-file jar-file})

      (println "Jar:" jar-file))

(defn uberjar [_]
      (prepare)

      (b/uber {:class-dir class-dir
               :uber-file uber-file
               :basis basis
               :main 'liskasys.main})

      (println "Uberjar:" uber-file))
