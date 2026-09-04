-- The child tables and the reference counter, ported from src/test/resources/reference/
-- deed-poll.prisma: DeedPollNote, DeedPollWitness, DeedPollSupportingDocumentType,
-- DeedPollSupportingDocument and ReferenceCounter. Completes the baseline begun in V2.
--
-- Same porting rules as V2 -- mixed-case column names are therefore quoted, "id" is text
-- holding an application-generated UUID, and "modifiedOn" is NOT NULL with no default
-- because the application always writes it (the reference's @updatedAt). Beyond those:
--
--   * Delete rules are the reference's, not a preference. "deedPollId" is
--     onDelete: Cascade on all three children -- deleting a case takes its notes,
--     witnesses and documents with it, which is intentional. "documentTypeId" declares no
--     onDelete, so it gets Prisma's default for a required relation, RESTRICT: a document
--     type that is still in use cannot be deleted. ON UPDATE CASCADE throughout is also
--     Prisma's default and is reproduced so a future `prisma migrate diff` against this
--     schema stays empty.
--   * deedpoll_notes has no "deedpoll_NoteID". The source's autonumber display code is
--     deliberately not ported -- Dataverse forces a GUID primary key so a short readable
--     handle had to be a second column, and Postgres does not. The reference records the
--     omission explicitly; do not add it back.
--   * deedpoll_referencecounter is keyed by year alone, and the serial is shared across
--     KBDP and KBDR within a year. It exists so allocation can be a locked read-modify-write
--     inside a transaction rather than the source flow's racy read-max-and-add-one.
--   * The two int[] multiselects on the document type are nullable with an empty-array
--     default, matching the reference and V2's three.

CREATE TABLE "deedpoll_notes" (
    "id"           text          NOT NULL,
    "deedPollId"   text          NOT NULL,

    "note"         varchar(2000) NOT NULL,

    -- Audit text mirroring the case's own. Nullable so notes predating these columns render
    -- "Not recorded" rather than "null", and so a caller sending no user name is never
    -- attributed to someone who did not write the note.
    "createdBy"    varchar(200),
    "modifiedBy"   varchar(200),

    "createdOn"    timestamp(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "modifiedOn"   timestamp(3)  NOT NULL,

    CONSTRAINT "deedpoll_notes_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "deedpoll_notes_deedPollId_idx" ON "deedpoll_notes" ("deedPollId");

ALTER TABLE "deedpoll_notes"
    ADD CONSTRAINT "deedpoll_notes_deedPollId_fkey"
    FOREIGN KEY ("deedPollId") REFERENCES "deedpoll_deedpoll" ("id")
    ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "deedpoll_witness" (
    "id"                  text         NOT NULL,
    "deedPollId"          text         NOT NULL,

    "witnessName"         varchar(100) NOT NULL,
    "witnessAddress"      varchar(250) NOT NULL,
    "witnessOccupation"   varchar(100) NOT NULL,
    -- Yes/No in the source: flags declarant vs witness.
    "declarant"           boolean      NOT NULL,

    "createdOn"           timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "modifiedOn"          timestamp(3) NOT NULL,

    CONSTRAINT "deedpoll_witness_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "deedpoll_witness_deedPollId_idx" ON "deedpoll_witness" ("deedPollId");

ALTER TABLE "deedpoll_witness"
    ADD CONSTRAINT "deedpoll_witness_deedPollId_fkey"
    FOREIGN KEY ("deedPollId") REFERENCES "deedpoll_deedpoll" ("id")
    ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "deedpoll_supportingdocumenttype" (
    "id"                        text         NOT NULL,
    "name"                      varchar(100) NOT NULL,

    -- Multiselect option-set values preserved as int[], as in V2.
    "applicableMaritalStatuses" integer[]    DEFAULT ARRAY[]::integer[],
    "applicableTypes"           integer[]    DEFAULT ARRAY[]::integer[],

    CONSTRAINT "deedpoll_supportingdocumenttype_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "deedpoll_referencecounter" (
    "year"       integer NOT NULL,
    "lastSerial" integer NOT NULL DEFAULT 0,

    CONSTRAINT "deedpoll_referencecounter_pkey" PRIMARY KEY ("year")
);

CREATE TABLE "deedpoll_supportingdocument" (
    "id"             text                NOT NULL,
    "deedPollId"     text                NOT NULL,

    "direction"      "DocumentDirection" NOT NULL,
    "name"           varchar(100)        NOT NULL,
    "documentTypeId" text                NOT NULL,

    -- File reference only. The blob lives outside the database; this is metadata.
    "storageKey"     text,
    "originalName"   text,
    "mimeType"       text,
    "sizeBytes"      integer,

    "createdOn"      timestamp(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "modifiedOn"     timestamp(3)        NOT NULL,

    CONSTRAINT "deedpoll_supportingdocument_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "deedpoll_supportingdocument_deedPollId_idx"
    ON "deedpoll_supportingdocument" ("deedPollId");

ALTER TABLE "deedpoll_supportingdocument"
    ADD CONSTRAINT "deedpoll_supportingdocument_deedPollId_fkey"
    FOREIGN KEY ("deedPollId") REFERENCES "deedpoll_deedpoll" ("id")
    ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "deedpoll_supportingdocument"
    ADD CONSTRAINT "deedpoll_supportingdocument_documentTypeId_fkey"
    FOREIGN KEY ("documentTypeId") REFERENCES "deedpoll_supportingdocumenttype" ("id")
    ON DELETE RESTRICT ON UPDATE CASCADE;
