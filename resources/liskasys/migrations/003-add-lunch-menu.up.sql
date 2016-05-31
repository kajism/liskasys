CREATE TABLE "lunch-menu" (
"id" BIGINT IDENTITY,
"text" CLOB,
"content-type" VARCHAR(128),
"orig-filename" VARCHAR(128),
"created" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP());
