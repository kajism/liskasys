CREATE TABLE "lunch-type" (
"id" BIGINT IDENTITY,
"label" VARCHAR(64) NOT NULL);

CREATE TABLE "user" (
"id" BIGINT IDENTITY,
"firstname" VARCHAR(64) NOT NULL,
"lastname" VARCHAR(64) NOT NULL,
"email" VARCHAR(128) NOT NULL,
"phone" VARCHAR(20),
"passwd" VARCHAR(128),
"failed-logins" SMALLINT NOT NULL DEFAULT 0,
"roles" VARCHAR(256),
"created" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP(),
"modified" TIMESTAMP NOT NULL AS CURRENT_TIMESTAMP());

INSERT INTO "user" ("firstname", "lastname", "email", "phone", "passwd", "roles")
VALUES ('Daniela', 'Chaloupková', 'daniela.chaloupkova@post.cz', '776 198 160',
'$s0$f0801$rK7k1fqX+anvoVBb4QhZxQ==$Fnn1W8bxgNQgh5u6H5bF7uZWk40l32gOJoF8OdniS04=', 'admin');
INSERT INTO "user"  ("firstname", "lastname", "email", "phone", "passwd", "roles")
VALUES ('Karel', 'Miarka', 'karel.miarka@seznam.cz', '702 573 669',
'$s0$f0801$rK7k1fqX+anvoVBb4QhZxQ==$Fnn1W8bxgNQgh5u6H5bF7uZWk40l32gOJoF8OdniS04=', 'admin');

CREATE TABLE "child" (
"id" BIGINT IDENTITY,
"firstname" VARCHAR(64) NOT NULL,
"lastname" VARCHAR(64) NOT NULL,
"var-symbol" INT NOT NULL,
"lunch-type-id" BIGINT,
"created" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP(),
"modified" TIMESTAMP NOT NULL AS CURRENT_TIMESTAMP());

ALTER TABLE "child" ADD CONSTRAINT "fk-child-to-lunch-type"
  FOREIGN KEY ("lunch-type-id") REFERENCES "lunch-type" ("id");

CREATE TABLE "user-child" (
"id" BIGINT IDENTITY,
"user-id" BIGINT NOT NULL,
"child-id" BIGINT NOT NULL,
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
"substitution-date" DATE,
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
"id" BIGINT IDENTITY,
"valid-from" DATE NOT NULL,
"valid-to" DATE,
"fdpw-1" DECIMAL, -- full days per week
"fdpw-2" DECIMAL,
"fdpw-3" DECIMAL,
"fdpw-4" DECIMAL,
"fdpw-5" DECIMAL,
"half-day" DECIMAL,
"lunch" DECIMAL,
"created" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP(),
"modified" TIMESTAMP NOT NULL AS CURRENT_TIMESTAMP());
