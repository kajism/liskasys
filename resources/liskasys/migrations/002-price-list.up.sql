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
