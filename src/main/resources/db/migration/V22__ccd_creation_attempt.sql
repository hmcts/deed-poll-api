-- The durable record of an attempt to create and link one CCD case for one deed-poll record (plan 02, task 4).
--
-- V22 as plan 02 names it, directly after V21__ccd_case_reference.sql.
--
-- WHY A TABLE AND NOT A COLUMN ON THE CASE. Creating a CCD case is a network conversation that can end without
-- an answer: CCD may have committed a case whose response was lost. Plan 02's decision 9 commits the attempt
-- locally before calling CCD, and the recovery in its task 7 is by READING what happened rather than retrying,
-- because a fresh start-event token mints a new idempotency key and can mint a second case. Reading needs
-- something to read: which operation was in flight, who held it, how many times it has been tried, and what went
-- wrong. That is this row. It is also the record the ccd-migration plan reuses for the bulk backfill, so it must
-- not grow a second retry engine beside it.
--
-- WHAT IS NOT HERE. No CCD start-event token. Plan 02 task 4 defers that decision to task 7, which records the
-- evidence if a token has to survive expiry -- and would then have to protect the storage and keep it out of
-- every log. No CCD reference either: the binding lives on "deedpoll_deedpoll"."ccdCaseReference" (V21), so
-- there is one place a reader asks "is this record linked" and it cannot disagree with a copy.

-- The lifecycle. Member names are the values stored, so they are spelled as CcdCreationAttemptStatus spells them.
-- Which moves between them are legal is stated once, in Java (CcdCreationAttemptStatus.canMoveTo), where a
-- container-free test holds it; the conditional UPDATEs in CcdCreationAttemptRepository enforce it against races.
CREATE TYPE "CcdCreationAttemptStatus" AS ENUM (
    'Pending',
    'Claimed',
    'AwaitingConfirmation',
    'Linked',
    'NeedsRepair'
);

CREATE TABLE "deedpoll_ccdcreationattempt" (
    "id"             uuid                       NOT NULL,

    -- The record the case is for. text, not uuid, because "deedpoll_deedpoll"."id" is text (V2) and Postgres
    -- will not build a foreign key between the two. Correlation is always on this id and never on the display
    -- reference, whose prefix can change (decision 8).
    "deedPollId"     text                       NOT NULL,

    -- The linking operation's own identity, minted by the application when the attempt is opened and carried
    -- through CCD so that the persistence callback can prove it belongs to THIS attempt -- a UUID in a payload is
    -- not permission to bind a case. No database default, on purpose: a writer that cannot say which operation
    -- it is should fail rather than have one invented for it.
    "operationId"    uuid                       NOT NULL,

    "status"         "CcdCreationAttemptStatus" NOT NULL DEFAULT 'Pending',

    -- The worker claim and its lease. Held while a worker talks to CCD, so a second worker cannot start the same
    -- conversation; an expired lease is how a worker that died mid-call becomes recoverable. Both are required
    -- while the attempt is Claimed, by the CHECK below, because a Claimed row with no expiry would be held for ever.
    "claimedBy"      varchar(200),
    "leaseExpiresAt" timestamp(3),

    -- Incremented by the claim itself, before CCD is called, so an attempt that killed the process still counts.
    "attempts"       integer                    NOT NULL DEFAULT 0,

    -- What went wrong last, bounded so a verbose upstream error cannot grow the row without limit. The service
    -- truncates to the same bound before writing, and never writes a token, a credential or a response body here.
    "failureDetail"  varchar(1000),

    "createdOn"      timestamp(3)               NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "modifiedOn"     timestamp(3)               NOT NULL,

    CONSTRAINT "deedpoll_ccdcreationattempt_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "deedpoll_ccdcreationattempt_operationId_key" UNIQUE ("operationId"),
    CONSTRAINT "deedpoll_ccdcreationattempt_attempts_nonnegative" CHECK ("attempts" >= 0),
    CONSTRAINT "deedpoll_ccdcreationattempt_claim_has_lease"
        CHECK ("status" <> 'Claimed' OR ("claimedBy" IS NOT NULL AND "leaseExpiresAt" IS NOT NULL))
);

-- ONE LIVE ATTEMPT PER DEED POLL, as a key rather than a check, so two workers racing to open one are settled by
-- Postgres. Live is every status but Linked -- NeedsRepair included, deliberately: an attempt that could not be
-- settled may already have created a CCD case, and a second attempt opened beside it is exactly how a record
-- ends up with two. A human resolves it, and the resolution moves THIS row on; nothing opens another.
-- CcdCreationAttemptStatus.isLive() states the same predicate, and CcdCreationAttemptRepository.open repeats it
-- as its ON CONFLICT target, character for character -- a mismatch makes the insert raise rather than refuse.
CREATE UNIQUE INDEX "deedpoll_ccdcreationattempt_live_key"
    ON "deedpoll_ccdcreationattempt" ("deedPollId")
 WHERE "status" <> 'Linked';

CREATE INDEX "deedpoll_ccdcreationattempt_deedPollId_idx"
    ON "deedpoll_ccdcreationattempt" ("deedPollId");

-- Cascade, like every other child of the case: an attempt means nothing without the record it was for.
ALTER TABLE "deedpoll_ccdcreationattempt"
    ADD CONSTRAINT "deedpoll_ccdcreationattempt_deedPollId_fkey"
    FOREIGN KEY ("deedPollId") REFERENCES "deedpoll_deedpoll" ("id")
    ON DELETE CASCADE ON UPDATE CASCADE;
