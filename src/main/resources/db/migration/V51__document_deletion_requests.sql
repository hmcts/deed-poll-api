-- Permanent document deletion (plan 20261125-permanent-document-deletion, Task 2; contracts in
-- docs/DOCUMENT-DELETION.md): a document's monotonic version, the durable request a member of staff's deletion
-- writes before any remote call, the inventory of the remote steps it still has to confirm, and the fence.
--
-- snake_case, as V48 is. The reason is the staff member's own words and the document name is what they confirmed;
-- both may carry applicant data, so both are held only until the request completes (the completion copies them into
-- the DOCUMENT_DELETED audit event and the case note, in the same transaction) and the request cascades with its case.

-- 1. The version a confirmation page reviewed. Incremented by the row's own trigger whenever a reviewed column
-- changes, so no writer can forget it; it takes no lock beyond the row being updated, so it is not the commit-time
-- case-lock trigger application deletion removed. An UPDATE that changes nothing reviewed (modifiedOn alone) keeps it.
ALTER TABLE deedpoll_supportingdocument
    ADD COLUMN "version" bigint NOT NULL DEFAULT 1
        CONSTRAINT deedpoll_supportingdocument_version_check CHECK ("version" >= 1);

CREATE FUNCTION deedpoll_supportingdocument_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.name, NEW.direction, NEW."documentTypeId", NEW."storageKey", NEW."originalName", NEW."mimeType",
        NEW."sizeBytes", NEW."storageState", NEW."cdamDocumentId") IS DISTINCT FROM
       (OLD.name, OLD.direction, OLD."documentTypeId", OLD."storageKey", OLD."originalName", OLD."mimeType",
        OLD."sizeBytes", OLD."storageState", OLD."cdamDocumentId") THEN
        NEW."version" := OLD."version" + 1;
    ELSE
        NEW."version" := OLD."version";
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER deedpoll_supportingdocument_version BEFORE UPDATE ON deedpoll_supportingdocument
    FOR EACH ROW EXECUTE FUNCTION deedpoll_supportingdocument_version();

