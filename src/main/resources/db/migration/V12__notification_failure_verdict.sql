-- A failure's taxonomy verdict as data rather than as a prefix inside "failureReason", so the
-- idempotency index can distinguish "Notify definitely refused this" from "the outcome is uncertain"
-- without making a LIKE over human-readable text load-bearing.
--
-- V12 rather than an edit to V11. V11 has shipped on this branch and committed Flyway migrations are
-- immutable: environments that already recorded its checksum must reach this shape by applying a new
-- migration.
CREATE TYPE "NotificationFailureVerdict" AS ENUM (
    'PERMANENT',
    'TEMPORARY',
    'TECHNICAL'
);

ALTER TABLE "deedpoll_notification"
    ADD COLUMN "failureVerdict" "NotificationFailureVerdict";

-- Existing rows predate the dedicated column. Reading their prefix once during migration is safe;
-- continuing to infer the verdict from prose on every insert would not be. A reason outside the three
-- known formats is classified TECHNICAL, the conservative answer: an unknown outcome must continue
-- occupying the uniqueness slot.
UPDATE "deedpoll_notification"
   SET "failureVerdict" =
       CASE
           WHEN "failureReason" LIKE 'PERMANENT:%' THEN 'PERMANENT'::"NotificationFailureVerdict"
           WHEN "failureReason" LIKE 'TEMPORARY:%'
             OR "failureReason" LIKE 'Gave up after %: TEMPORARY:%'
               THEN 'TEMPORARY'::"NotificationFailureVerdict"
           ELSE 'TECHNICAL'::"NotificationFailureVerdict"
       END
 WHERE "failureReason" IS NOT NULL;

-- The old index treats every historical row alike, including a permanent refusal where Notify
-- definitely sent nothing. Replace it under the same name: callers and diagnostics already name it,
-- and the key itself is unchanged.
DROP INDEX "deedpoll_notification_referral_template_key";

-- A permanent refusal does not occupy the slot, so correcting a bad template or API key and trying
-- again writes a new row. Pending, Sent, temporary, technical and verdict-less old rows still occupy
-- it because any of them may already have reached Notify.
--
-- IS DISTINCT FROM is load-bearing. During a rolling deployment an older application instance can
-- still write a Failed row without "failureVerdict". NULL must mean "unknown and blocked", not fall
-- through SQL's three-valued NOT expression and disappear from this index.
CREATE UNIQUE INDEX "deedpoll_notification_referral_template_key"
    ON "deedpoll_notification" ("referralId", "template")
 WHERE "referralId" IS NOT NULL
   AND ("state" <> 'Failed' OR "failureVerdict" IS DISTINCT FROM 'PERMANENT');
