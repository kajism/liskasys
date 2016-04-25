(ns liskasys.cljc.schema
  (:require [schema.core :as s]))

(def User
  {(s/optional-key :id) s/Int
   (s/optional-key :firstname) s/Str
   (s/optional-key :lastname) s/Str
   (s/optional-key :email) s/Str
   (s/optional-key :phone) (s/maybe s/Str)
   (s/optional-key :passwd) (s/maybe s/Str)
   (s/optional-key :roles) s/Any
   (s/optional-key :failed-logins) (s/maybe s/Int)
   (s/optional-key :created) s/Inst
   (s/optional-key :modified) s/Inst})

(def AppDb
  {:current-page s/Keyword
   (s/optional-key :auth-user) User
   (s/optional-key :table-states) s/Any
   (s/optional-key :entity-edit) {s/Keyword {:id (s/maybe s/Int)
                                             :edit? s/Bool}}
   (s/optional-key :user) {(s/maybe s/Int) (s/maybe User)}
   (s/optional-key :msg) {(s/optional-key :error) (s/maybe s/Str)
                          (s/optional-key :info) (s/maybe s/Str)}})

