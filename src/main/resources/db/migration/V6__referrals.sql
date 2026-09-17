-- Referrals to the judiciary: the referral, and the state machine it moves through.
--
-- NOT a port, in exactly the sense V5's saved queries are not. There is no referral model in
-- src/test/resources/reference/deed-poll.prisma -- the Dynamics original held one referral inline on
-- the case, latest-wins ("dateReferred", "masterName", "masterDecision", "referralNotes" and the rest
-- of that block in V2), and this table replaces that flat snapshot with a real entity so a case can
-- carry many referrals over its lifetime. So SchemaConformanceTest has nothing to compare this
-- against, and its forward-only coverage is what makes that silent rather than a failure. Do not go
-- looking for a reference model and do not add one.
--
-- V2's flat columns are deliberately left alone. They stay the ported historical snapshot, and
-- V7__backfill_referrals.sql reads them once to seed this table. Nothing in the application writes
-- them any more; whether a closed referral syncs back into them is an open question owned by the
-- directions plan.
--
-- The two naming conventions meet here as they do in V5, and for the same reasons:
--
--   * The table name is "deedpoll_"-prefixed and all lower case, matching the existing eight.
--     Nothing mechanical enforces that -- consistency is the whole argument.
--   * Column names are mixed case, and that one IS mechanical. `globally_quoted_identifiers` and
--     `PhysicalNamingStrategyStandardImpl` are set service-wide, so a snake_case column here would
--     be quoted as written and Hibernate would then not find it.
--
-- Two type choices that are not free:
--
--   * "deedPollId" is text, not uuid. It has to be: it is a foreign key onto
--     "deedpoll_deedpoll"("id"), which V2 declares as text holding an application-generated UUID
--     (matching the reference so ids survive the data migration), and Postgres will not build a
--     foreign key between uuid and text. The build plan said uuid for this column; the foreign key
--     is the stronger requirement and this follows V3's children, all of which key the case by text.
--   * "id" IS uuid, like V5's saved queries. This row has no counterpart in the source store, so
--     there is no id to preserve, and nothing here has to survive a Dataverse re-import.

-- All five states now, though only 'Open' and 'Withdrawn' are reached by the operations this
-- migration ships with. Defining the whole lifecycle in one go keeps the type additive: the plans
-- that add claiming, a recorded direction and closing add transitions, not a migration -- and
-- ALTER TYPE ... ADD VALUE cannot run in the same transaction as the statements that use it, which
-- is precisely the sort of migration worth not needing.
--
-- 'Closed' and 'Withdrawn' are the terminal pair and they are NOT synonyms: closed means a direction
-- came back and was actioned, withdrawn means the caseworker pulled the referral back. Merging them
-- would lose the only record that a referral was abandoned. 'Open', 'Claimed' and 'Decided' are the
-- non-terminal set, which is what the one-open-per-case guard tests against.
--
-- Unlike V1's option sets, member order carries no meaning here: there is no Dynamics option-set
-- integer behind it, because there was no Dynamics referral entity. It is the lifecycle order,
-- for legibility only.
CREATE TYPE "ReferralState" AS ENUM (
    'Open',
    'Claimed',
    'Decided',
    'Closed',
    'Withdrawn'
);

CREATE TABLE "deedpoll_referral" (
    "id"            uuid            NOT NULL,

    -- The parent case. ON DELETE CASCADE below, as V3 does for notes, witnesses and documents: a
    -- referral has no meaning without its case, and every read of one is scoped by this column.
    "deedPollId"    text            NOT NULL,

    "state"         "ReferralState" NOT NULL DEFAULT 'Open',

    -- Why the case is being referred: a JSON array of ReferralReason names, the same shape and for
    -- the same reason as "deedpoll_savedquery"."conditions" -- validated on write against the Java
    -- enum rather than being a Postgres enum array, because Hibernate maps jsonb to a List<String>
    -- with one annotation and an enum array with none. ReferralReason is deliberately NOT a Postgres
    -- type: nothing queries this column, whereas "state" is queried on every guard.
    --
    -- NOT NULL with an empty-array default because a backfilled referral genuinely has no reasons --
    -- the field did not exist in the power app -- and '[]' says that where NULL would not.
    "reasons"       jsonb           NOT NULL DEFAULT '[]'::jsonb,

    -- One free-text field, not two. It is the explanation 'Other' requires and the general referral
    -- note, unified: a second near-identical column would have to be kept in step for no gain. The
    -- "mandatory when Other" rule is the domain's, not a constraint here, because it depends on the
    -- contents of "reasons".
    "notes"         text,

    -- The referral's own urgency, reusing V1's "Urgency" type. Separate from the case's "urgency"
    -- and free to differ: a normal case can be referred urgently. "urgencyReason" is required by the
    -- domain when this is 'Urgent', so an urgent referral always says why it jumped the queue.
    "urgency"       "Urgency"       NOT NULL DEFAULT 'Normal',
    "urgencyReason" text,

    -- Seeded by V7 from the legacy flat columns rather than raised by a caseworker. It exists so an
    -- empty "reasons" array on those rows reads as honest history instead of a bug.
    "migrated"      boolean         NOT NULL DEFAULT false,

    -- The audit columns V2 uses, copied including the choices: "modifiedOn" is NOT NULL with no
    -- default because the application always writes it, and the two "By" columns hold a display name
    -- resolved at write time, not a foreign key -- there is no user table.
    "createdOn"     timestamp(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "modifiedOn"    timestamp(3)    NOT NULL,
    "createdBy"     varchar(200),
    "modifiedBy"    varchar(200),

    CONSTRAINT "deedpoll_referral_pkey" PRIMARY KEY ("id")
);

-- Postgres does not index a foreign key column for you, and every read in this plan is case-scoped:
-- the referrals tab lists by "deedPollId", the get is by id AND "deedPollId", and the one-open guard
-- is an existence check on "deedPollId" plus "state". V3 indexes the children's "deedPollId" for the
-- same reason, and without it deleting a case would also scan this whole table for the cascade.
CREATE INDEX "deedpoll_referral_deedPollId_idx" ON "deedpoll_referral" ("deedPollId");

ALTER TABLE "deedpoll_referral"
    ADD CONSTRAINT "deedpoll_referral_deedPollId_fkey"
    FOREIGN KEY ("deedPollId") REFERENCES "deedpoll_deedpoll" ("id")
    ON DELETE CASCADE ON UPDATE CASCADE;
