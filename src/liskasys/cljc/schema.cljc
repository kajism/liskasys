(ns liskasys.cljc.schema
  (:require [schema.core :as s]))

(def CommonAttrs
  {(s/optional-key :db/id) s/Int
   (s/optional-key :-errors) (s/maybe {s/Keyword  s/Str})})

(def LunchType
  (merge
   CommonAttrs
   { (s/optional-key :lunch-type/label) s/Str
    (s/optional-key :lunch-type/color) s/Str}))

(def Person
  (merge
   CommonAttrs
   {(s/optional-key :person/firstname) s/Str
    (s/optional-key :person/lastname) s/Str
    (s/optional-key :-fullname) s/Str
    (s/optional-key :person/email) s/Str
    (s/optional-key :person/phone) (s/maybe s/Str)
    (s/optional-key :person/passwd) (s/maybe s/Str)
    (s/optional-key :person/roles) s/Any
    (s/optional-key :person/var-symbol) s/Int
    (s/optional-key :person/lunch-type) (s/maybe {:db/id s/Int})
    (s/optional-key :person/active?) (s/maybe s/Bool)
    (s/optional-key :person/child?) (s/maybe s/Bool)
    (s/optional-key :person/parent) (s/maybe [{:db/id s/Int}])
    (s/optional-key :person/lunch-pattern) (s/maybe s/Str)
    (s/optional-key :person/att-pattern) (s/maybe s/Str)}))

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
         {(s/optional-key :bank-holiday/label) s/Str
          (s/optional-key :bank-holiday/day) (s/maybe s/Int)
          (s/optional-key :bank-holiday/month) (s/maybe s/Int)
          (s/optional-key :bank-holiday/easter-delta) (s/maybe s/Int)}))

(def BillingPeriod
  (merge CommonAttrs
         {(s/optional-key :billing-period/from-yyyymm) (s/maybe s/Int)
          (s/optional-key :billing-period/to-yyyymm) (s/maybe s/Int)}))

(def PriceList
  (merge CommonAttrs
         {(s/optional-key :price-list/days-1) (s/maybe s/Int)
          (s/optional-key :price-list/days-2) (s/maybe s/Int)
          (s/optional-key :price-list/days-3) (s/maybe s/Int)
          (s/optional-key :price-list/days-4) (s/maybe s/Int)
          (s/optional-key :price-list/days-5) (s/maybe s/Int)
          (s/optional-key :price-list/half-day) (s/maybe s/Int)
          (s/optional-key :price-list/lunch) (s/maybe s/Int)}))

(def AppDb
  {:current-page s/Keyword
   (s/optional-key :auth-user) Person
   (s/optional-key :table-states) s/Any
   (s/optional-key :entity-edit) {s/Keyword {:db/id (s/maybe s/Int)
                                             :edit? s/Bool}}
   (s/optional-key :msg) {(s/optional-key :error) (s/maybe s/Str)
                          (s/optional-key :info) (s/maybe s/Str)}
   (s/optional-key :entities-where) {s/Keyword s/Any}
   (s/optional-key :person) {(s/maybe s/Int) (s/maybe Person)}
   (s/optional-key :attendance) {(s/maybe s/Int) Attendance}
   (s/optional-key :cancellation) {(s/maybe s/Int) Cancellation}
   (s/optional-key :lunch-type) {(s/maybe s/Int) LunchType}
   (s/optional-key :bank-holiday) {(s/maybe s/Int) BankHoliday}
   (s/optional-key :billing-period) {(s/maybe s/Int) BillingPeriod}
   (s/optional-key :price-list) {(s/maybe s/Int) PriceList}})

