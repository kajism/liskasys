CREATE TABLE IF NOT EXISTS "lunch-order" (
"id" BIGINT IDENTITY,
"date" DATE NOT NULL,
"total" SMALLINT NOT NULL DEFAULT 0);

ALTER TABLE "lunch-order" ADD CONSTRAINT "lunch-order-unique-date" UNIQUE ("date");

CREATE TABLE IF NOT EXISTS "bank-holiday" (
"id" BIGINT IDENTITY,
"label" VARCHAR(150),
"day" SMALLINT,
"month" SMALLINT,
"easter-delta" SMALLINT,
"valid-from-year" SMALLINT NOT NULL,
"valid-to-year" SMALLINT,
"created" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP(),
"modified" TIMESTAMP NOT NULL AS CURRENT_TIMESTAMP());

INSERT INTO PUBLIC."bank-holiday"("id", "label", "day", "month", "easter-delta", "valid-from-year", "valid-to-year") VALUES
(1, STRINGDECODE('Den \u010desk\u00e9 st\u00e1tnosti'), 28, 9, NULL, 2001, NULL),
(2, STRINGDECODE('Den vzniku samostatn\u00e9ho \u010deskoslovensk\u00e9ho st\u00e1tu'), 28, 10, NULL, 2000, NULL),
(3, STRINGDECODE('Velk\u00fd p\u00e1tek'), NULL, NULL, -3, 2016, NULL),
(4, STRINGDECODE('Pond\u011bl\u00ed Velikono\u010dn\u00ed'), NULL, NULL, 0, 2000, NULL),
(5, STRINGDECODE('Nov\u00fd rok'), 1, 1, NULL, 2000, NULL),
(6, STRINGDECODE('Sv\u00e1tek pr\u00e1ce'), 1, 5, NULL, 2000, NULL),
(7, STRINGDECODE('Den osvobozen\u00ed a matek'), 8, 5, NULL, 2000, NULL),
(8, STRINGDECODE('Cyril a Metod\u011bj'), 5, 7, NULL, 2000, NULL),
(9, 'Mistr Jan Hus', 6, 7, NULL, 2000, NULL),
(10, 'Den boje za svobodu a demokracii', 17, 11, NULL, 2000, NULL),
(11, STRINGDECODE('\u0160t\u011bdr\u00fd den'), 24, 12, NULL, 2000, NULL),
(12, STRINGDECODE('1. sv\u00e1tek v\u00e1no\u010dn\u00ed'), 25, 12, NULL, 2000, NULL),
(13, STRINGDECODE('2. sv\u00e1tek v\u00e1no\u010dn\u00ed'), 26, 12, NULL, 2000, NULL);
