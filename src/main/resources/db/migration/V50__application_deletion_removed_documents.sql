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

-- Rolling back the application below this version: older code never writes the tombstones and ignores them, so the
-- table may stay. Documents removed while older code runs leave no tombstone. Older code never reads
-- deletion_grace_days, and its default keeps older writes of the singleton valid.
