(ns liskasys.cljc.schema
  (:require [schema.core :as s]))

(def User
  {})
(def AppDb
  {:current-page s/Keyword
   (s/optional-key :auth-user) User
   (s/optional-key :msg) {(s/optional-key :error) (s/maybe s/Str)
                          (s/optional-key :info) (s/maybe s/Str)}})
