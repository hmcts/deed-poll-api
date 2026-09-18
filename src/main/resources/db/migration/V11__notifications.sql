-- The notification outbox: one row per email this service owes an applicant, and the record of what
-- was actually sent.
--
-- V11 rather than the V10 the plan named. V10__backfill_current_referral_state.sql was already
-- committed when this was written, and a committed migration is never edited or renumbered -- the
-- plan says to use the next free number and say so, which this is.
--
-- NOT a port, in the sense V5's saved queries and V6's referrals are not: there is no notification
-- model in src/test/resources/reference/deed-poll.prisma, because the TypeScript store sent nothing.
-- So SchemaConformanceTest has nothing to compare this against and EntityMappingTest's
-- reference-enum factory does not reach "NotificationState" -- ddl-auto: validate and the round-trip
-- test are what hold the entity to this table. Do not go looking for a reference model.
--
-- WHY A TABLE AND NOT A QUEUE. This service has no scheduling -- no @EnableScheduling, no Quartz, no
-- db-scheduler -- and ReferralService's class javadoc says so deliberately. Rows are therefore
-- enqueued in the transaction that causes them and drained by a mutating read hung off caseworker
-- traffic, exactly as the referral inactivity sweep hangs off the judicial pool. That is safe here
-- because every send is a caseworker action: nothing citizen-facing waits on a queue nobody visits.
--
-- WHY THE ROW STORES THE PERSONALISATION RATHER THAN HOW TO REBUILD IT. Part of the content is text a
-- caseworker wrote and may have edited -- the judge's reason arrives pre-populated and editable -- so
-- recomputing it later would answer "what would we send now", not "what was this applicant told".
-- The rendered template version is stored beside it for the same reason.
--
-- Column names are mixed case, for the mechanical reason V6 gives: `globally_quoted_identifiers` and
-- `PhysicalNamingStrategyStandardImpl` are set service-wide, so a snake_case column here would be
-- quoted as written and Hibernate would then not find it. The table name is "deedpoll_"-prefixed and
-- all lower case, matching the existing nine.

-- Three states, and no fourth. There is deliberately no 'Claiming' or 'InFlight': a drain leases a
-- row by pushing "nextAttemptAt" into the future inside the same conditional UPDATE that re-asserts
-- the row is Pending and due, so "claimed" is a fact about the lease rather than a state to get
-- stuck in. A replica that dies mid-send leaves a Pending row that becomes claimable again when the
-- lease expires, with its attempt already counted -- which is what stops a crash looping forever.
--
-- 'Sent' means Notify accepted it, not that it was delivered. There is no delivery callback and no
-- polling in this plan; "providerNotificationId" is stored so adding one later is additive.
CREATE TYPE "NotificationState" AS ENUM (
    'Pending',
    'Sent',
    'Failed'
);

