-- What the CCD creation attempt needs so that it can be retried on a schedule and repaired by a person (plan 02b,
-- task 1).
--
-- V23 as plan 02b names it, directly after V22__ccd_creation_attempt.sql, whose table this extends.
--
-- WHY ANY OF THIS. Plan 02 left an attempt that reached NeedsRepair with no way out but hand-written SQL, and a
-- released attempt with nothing to stop the next caller claiming it again at once. Plan 02b adds a way out for an
-- admin (NeedsRepair -> Pending, "try again from scratch") and a wait between tries. The first is safe only while
-- nothing may have reached CCD -- a fresh start-event token mints a fresh idempotency key and can mint a second
-- case -- so the attempt has to record that fact, not leave it to be inferred from prose in "failureDetail".
--
-- ONE TRANSACTION. Flyway runs a PostgreSQL migration in a single transaction, and the table is one row per
-- deed-poll record at most, so the ACCESS EXCLUSIVE lock the ADDs take is held briefly. No step rewrites the
-- table: a constant default is stored in the catalogue, not in every row.

ALTER TABLE "deedpoll_ccdcreationattempt"
    -- Whether submitCaseCreation may have been called for this attempt: set true, by a conditional UPDATE on the
    -- worker's own Claimed row, after startCase succeeds and BEFORE the submission, so that a process that dies
    -- mid-call has already said so. Only while it is false may an admin send the attempt back to Pending
    -- (CcdLinkingRule.mayRetryFromScratch). Never reset, not even by that retry: it describes the attempt's
    -- history, and a retry is permitted only while it is still false, so there is nothing to reset.
    --
    -- Every EXISTING row is backfilled true, the conservative value. Plan 02 recorded no such fact, so for a row
    -- written before this migration nobody can vouch that nothing was submitted, and a row nobody can vouch for
    -- is never retried from scratch. Hence DEFAULT true on the ADD -- which is what fills the existing rows --
    -- and only then the default a new row gets, false: a freshly opened attempt has submitted nothing.
    ADD COLUMN "submissionMayHaveReached" boolean NOT NULL DEFAULT true,

    -- When a Pending attempt may next be claimed. NULL means now: every attempt opened before this migration,
    -- and every freshly opened one. Set when a claim that submitted nothing is released back to Pending, to now
    -- plus the configured retry-after times the tries so far, so that a CCD refusal is not repeated on every
    -- request that happens to pass. Read by the claim itself as well as by the caller that selects work, so the
    -- two cannot disagree about whether a row is due.
    ADD COLUMN "notBefore" timestamp(3),

    -- The IDAM subject of the admin who last repaired the attempt, and when. text, as V14's "claimedById" is: a
    -- subject is an opaque identifier whose length this service does not control. Attribution only -- neither is
    -- published in a DTO or written to a log line -- and only the latest repair is kept; the log line each repair
    -- writes carries the attempt id, which leads back here. NULL on an attempt nobody has repaired.
    ADD COLUMN "repairedById" text,
    ADD COLUMN "repairedOn" timestamp(3);

-- The default a NEW row gets. Separate from the ADD because the ADD's default is what backfilled the existing
-- rows, and they needed the opposite value.
ALTER TABLE "deedpoll_ccdcreationattempt"
    ALTER COLUMN "submissionMayHaveReached" SET DEFAULT false;
