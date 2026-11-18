-- Record that the dm-store team has purged every soft-deleted CDAM document of one application deletion request, and
-- requeue it. docs/APPLICATION-DELETION.md, "Removed CDAM documents". DeletionCleanupTest.confirmCdamPurge runs this
-- file verbatim.
--
-- Only after the dm-store team has confirmed, in writing, that each CDAM id listed by
--   SELECT locator FROM deedpoll_deletion_step
--    WHERE request_id = :'request_id'::uuid AND kind = 'CDAM_REMOVED_DOCUMENT' AND state = 'Pending';
-- is permanently gone. This service never purges them itself (it never sends CDAM permanent=true).
--
-- psql:  \set request_id '<the request id from GET /caseworker/application-deletions/{requestId}>'
--        \i docs/deletion/confirm-cdam-purge.sql
-- Expect requests_requeued = 1 and steps_confirmed = the number of ids listed. Zero means the request is not
-- NeedsAttention with CDAM_REMOVED_DOCUMENT_UNPURGED: stop.
--
-- One statement, so the lines and the request change together or not at all. It touches no admission field (V48's
-- trigger would refuse it) and cannot reopen a Completed request. next_attempt_at = requested_at is in the past on the
-- worker's own (UTC) clock, so the next run claims it and finalises.
WITH requeued AS (
    UPDATE deedpoll_deletion_request
       SET status = 'InProgress', failure = NULL, next_attempt_at = requested_at
     WHERE id = :'request_id'::uuid
       AND status = 'NeedsAttention'
       AND owner IS NULL
       AND failure = 'CDAM_REMOVED_DOCUMENT_UNPURGED'
    RETURNING id),
confirmed AS (
    UPDATE deedpoll_deletion_step s
       SET state = 'Done', outcome = 'PURGED_BY_DM_STORE', completed_at = (now() AT TIME ZONE 'UTC')
      FROM requeued
     WHERE s.request_id = requeued.id AND s.kind = 'CDAM_REMOVED_DOCUMENT' AND s.state = 'Pending'
    RETURNING s.id)
SELECT (SELECT count(*) FROM requeued) AS requests_requeued, (SELECT count(*) FROM confirmed) AS steps_confirmed;
