(ns liskasys.cljc.schema
  (:require [schema.core :as s]))

(def User
  {:id s/Int
   :firstname s/Str
   :lastname s/Str
   (s/optional-key :email) s/Str
   (s/optional-key :phone) s/Str})

(def AppDb
  {:current-page s/Keyword
   (s/optional-key :auth-user) User
   (s/optional-key :msg) {(s/optional-key :error) (s/maybe s/Str)
                          (s/optional-key :info) (s/maybe s/Str)}})

