ALTER TABLE "user" ADD CONSTRAINT "user-unique-email" UNIQUE ("email");
ALTER TABLE "user" ADD CONSTRAINT "user-unique-phone" UNIQUE ("phone");
ALTER TABLE "child" ADD CONSTRAINT "child-unique-var-symbol" UNIQUE ("var-symbol");
