-- When the judge who directed a referral last revised or withdrew that direction (business decisions of
-- 2026-10-19): the flag that tells the caseworker the direction they are about to action is not the one first
-- given.
--
-- Purely additive: one nullable column on "deedpoll_referral", nothing else. Nullable because most referrals
-- never acquire the fact, and a NOT NULL with a default would have to invent a change for each of them.
--
-- Never cleared once set. It is a fact about the referral's history, not its current state: a direction revised
-- and then revised back is still a direction that changed. The old and new values themselves are in the audit
-- trail (V36), which is where "what did it say before" is answered; this column only says "look".
--
-- Mixed case for the reason V6 states.

ALTER TABLE "deedpoll_referral"
    ADD COLUMN "directionChangedAt" timestamp(3);
