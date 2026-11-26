# Permanent document deletion: rehearsal and prod CDAM smoke

Prepared by Task 7 of [the plan](plans/20261125-permanent-document-deletion.md), 2026-11-26. **Not run.** Nothing
here has been executed against AAT or prod, and preparing it deleted nothing. The contract and runbook are
[DOCUMENT-DELETION.md](DOCUMENT-DELETION.md). Use disposable, synthetic applications and harmless files only;
never a real applicant's application. Record the deployed API commit, the Admin commit, the date, the environment,
request ids and observed status codes. Keep tokens, the CDAM document id, storage keys and the reason text out of
any shared record.

## Preconditions (both environments)

1. This API is deployed with V51 applied (`/health/readiness` names no missing table) and Admin exposes the
   deletion controls; otherwise call the API through the normal staff client.
2. `DOCUMENT_DELETION_RECOVERY_ENABLED` is unset or true, so an interrupted request is finished by the sweep.
3. A staff user (caseworker for AAT; a Delivery Manager for prod, who can also delete the application afterwards)
   and a read-only judicial user for the refusal check.
4. Read-only database access for the checks below, through the environment's normal break-glass route.

## A. AAT, filesystem store

AAT runs `DOCUMENT_STORE=filesystem` (`DocumentStoreEnvironmentGuard` allows it there), so this proves the HTTP
contract, the fence, the note and the audit, and the verified file removal, but **not** the CDAM path.

1. Create a disposable application (`POST /caseworker/cases`). Upload a small PDF
   (`POST /caseworker/cases/{id}/documents`), and a second one as a sibling. Note both document ids and the first's
   `documentVersion` from `GET /caseworker/cases/{id}`. Read the first's `"storageKey"` from
   `deedpoll_supportingdocument` (read-only) and keep it in the restricted record only: a completed deletion clears
   its locators and the audit never holds one.
2. As the judicial user, `POST …/documents/{documentId}/deletion-requests`: expect 403. Without a token: 401.
3. As staff, send the POST with a fresh UUID `Idempotency-Key` and the body
   `{"reason": "Rehearsal: uploaded in error", "expectedDocumentVersion": <version - 1 if > 1, else 999>}`: expect
   409 `DOCUMENT_CHANGED`, and the document still listed and downloadable.
4. Send it again with a new key and the right version: expect 200 `{requestId, status: "Completed"}` within a few
   seconds (record latency; it must be well under 20 s).
5. Replay step 4 exactly (same key and body): expect 200 with the same `requestId`. The same key with another reason:
   409 `KEY_REUSED`.
6. `GET /caseworker/cases/{id}/document-deletions/{requestId}`: `Completed` with `completedAt`, and no reason, name or
   locator. `GET /caseworker/cases/{id}`: the document is gone, the sibling is still listed and downloads.
7. `GET …/documents/{documentId}/file` and `/preview` for the deleted id: 404.
8. Case notes: exactly one `Document <name> (<id>) permanently deleted. Reason: Rehearsal: uploaded in error` by the
   staff user. Database, read-only:
   ```sql
   SELECT action, actor_name, occurred_at, detail ? 'documentDeletionReason' AS has_reason
     FROM deedpoll_audit_event
    WHERE case_id = :case_id AND action IN ('DOCUMENT_DELETION_REQUESTED', 'DOCUMENT_DELETED', 'NOTE_CREATED')
    ORDER BY occurred_at;
   ```
   Expect one `DOCUMENT_DELETION_REQUESTED`, one `DOCUMENT_DELETED` and one `NOTE_CREATED` for this deletion, all
   under the staff user, both deletion events with `has_reason` true.
9. On the pod, confirm the file under `DOCUMENT_STORE_ROOT` at the storage key read in step 1 is absent.
10. **In use (optional).** Only if the environment can hold a forms email `Pending` (Notify reachable but the drain
    not yet run) for a generated deed: try to delete that deed and expect 409 `DOCUMENT_IN_USE` with Admin showing
    "This document is currently being used and cannot be deleted. Try again once the operation has finished." If no
    reliable way exists, record it as not exercised; `DocumentDeletionServiceTest` covers it.
11. Delete the disposable application with application deletion (`docs/APPLICATION-DELETION.md`), or leave it for
    AAT's own clean-up if that is the agreed practice.

## B. Prod, CDAM (the prod smoke)

AAT cannot prove the CDAM path, so the permanent CDAM delete is proved once in prod, on a disposable application.

Before starting, check the **activation blockers** in DOCUMENT-DELETION.md: the deployed CDAM's `service_config.json`
grants `deed_poll_api` `UPDATE` on `DEEDPOLL` (it is on CDAM's `origin/master` `da4723e`; check the deployed image),
and the API runs `DOCUMENT_STORE=cdam` with the confirmed CCD identifiers. dm-store has no further gate. Agree the
window with the dm-store team, who can confirm `hardDeleted` on their side.

1. A Delivery Manager creates a disposable test application whose names make it unmistakably a test. Wait for it to
   link to CCD (the case list's linking sweep; confirm through `/caseworker/ccd-attempts` or an authorised read).
2. Upload a harmless test PDF. Confirm it is `Attached` and downloads byte for byte. Read its `"cdamDocumentId"`
   from `deedpoll_supportingdocument` (read-only) into the restricted record now: completion clears the locators and
   the audit never holds one.
3. Delete it with a reason, for example "Prod smoke: test document". Expect 200 `Completed`. If 202, poll the status
   resource; `InProgress` should become `Completed` within a few sweep intervals. Stop at `NeedsAttention` and follow
   the runbook; do not retry by hand.
4. Confirm, as in A.6–A.8: the document is gone from the list, the case note is present once under the Delivery
   Manager, and the audit has one `DOCUMENT_DELETION_REQUESTED` and one `DOCUMENT_DELETED`.
5. Confirm with the dm-store team, on a restricted channel, that the document id read in step 2 is `hardDeleted`, and that a CDAM `GET` for it is 404. Record CCD's current
   `supportingDocuments` for the case: empty.
6. Delete the disposable application through application deletion. Its inventory must list no
   `CDAM_REMOVED_DOCUMENT` line for the deleted document (a permanent receipt is not a soft removal).

## Record

| Item | AAT | Prod |
|---|---|---|
| API / Admin commits | | |
| POST latency and status | | |
| Replay and `KEY_REUSED` | | n/a |
| Note and audit rows | | |
| File absent / dm-store `hardDeleted` and CDAM 404 | | |
| `DOCUMENT_IN_USE` | | n/a |
| Application deleted afterwards | | |

A failure is a finding: keep the controls hidden in Admin, leave the request as it stands for investigation and
follow the runbook. Do not loosen a check to make the smoke pass.
