ALTER TABLE "attendance-day" ADD CONSTRAINT "attendance-day-unique" UNIQUE ("attendance-id", "day-of-week");
ALTER TABLE "lunch-type" ADD CONSTRAINT "lunch-type-unique-label" UNIQUE ("label");
ALTER TABLE "user-child" ADD CONSTRAINT "user-child-unique" UNIQUE ("user-id", "child-id");
ALTER TABLE "user" ADD CONSTRAINT "user-unique-email" UNIQUE ("email");
ALTER TABLE "user" ADD CONSTRAINT "user-unique-phone" UNIQUE ("phone");
ALTER TABLE "child" ADD CONSTRAINT "child-unique-var-symbol" UNIQUE ("var-symbol");
ALTER TABLE "cancellation" ADD CONSTRAINT "cancellation-unique-child-date" UNIQUE ("date", "child-id");
