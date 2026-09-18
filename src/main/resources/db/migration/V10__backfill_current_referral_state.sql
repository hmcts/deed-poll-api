-- One-time recompute of "deedpoll_deedpoll"."currentReferralState" for rows that already existed.
--
-- Data, not schema. V9 added the column; this sets it, once, for every case V9 left NULL by default.
-- Purely additive: no column, type or constraint changes, no committed migration is touched, and
-- "deedpoll_referral" is only read.
--
-- WHY THIS EXISTS, when V9 says the column is "NOT BACKFILLED, deliberately". V9's comment -- and the
-- plan it was written from -- states that V7__backfill_referrals.sql "creates one 'Closed' referral per
-- legacy case that had referral data", and concludes that NULL is therefore right for every migrated
-- case. V7 does not do that. Read it: its state rule has two arms, and only the first is 'Closed'.
--
--   * "masterDecision" set          -> 'Closed'  (terminal, so the case's current state IS NULL)
--   * "dateReferred" set, no decision -> 'Open'  (NON-terminal: a live backlog item, in the pool)
--
-- So a case referred before cutover and still awaiting its decision comes out of V7 with a live 'Open'
-- referral and, under V9 alone, a case column reading NULL -- which is the read model disagreeing with
-- the referral table from the first minute of the first environment. Every such case would be missing
-- from "which cases have a live referral" and would only correct itself if a judge happened to claim
-- it. V9 cannot be edited (it is committed and has run), so the correction is this version.
--
-- WHAT IT WRITES, and why it is not a second statement of the rule. The value comes from a correlated
-- read of the case's own non-terminal referral -- the same projection DeedPollRepository's
-- refreshCurrentReferralStates runs after the stale-claim sweep, and the same rule
-- domain/referrals/CurrentReferralState holds for one case. Recompute-from-truth, not a per-arm guess:
-- writing 'Open' for the awaiting arm would be a third copy of V7's CASE and would drift from it.
--
-- Which means V9's actual conclusion still holds where it was aimed: a decided legacy case has one
-- Closed referral, the subquery matches nothing, and the column stays NULL. This does NOT write 'Closed'
-- onto a migrated case -- that is the thing V9 forbids, and the subquery cannot produce it, because
-- 'Closed' is not in the terminal-excluding set below.
--
-- The subquery yields at most one row because a case holds at most one non-terminal referral
-- (ReferralService.createReferral 409s otherwise), and V7 inserts at most one referral per case anyway.
-- If it ever yielded two this statement would fail loudly rather than pick one, which is the failure to
-- prefer.
--
-- The terminal states are spelled out here rather than asked of ReferralState.isTerminal(), because SQL
-- cannot call it. They are V6's own two and adding a sixth member would need this list revisited --
-- which is why it is stated as "not terminal" rather than as a list of the three live ones: a new
-- non-terminal state added later is then included by default, and being included is the safe direction.
--
-- On a fresh database this updates nothing: V7 inserted nothing into an empty case table, so every
-- subquery is NULL and every column is already NULL. It is written as one statement so it is
-- all-or-nothing without a transaction block of its own.
UPDATE "deedpoll_deedpoll" AS d
   SET "currentReferralState" = (SELECT r."state"
                                   FROM "deedpoll_referral" AS r
                                  WHERE r."deedPollId" = d."id"
                                    AND r."state" NOT IN ('Closed', 'Withdrawn'))
 WHERE EXISTS (SELECT 1
                 FROM "deedpoll_referral" AS r
                WHERE r."deedPollId" = d."id"
                  AND r."state" NOT IN ('Closed', 'Withdrawn'));
