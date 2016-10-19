(ns liskasys.cljc.specs
  (:require [clojure.spec :as s]
            [liskasys.cljc.validation :as validation]))

(s/def :person/firstname string?)
(s/def :person/lastname string?)
(s/def :person/active? boolean?)
(s/def :person/child? boolean?)
(s/def :person/email (s/and string? validation/valid-email?))
(s/def :person/phone (s/and string? validation/valid-phone?))

(s/def ::person (s/keys :req [:person/firstname :person/lastname :person/active? :person/child?]
                        :opt [:person/email :person/phone]))
