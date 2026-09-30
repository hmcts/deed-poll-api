-- Where each supporting document's remote storage has got to (plan 03a, task 3).
--
-- V25 as plan 03a names it, directly after V24__ccd_attempt_proven_nothing_created.sql.
--
-- WHY ANY OF THIS. Today every document is on the pod's filesystem, and a save either wrote the file or threw. The
-- CDAM store is a network conversation in two remote steps -- an upload, then an attach to the record's CCD case --
-- and either step can end without an answer. A lost response must never be read as success, and an uncertain
-- upload must never be answered by sending the binary again and again. So, as V22 does for the CCD case, the row
-- records how far it got, committed before each remote call and after it, and no remote call is made inside a
-- database transaction.
--
-- WHICH MOVES ARE LEGAL is stated once, in Java (DocumentStorageState.successors, and DocumentStorageRule for the
-- one conditional edge), where a container-free test holds it. The CHECKs below state only what each state requires
-- the row to carry, and DocumentStorageStateTest holds the state list here to the enum.
--
-- ONE TRANSACTION. Flyway runs a PostgreSQL migration in a single transaction. No step rewrites the table: a
-- constant default is stored in the catalogue, not in every row, and a CHECK added in the same ALTER is validated
-- by one scan.

ALTER TABLE "deedpoll_supportingdocument"
    -- The storage lifecycle. varchar rather than a Postgres enum type, as plan 03a asks: the states belong to this
    -- plan's store and are expected to grow in 03b and 03c, and adding a member to a varchar CHECK is a migration
    -- that can be written in one transaction. Member names are spelled as DocumentStorageState spells them.
    --
    -- Every EXISTING row is backfilled 'Local': until this migration there was no other store. The default stays
    -- 'Local' for new rows too, deliberately, so that an instance still on V24's code during a rolling deployment
    -- -- which only ever writes to the filesystem -- inserts a row that says so. Code on V25 names the state.
    ADD COLUMN "storageState" varchar(32) NOT NULL DEFAULT 'Local',

    -- CDAM's id for the document, the last 36 characters of its self href. A storage reference only (decision 7):
    -- callers keep using this row's own id, and no response ever carries this one or a URL built from it. NULL on
    -- a Local row, and on a CDAM row until an upload has answered. uuid, because CDAM's is one, so a malformed
    -- value is refused here rather than sent back to CDAM later.
    ADD COLUMN "cdamDocumentId" uuid,

    -- TRANSIENT (decision 8). CDAM's hash token for the upload: whoever holds it can attach the document to any case
    -- of the type it names. Held only between the upload answering and the attach being confirmed -- so exactly while
    -- the row is Uploaded or AttachUncertain, which the CHECK below enforces -- and cleared in the same statement
    -- that records Attached. Never written to a log line, a response, the CCD projection or an event summary. A SHA-256,
    -- hex-encoded, so 64 characters; the bound leaves room for nothing longer to be stored silently.
    ADD COLUMN "cdamHashToken" varchar(64),

    -- When the latest remote step on this row was begun. NULL on a Local row. What an operator reads to tell an
    -- uncertain row that is still in flight from one that was abandoned.
    ADD COLUMN "storageAttemptedOn" timestamp(3),

    -- How many uploads this row has sent, incremented before each one is sent so a process that dies mid-call has
    -- already counted it. Capped at 2 (plan 03a, flag 2): an uncertain upload is retried once, in the same request,
    -- and a second uncertain outcome is parked. The cap is a CHECK as well as a rule, so a third upload cannot be
    -- recorded even by a statement that forgot the rule. Existing rows sent none.
    ADD COLUMN "storageUploadAttempts" smallint NOT NULL DEFAULT 0,

    ADD CONSTRAINT "deedpoll_supportingdocument_storageState_known"
        CHECK ("storageState" IN (
            'Local',
            'UploadPending',
            'UploadUncertain',
            'Uploaded',
            'AttachUncertain',
            'Attached',
            'Failed'
        )),

    ADD CONSTRAINT "deedpoll_supportingdocument_storageUploadAttempts_capped"
        CHECK ("storageUploadAttempts" BETWEEN 0 AND 2),

    -- The hash token is held exactly while an attach is still to be confirmed. Any other state holding one has kept
    -- a credential at rest past the point it was needed; an Uploaded or AttachUncertain row without one could never
    -- be attached, because the token travels in the event payload that attaches it.
    ADD CONSTRAINT "deedpoll_supportingdocument_cdamHashToken_transient"
        CHECK (("cdamHashToken" IS NOT NULL) = ("storageState" IN ('Uploaded', 'AttachUncertain'))),

    -- A row that says CDAM holds its document names the document.
    ADD CONSTRAINT "deedpoll_supportingdocument_cdam_state_has_id"
        CHECK ("storageState" NOT IN ('Uploaded', 'AttachUncertain', 'Attached') OR "cdamDocumentId" IS NOT NULL);
