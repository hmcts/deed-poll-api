-- How long a case has been waiting for the applicant's confirmation, as a column the case list can filter,
-- sort and show.
--
-- V17 rather than a number this plan named in advance. V16__referral_confirmation.sql was the highest
-- committed when this was written, confirmed by listing src/main/resources/db/migration rather than assumed,
-- which is what every migration since V11 has had to do. Nothing is left reserved: Flyway runs with
-- out-of-order disabled.
--
-- WHY A COLUMN ON THE CASE, when V16 already holds the answer on "deedpoll_referral". This is
-- V9__current_referral_state.sql's argument, unchanged, and it is worth restating because it is the only
-- reason to denormalise anything here:
--
--   * The case list filters, sorts and renders columns of "deedpoll_deedpoll" and nothing else. Every
--     filterable field resolves through `root.get(name)` against that one entity (CaseSpecifications), every
--     order is a Spring Data Sort over a root property (DeedPollService.order), and every list row is mapped
--     from the case the page returned (CaseSummaryResponse). So "which cases are still waiting for a reply,
--     longest wait first, and how long has each waited" has no expressible form without a column here.
--   * The alternatives were a correlated subquery and a join to "deedpoll_referral". The subquery cannot
--     carry the ORDER BY a paged query needs; the join multiplies a case by its referrals and makes the page
--     count disagree with the page; and reading each row's referrals through the lazy collection instead
--     would be one query per row of the list.
--
-- WHAT IT HOLDS, and why it is a date and not a flag. The EARLIEST "confirmationRequestedAt" among this
-- case's referrals whose "confirmationOutcome" is still NULL, or NULL when no referral of the case is
-- waiting for anything. A timestamp rather than a boolean because the list orders by it and the row shows
-- the days it implies, and the earliest because the longest wait is the one a caseworker is being reminded
-- of -- a case with two waiting referrals is not a shape the service can produce today (one live referral
-- per case), but the rule answers it rather than picking arbitrarily.
--
-- KEPT IN STEP BY A RECOMPUTE FROM TRUTH, never by a push of what a write thought it did: the send that
-- stamps "confirmationRequestedAt" and the recording that answers it both ask the referral table what is
-- outstanding now, in their own transaction. ConfirmationWait holds that rule with no database in it, for
-- the reason CurrentReferralState gives -- container-backed tests are excluded on the Jenkins agent, and
-- "NULL once the answer is recorded" is exactly the rule a drifting push gets wrong.
--
-- THIS IS NOT A NINTH CASE STATE, and nothing here touches the date cascade. A case's state is derived from
-- its date columns by a verbatim port whose member order is the Dynamics option-set integers; the wait is
-- surfaced ALONGSIDE that state, never inside it. See V16.
--
-- Mixed case for the mechanical reason V6, V9, V11, V15 and V16 all give: `globally_quoted_identifiers` and
-- `PhysicalNamingStrategyStandardImpl` are set service-wide, so a snake_case column would be quoted as
-- written and Hibernate would not find it.
--
-- Purely additive and nullable. Nothing is dropped, retyped or renamed, no committed migration is touched,
-- and no backfill is needed or wanted: "confirmationRequestedAt" only exists from V16, nothing has been
-- stamped yet, so NULL -- "this case is waiting for nothing" -- is the correct value for every existing row
-- rather than a value nobody got round to.
ALTER TABLE "deedpoll_deedpoll"
    ADD COLUMN "awaitingConfirmationSince" timestamp(3);

-- The only question asked of this column is "what is still waiting, longest first", so the index is partial
-- on exactly those rows. In the steady state that is a handful out of the whole table, and the ordering the
-- filter applies is the index's own -- the same shape, and the same reasoning, as V16's index on the
-- referral side of the same fact.
CREATE INDEX "deedpoll_deedpoll_awaiting_confirmation_idx"
    ON "deedpoll_deedpoll" ("awaitingConfirmationSince")
 WHERE "awaitingConfirmationSince" IS NOT NULL;
