-- Record that the dm-store team has purged the soft-deleted CDAM documents of one application deletion request, and
-- requeue it. docs/APPLICATION-DELETION.md, "Removed CDAM documents". DeletionCleanupTest runs this file verbatim.
--
-- Only after the dm-store team has confirmed, in writing, that each CDAM id listed by
--   SELECT locator FROM deedpoll_deletion_step
--    WHERE request_id = '<request id>'::uuid AND kind = 'CDAM_REMOVED_DOCUMENT' AND state = 'Pending';
-- is permanently gone. This service never purges them itself (it never sends CDAM permanent=true).
--
-- psql:  \set request_id '<the request id from GET /caseworker/application-deletions/{requestId}>'
--        \set cdam_ids '<every CDAM id the dm-store team confirmed purged, comma-separated>'
--        \set operator '<your name, as the audit trail should attribute this>'
--        \i docs/deletion/confirm-cdam-purge.sql
--
-- It writes only if the ids listed are exactly the request's Pending CDAM_REMOVED_DOCUMENT ids (order, spaces and
-- repeats aside), the request is NeedsAttention with CDAM_REMOVED_DOCUMENT_UNPURGED and unclaimed, and the operator
-- is named. Then expect requests_requeued = 1, steps_confirmed = the number of ids and audit_events = 1. Otherwise it
-- writes nothing and answers 0, 0, 0: an id missing, an extra id, a second run, or a request in another state. Stop
-- and compare the list again; never edit the ids to make it match.
--
-- One statement, so the lines, the request and the audit event change together or not at all. It touches no admission
-- field (V48's trigger would refuse it) and cannot reopen a Completed request. next_attempt_at = requested_at is in
-- the past on the worker's own (UTC) clock, so the next run claims it and finalises. The audit event
-- (DELETION_CDAM_PURGE_CONFIRMED) carries the request id and field names only, never a CDAM id; it has no actor id,
-- because the operator is not a caller this service verified.
WITH listed AS (
    SELECT DISTINCT btrim(id) AS locator
      FROM unnest(string_to_array(:'cdam_ids', ',')) AS id
     WHERE btrim(id) <> ''),
target AS (
    SELECT r.id, r.case_id
      FROM deedpoll_deletion_request r
     WHERE r.id = :'request_id'::uuid
       AND r.status = 'NeedsAttention'
       AND r.owner IS NULL
       AND r.failure = 'CDAM_REMOVED_DOCUMENT_UNPURGED'
       AND btrim(:'operator') <> ''),
pending AS (
    SELECT s.locator
      FROM deedpoll_deletion_step s JOIN target ON s.request_id = target.id
     WHERE s.kind = 'CDAM_REMOVED_DOCUMENT' AND s.state = 'Pending'),
agreed AS (
    SELECT target.id, target.case_id
      FROM target
     WHERE EXISTS (SELECT 1 FROM pending)
       AND NOT EXISTS (SELECT locator FROM listed EXCEPT SELECT locator FROM pending)
       AND NOT EXISTS (SELECT locator FROM pending EXCEPT SELECT locator FROM listed)),
requeued AS (
    UPDATE deedpoll_deletion_request r
       SET status = 'InProgress', failure = NULL, next_attempt_at = requested_at
      FROM agreed
     WHERE r.id = agreed.id
       AND r.status = 'NeedsAttention'
       AND r.owner IS NULL
       AND r.failure = 'CDAM_REMOVED_DOCUMENT_UNPURGED'
    RETURNING r.id, r.case_id),
confirmed AS (
    UPDATE deedpoll_deletion_step s
       SET state = 'Done', outcome = 'PURGED_BY_DM_STORE', completed_at = (now() AT TIME ZONE 'UTC')
      FROM requeued
     WHERE s.request_id = requeued.id AND s.kind = 'CDAM_REMOVED_DOCUMENT' AND s.state = 'Pending'
       AND s.locator IN (SELECT locator FROM listed)
    RETURNING s.id),
audited AS (
    INSERT INTO deedpoll_audit_event
        (occurred_at, actor_id, actor_name, action, entity_type, entity_id, case_id, fields, detail)
    SELECT (now() AT TIME ZONE 'UTC'), NULL, btrim(:'operator'), 'DELETION_CDAM_PURGE_CONFIRMED', 'DeedPoll',
           requeued.case_id, requeued.case_id, ARRAY['status', 'failure', 'steps'],
           jsonb_build_object('deletionRequestId', requeued.id::text)
      FROM requeued
    RETURNING id)
SELECT (SELECT count(*) FROM requeued) AS requests_requeued, (SELECT count(*) FROM confirmed) AS steps_confirmed,
       (SELECT count(*) FROM audited) AS audit_events;
