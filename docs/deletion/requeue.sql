-- Requeue one NeedsAttention application deletion request, after its named cause has been resolved.
-- docs/APPLICATION-DELETION.md, "Requeueing". DeletionCleanupTest.requeue runs this file verbatim.
--
-- psql:  \set request_id '<the request id from GET /caseworker/application-deletions/{requestId}>'
--        \i docs/deletion/requeue.sql
-- Expect requests_requeued = 1. Zero means the request is not NeedsAttention with a requeueable code: stop.
--
-- Requeueable codes only. LOCAL_DOCUMENT_INVALID_KEY, CCD_CASE_MISSING, CDAM_WITHOUT_CCD_CASE, CCD_COPY_PRESENT,
-- UNLISTED_OWNED_STAGE and CLEANUP_STEP_FAILED are escalations, never requeued by this statement;
-- CDAM_REMOVED_DOCUMENT_UNPURGED is resumed only by docs/deletion/confirm-cdam-purge.sql.
-- One statement, so the request and its document tries change together or not at all. It touches no admission field
-- (V48's trigger would refuse it) and cannot reopen a Completed request. next_attempt_at = requested_at is in the past
-- on the worker's own (UTC) clock, so the next run claims it, but never before the request's restorable_until: the
-- worker's claim checks that too, so a requeue cannot end the grace period early.
WITH requeued AS (
    UPDATE deedpoll_deletion_request
       SET status = 'InProgress', failure = NULL, next_attempt_at = requested_at
     WHERE id = :'request_id'::uuid
       AND status = 'NeedsAttention'
       AND owner IS NULL
       AND failure IN ('LOCAL_STORE_UNAVAILABLE', 'LOCAL_DOCUMENT_UNREMOVED', 'RETAINED_PAYLOAD',
                       'LOCAL_FINALISATION_REFUSED')
    RETURNING id),
reset AS (
    UPDATE deedpoll_deletion_step s
       SET attempts = 0
      FROM requeued
     WHERE s.request_id = requeued.id AND s.kind = 'LOCAL_DOCUMENT' AND s.state = 'Pending'
    RETURNING s.id)
SELECT (SELECT count(*) FROM requeued) AS requests_requeued, (SELECT count(*) FROM reset) AS steps_reset;
