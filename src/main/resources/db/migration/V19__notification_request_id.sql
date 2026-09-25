-- The caller's own identifier for one send, so a deliberate repeat and a double-click stop looking alike.
--
-- V19 rather than the V18 an earlier draft of the plan named. V18__referral_confirmation_index.sql was already
-- committed when this was written -- the plan says to take the next free number, confirm it by looking, and say
-- so, which this is. Nothing is left reserved: Flyway runs with out-of-order disabled, so a V18 appearing after
-- V19 had been applied would fail every environment that applied V19, which is worse than a renumber.
--
-- WHY THE OTHER TWO KEYS CANNOT ANSWER THIS. V15's key is ("referralId", "exclusionGroup"), which is a business
-- constraint about one referral getting one of two contradictory emails -- and it is partial on
-- "referralId" IS NOT NULL, so a case-driven email sits outside it entirely. That is correct for the chase: a
-- case can stay in AwaitingFurtherDocs after one reminder and the caseworker may legitimately need to ask again,
-- so the database must not make a second chase impossible. But then nothing at all stands between a caseworker's
-- double-click and two identical emails reaching one applicant.
--
-- WHY NOT PAYLOAD EQUALITY OR A TIME WINDOW. Neither can express intent. The same text is a valid reminder next
-- week, so equality would refuse a legitimate second chase for ever; and two clicks milliseconds apart have
-- different timestamps, so any window is either too short to catch a double-click or long enough to refuse a
-- real one. Only the caller knows whether this is the same act of sending or a new one, so the caller says:
-- one compose journey creates one UUID and reuses it for every retry of its final POST, and a later journey
-- gets another. See CaseworkerEmailController's Idempotency-Key header.
--
-- SCOPED TO THE CASE, and that is a safety property rather than tidiness. The key a caller chooses is a UUID they
-- supply, so a global unique index would let one client's chosen value collide with another case's row -- turning
-- an unrelated case's send into a 409, or worse into an idempotent "already owed" answer naming a notification
-- for a different applicant. ("deedPollId", "requestId") makes a collision only ever possible inside the case the
-- caller is already working on, which is where it means something.
--
-- NOT PARTIAL ON THE FAILURE VERDICT, unlike V12's and V15's predicate, and deliberately so. A row that Notify
-- refused permanently still occupies its request id: a retry of the SAME request must go on answering with that
-- failed row rather than quietly writing a second one, because the caseworker's next move is to compose a
-- corrected email -- which is a new journey with a new id -- and the failed attempt has to survive as history.
-- V15's replacement-after-permanent-refusal behaviour is unaffected: that replacement carries a new request id.
--
-- Column name is mixed case for the mechanical reason V6, V11 and V15 give: `globally_quoted_identifiers` and
-- `PhysicalNamingStrategyStandardImpl` are set service-wide, so a snake_case column would be quoted as written
-- and Hibernate would not find it.

-- THE ROLLING-DEPLOYMENT SEQUENCE, in the order it has to happen. Each step is safe on its own and the order is
-- what makes the set of them safe; NotificationRequestIdMigrationTest runs this whole file against a
-- pre-migration copy of the table and asserts each step's effect, because every environment that ever applies it
-- has rows in the table and this build's does not.
--
-- 1. NULLABLE, WITH NO DEFAULT. Adding a column with a volatile default -- gen_random_uuid() is volatile --
--    rewrites the whole table under an ACCESS EXCLUSIVE lock while it fills every row in. Added empty, the
--    catalogue change is all that happens and the lock is held for an instant.
ALTER TABLE "deedpoll_notification"
    ADD COLUMN "requestId" uuid;

-- 2. BACKFILLED FROM THE ROW'S OWN ID, which is unique by the primary key, so the index below can be built
--    without a duplicate being possible. From "id" rather than from gen_random_uuid() because it is
--    deterministic: a repair run of this statement writes the same values again, and a row whose request id is
--    its own id reads as "nobody supplied one" rather than as a request somebody made. It is also already the
--    Notify `reference` the send carries, so the two identifiers a historical row has agree with each other.
--
--    WHERE "requestId" IS NULL so this is idempotent and so that a row written between step 1 and this
--    statement -- an old instance's INSERT taking the column's absence of a default -- is not overwritten. There
--    is no such row in one transaction, and this file may be run by hand.
UPDATE "deedpoll_notification"
   SET "requestId" = "id"
 WHERE "requestId" IS NULL;

-- 3. THE DEFAULT, and it is what carries an application instance that predates this migration. Such an instance
--    issues an INSERT that does not name this column at all -- exactly as V15's "exclusionGroup" default carries
--    one that predates V15 -- so without a default every send from an old pod would violate the NOT NULL below
--    for as long as old and new pods overlap. gen_random_uuid() rather than a constant because the value has to
--    be unique per row within its case: a constant would make the second send on any one case collide with the
--    first and be answered as an idempotent repeat of an email it has nothing to do with. Available without an
--    extension since Postgres 13, and already used by V7.
--
--    New code binds the caller's own value explicitly rather than leaning on this default, so a row written by
--    this release records the request the caseworker's journey actually made.
ALTER TABLE "deedpoll_notification"
    ALTER COLUMN "requestId" SET DEFAULT gen_random_uuid();

-- 4. NOT NULL, after the backfill and after the default, so there is no instant at which a row could fail to
--    satisfy it. A nullable column would leave the index below unable to distinguish two rows that both supplied
--    nothing -- Postgres does not treat two NULLs as equal -- which is the hole this whole migration closes.
ALTER TABLE "deedpoll_notification"
    ALTER COLUMN "requestId" SET NOT NULL;

-- 5. THE KEY ITSELF. One row per (case, request), enforced by Postgres rather than checked by the service: a
--    check-then-insert has a window between its two statements and this does not, which is the whole argument
--    V11 makes about the other key and it is the same argument here with two replicas racing.
--
--    NotificationRepository.enqueue infers this with a bare ON CONFLICT DO NOTHING -- no target -- because after
--    this migration an insert can conflict on either this index or V15's, and naming one target would make the
--    other throw a DataIntegrityViolationException at the caseworker's transaction. The service then reads which
--    of the two refused it, in a defined order, and answers accordingly.
CREATE UNIQUE INDEX "deedpoll_notification_case_request_key"
    ON "deedpoll_notification" ("deedPollId", "requestId");
