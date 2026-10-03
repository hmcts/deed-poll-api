-- Stop every old drain before this migration/new workers: old code ignores these ownership fields.
ALTER TABLE "deedpoll_notification"
    ADD COLUMN "claimOwner" uuid,
    ADD COLUMN "sendStartedAt" timestamp(6);

-- Legacy Pending attempts cannot distinguish a safe refusal from a crashed send. Park conservatively.
-- A new worker writes the terminal technical outcome and its note atomically when this row is due.
-- Untouched Pending rows remain deliverable; Sent/Failed history and exclusion rules are unchanged.
UPDATE "deedpoll_notification"
   SET "sendStartedAt" = "modifiedOn"
 WHERE "state" = 'Pending' AND "attempts" > 0;