CREATE TABLE "deedpoll_notification" (
    -- Also the Notify `reference` the send carries, so a row and a notification are mutually
    -- traceable: given one, the other is findable. A uuid like V5's and V6's ids -- this row has no
    -- counterpart in the source store, so there is no id to preserve across a data migration.
    "id"                     uuid               NOT NULL,

    -- Who the email is about, and how a caseworker read finds it. text, not uuid, for the reason V6
    -- gives: it is a foreign key onto "deedpoll_deedpoll"("id"), which V2 declares as text, and
    -- Postgres will not build a foreign key between uuid and text.
    "deedPollId"             text               NOT NULL,

    -- The referral that caused it. Nullable because not every later template is referral-driven --
    -- the two forms-for-completion emails are sent off the case alone -- and the unique index below
    -- is partial for exactly that reason.
    "referralId"             uuid,

    -- The EmailTemplate member name, NOT the Notify UUID. The UUID differs per environment, so it is
    -- configuration; storing it here would make a row unreadable after a template was re-pointed,
    -- and would say nothing about which email this is.
    "template"               text               NOT NULL,

    -- What Notify rendered when the caseworker previewed it. Nullable: a row enqueued without a
    -- preview has no version to record, and inventing one would claim knowledge of the wording.
    "templateVersion"        integer,

    -- Resolved at send and frozen, so a later edit to the case cannot redirect an email already
    -- owed. NOT NULL because a blank recipient is refused at the operation with a 422 that names the
    -- missing field, rather than being enqueued and quietly skipped later.
    "recipient"              text               NOT NULL,

    -- The resolved values and the caseworker's slots, as sent. jsonb and SqlTypes.JSON, the shape
    -- "deedpoll_savedquery"."conditions" uses. Defaulted to '{}' rather than nullable: a row with no
    -- personalisation is an empty map, and NULL would be a third case every reader has to handle.
    "personalisation"        jsonb              NOT NULL DEFAULT '{}'::jsonb,

    "state"                  "NotificationState" NOT NULL DEFAULT 'Pending',

    -- Incremented by the claim itself, before the send is attempted, so an attempt that killed the
    -- process is still counted. A counter that only advanced on a completed attempt would let one
    -- row be retried forever.
    "attempts"               integer            NOT NULL DEFAULT 0,

    -- The backoff, and the lease. `state = 'Pending' AND "nextAttemptAt" <= now` is the claimable
    -- set, which is the only predicate the drain uses. NOT NULL with a default of now, so a row is
    -- claimable the instant it is enqueued.
    "nextAttemptAt"          timestamp(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- What Notify answered with. Stored so a delivery query is possible later without a migration.
    "providerNotificationId" uuid,

    -- The taxonomy's verdict plus whatever Notify said. Kept on a row that is still Pending as well
    -- as on a failed one, so "waiting, and here is why the last attempt did not work" is answerable.
    "failureReason"          text,

    -- WHICH CASEWORKER SENT IT, and NOT NULL unlike every other "createdBy" in this schema. They
    -- authored part of the content, so this is the author of a letter to an applicant rather than
    -- ordinary audit noise, and there is always one: a send is a caseworker action, and
    -- CurrentUser.name() falls back to the service account rather than to null.
    "createdBy"              varchar(200)       NOT NULL,

    -- The rest of the audit columns V2 uses, copied including the choices: "modifiedOn" is NOT NULL
    -- with no default because the application always writes it, and "modifiedBy" holds a display name
    -- resolved at write time -- the drain writes the service account there.
    "createdOn"              timestamp(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "modifiedOn"             timestamp(3)       NOT NULL,
    "modifiedBy"             varchar(200),

    CONSTRAINT "deedpoll_notification_pkey" PRIMARY KEY ("id")
);

-- IDEMPOTENCY AS A CONSTRAINT RATHER THAN A CHECK. One Application Rejected per referral, ever. A
-- caseworker double-clicking, or two replicas racing, must not tell an applicant twice that their
-- application was refused -- and a check-then-insert in application code has a window between the two
-- statements that this does not.
--
-- Partial, on "referralId" IS NOT NULL, because a later template that is not referral-driven has no
-- referral to be unique by and a NULL would not collide anyway. Being partial is also what lets an
-- insert infer it with ON CONFLICT ("referralId", "template") WHERE "referralId" IS NOT NULL.
--
-- The key is per referral, which suits this template and may not generalise: a reminder that
-- legitimately sends twice for one referral needs a different key. Flagged in the plan so this is not
-- read as a universal rule.
CREATE UNIQUE INDEX "deedpoll_notification_referral_template_key"
    ON "deedpoll_notification" ("referralId", "template")
 WHERE "referralId" IS NOT NULL;

-- The claimable predicate, in the order the drain asks it: equality on "state" then a range on
-- "nextAttemptAt". Both columns, so a drain on a table whose rows are almost all 'Sent' reads the few
-- that are due rather than the whole table -- which is the steady state, since a row leaves 'Pending'
-- on its first successful attempt and never returns.
CREATE INDEX "deedpoll_notification_claimable_idx"
    ON "deedpoll_notification" ("state", "nextAttemptAt");

-- Postgres does not index a foreign key column for you. The Email tab reads one case's notifications,
-- and without this, deleting a case would scan this whole table for the cascade -- the reason V3 and
-- V6 index their "deedPollId" too.
CREATE INDEX "deedpoll_notification_deedPollId_idx"
    ON "deedpoll_notification" ("deedPollId");

-- Both foreign keys cascade on delete, and the referral one is not SET NULL on purpose. A referral is
-- only ever deleted with its case -- withdrawing one is a state transition, not a delete -- so
-- SET NULL would only ever fire while the notification row was itself being removed, and it would
-- destroy the very key the unique index above is built on.
ALTER TABLE "deedpoll_notification"
    ADD CONSTRAINT "deedpoll_notification_deedPollId_fkey"
    FOREIGN KEY ("deedPollId") REFERENCES "deedpoll_deedpoll" ("id")
    ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "deedpoll_notification"
    ADD CONSTRAINT "deedpoll_notification_referralId_fkey"
    FOREIGN KEY ("referralId") REFERENCES "deedpoll_referral" ("id")
    ON DELETE CASCADE ON UPDATE CASCADE;
