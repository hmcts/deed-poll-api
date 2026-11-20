-- Application deletion's further schema (plan 20261118-dm-application-deletion and its addendum
-- 20261119-deletion-grace-period-and-restore).
--
-- A removed document leaves a tombstone until its case is deleted.
--
-- A caseworker's document removal deletes the row and only then, best effort after the commit, unlinks the file or
-- soft-deletes it in CDAM. A failed unlink leaves bytes on disk, and a CDAM soft delete always leaves the document in
-- dm-store, where CCD's disposer (which skips deleted documents) never reaches it. Without the row, an application
-- deletion's inventory could not list either. DocumentService.removeDocument writes this row in the removal's own
-- transaction; admission lists each one (a filesystem one as LOCAL_DOCUMENT, which the worker removes and verifies; a
-- CDAM one as CDAM_REMOVED_DOCUMENT, which nothing here can purge, so the request needs attention until the dm-store
-- team has purged it: docs/APPLICATION-DELETION.md). It cascades with the case, which finalisation deletes only after
-- every line is Done. The locator is a storage key or a CDAM id, never logged or published.

CREATE TABLE deedpoll_removed_document (
    -- The removed supporting document's own id.
    id text PRIMARY KEY,
    case_id text NOT NULL REFERENCES deedpoll_deedpoll (id) ON DELETE CASCADE,
    storage_state varchar(16) NOT NULL CHECK (storage_state IN ('Local', 'Attached')),
    locator text NOT NULL CHECK (btrim(locator) <> ''),
    removed_at timestamp NOT NULL
);

CREATE INDEX deedpoll_removed_document_case_idx ON deedpoll_removed_document (case_id);

CREATE CONSTRAINT TRIGGER deedpoll_deletion_fence AFTER INSERT OR UPDATE ON deedpoll_removed_document
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_write('case_id');

ALTER TABLE deedpoll_deletion_step DROP CONSTRAINT deedpoll_deletion_step_kind_check;
ALTER TABLE deedpoll_deletion_step ADD CONSTRAINT deedpoll_deletion_step_kind_check
    CHECK (kind IN ('LOCAL_DOCUMENT', 'CDAM_DOCUMENT', 'CDAM_REMOVED_DOCUMENT', 'OWNED_STAGE', 'CCD_DISPOSAL'));

-- The deletion grace period (addendum 20261119): how long a confirmed application deletion stays a soft delete, in
-- whole days, during which a Delivery Manager may restore it. A Delivery Manager process setting, 1 to 90 days,
-- seeded 14. Admission freezes it onto the request, so a later change affects only new requests.
ALTER TABLE deedpoll_process_settings
    ADD COLUMN deletion_grace_days integer NOT NULL DEFAULT 14
        CONSTRAINT deedpoll_process_settings_deletion_grace_days_check CHECK (deletion_grace_days BETWEEN 1 AND 90);

-- The end of a request's grace period (addendum 20261119): requested_at plus the grace period read at admission,
-- frozen there so the date a Delivery Manager was shown never moves. Until then the request is a soft delete that a
-- Delivery Manager may restore, and the worker claims nothing (its claim checks this column as well as
-- next_attempt_at, so an operator's requeue cannot shorten the period). No request has been admitted in any
-- environment before this version, so the column needs no backfill.
ALTER TABLE deedpoll_deletion_request
    ADD COLUMN restorable_until timestamp(3) NOT NULL
        CONSTRAINT deedpoll_deletion_request_restorable_until_check CHECK (restorable_until > requested_at);

-- V48's immutable fields, with restorable_until among them.
CREATE OR REPLACE FUNCTION deedpoll_preserve_deletion_request() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.case_id, NEW.idempotency_key, NEW.reason, NEW.note, NEW.fingerprint, NEW.expected_last_changed_on,
        NEW.requested_by_subject, NEW.requested_by_name, NEW.requested_at, NEW.ccd_case_reference,
        NEW.restorable_until) IS DISTINCT FROM
       (OLD.id, OLD.case_id, OLD.idempotency_key, OLD.reason, OLD.note, OLD.fingerprint, OLD.expected_last_changed_on,
        OLD.requested_by_subject, OLD.requested_by_name, OLD.requested_at, OLD.ccd_case_reference,
        OLD.restorable_until)
       OR (OLD.status = 'Completed' AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'A deletion request cannot be rewritten';
    END IF;
    RETURN NEW;
END;
$$;

-- Rolling back the application below this version: older code never writes the tombstones and ignores them, so the
-- table may stay. Documents removed while older code runs leave no tombstone. Older code never reads
-- deletion_grace_days, and its default keeps older writes of the singleton valid. Older code does not write
-- restorable_until, so its admissions would fail, and it ignores the column, so its worker would claim a request still
-- inside its grace period: restore first any request that should survive, then drop the column before rolling back.
