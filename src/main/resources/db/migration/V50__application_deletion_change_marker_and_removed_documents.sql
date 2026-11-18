-- Two gaps in application deletion (plan 20261118-dm-application-deletion), closed in the database.
--
-- 1. A case's lastChangedOn becomes a value every audited commit moves.
--
-- The stale-confirmation check compared the later of "modifiedOn" and the case's newest audited write. Both are
-- stamped from a pod's clock while the write runs, not when it commits, so a write stamped T1 that committed after a
-- write stamped T2 > T1 had already been shown left the maximum where it was, and a deletion reviewed at T2 was
-- admitted over a change nobody saw (clock skew between pods did the same).
--
-- "lastChangedOn" is moved by a constraint trigger deferred to commit, on every audited write with a case. It takes
-- the case row's lock to do so (an UPDATE), so the moves are in commit order, and each sets a value at least a
-- millisecond past both the column's last value and the row's "modifiedOn": the published lastChangedOn
-- (CaseLastChanged, the later of the two) strictly increases with every audited commit. Admission reads it after
-- taking the case row FOR UPDATE, so a commit either moved it first or waits for admission and is then refused by
-- V49's fence.
--
-- Reads are not changes: opening the case to review it must not make the review stale. The list below is
-- AuditAction's isRead members, and CaseChangeMarkerTest holds the two together. A case with a deletion request is
-- not moved: its admission, worker and finalisation record events on it, V49 refuses every other write, and moving
-- the fenced row would be refused by that same fence.

ALTER TABLE deedpoll_deedpoll ADD COLUMN "lastChangedOn" timestamp(3);

CREATE FUNCTION deedpoll_mark_case_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.case_id IS NULL OR NEW.action IN ('CASE_VIEWED', 'DOCUMENT_DOWNLOADED', 'DOCUMENT_PREVIEWED') THEN
        RETURN NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM deedpoll_deletion_request WHERE case_id = NEW.case_id) THEN
        RETURN NULL;
    END IF;
    UPDATE deedpoll_deedpoll
       SET "lastChangedOn" = greatest(clock_timestamp() AT TIME ZONE 'UTC',
                                      "lastChangedOn" + interval '1 millisecond',
                                      "modifiedOn" + interval '1 millisecond')
     WHERE id = NEW.case_id;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER deedpoll_case_changed AFTER INSERT ON deedpoll_audit_event
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_mark_case_changed();

-- Existing cases start where CaseLastChanged's previous rule left them, so a confirmation read before this version
-- still matches. A case with a deletion request is left alone, as above.
UPDATE deedpoll_deedpoll d
   SET "lastChangedOn" = e.newest
  FROM (SELECT case_id, max(occurred_at) AS newest FROM deedpoll_audit_event
         WHERE case_id IS NOT NULL AND action NOT IN ('CASE_VIEWED', 'DOCUMENT_DOWNLOADED', 'DOCUMENT_PREVIEWED')
         GROUP BY case_id) e
 WHERE e.case_id = d.id
   AND NOT EXISTS (SELECT 1 FROM deedpoll_deletion_request r WHERE r.case_id = d.id);

-- 2. A removed document leaves a tombstone until its case is deleted.
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

-- Rolling back the application below this version: older code never writes "lastChangedOn" or the tombstones and
-- ignores both, and its CaseLastChanged reads only "modifiedOn" and the events, so the trigger and table may stay.
-- Documents removed while older code runs leave no tombstone.
