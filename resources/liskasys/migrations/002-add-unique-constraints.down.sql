ALTER TABLE "attendance-day" DROP CONSTRAINT "attendance-day-unique";
ALTER TABLE "lunch-type" DROP CONSTRAINT "lunch-type-unique-label";
ALTER TABLE "user-child" DROP CONSTRAINT "user-child-unique";
ALTER TABLE "user" DROP CONSTRAINT "user-unique-email";
ALTER TABLE "user" DROP CONSTRAINT "user-unique-phone";
ALTER TABLE "child" DROP CONSTRAINT "child-unique-var-symbol";
ALTER TABLE "cancellation" DROP CONSTRAINT "cancellation-unique-child-date";
