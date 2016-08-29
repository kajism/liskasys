(ns liskasys.cljc.schema
  (:require [schema.core :as s]))

(def CommonAttrs
  {(s/optional-key :id) s/Int
   (s/optional-key :created) s/Inst
   (s/optional-key :modified) s/Inst
   (s/optional-key :-errors) (s/maybe {s/Keyword  s/Str})})

(def LunchType
  {(s/optional-key :id) s/Int
   (s/optional-key :label) s/Str
   (s/optional-key :color) s/Str
   (s/optional-key :-errors) (s/maybe {s/Keyword  s/Str})})

(def User
  (merge
   CommonAttrs
   {(s/optional-key :firstname) s/Str
    (s/optional-key :lastname) s/Str
    (s/optional-key :-fullname) s/Str
    (s/optional-key :email) s/Str
    (s/optional-key :phone) (s/maybe s/Str)
    (s/optional-key :passwd) (s/maybe s/Str)
    (s/optional-key :roles) s/Any
    (s/optional-key :failed-logins) (s/maybe s/Int)}))

(def Child
  (merge
   CommonAttrs
   {(s/optional-key :firstname) s/Str
    (s/optional-key :lastname) s/Str
    (s/optional-key :-fullname) s/Str
    (s/optional-key :var-symbol) s/Int
    (s/optional-key :lunch-type-id) (s/maybe s/Int)}))

(def UserChild
  {:id s/Int :user-id s/Int :child-id s/Int :created s/Inst})

(def Attendance
  (merge
   CommonAttrs
   {(s/optional-key :valid-from) s/Inst
    (s/optional-key :valid-to) (s/maybe s/Inst)
    :child-id s/Int
    (s/optional-key :days) {s/Int {:type (s/maybe s/Int)
                                   (s/optional-key :lunch?) s/Bool}}}))

(def Cancellation
  (merge
   CommonAttrs
   {(s/optional-key :date) s/Inst
    (s/optional-key :child-id) s/Int
    (s/optional-key :-child-fullname) s/Str
    (s/optional-key :attendance-day-id) s/Int
    (s/optional-key :user-id) s/Int
    (s/optional-key :-user-fullname) s/Str
    (s/optional-key :substitution-date) (s/maybe s/Inst)
    (s/optional-key :lunch-cancelled?) s/Bool}))

(def BankHoliday
  (merge CommonAttrs
         {(s/optional-key :label) s/Str
          (s/optional-key :day) (s/maybe s/Int)
          (s/optional-key :month) (s/maybe s/Int)
          (s/optional-key :easter-delta) (s/maybe s/Int)
          (s/optional-key :valid-from-year) s/Int
          (s/optional-key :valid-to-year) (s/maybe s/Int)}))

(def AppDb
  {:current-page s/Keyword
   (s/optional-key :auth-user) User
   (s/optional-key :table-states) s/Any
   (s/optional-key :entity-edit) {s/Keyword {:id (s/maybe s/Int)
                                             :edit? s/Bool}}
   (s/optional-key :msg) {(s/optional-key :error) (s/maybe s/Str)
                          (s/optional-key :info) (s/maybe s/Str)}
   (s/optional-key :user) {(s/maybe s/Int) (s/maybe User)}
   (s/optional-key :child) {(s/maybe s/Int) (s/maybe Child)}
   (s/optional-key :user-child) {s/Int UserChild}
   (s/optional-key :attendance) {(s/maybe s/Int) Attendance}
   (s/optional-key :cancellation) {(s/maybe s/Int) Cancellation}
   (s/optional-key :lunch-type) {(s/maybe s/Int) LunchType}
   (s/optional-key :bank-holiday) {(s/maybe s/Int) BankHoliday}})

