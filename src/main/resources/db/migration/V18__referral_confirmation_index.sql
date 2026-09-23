-- Narrows V16's partial index to the referrals that are actually waiting, and corrects the reason V16 gives.
--
-- V18 rather than an edit to V16, for the rule every migration here restates: a committed migration is never
-- touched, because Flyway validates the checksum of one that has already run. V17 was the highest committed
-- when this was written, confirmed by listing src/main/resources/db/migration.
--
-- WHAT V16 GOT WRONG, stated plainly because its comment asserts the opposite and a wrong reason is worse than
-- no reason: "a referral that was never asked has a NULL confirmationRequestedAt and Postgres does not index a
-- NULL leading key" is false. A Postgres B-tree DOES store NULL keys -- which is exactly why `IS NULL` is
-- index-scannable -- so `WHERE "confirmationOutcome" IS NULL` alone admitted every referral that was never
-- asked to confirm. That is every row of the table today and the great majority of it for ever, so the index
-- was not "a handful of rows out of the whole table" as V16 claims: it was the whole table minus the answered
-- ones, paying write amplification on every referral insert and update for rows no query of it can return.
--
-- THE PREDICATE IS NOW BOTH HALVES OF "OUTSTANDING", which is the predicate
-- ReferralRepository.findOutstandingConfirmationRequestsByDeedPollId states and the one V17's sibling index on
-- the case side already had right ("awaitingConfirmationSince" IS NOT NULL). The three are now the same shape
-- and the same reasoning: a referral is waiting when it has been asked and has not answered.
--
-- Dropped and recreated rather than reindexed: the predicate is part of the index's definition, so there is no
-- ALTER for it. Both statements are in one migration, so no environment is left without the index.
DROP INDEX "deedpoll_referral_awaiting_confirmation_idx";

CREATE INDEX "deedpoll_referral_awaiting_confirmation_idx"
    ON "deedpoll_referral" ("confirmationRequestedAt")
 WHERE "confirmationOutcome" IS NULL
   AND "confirmationRequestedAt" IS NOT NULL;
