-- The rest of the referral state machine's columns: what a judge's claim is, and what the direction
-- they send back records.
--
-- Purely additive. It ADDs eight nullable columns to "deedpoll_referral" and does nothing else: no
-- column is dropped, retyped or renamed, no committed migration is touched, and nothing on
-- "deedpoll_deedpoll" changes -- V2's flat referral block ("masterName", "masterDecision",
-- "masterDirections", "publishToLondonGazette" and the rest) stays the ported historical snapshot,
-- read once by V7 and written by nothing. That is a decision, not an omission: syncing a closed
-- referral's direction back onto the case would make those columns mean two different things over
-- time and re-introduce the one-referral-per-case model this feature replaced.
--
-- No new type is created. Both enums this needs already exist -- V6's "ReferralState" defined the
-- whole lifecycle in one go precisely so the transition plans would not need an ALTER TYPE, and
-- "MasterDecision" is V1's. Reusing "MasterDecision" for the judge's direction rather than minting a
-- referral-specific Approved/Rejected keeps one decision vocabulary in the service: it is the ported
-- one, it is already on the case DTOs, and a second enum with the same two members would be two
-- things to keep in step for no gain.
--
-- Every column is nullable, and that is the honest shape rather than a convenience. An 'Open'
-- referral has no claim and no direction; a referral V7 backfilled has neither and never will, since
-- the power app recorded no claim at all and its decision lives in the case's own columns. A NOT NULL
-- with a default would have to invent a value for both cases.
--
-- Column names are mixed case for the mechanical reason V6 states: `globally_quoted_identifiers` and
-- `PhysicalNamingStrategyStandardImpl` are set service-wide, so a snake_case column would be quoted
-- as written and Hibernate would not find it.

ALTER TABLE "deedpoll_referral"
    -- The claim. A judge picks a referral out of the shared pool and it becomes theirs, dropping off
    -- everyone else's; these three columns are the whole of that fact.
    --
    -- "claimedBy" is a display name resolved from the token at claim time, not a foreign key -- there
    -- is no user table, and it is text for the same reason V2's "masterName" is: a name has no length
    -- worth enforcing here, and the audit columns' varchar(200) is copied from the reference schema
    -- rather than chosen.
    ADD COLUMN "claimedBy"       text,
    ADD COLUMN "claimedAt"       timestamp(3),

    -- The input to the inactivity sweep, and the reason this is a third column rather than reusing
    -- "claimedAt": the 2-hour expiry is measured from the judge's last interaction with the referral,
    -- not from when they picked it up, so a judge still working on one keeps it. Refreshed on every
    -- judicial interaction (claiming, reading it through the judicial surface, saving a direction).
    --
    -- Nulled when a claim is released, so the sweep's `WHERE state = 'Claimed' AND "lastTouchedAt" <
    -- ...` cannot match a row it already reclaimed -- which is what makes the sweep write exactly one
    -- lapse note per lapse, even with two replicas loading the pool at once.
    ADD COLUMN "lastTouchedAt"   timestamp(3),

    -- The direction. Recorded in one transition, not a draft then a submit: the "check your answers"
    -- step and its attestation are the admin app's, and api receives one complete confirmed direction.
    ADD COLUMN "decision"        "MasterDecision",
    ADD COLUMN "directionsText"  text,

    -- The judge's decision on Gazette publication, taken from the judge and never derived from the
    -- caseworker's referral reasons. A 'NonPublication' reason is why the case was referred; whether
    -- to publish is the answer, and conflating the two would put an unasked answer on the record.
    ADD COLUMN "publishToGazette" boolean,

    -- Stamped at decision time from the clock and the token, alongside the case's own
    -- "masterDateOfDecision"/"masterName" rather than instead of them.
    ADD COLUMN "decisionDate"    timestamp(3),
    ADD COLUMN "decidedBy"       text;
