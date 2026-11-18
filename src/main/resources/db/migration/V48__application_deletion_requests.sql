-- A Delivery Manager's request to delete an application permanently (plan 20261118-dm-application-deletion,
-- Task 2), and the inventory of what its cleanup still has to do.
--
-- snake_case, as V31, V40, V42 and V44 are. Nothing here is applicant data except the note, which the Delivery
-- Manager is told must say why and must not name the applicant, and which outlives the case with the audit event.
--
-- **No foreign key to "deedpoll_deedpoll".** The request is the deletion's idempotency tombstone: it has to answer a
-- replay of the same key after the case it names is gone, so a cascade would delete the one row that knows it was
-- deleted, and a restrictive key would stop the deletion itself. case_id is the case's text id (V2) and nothing more.

CREATE TABLE deedpoll_deletion_request (
    id uuid PRIMARY KEY,
    case_id text NOT NULL,
    -- The caller's Idempotency-Key. Scoped to the case: the same key on another case is another request.
    idempotency_key uuid NOT NULL,
    reason varchar(32) NOT NULL CHECK (reason IN ('CREATED_IN_ERROR', 'DUPLICATE')),
    -- Trimmed, 10 to 500 characters, and no control character at all (tab, newline and C1 included), as
    -- DeletionNote.parse states it. Never logged.
    note varchar(500) NOT NULL,
    -- SHA-256 of the admitted body (reason, note, expectedModifiedOn), lower-case hex: a replay with the same key
    -- and a different body is a 409, decided by comparing this and nothing else.
    fingerprint varchar(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    -- The case's "modifiedOn" the Delivery Manager confirmed against, as admitted.
    expected_modified_on timestamp(3) NOT NULL,
    -- The original Delivery Manager: CASE_DELETED is recorded under these, whichever worker finishes the job.
    requested_by_subject varchar(255) NOT NULL CHECK (btrim(requested_by_subject) <> ''),
    requested_by_name varchar(255) NOT NULL,
    requested_at timestamp NOT NULL,
    -- The CCD case the request has to see disposed, captured at admission; null for an unlinked case.
    ccd_case_reference varchar(16) CHECK (ccd_case_reference ~ '^[0-9]{16}$'),
    status varchar(16) NOT NULL
        CHECK (status IN ('Pending', 'InProgress', 'AwaitingDisposal', 'NeedsAttention', 'Completed')),
    -- An allowlisted failure code (DeletionFailure), never a provider message, an exception text or a locator.
    failure varchar(64) CHECK (failure ~ '^[A-Z][A-Z0-9_]*$'),
    -- The worker's claim, as V44's ledger holds one: an owner and a lease, set together.
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    owner uuid,
    lease_until timestamp,
    next_attempt_at timestamp,
    completed_at timestamp,

    CONSTRAINT deedpoll_deletion_request_note_rule CHECK (
        note = btrim(note, ' ') AND char_length(note) BETWEEN 10 AND 500 AND note !~ '[\x01-\x1f\x7f-\x9f]'),
    CONSTRAINT deedpoll_deletion_request_claim CHECK ((owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT deedpoll_deletion_request_completion CHECK ((status = 'Completed') = (completed_at IS NOT NULL)),
    CONSTRAINT deedpoll_deletion_request_completed_unclaimed CHECK (status <> 'Completed' OR owner IS NULL),
    CONSTRAINT deedpoll_deletion_request_failure_status CHECK (failure IS NULL OR status = 'NeedsAttention'),
    -- One deletion per case, whatever its key: a second key returns this request or is refused, and never writes
    -- another. It also makes the case and key together unique, which is the replay's lookup.
    CONSTRAINT deedpoll_deletion_request_case UNIQUE (case_id)
);

-- The worker's claim: the requests with work left, the soonest due first.
CREATE INDEX deedpoll_deletion_request_due_idx ON deedpoll_deletion_request (next_attempt_at)
    WHERE status IN ('Pending', 'InProgress', 'AwaitingDisposal');

-- What admission fixed stays fixed: the actor, the reason, the note, the key and its body, the case and its CCD
-- reference. A Completed request is a tombstone and never changes again. The worker may move the status, its claim
-- and its outcome, and nothing else.
CREATE FUNCTION deedpoll_preserve_deletion_request() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.case_id, NEW.idempotency_key, NEW.reason, NEW.note, NEW.fingerprint, NEW.expected_modified_on,
        NEW.requested_by_subject, NEW.requested_by_name, NEW.requested_at, NEW.ccd_case_reference) IS DISTINCT FROM
       (OLD.id, OLD.case_id, OLD.idempotency_key, OLD.reason, OLD.note, OLD.fingerprint, OLD.expected_modified_on,
        OLD.requested_by_subject, OLD.requested_by_name, OLD.requested_at, OLD.ccd_case_reference)
       OR (OLD.status = 'Completed' AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'A deletion request cannot be rewritten';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER deedpoll_deletion_request_immutable BEFORE UPDATE ON deedpoll_deletion_request
    FOR EACH ROW EXECUTE FUNCTION deedpoll_preserve_deletion_request();

-- The cleanup inventory: one row per thing the deletion must remove and verify, captured at admission. A row's
-- locator (a storage key, a payload id, a CDAM id) is retained until its cleanup is verified and erased once the
-- request completes; the step's kind and outcome code are what the tombstone keeps.
CREATE TABLE deedpoll_deletion_step (
    id uuid PRIMARY KEY,
    -- Cascades from the request, never from the case.
    request_id uuid NOT NULL REFERENCES deedpoll_deletion_request (id) ON DELETE CASCADE,
    kind varchar(32) NOT NULL
        CHECK (kind IN ('LOCAL_DOCUMENT', 'CDAM_DOCUMENT', 'STAGED_PAYLOAD', 'OWNED_STAGE', 'CCD_DISPOSAL')),
    -- The local row it came from (a supporting document's id, a payload's id), if any.
    item_id text,
    locator varchar(1024),
    state varchar(16) NOT NULL CHECK (state IN ('Pending', 'Done', 'Failed')),
    outcome varchar(64) CHECK (outcome ~ '^[A-Z][A-Z0-9_]*$'),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    completed_at timestamp,

    CONSTRAINT deedpoll_deletion_step_completion CHECK ((state = 'Done') = (completed_at IS NOT NULL)),
    -- Work not yet verified still knows what it has to remove.
    CONSTRAINT deedpoll_deletion_step_locator_held CHECK (state = 'Done' OR locator IS NOT NULL),
    -- Also the index the per-request reads use.
    CONSTRAINT deedpoll_deletion_step_item UNIQUE (request_id, kind, item_id)
);

-- Rolling back the application below this version needs nothing: no older code reads either table.
