CREATE TABLE "user" (
"id" BIGINT IDENTITY,
"firstname" VARCHAR(64) NOT NULL,
"lastname" VARCHAR(64) NOT NULL,
"email" VARCHAR(128) NOT NULL,
"phone" VARCHAR(20),
"passwd" VARCHAR(128),
"failed-logins" SMALLINT NOT NULL DEFAULT 0,
"roles" VARCHAR(128),
"created" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP(),
"modified" TIMESTAMP NOT NULL AS CURRENT_TIMESTAMP());

CREATE TABLE "child" (
"id" BIGINT IDENTITY,
"firstname" VARCHAR(64) NOT NULL,
"lastname" VARCHAR(64) NOT NULL,
"var-symbol" INT NOT NULL,
"created" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP(),
"modified" TIMESTAMP NOT NULL AS CURRENT_TIMESTAMP());

CREATE TABLE "user-child" (
"id" BIGINT IDENTITY,
"user-id" BIGINT NOT NULL,
"child-id" BIGINT NOT NULL ,
"created" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP());

ALTER TABLE "user-child" ADD CONSTRAINT "fk-user-child-to-user"
  FOREIGN KEY ("user-id") REFERENCES "user" ("id");
ALTER TABLE "user-child" ADD CONSTRAINT "fk-user-child-to-child"
  FOREIGN KEY ("child-id") REFERENCES "child" ("id");

CREATE TABLE "attendance" (
"id" BIGINT IDENTITY,
"child-id" BIGINT NOT NULL,
"valid-from" DATE NOT NULL,
"valid-to" DATE,
"created" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP(),
"modified" TIMESTAMP NOT NULL AS CURRENT_TIMESTAMP());

ALTER TABLE "attendance" ADD CONSTRAINT "fk-attendance-to-child"
  FOREIGN KEY ("child-id") REFERENCES "child" ("id");

CREATE TABLE "attendance-day" (
"id" BIGINT IDENTITY,
"attendance-id" BIGINT NOT NULL,
"day-of-week" TINYINT NOT NULL, -- 1 = Monday ... 5 = Friday
"full-day?" BOOLEAN NOT NULL,
"lunch?" BOOLEAN NOT NULL);

ALTER TABLE "attendance-day" ADD CONSTRAINT "fk-attendance-day-to-attendance"
  FOREIGN KEY ("attendance-id") REFERENCES "attendance" ("id")
  ON DELETE CASCADE;

CREATE TABLE "cancellation" (
"id" BIGINT IDENTITY,
"child-id" BIGINT NOT NULL,
"date" DATE NOT NULL,
"attendance-day-id" BIGINT NOT NULL,
"lunch-cancelled?" BOOLEAN NOT NULL,
"user-id" BIGINT NOT NULL,
"created" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP());

ALTER TABLE "cancellation" ADD CONSTRAINT "fk-cancellation-to-user"
  FOREIGN KEY ("user-id") REFERENCES "user" ("id");
ALTER TABLE "cancellation" ADD CONSTRAINT "fk-cancellation-to-child"
  FOREIGN KEY ("child-id") REFERENCES "child" ("id");
ALTER TABLE "cancellation" ADD CONSTRAINT "fk-cancellation-to-attendance-day"
  FOREIGN KEY ("attendance-day-id") REFERENCES "attendance-day" ("id");

CREATE TABLE "price-list" (
"id" BIGINT INDENTITY,
"valid-from" DATE NOT NULL,
"valid-to" DATE,
"fdpw-1" BIGDEC, -- full days per week
"fdpw-2" BIGDEC,
"fdpw-3" BIGDEC,
"fdpw-4" BIGDEC,
"fdpw-5" BIGDEC,
"half-day" BIGDEC,
"lunch" BIGDEC,
"created" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP(),
"modified" TIMESTAMP NOT NULL AS CURRENT_TIMESTAMP());
