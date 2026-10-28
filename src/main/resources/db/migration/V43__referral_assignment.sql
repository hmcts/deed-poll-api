-- Plan D2 (Master Sullivan's feedback, item D): a referral is assigned to a Master by the caseworker raising it,
-- rather than claimed from a shared pool. No "ReferralState" member is added: 'Open' now means waiting for
-- assignment and 'Claimed' means assigned, with "claimedBy" the Master's name and "claimedById" their linked IDAM
-- subject (null until the Master first signs in).
--
-- Purely additive: four nullable columns, their checks and one index. No row is rewritten, and that is the go-live
-- rule (D-10). A referral 'Claimed' before this migration keeps its claimer's subject and has no "assignedMasterId";
-- it stays visible to its claimer, its processing window counts from "claimedAt", and linking attaches it to the
-- Masters-list entry once that Master is listed and signs in. An 'Open' one is simply waiting for assignment.
--
-- Mixed case and quoted, as V6 states, because the referral table is.

ALTER TABLE "deedpoll_referral"
    -- The Masters-list entry the referral is assigned to. Kept once directed, so a Decided or Closed referral still
    -- says whose it was; cleared only when the processing window unassigns it. Masters are never deleted (V42), so
    -- the default restrictive foreign key costs nothing.
    ADD COLUMN "assignedMasterId" uuid REFERENCES "deedpoll_master" ("id"),

    -- When the referral was last assigned: the start of the processing window. Null on a referral claimed before
    -- this migration, whose window counts from "claimedAt".
    ADD COLUMN "assignedAt" timestamp,

    -- The referral window the assignment counts towards for the per-Master cap, in Europe/London: a working day
    -- and its morning or afternoon. Kept when a direction is withdrawn, so the withdrawal takes no fresh window.
    ADD COLUMN "windowDate" date,
    ADD COLUMN "windowSlot" varchar(2),

    ADD CONSTRAINT "deedpoll_referral_windowSlot_check" CHECK ("windowSlot" IN ('AM', 'PM')),

    -- A window is a date and a slot, never one of them.
    ADD CONSTRAINT "deedpoll_referral_window_check" CHECK (("windowDate" IS NULL) = ("windowSlot" IS NULL)),

    -- A window is taken only by an assignment, which also stamps "assignedAt".
    ADD CONSTRAINT "deedpoll_referral_window_assigned_check" CHECK ("windowDate" IS NULL OR "assignedAt" IS NOT NULL),

    -- An assignment belongs to somebody: a Masters-list entry or, once a pre-V43 claim has had its window restarted
    -- by a withdrawn direction, at least the subject it was claimed by.
    ADD CONSTRAINT "deedpoll_referral_assigned_owner_check"
        CHECK ("assignedAt" IS NULL OR "assignedMasterId" IS NOT NULL OR "claimedById" IS NOT NULL),

    -- A referral waiting for assignment holds none.
    ADD CONSTRAINT "deedpoll_referral_open_unassigned_check"
        CHECK ("state" <> 'Open'
               OR ("assignedMasterId" IS NULL AND "assignedAt" IS NULL AND "windowDate" IS NULL));

-- The cap's count: one Master's assignments in one window.
CREATE INDEX "deedpoll_referral_assignedMasterId_window_idx"
    ON "deedpoll_referral" ("assignedMasterId", "windowDate", "windowSlot");
