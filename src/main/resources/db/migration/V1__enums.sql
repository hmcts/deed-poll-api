-- Option sets, ported from src/test/resources/reference/deed-poll.prisma.
--
-- The member ORDER is load-bearing and must not be changed. Each member's position
-- corresponds to the Dynamics 365 option-set integer documented in the reference file's
-- comments (Adult = 1, AdultTransgender = 2, ...), and those integers are what the
-- Dataverse exports hold and what a future re-import would expect. The integers are not
-- stored here -- the app-layer label maps carry them (reference/ts/deed-poll/enums/
-- dynamics-values.ts) -- so the declaration order is the only place the mapping is
-- anchored in the database.
--
-- Type names are mixed case and therefore quoted, matching the names the reference
-- schema produces. Reproducing them exactly is what lets the two stores be compared
-- column for column during the data migration.

CREATE TYPE "DeedPollType" AS ENUM (
    'Adult',                 -- 1
    'AdultTransgender',      -- 2
    'Minor',                 -- 3
    'MinorTransgender',      -- 4
    'DeedOfRelinquishment',  -- 5
    'Unknown'                -- 6
);

CREATE TYPE "DeedPollState" AS ENUM (
    'Received',             -- 1
    'ReadyForMaster',       -- 2
    'ReferredToMaster',     -- 3
    'Enroled',              -- 4
    'Returned',             -- 5
    'AwaitingFurtherDocs',  -- 6
    'AwaitingSignature',    -- 7
    'AwaitingEnrolment'     -- 8
);

CREATE TYPE "MaritalStatus" AS ENUM (
    'Single',                   -- 1
    'MarriedCivilPartnership',  -- 2
    'Widowed',                  -- 3
    'DivorcedDissolved'         -- 4
);

CREATE TYPE "Privacy" AS ENUM (
    'Normal',  -- 1
    'Private'  -- 2
);

CREATE TYPE "Urgency" AS ENUM (
    'Normal',  -- 1
    'Urgent'   -- 2
);

CREATE TYPE "PostCounter" AS ENUM (
    'Counter',  -- 1
    'Online',   -- 2
    'Post',     -- 3
    'Unknown'   -- 4
);

CREATE TYPE "Nationality" AS ENUM (
    'BritishCitizen',                      -- 1
    'BritishDependantTerritoriesCitizen',  -- 2
    'CommonwealthCitizen',                 -- 3
    'Unknown'                              -- 4
);

CREATE TYPE "NationalitySection" AS ENUM (
    'Section1',        -- 1
    'Section11',       -- 2
    'Section37',       -- 3
    'Section6',        -- 4
    'Unknown',         -- 5
    'NoneOfTheAbove'   -- 6
);

CREATE TYPE "MasterDecision" AS ENUM (
    'Approved',  -- 1
    'Rejected'   -- 2
);

CREATE TYPE "ReferToMaster" AS ENUM (
    'Yes',  -- 1
    'No'    -- 2
);

CREATE TYPE "DocumentDirection" AS ENUM (
    'Incoming',  -- 1
    'Outgoing'   -- 2
);
