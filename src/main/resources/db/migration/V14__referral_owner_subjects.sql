-- Who owns a referral's claim and its direction, as the identity a token actually carries.
--
-- Purely additive. It ADDs two nullable columns to "deedpoll_referral" and does nothing else: no column
-- is dropped, retyped or renamed, no committed migration is touched, and nothing on
-- "deedpoll_deedpoll" changes. V8's "claimedBy"/"decidedBy" stay exactly as they are -- they hold the
-- judge's display name and they keep holding it, because staff reading a case and a lapse note need a
-- person's name rather than an opaque identifier.
--
-- WHY, and it is a security fix rather than tidying. Until now "claimedBy" and "decidedBy" were both
-- the attribution AND the authorisation: JudicialReferralService.visibleTo, its heldBy helper and
-- ReferralService.requireHeldBy all compared CurrentUser.name() against them, which CurrentUser itself
-- records as a known defect with two consequences -- two judges sharing a display name could read and
-- direct each other's referrals, and a judge whose display name changed lost access to their own. A
-- display name is not an identity: two people can share one and one person's changes over time. The
-- token's subject is the identity, so these two columns are what the three ownership checks compare
-- from here on, and the name columns become attribution only. See
-- docs/plans/referrals-api-6-judicial-my-referrals.md.
--
-- NOT BACKFILLED, and that is the point rather than an omission. There is no way to derive a subject
-- from a display name -- guessing one would re-create the collision this migration exists to remove, on
-- exactly the rows where identity is least certain -- so a pre-migration 'Claimed' or 'Decided' row
-- keeps a NULL owner and fails closed: nobody owns it, so nobody may direct it or see it through the
-- judicial surface. A lapsed claim on such a row is returned to the shared pool by the existing
-- inactivity sweep, which matches on "lastTouchedAt" and never on identity, and is then claimable
-- normally. A 'Decided' one stays fully visible and actionable to caseworkers, which is where the
-- direction is needed. There are no such rows in any persistent environment today -- api has never been
-- deployed to one -- and the null handling is kept anyway for the first time that stops being true.
--
-- Text rather than a foreign key, like every other identity column here: there is no user table, and
-- "ownerId" on "deedpoll_saved_query" -- the other resource keyed on the IDAM subject -- is text for
-- the same reason.
--
-- NO INDEX, for the reason V6 gives for the pool: the table is small, and the personal-list query this
-- migration precedes filters on the same "state" column the pool already scans without one. Adding one
-- when a measured plan asks for it is a later migration, not a redesign.
--
-- Column names are mixed case for the mechanical reason V6 states: `globally_quoted_identifiers` and
-- `PhysicalNamingStrategyStandardImpl` are set service-wide, so a snake_case column would be quoted as
-- written and Hibernate would not find it.

ALTER TABLE "deedpoll_referral"
    -- The IDAM subject of the judge currently holding the referral. Set by the claim transition
    -- alongside "claimedBy", and cleared with the rest of the active-claim fields when a claim is
    -- released -- so a released claim has no owner, exactly as it has no holder's name.
    ADD COLUMN "claimedById" text,

    -- The IDAM subject of the judge who recorded the direction. Never cleared: a direction is a
    -- permanent act, and this is what lets its author re-read it while the office actions it.
    ADD COLUMN "decidedById" text;
