-- The Master's Gazette answer changes question (Master Sullivan's feedback, Steve, 2026-10-27).
--
-- The direction used to ask "Should this deed poll be published in the Gazette?" and stored the answer as
-- "publishToGazette". It now asks "Are there any changes to the standard publications in the Gazette?", where
-- Yes means do not publish, or publish only as the direction says (the last name only, for example), and the
-- Master writes which in the direction text. So the column is renamed to say what it now records, and every
-- stored answer is inverted to keep its meaning: not publishing at all was always a change to the standard
-- publication, and publishing as standard was not.
--
--   publishToGazette = false  ->  changesToStandardPublication = true
--   publishToGazette = true   ->  changesToStandardPublication = false
--   null (no direction)       ->  null
--
-- The audit trail is left alone. Its earlier rows name the field "publishToGazette" and carry the old
-- question's answer, which is what was recorded at the time; rows from here on name the new field. The case
-- notes already written quote the old question in their own words, and are not rewritten either.
--
-- The case's own "publishToLondonGazette" (V2, the caseworker's field and the Gazette ready list's predicate)
-- is a different fact and is untouched.

ALTER TABLE "deedpoll_referral" RENAME COLUMN "publishToGazette" TO "changesToStandardPublication";

UPDATE "deedpoll_referral"
   SET "changesToStandardPublication" = NOT "changesToStandardPublication"
 WHERE "changesToStandardPublication" IS NOT NULL;
