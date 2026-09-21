-- Mutual exclusion between two emails that contradict each other, as a database key rather than as a
-- service check.
--
-- V15 rather than the V14 the plan first named. V14__referral_owner_subjects.sql was already committed
-- when this was written -- the plan says to take the next free number, confirm it by looking, and say
-- so, which this is. Nothing was left reserved: Flyway runs with out-of-order disabled, so a V14
-- appearing after V15 had been applied would fail every environment that applied V15, which is worse
-- than a renumber.
--
-- WHY. "REQUEST_CONFIRMATION_TO_PROCEED" is the second email sent off a refused referral, and it says the
-- opposite of the first: "APPLICATION_REJECTED" tells the applicant enrolment is refused, while this one
-- asks whether they still want the deed enrolled now that their non-publication request has been turned
-- down. V11's key on ("referralId", "template") stops a second copy of either and happily permits one of
-- each -- which is the contradiction reaching one applicant. So the key is widened to the exclusion group
-- both templates belong to, and Postgres decides which of the two a referral gets.
--
-- WHY A GROUP COLUMN AND NOT A TABLE OF INCOMPATIBLE PAIRS. A table is data, and data cannot force a
-- decision: somebody adds a fifth template, forgets its rows, and it silently excludes nothing. The group
-- is a required constructor argument on EmailTemplate instead -- the property CaseEmailService.cause()
-- already relies on, where a new template does not compile until somebody says what it is -- and this
-- index goes on doing the enforcing. See EmailExclusionGroup.
--
-- WHY THE ROW STORES THE GROUP RATHER THAN DERIVING IT FROM "template". The index needs a column, and a
-- row keeps the group it was written under: re-grouping templates in a later release must not
-- retrospectively make two already-sent emails conflict. History stays true to what was sent.
--
-- Column name is mixed case for the mechanical reason V6 and V11 give: `globally_quoted_identifiers` and
-- `PhysicalNamingStrategyStandardImpl` are set service-wide, so a snake_case column would be quoted as
-- written and Hibernate would not find it.

-- NOT NULL WITH A DATABASE DEFAULT, and the default is load-bearing during a rolling deployment. It
-- backfills every existing row correctly -- "APPLICATION_REJECTED" is the only template that has ever been
-- enqueued, and it is in this group -- and it keeps an older application instance, whose INSERT does not
-- name this column, able to write. New code binds EmailTemplate.exclusionGroup().name() explicitly rather
-- than leaning on the default, so a later template in a group of its own is written as itself.
--
-- text, like "template" beside it, and for the same reason: it holds an enum member name that is stable
-- across environments, not an id.
ALTER TABLE "deedpoll_notification"
    ADD COLUMN "exclusionGroup" text NOT NULL DEFAULT 'REFERRAL_OUTCOME';

-- Re-keyed, not supplemented: two mechanisms answering the same question is how they come to disagree.
-- V12 replaced this index under its old name because the key was unchanged and only the predicate moved.
-- Here the key itself changes, so the name changes with it -- an index called "..._template_key" that is
-- not keyed on "template" is a lie a reader would have to check the definition to catch.
DROP INDEX "deedpoll_notification_referral_template_key";

-- V12's predicate carried through verbatim, and it must stay that way. A permanent refusal does not occupy
-- the slot, so correcting a bad template id or API key and trying again writes a new row; Pending, Sent,
-- temporary, technical and verdict-less old rows still occupy it because any of them may already have
-- reached Notify. IS DISTINCT FROM rather than <> because during a rolling deployment an older instance can
-- still write a Failed row with no verdict, and NULL must mean "unknown and blocked" rather than falling
-- through SQL's three-valued logic and disappearing from this index.
--
-- Still partial on "referralId" IS NOT NULL, which is also what lets an insert infer it with
-- ON CONFLICT ("referralId", "exclusionGroup") WHERE ... -- NotificationRepository.enqueue repeats this
-- target and this predicate exactly, and a mismatch there makes the statement silently stop deduplicating
-- rather than fail. The two are one mechanism.
--
-- A template alone in its group is unaffected: a group of one makes this key and the old one the same key.
CREATE UNIQUE INDEX "deedpoll_notification_referral_exclusion_group_key"
    ON "deedpoll_notification" ("referralId", "exclusionGroup")
 WHERE "referralId" IS NOT NULL
   AND ("state" <> 'Failed' OR "failureVerdict" IS DISTINCT FROM 'PERMANENT');
