CREATE TABLE IF NOT EXISTS "person" (
"id" BIGINT IDENTITY,
"firstname" VARCHAR(64) NOT NULL,
"lastname" VARCHAR(64) NOT NULL,
"active?" BOOLEAN NOT NULL DEFAULT TRUE,
"var-symbol" INT,
"lunch-type-id" BIGINT,
"lunch-pattern" CHAR(7) NOT NULL DEFAULT '0000000',
"free-lunches?" BOOLEAN NOT NULL DEFAULT FALSE,
"email" VARCHAR(128),
"phone" VARCHAR(20),
"passwd" VARCHAR(128),
"roles" VARCHAR(256),
"child?" BOOLEAN NOT NULL DEFAULT FALSE,
"att-pattern" CHAR(7) NOT NULL DEFAULT '0000000',
"free-att?" BOOLEAN NOT NULL DEFAULT FALSE
);

ALTER TABLE "person" ADD CONSTRAINT "fk-person-to-lunch-type"
  FOREIGN KEY ("lunch-type-id") REFERENCES "lunch-type" ("id");
ALTER TABLE "person" ADD CONSTRAINT "person-unique-email" UNIQUE ("email");
ALTER TABLE "person" ADD CONSTRAINT "person-unique-phone" UNIQUE ("phone");
ALTER TABLE "person" ADD CONSTRAINT "person-unique-var-symbol" UNIQUE ("var-symbol");

CREATE TABLE IF NOT EXISTS "parent-child" (
"id" BIGINT IDENTITY,
"parent-id" BIGINT NOT NULL,
"child-id" BIGINT NOT NULL
);

ALTER TABLE "parent-child" ADD CONSTRAINT "fk-parent-to-person"
  FOREIGN KEY ("parent-id") REFERENCES "person" ("id");
ALTER TABLE "parent-child" ADD CONSTRAINT "fk-child-to-person"
  FOREIGN KEY ("child-id") REFERENCES "person" ("id");
ALTER TABLE "parent-child" ADD CONSTRAINT "parent-child-unique"
  UNIQUE ("parent-id", "child-id");

DROP TABLE IF EXISTS "price-list";
CREATE TABLE "price-list" (
"id" BIGINT IDENTITY,
"days-1" INT NOT NULL,
"days-2" INT NOT NULL,
"days-3" INT NOT NULL,
"days-4" INT NOT NULL,
"days-5" INT NOT NULL,
"half-day" INT NOT NULL,
"lunch" INT NOT NULL,
"valid-from" DATE NOT NULL,
"valid-to" DATE);

CREATE TABLE IF NOT EXISTS "billing-period" (
"id" BIGINT IDENTITY,
"from-yyyymm" INT NOT NULL,
"to-yyyymm" INT NOT NULL
);

CREATE TABLE IF NOT EXISTS "person-bill" (
"id" BIGINT IDENTITY,
"period-id" BIGINT NOT NULL,
"person-id" BIGINT NOT NULL,
"total-cents" INT NOT NULL,
"paid?" BOOLEAN NOT NULL DEFAULT FALSE,
"total-lunches" SMALLINT NOT NULL,
"att-price-cents" INT NOT NULL,
"var-symbol" INT NOT NULL,
"att-pattern" CHAR(7) NOT NULL,
"lunch-pattern" CHAR(7) NOT NULL
);

ALTER TABLE "person-bill" ADD CONSTRAINT "fk-person-bill-to-period"
  FOREIGN KEY ("period-id") REFERENCES "billing-period" ("id");
ALTER TABLE "person-bill" ADD CONSTRAINT "fk-person-bill-to-person"
  FOREIGN KEY ("person-id") REFERENCES "person" ("id");

CREATE TABLE IF NOT EXISTS "daily-plan" (
"id" BIGINT IDENTITY,
"bill-id" BIGINT,
"person-id" BIGINT NOT NULL,
"date" DATE NOT NULL,
"lunch?" BOOLEAN NOT NULL,
"child-att" TINYINT NOT NULL,
"lunch-ordered?" BOOLEAN NOT NULL DEFAULT FALSE,
"cancelled-at" TIMESTAMP,
"substitution-id" BIGINT
);

ALTER TABLE "daily-plan" ADD CONSTRAINT "fk-daily-plan-to-bill"
  FOREIGN KEY ("bill-id") REFERENCES "person-bill" ("id");
ALTER TABLE "daily-plan" ADD CONSTRAINT "fk-daily-plan-to-person"
  FOREIGN KEY ("person-id") REFERENCES "person" ("id");
ALTER TABLE "daily-plan" ADD CONSTRAINT "daily-plan-unique"
  UNIQUE ("date", "person-id");
