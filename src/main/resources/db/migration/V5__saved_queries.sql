-- Saved and shared case-list queries, and each user's selection of them.
--
-- The first tables in this schema that are NOT a port. There is nothing for these in
-- src/test/resources/reference/deed-poll.prisma -- the Dynamics original had saved views, but this
-- service's version of them is new behaviour -- so SchemaConformanceTest has nothing to compare them
-- against, and its forward-only coverage is what makes that silent rather than a failure. Do not go
-- looking for a reference model, and do not add one.
--
-- Two conventions meet here and they have different justifications:
--
--   * Table names are "deedpoll_"-prefixed and all lower case, matching the existing six. Nothing
--     mechanical enforces that -- consistency is the whole argument.
--   * Column names are mixed case, and that one IS mechanical. `globally_quoted_identifiers` and
--     `PhysicalNamingStrategyStandardImpl` are set service-wide, so a snake_case column here would be
--     quoted as written and Hibernate would then not find it.
--
-- "conditions" is jsonb holding the JSON array of wire strings the URL carried
-- ("state:in:ENROLED,SUBMITTED"), not structured objects. That is what lets CaseQuery.parse validate a
-- stored query with no adapter and no second parser, and it is why a saved query round-trips a list URL
-- unchanged. "sortField"/"sortDirection" are decomposed only because they are two bounded scalars;
-- CaseSort.parse is still their validator.
--
-- "ownerId" is a separate column and not a reuse of "createdBy": the audit columns hold a display name
-- resolved at write time, and the owner needs a stable id from the token's subject as well as the name
-- as it read when saved.

CREATE TABLE "deedpoll_savedquery" (
    "id"             uuid          NOT NULL,

    "name"           text          NOT NULL,
    "description"    text,

    -- Publishing makes a query findable, not present in anyone else's list. Selection is the separate
    -- table below.
    "shared"         boolean       NOT NULL DEFAULT false,

    -- The token's subject, and the display name as it read when saved. A departed owner's query then
    -- still says whose it was rather than rendering blank.
    "ownerId"        text          NOT NULL,
    "ownerName"      text          NOT NULL,

    -- The parts of the list URL that define a view. Deliberately NOT "page": page 4 of a previous
    -- result set is not a page of this one.
    "filter"         text,
    "conditions"     jsonb         NOT NULL,
    "sortField"      text,
    "sortDirection"  text,
    "searchTerm"     text,

    -- The audit columns V2 uses, copied including the choices: "modifiedOn" is NOT NULL with no
    -- default because the application always writes it, and the two "By" columns hold a display name.
    "createdOn"      timestamp(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "modifiedOn"     timestamp(3)  NOT NULL,
    "createdBy"      varchar(200),
    "modifiedBy"     varchar(200),

    CONSTRAINT "deedpoll_savedquery_pkey" PRIMARY KEY ("id")
);

-- Which queries a given user has chosen to see. Per user, and one row is not a grant: visibility is
-- still "shared or mine", so a query unshared after selection stops appearing for the subscriber.
--
-- ON DELETE CASCADE is load-bearing. An owner may delete a shared query others have selected, and an
-- orphaned selection row would resurface as a broken navigation entry in every one of their lists.
CREATE TABLE "deedpoll_savedqueryselection" (
    "savedQueryId"   uuid          NOT NULL,
    "userId"         text          NOT NULL,

    CONSTRAINT "deedpoll_savedqueryselection_pkey" PRIMARY KEY ("savedQueryId", "userId")
);

ALTER TABLE "deedpoll_savedqueryselection"
    ADD CONSTRAINT "deedpoll_savedqueryselection_savedQueryId_fkey"
    FOREIGN KEY ("savedQueryId") REFERENCES "deedpoll_savedquery" ("id")
    ON DELETE CASCADE ON UPDATE CASCADE;

-- The primary key's leading column serves "who has selected this query"; the read every list load
-- makes is the other direction, "which queries has this user selected", which it cannot seek on.
CREATE INDEX "deedpoll_savedqueryselection_userId_idx"
    ON "deedpoll_savedqueryselection" ("userId");
