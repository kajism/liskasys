(ns liskasys.cljc.schema
  (:require [schema.core :as s]))

(def CommonAttrs
  {(s/optional-key :db/id) s/Int
   (s/optional-key :-errors) (s/maybe {s/Keyword  s/Str})})

(def LunchMenu
  (merge
   CommonAttrs
   {(s/optional-key :lunch-menu/from) s/Inst
    (s/optional-key :lunch-menu/text) s/Str}))

(def LunchOrder
  (merge
   CommonAttrs
   {(s/optional-key :lunch-order/date) s/Inst
    (s/optional-key :lunch-order/total) s/Int}))

(def LunchType
  (merge
   CommonAttrs
   {(s/optional-key :lunch-type/label) s/Str
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
    (s/optional-key :person/lunch-fund) (s/maybe s/Int)
    (s/optional-key :person/lunch-type) (s/maybe {:db/id s/Int})
    (s/optional-key :person/active?) (s/maybe s/Bool)
    (s/optional-key :person/child?) (s/maybe s/Bool)
    (s/optional-key :person/parent) (s/maybe [{:db/id s/Int}])
    (s/optional-key :person/lunch-pattern) (s/maybe s/Str)
    (s/optional-key :person/att-pattern) (s/maybe s/Str)}))

(def DailyPlan
  (merge
   CommonAttrs
   {(s/optional-key :daily-plan/date) (s/maybe s/Inst)
    (s/optional-key :daily-plan/person) (s/maybe {:db/id s/Int})
    (s/optional-key :daily-plan/bill) (s/maybe {:db/id s/Int})
    (s/optional-key :daily-plan/child-att) (s/maybe s/Int)
    (s/optional-key :daily-plan/att-cancelled?) (s/maybe s/Bool)
    (s/optional-key :daily-plan/lunch-req) (s/maybe s/Int)
    (s/optional-key :daily-plan/lunch-ord) (s/maybe s/Int)
    (s/optional-key :daily-plan/lunch-cancelled?) (s/maybe s/Bool)}))

(def BankHoliday
  (merge CommonAttrs
         {(s/optional-key :bank-holiday/label) s/Str
          (s/optional-key :bank-holiday/day) (s/maybe s/Int)
          (s/optional-key :bank-holiday/month) (s/maybe s/Int)
          (s/optional-key :bank-holiday/easter-delta) (s/maybe s/Int)}))

(def SchoolHoliday
  (merge CommonAttrs
         {(s/optional-key :school-holiday/label) s/Str
          (s/optional-key :school-holiday/from) (s/maybe s/Inst)
          (s/optional-key :school-holiday/to) (s/maybe s/Inst)
          (s/optional-key :school-holiday/every-year?) (s/maybe s/Bool)}))

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
   (s/optional-key :new-ents) {s/Keyword s/Any}
   (s/optional-key :msg) {(s/optional-key :error) (s/maybe s/Str)
                          (s/optional-key :info) (s/maybe s/Str)}
   (s/optional-key :entities-where) {s/Keyword s/Any}
   (s/optional-key :person) {(s/maybe s/Int) (s/maybe Person)}
   (s/optional-key :daily-plan) {(s/maybe s/Int) DailyPlan}
   (s/optional-key :lunch-menu) {(s/maybe s/Int) LunchMenu}
   (s/optional-key :lunch-order) {(s/maybe s/Int) LunchOrder}
   (s/optional-key :lunch-type) {(s/maybe s/Int) LunchType}
   (s/optional-key :bank-holiday) {(s/maybe s/Int) BankHoliday}
   (s/optional-key :school-holiday) {(s/maybe s/Int) SchoolHoliday}
   (s/optional-key :billing-period) {(s/maybe s/Int) BillingPeriod}
   (s/optional-key :price-list) {(s/maybe s/Int) PriceList}})

