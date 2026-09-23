-- The confirmation a caseworker asked for, and the answer that came back.
--
-- V16 rather than a number this plan named in advance. V15__notification_exclusion_group.sql was already
-- committed when this was written, so V16 is the next free number -- confirmed by looking at
-- src/main/resources/db/migration rather than assumed, which is what the plan asks for and what V11 and
-- V15 each had to do before it. Nothing is left reserved: Flyway runs with out-of-order disabled, so a
-- V15 appearing after V16 had been applied would fail every environment that applied V16.
--
-- WHY. notify-3 sends "REQUEST_CONFIRMATION_TO_PROCEED": the applicant is asked to reply saying whether
-- they still want the deed enrolled now that the judge has refused their non-publication request. Until
-- this migration nothing recorded that a case was waiting for that reply and nothing recorded the reply,
-- so the only trace of the whole exchange was an email thread in a shared inbox.
--
-- WHY ON THE REFERRAL AND NOT ON THE CASE. The non-publication request, the judge's refusal and the
-- direction all live on "deedpoll_referral", and "did they confirm?" is a fact about that referral's
-- outcome. On the case it would be ambiguous the moment a case carried a second referral -- which is
-- exactly the one-referral-per-case model V6 replaced.
--
-- WHY NOT A NINTH "DeedPollState". Because a case's state is DERIVED, not stored: CaseState.deriveState is
-- a verbatim port of SetStatesbasedonDatescompleted.xaml and picks a DeedPollState from which date columns
-- are populated, and each member's POSITION in that enum is the Dynamics 365 option-set integer the
-- Dataverse exports hold. A ninth member would mint a Dataverse value this service is not entitled to
-- mint, which is the same rule that keeps the CCD case type a placeholder. "Awaiting confirmation" is
-- therefore surfaced ALONGSIDE the derived state, never inside it, and nothing in this migration touches
-- the date cascade or the columns it reads.
--
-- Column names are mixed case for the mechanical reason V6, V11 and V15 all give:
-- `globally_quoted_identifiers` and `PhysicalNamingStrategyStandardImpl` are set service-wide, so a
-- snake_case column would be quoted as written and Hibernate would not find it.

-- A NEW TYPE, and minting this one IS legitimate where minting a DeedPollState member is not. V11's
-- "NotificationState" is the precedent: an internal enum this service owns end to end, with no counterpart
-- in reference/deed-poll.prisma and no Dynamics option set behind it, so its member order carries no
-- integer anybody re-imports and is legibility only.
--
-- Two members and no third. There is deliberately no 'NoReply' or 'Chased': silence is the ABSENCE of an
-- outcome, which is NULL and is what the partial index below indexes, and a state for it would make "still
-- waiting" answerable two contradictory ways. There is no 'Withdrawn and refunded' either -- the refund is
-- the case's own "refundAmount" and a separate act.
CREATE TYPE "ConfirmationOutcome" AS ENUM (
    -- The applicant still wants the deed enrolled, knowing it will be published.
    'Confirmed',
    -- The applicant does not, and wants a refund.
    'Withdrawn'
);

-- ALL FOUR NULLABLE, and that is the honest shape rather than a convenience: every referral that exists
-- predates this feature, none of them was ever asked to confirm, and a NOT NULL with a default would have
-- to invent both a request date and an answer for all of them. Purely additive -- nothing is dropped,
-- retyped or renamed, no committed migration is touched, and nothing on "deedpoll_deedpoll" changes.
ALTER TABLE "deedpoll_referral"
    -- When the clock started: stamped as the caseworker sends ④, in the SAME transaction as the outbox
    -- row, and not when Notify accepts it. The outbox's whole design is that the row is written inside the
    -- caseworker's transaction, and hanging this off delivery would make a case's process state depend on
    -- an asynchronous outcome that may still be retrying.
    --
    -- The consequence is real and named rather than hidden: if the send then fails terminally, the case is
    -- waiting for a reply to an email nobody received. That is notify-1's flag 8, not something this
    -- migration fixes.
    --
    -- NULL means "never asked", which is every referral today. It is also what makes recording an answer
    -- nobody asked for refusable -- see the service's 409 -- rather than a silently valid state.
    ADD COLUMN "confirmationRequestedAt" timestamp(3),

    -- The answer. NULL means outstanding, and that is the only thing this column's NULL means, which is
    -- what lets the index below be the whole of "what is still waiting".
    ADD COLUMN "confirmationOutcome"     "ConfirmationOutcome",

    -- When the caseworker recorded it, from the clock. Not when the applicant sent their reply -- nobody
    -- here knows that, and a column claiming to would be a guess written down as a fact.
    ADD COLUMN "confirmationReceivedAt"  timestamp(3),

    -- WHICH CASEWORKER recorded the answer, as a display name resolved from their token. text and not a
    -- foreign key, like V8's "claimedBy"/"decidedBy" and for the same reason: there is no user table.
    --
    -- Attribution only. Unlike V14's "claimedById"/"decidedById" there is no matching subject column,
    -- because nothing compares this: recording an outcome is a caseworker act on a case the caller can
    -- already read, so no ownership check turns on who did it and a subject would be an identifier stored
    -- for nobody to use. Never client-supplied.
    ADD COLUMN "confirmationRecordedBy"  text;

-- THE ONLY QUESTION ASKED OF THESE COLUMNS is "what is still outstanding, longest wait first", and this is
-- that question. Partial on "confirmationOutcome" IS NULL, so the index holds only the rows a caseworker
-- can still act on: a referral that was never asked has a NULL "confirmationRequestedAt" and Postgres does
-- not index a NULL leading key, and one whose answer has been recorded leaves the index as it is recorded.
-- In the steady state that is a handful of rows out of the whole table.
--
-- No sweep and no scheduler go with it, deliberately. ReferralService.reclaimStaleClaims sweeps because a
-- stale CLAIM has to be mutated to be released; nothing here needs mutating -- overdue is a WHERE clause
-- over these two columns and nothing more.
CREATE INDEX "deedpoll_referral_awaiting_confirmation_idx"
    ON "deedpoll_referral" ("confirmationRequestedAt")
 WHERE "confirmationOutcome" IS NULL;