-- 2. The request. One per document, whatever its key; the key is unique within the case, so a replay finds its
-- request after the document row is gone. It cascades with the case: application deletion's finalisation disposes
-- it (Task 5 refuses application admission while one is unresolved). No foreign key to the document, which the
-- request outlives.
CREATE TABLE deedpoll_document_deletion_request (
    id uuid PRIMARY KEY,
    case_id text NOT NULL REFERENCES deedpoll_deedpoll (id) ON DELETE CASCADE,
    document_id text NOT NULL CHECK (btrim(document_id) <> ''),
    idempotency_key uuid NOT NULL,
    -- The staff member's words (DocumentDeletionReason.parse): outer whitespace trimmed, 1 to 500 code points, no
    -- control character, line or paragraph separator or bidirectional control. Null only once Completed, when the
    -- audit event and case note hold it. Never logged.
    reason varchar(500),
    -- The document's name as confirmed, for the case note; null only once Completed.
    document_name varchar(100),
    -- SHA-256 of the admitted body (document, reason, expected version), lower-case hex: the same key with another
    -- fingerprint is KEY_REUSED.
    fingerprint varchar(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    expected_document_version bigint NOT NULL CHECK (expected_document_version >= 1),
    -- The original requester: DOCUMENT_DELETED and the case note are recorded under these, whoever finishes the job.
    requested_by_subject varchar(255) NOT NULL CHECK (btrim(requested_by_subject) <> ''),
    requested_by_name varchar(255) NOT NULL,
    requested_at timestamp NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('InProgress', 'NeedsAttention', 'Completed')),
    -- An allowlisted failure code (DocumentDeletionFailure), never a provider message, an exception text or a locator.
    failure varchar(64) CHECK (failure ~ '^[A-Z][A-Z0-9_]*$'),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    owner uuid,
    lease_until timestamp,
    next_attempt_at timestamp,
    completed_at timestamp,

    CONSTRAINT deedpoll_document_deletion_request_reason_rule CHECK (
        reason IS NULL OR (char_length(reason) BETWEEN 1 AND 500 AND reason = btrim(reason)
            AND reason !~ '[\x01-\x1f\x7f-\x9f\u061c\u200e\u200f\u2028-\u202e\u2066-\u2069]')),
    CONSTRAINT deedpoll_document_deletion_request_held CHECK (
        status = 'Completed' OR (reason IS NOT NULL AND document_name IS NOT NULL)),
    CONSTRAINT deedpoll_document_deletion_request_claim CHECK ((owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT deedpoll_document_deletion_request_completion CHECK ((status = 'Completed') = (completed_at IS NOT NULL)),
    CONSTRAINT deedpoll_document_deletion_request_completed_unclaimed CHECK (status <> 'Completed' OR owner IS NULL),
    CONSTRAINT deedpoll_document_deletion_request_failure_status CHECK (failure IS NULL OR status = 'NeedsAttention'),
    CONSTRAINT deedpoll_document_deletion_request_document UNIQUE (document_id),
    CONSTRAINT deedpoll_document_deletion_request_key UNIQUE (case_id, idempotency_key)
);

CREATE INDEX deedpoll_document_deletion_request_case_idx ON deedpoll_document_deletion_request (case_id);

-- The recovery sweep's claim: the requests with work left, the soonest due first.
CREATE INDEX deedpoll_document_deletion_request_due_idx ON deedpoll_document_deletion_request (next_attempt_at)
    WHERE status = 'InProgress';

-- What admission fixed stays fixed. The reason and the document name may go to null, together, only on the move to
-- Completed; a Completed request is a receipt and never changes again.
CREATE FUNCTION deedpoll_preserve_document_deletion_request() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.case_id, NEW.document_id, NEW.idempotency_key, NEW.fingerprint, NEW.expected_document_version,
        NEW.requested_by_subject, NEW.requested_by_name, NEW.requested_at) IS DISTINCT FROM
       (OLD.id, OLD.case_id, OLD.document_id, OLD.idempotency_key, OLD.fingerprint, OLD.expected_document_version,
        OLD.requested_by_subject, OLD.requested_by_name, OLD.requested_at)
       OR OLD.status = 'Completed'
       OR ((NEW.reason, NEW.document_name) IS DISTINCT FROM (OLD.reason, OLD.document_name)
           AND NOT (NEW.status = 'Completed' AND NEW.reason IS NULL AND NEW.document_name IS NULL)) THEN
        RAISE EXCEPTION 'A document deletion request cannot be rewritten';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER deedpoll_document_deletion_request_immutable BEFORE UPDATE ON deedpoll_document_deletion_request
    FOR EACH ROW EXECUTE FUNCTION deedpoll_preserve_document_deletion_request();

-- A fenced application admits no new document deletion (V49's function, on insert only: an admitted request's own
-- moves are not new writes to the case, and Task 5 refuses application admission while one is unresolved).
CREATE CONSTRAINT TRIGGER deedpoll_deletion_fence AFTER INSERT ON deedpoll_document_deletion_request
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_write('case_id');

-- 3. The remote steps admission found, each with the locator it removes, kept until that step is confirmed.
CREATE TABLE deedpoll_document_deletion_step (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES deedpoll_document_deletion_request (id) ON DELETE CASCADE,
    kind varchar(32) NOT NULL CHECK (kind IN ('CCD_REFERENCE', 'CDAM_BINARY', 'LOCAL_FILE')),
    -- A CCD case reference, a CDAM document id or a filesystem storage key. Never logged or published.
    locator varchar(1024),
    state varchar(16) NOT NULL CHECK (state IN ('Pending', 'Done', 'Failed')),
    outcome varchar(64) CHECK (outcome ~ '^[A-Z][A-Z0-9_]*$'),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    completed_at timestamp,

    CONSTRAINT deedpoll_document_deletion_step_completion CHECK ((state = 'Done') = (completed_at IS NOT NULL)),
    CONSTRAINT deedpoll_document_deletion_step_locator_held CHECK (state = 'Done' OR locator IS NOT NULL),
    CONSTRAINT deedpoll_document_deletion_step_kind UNIQUE (request_id, kind)
);

-- 4. The document fence's backstop at commit. The application asks first (the entity's restriction hides a fenced
-- document from every JPA read; writers ask after their lock); these refuse at commit whatever slipped past: an
-- INSERT or UPDATE of a document a request names (DELETE is the completion's own work), and a new email whose frozen
-- personalisation names one. Any personalisation value is compared, not a list of document keys, so a new
-- document-carrying template is covered without anyone remembering; the case scope keeps it exact.
CREATE FUNCTION deedpoll_refuse_fenced_document() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'deedpoll_supportingdocument' THEN
        IF EXISTS (SELECT 1 FROM deedpoll_document_deletion_request WHERE document_id = NEW.id) THEN
            RAISE EXCEPTION 'The document is being deleted'
                USING ERRCODE = 'P0001', HINT = 'deedpoll_document_deletion_fence';
        END IF;
    ELSIF NEW.personalisation IS NOT NULL AND jsonb_typeof(NEW.personalisation) = 'object' THEN
        PERFORM 1 FROM deedpoll_deedpoll WHERE id = NEW."deedPollId" FOR KEY SHARE;
        IF EXISTS (SELECT 1 FROM jsonb_each_text(NEW.personalisation) item
                     JOIN deedpoll_document_deletion_request fence
                       ON fence.document_id = item.value AND fence.case_id = NEW."deedPollId") THEN
            RAISE EXCEPTION 'The document is being deleted'
                USING ERRCODE = 'P0001', HINT = 'deedpoll_document_deletion_fence';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER deedpoll_document_deletion_fence AFTER INSERT OR UPDATE ON deedpoll_supportingdocument
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_document();
CREATE CONSTRAINT TRIGGER deedpoll_document_deletion_fence AFTER INSERT ON deedpoll_notification
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_document();

-- Rolling back the application below this version: older code never writes either table and ignores the version
-- column, whose default keeps its inserts valid and whose trigger keeps counting its updates. The fence refuses only
-- writes to a document a request names, which older code cannot create, so everything may stay. Complete or settle
-- any InProgress request first: older code does not know to hide its fenced document.
