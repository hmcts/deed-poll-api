-- The deletion fence, enforced by the database (plan 20261118-dm-application-deletion, Task 4).
--
-- Once a Delivery Manager's deletion request (V48) names a case, nothing may write to that case or its children
-- again: no patch, note, witness, document, referral, email, CCD creation attempt, deed preparation, owned stage or
-- Gazette membership. The application refuses each of those first, in its own words (DeletionFence, and the
-- predicates its native statements carry); these triggers are the backstop for a write that read the case before the
-- request committed, and for any writer nobody taught.
--
-- Why deferred, and why the lock. Admission holds the case row FOR UPDATE until it commits. A writer that checks for
-- a request in the statement that writes takes its snapshot before it waits for that lock, so it would see no request
-- and write anyway. These are constraint triggers deferred to commit, and the function takes the case row FOR KEY
-- SHARE before it looks, in a statement of its own and so on a fresh snapshot (READ COMMITTED, which every writer
-- here uses). Either admission committed before this transaction held its lock, and the request is seen and the write
-- refused, or admission started later and waits for this one to commit, and then reads what it wrote.
--
-- DELETE is never refused: removing the case and its rows is what the deletion itself does. Notifications are fenced
-- on INSERT only, so an outcome the drain already owed can still settle; their claims and send-starts are fenced in
-- the application (NotificationDeliveryTransactions), under the same lock. Nothing here touches the deletion's own
-- tables, the audit trail or the SDK's ccd schema.

CREATE FUNCTION deedpoll_refuse_fenced_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    target text;
BEGIN
    IF TG_TABLE_NAME = 'deedpoll_ownedstage' THEN
        target := coalesce(NEW."caseId", CASE WHEN NEW."ownerKind" = 'RECORD' THEN NEW."ownerId" END);
    ELSE
        target := to_jsonb(NEW) ->> TG_ARGV[0];
    END IF;
    IF target IS NULL THEN
        RETURN NULL;
    END IF;
    PERFORM 1 FROM deedpoll_deedpoll WHERE id = target FOR KEY SHARE;
    IF EXISTS (SELECT 1 FROM deedpoll_deletion_request WHERE case_id = target) THEN
        RAISE EXCEPTION 'The application is being deleted' USING ERRCODE = 'P0001', HINT = 'deedpoll_deletion_fence';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER deedpoll_deletion_fence AFTER UPDATE ON deedpoll_deedpoll
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_write('id');
CREATE CONSTRAINT TRIGGER deedpoll_deletion_fence AFTER INSERT OR UPDATE ON deedpoll_notes
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_write('deedPollId');
CREATE CONSTRAINT TRIGGER deedpoll_deletion_fence AFTER INSERT OR UPDATE ON deedpoll_witness
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_write('deedPollId');
CREATE CONSTRAINT TRIGGER deedpoll_deletion_fence AFTER INSERT OR UPDATE ON deedpoll_supportingdocument
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_write('deedPollId');
CREATE CONSTRAINT TRIGGER deedpoll_deletion_fence AFTER INSERT OR UPDATE ON deedpoll_referral
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_write('deedPollId');
CREATE CONSTRAINT TRIGGER deedpoll_deletion_fence AFTER INSERT ON deedpoll_notification
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_write('deedPollId');
CREATE CONSTRAINT TRIGGER deedpoll_deletion_fence AFTER INSERT OR UPDATE ON deedpoll_ccdcreationattempt
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_write('deedPollId');
CREATE CONSTRAINT TRIGGER deedpoll_deletion_fence AFTER INSERT OR UPDATE ON deedpoll_deedpreparation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_write('deedPollId');
CREATE CONSTRAINT TRIGGER deedpoll_deletion_fence AFTER INSERT OR UPDATE ON deedpoll_ownedstage
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_write();
CREATE CONSTRAINT TRIGGER deedpoll_deletion_fence AFTER INSERT OR UPDATE ON deedpoll_gazette_notice_batch_case
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_write('deed_poll_id');
CREATE CONSTRAINT TRIGGER deedpoll_deletion_fence AFTER INSERT OR UPDATE ON deedpoll_gazette_request_case
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_write('deed_poll_id');
CREATE CONSTRAINT TRIGGER deedpoll_deletion_fence AFTER INSERT OR UPDATE ON deedpoll_gazette_reservation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_fenced_write('deed_poll_id');

-- Rolling back the application below this version: the triggers refuse only writes to a case that has a deletion
-- request, which no older code can create, so they may stay.
