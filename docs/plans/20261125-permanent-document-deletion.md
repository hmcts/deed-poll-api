# Plan: permanent document deletion with a reason, audit and case note — API

## Overview

Status: planned, 2026-11-25. Planning only; implementation and live deletion have not started.

Steve confirmed the outcome in this conversation:

1. Anyone already authorised to access an application's document can delete it; no Delivery Manager restriction.
2. Document deletion is permanent.
3. The user must enter a reason in their own words, for example "Uploaded in error".
4. Record the deletion in the audit table **and as a case note**, identifying the document, actor, time and reason.
5. Refuse deletion while the document is actively in use and explain that refusal in user-friendly language.
6. Show a confirmation page identifying the document and warning that deletion cannot be undone.

This plan implements API ownership and cleanup. The companion is
`deed-poll-admin:docs/plans/20261125-permanent-document-deletion.md`. Run each plan in its own repository/worktree;
API merges and deploys before Admin exposes the new controls. There is no bulk-delete action in this scope.

Whole-application deletion has already been amended and implemented. Preserve its Delivery Manager permission,
grace period, restore and Retain and Dispose flow. **This feature has no grace period or restore.** Permanent
document deletion must work while application deletion's cleanup worker is switched off.

## Context

Planning baseline: API `edb705272e4840465cf031395a3f6096a4ae3034` (master including #175/#178/#177);
Admin `43997276fde4d97ec039fe0ef802dcc66b533f69` (#422). Fetch/rebase and record execution SHAs before starting.
Read `CLAUDE.md` in full. Do not edit somebody else's checkout or copy the superseded November 18 proposal.

Read first (Java paths relative to `src/main/java/uk/gov/hmcts/reform/deedpoll/`):

- `domain/documents/{DocumentService,DocumentUploadAudit,RemoteDocumentUploads,DocumentStore}.java` and
  `storage/{CdamDocumentStore,CdamDocumentUploads,DocumentStorageStates,LocalFilesystemDocumentStore}.java`.
  `removeDocument(caseId,id)` is an internal, system-attributed, best-effort removal: V50 tombstone, local row
  delete, after-commit CCD event followed by **soft** CDAM delete. It is not the new permanent contract.
- `repository/RemovedDocumentRepository.java`; `src/main/resources/db/migration/V50__application_deletion_removed_documents.sql`, and
  `domain/deletion/{ApplicationDeletionService,DeletionCleanup,DeletionFinalisation,DeletionFence}.java`.
  Whole-application admission currently classifies every Attached removed-document tombstone as
  `CDAM_REMOVED_DOCUMENT`, requiring operator purge confirmation. New verified permanent deletions must not be
  misclassified as unresolved soft deletions. Never simply drop their cleanup evidence.
- `docs/APPLICATION-DELETION.md`, particularly "Removed CDAM documents", "The stale check's window" and
  "Grace period and restore"; both completed application-deletion/grace-period plans. The grace-period
  timestamp race accepted there is **not** evidence that an irreversible document operation has the same protection.
- `api/caseworker/controller/CaseworkerDocumentController.java`, `domain/auth/CurrentUser.java`,
  `api/judicial/controller/JudicialReferralController.java`, `domain/referrals/JudicialReferralService.java`.
  Judicial access is referral-scoped; do not expose the staff case surface to judges to implement "anyone".
- `domain/notes/{NoteService,NoteAuthor,CreateNoteInput}.java`, `domain/audit/{AuditTrail,AuditEntry,AuditAction}.java`,
  `api/WriteEndpointAuditCoverageTest.java` in the mirrored test package. Audit detail's existing `deletionNote`
  uses application-specific validation. Do not silently weaken it to accept a document reason.
- `notify/{NotificationDeliveryTransactions,NotificationClaim}.java`, document readers/preview response cleanup,
  `domain/deeds/`, document staging/promotion/recovery, `repository/DeletionAdmissionRepository.java` and the
  Gazette ledger. Trace consumers that copy bytes as well as consumers that retain document IDs/URLs.
- Existing tests `storage/CdamDocumentScenariosTest`, `storage/CdamDocumentUploadsTest`, document preview tests,
  application deletion concurrency/finalisation tests and `src/integrationTest/.../openapi/OpenAPIPublisherTest.java`.

Other-service evidence already investigated (local CFT clones; verify pinned contracts at execution): FinRem's
`EvidenceManagementDeleteService` passes `false`, nfdiv's `DocumentRemovalService` passes `true` for normal
uploaded/generated documents, and Civil's `SecuredDocumentManagementService.deleteDocument` passes `true`.
Use CDAM's client with `permanent=true`; do not call DM Store directly from this service. The CDAM endpoint checks
service UPDATE permission for the case type/jurisdiction. Our API must enforce the human's authorisation.
DM Store's hard delete removes binary versions but retains metadata marked deleted/hardDeleted: no promise that
this operation erases platform audit/metadata, delivered emails, backups or every historical CCD event snapshot.

## Development Approach

### Access, document scope and reason

Implement these requirements; do not reintroduce the earlier Delivery Manager-only capability or fixed reasons.

- Every existing staff role admitted to ordinary casework can delete a document of an application it may access.
  A judicial caller who may access a document through their referral can do the same through that referral's scope.
  Anonymous, citizen-only, unrelated judicial and service-only identities do not gain a new permission. Re-evaluate
  access on confirmation, admission, replay and status; possession of an ID or old signed page is not permission.
- No new `DELETE_DOCUMENT` privileged capability and no ownership-by-uploader restriction. Include uploaded,
  scanned and generated supporting-document rows already exposed by the document lists. Do not silently limit
  this to uploaded documents; do not expose documents the existing access rules hide.
- Mandatory **free-text** `reason`, not a dropdown. Implementation default: trim outer whitespace, require 1–500
  Unicode code points, reject malformed Unicode and control characters; no minimum word count. "Duplicate" is
  valid. The textarea hint says to explain why and avoid unnecessary applicant personal details. Validate in API,
  mirror the published bounds in Admin, preserve accepted text exactly and never log it or echo it in errors.
- A document without a binary still has a deletable metadata row. Refuse unresolved upload/attach/promotion or
  storage-uncertain states until reconciled rather than discarding bytes whose ownership is unknown.

### Proposed operation contract

Publish and test this contract in API before the companion consumes it. Paths/shapes below are a design, not an
assertion that they exist today; align naming with current request resources during Task 1 and record any delta.

| Operation | Behaviour |
|---|---|
| `POST /caseworker/cases/{id}/documents/{documentId}/deletion-requests` | Closed JSON `{ reason, expectedDocumentVersion }`; required UUID `Idempotency-Key`; 202 `{ requestId, status }` and Location |
| `GET /caseworker/cases/{id}/document-deletions/{requestId}` | Read only, authorised and case-scoped; Pending/InProgress/NeedsAttention/Completed plus typed outcome/failure, timestamps and document ID; no storage locator or free-text reason |
| Referral-scoped admission/status equivalents under `/judicial/referrals/{referralId}/...` | Same deletion service and request, after the existing referral visibility check; do not disclose the case ID or case notes |

Use typed 409 codes, including `DOCUMENT_IN_USE`, `DOCUMENT_CHANGED`, `DOCUMENT_STORAGE_UNRESOLVED`,
`DOCUMENT_SHARED`, `APPLICATION_DELETION_PENDING`, `KEY_REUSED`; 400 reason/version/key errors; existing 401/403/404
conventions and bounded JSON requests. Never distinguish inaccessible and absent documents more widely than today.
Status resources remain available after the document row disappears, provided the caller still has parent access.
Whole-application deletion may later remove its document status resource; 404 then is not proof of document completion.

Mint the idempotency key when the confirmation renders. Persist a unique request per document with a fingerprint
of the target, reason and reviewed version. Replay looks up the admitted request before requiring the live document;
same key/body returns the original request after deletion, changed body is 409, another key cannot create a second
completion/note or replace the original reason/actor. Different users/tabs must not overwrite that provenance.
Timeout, 5xx or unreadable admission response is an unknown outcome, recoverable by the same request/key only.

### Active use and stale confirmation

"In use" means an in-flight server operation, a queued operation already bound to those bytes, or an uncertain
external operation that may still read them. Opening an idle browser tab must not lock a document forever. A browser
that already downloaded bytes cannot have them recalled; say nothing suggesting otherwise.

Inventory actual use paths: preview/conversion, binary streaming, Notify document delivery, deed generation or
storage recovery where they reference the target, uploads/promotions, CCD document synchronisation and Gazette
work if it consumes the same bytes. Do not block unrelated application work by guessing every job uses every document.

Use a durable per-document coordination point shared across pods, with bounded leases where necessary and a
document deletion fence. Acquiring use and admitting deletion must be mutually exclusive under one documented lock
order. Existing users hold authority until streaming/conversion/remote use truly ends, not merely until HTTP headers
are sent. Lease expiry alone does not prove an uncertain Notify operation is over. A new use after admission refuses.
Do not hold a database transaction open around streaming or external calls. Check row-lock and lease ordering against
the application-deletion code; its previous case-row audit trigger deadlocked and must not be reintroduced.

Admission checks a document-specific monotonic version/content identity published in the list/projection for the
confirmation, not the application's grace-period `lastChangedOn` timestamp. Increment it atomically for metadata,
binary and storage-identity changes that affect the reviewed item. Concurrent use/replacement wins or deletion wins;
both cannot commit permission. A refusal creates no deletion request, completion audit or case note.

For `DOCUMENT_IN_USE`, Admin displays exactly:
"This document is currently being used and cannot be deleted. Try again once the operation has finished."
API provides a stable code, never a provider exception or guessed duration.

### Cleanup, audit and case note

1. Admission locks/checks parent and document, verifies version, active-use/ref-sharing and whole-application fence,
   and persists a durable request plus cleanup identifiers/fence. Record a request audit under the verified actor.
   The document becomes unavailable for new reads/uses. The UI must call this pending, not deleted.
2. A dedicated bounded recoverable worker removes the document from the **current** case/CCD collection, permanently
   deletes its owned binary versions through CDAM (`true`) or the correct filesystem store, and verifies each result
   under the pinned contract. The document list comes from fresh authoritative rows excluding deletion-fenced rows;
   two concurrent document removals must not reattach one another using an old remaining-documents snapshot.
3. Complete only after all required external steps are confirmed. In one local transaction delete remaining local
   document data, reconcile V50 evidence, insert one `DOCUMENT_DELETED` event and create one case note under the
   **original requesting user**, then mark Completed. Reason and document ID survive in the event and case note.
   A suggested note: `Document [display name] ([document ID]) permanently deleted. Reason: [reason]`.
   Use existing NoteService attribution/time and audit its NOTE_CREATED normally. Notes/rendering escape all text.
4. Extend audit detail with a separately typed document-deletion reason/request ID, or an equally constrained
   documented representation; do not relax application's enum/minimum-length rules or store arbitrary JSON.
   Keep the reason in audit event detail so removal of audit value rows during later application disposal cannot
   erase why. Case-note deletion/edit policies remain as today; the audit is the durable original record.
5. A note/audit insert failure rolls back local completion, leaving durable work to resume after already-finished
   remote steps. Request creation, audit and note must not be best effort. Exactly one completion/note across
   retries, worker races, crashes and lost responses. A request-audit or NOTE_CREATED event is not a duplicate
   DOCUMENT_DELETED event. No network call is part of the final DB transaction.
6. New requests use the permanent cleanup path; ordinary internal `removeDocument` must not accidentally soft-delete
   the binary first. Remove/bound any bypass and retain needed internal compatibility without exposing an unguarded
   overload. Never discard locators until permanent deletion is proven. Shared binary references are a typed refusal
   until ownership can be resolved; do not delete another document's content or offer success after unlinking only one reference.
   Shared ownership means another current document record/consumer, not simply an old audit/history link: a
   historical reference does not make permanent deletion impossible or promise that its binary will remain readable.

CDAM's current metadata-before-delete check may make a replay after soft/hard deletion 404 before forwarding it to
DM Store. A generic inaccessible GET 404 is not a permanent-purge receipt. Task 1 must establish the exact successful
response/replay verification contract and any supported purge route for already-soft-deleted files. If that outcome
is ambiguous, retain NeedsAttention and its evidence; do not pretend soft delete or a blind second 404 proved purge.
Historical V50 soft-deleted documents are not retroactively bulk-purged by this feature.

### Integration with whole-application deletion

- Existing application fences still refuse all new document writes/deletions. Application admission must atomically
  refuse a pending/uncertain document cleanup using its existing unresolved-storage refusal or a published new code.
  Stop its worker from cascading a live document request/lease/evidence away. Do not share its grace-period setting.
- Once document cleanup completes, application admission must recognise the verified permanently-deleted document
  and not send it to the existing manual `CDAM_REMOVED_DOCUMENT_UNPURGED` branch. Preserve the old conservative
  handling for historical soft removals. Migrate/add evidence with the next free Flyway number; no edits to V48–V50.
- Finalisation of the application disposes document request reason copies/locators with the case where appropriate,
  preserving audit events and the existing application tombstone policy. An application restored during its own grace
  period never resurrects a document already permanently deleted before that application request.

## Validation Commands

Baseline and final gate from this repository root: `./gradlew build --no-watch-fs` (force test/integration execution
at the final gate). After each task run its focused test classes, compile affected source sets, applicable Checkstyle
tasks and `git diff --check`. New suite names are proposals until introduced; do not use an empty selector as proof.

```bash
./gradlew test --tests '*DocumentDeletion*' --no-watch-fs
./gradlew compileJava compileTestJava compileIntegrationTestJava checkstyleMain checkstyleTest --no-watch-fs
git diff --check
```

Use Postgres/Testcontainers for locks, constraints, fence/finalisation and exactly-once tests; stub all remote work.
Run commands in the foreground and record actual counts, failures and skips. Follow the repository's documented
JVM/direct-Checkstyle workaround if required, running the equivalent check, not copying historic `/tmp` scripts.
Generate the full OpenAPI artifact using OpenAPIPublisherTest and OPENAPI_OUTPUT_PATH after contract tests pass.

## Implementation Steps

### Task 1: Record the source baseline and concrete ownership/use/deletion contracts

**Read:** all Context paths; pinned CDAM/client/DM Store code; both completed application deletion plans and tests.

- [ ] Record execution SHAs and baseline gates; write `docs/DOCUMENT-DELETION.md` with every reader, queued consumer,
  reference owner and writer of a document, including preview streaming lifetime and already-copied provider bytes.
- [ ] Specify shared lock order, the version token, role/access matrix (including referral-scoped judiciary), binary
  ownership, permanent CDAM success/replay/404 semantics and native-SQL/JPA fence points against actual code.
- [ ] Add/locate a stubbed proof of permanent CDAM deletion and safe retry. Document external permissions/modes and
  unsupported outcomes as activation blockers. Do not replace permanent deletion with soft deletion or a guessed API.

### Task 2: Add request, reason, version, use and audit persistence

**Files:** new `domain/documents/deletion/` types/repository, next free Flyway migration, document entity/projections,
`health/DeedPollSchema.java`, `domain/audit/AuditEntry.java`, audit vocabulary and migration-order tests.

- [ ] Implement durable request/step and use coordination, version, uniqueness, actor/reason/fingerprint and redacted
  toString; fence reads without losing recovery access. Keep request/cleanup inventory until confirmed completion.
- [ ] Implement bounded plain-text reason validation and typed document-specific audit metadata. Preserve the
  existing application's reason/note checks. Define minimal completed receipt retention and case-disposal ownership.
- [ ] Test schema constraints, rejected reason forms/Unicode, version changes, redaction, migration compatibility
  and uniqueness using real Postgres. Include the schema/SDK migration-order health gates.

### Task 3: Coordinate active readers/writers and authorised admission

**Files:** new deletion service, DocumentService/storage states/preview readers, notification/deed/Gazette consumers
identified in Task 1, JudicialReferralService and document/referral tests.

- [ ] Enforce ordinary staff access and existing judicial visibility, parent/document ownership and deletion fences
  in the domain; implement admission/replay/version/body conflicts. No privileged DELETE_DOCUMENT grant.
- [ ] Wire durable use coordination at actual consumption/claim boundaries, release on success/error/disconnect,
  and prevent expiry granting deletion while uncertain use continues. Block unresolved promotion/storage.
- [ ] Prove races deterministically across two transactions/workers: delete versus stream, preview, queued/send
  claim, upload/replace/attach and new read; no external operation starts on a fenced document. Another document on
  the same application remains usable. Rejected active use causes no side effects and returns DOCUMENT_IN_USE.

### Task 4: Permanently clean up, recover and atomically record completion

**Files:** new document deletion worker/step/finalisation, CdamDocumentStore/permanent seam, CCD document projection,
NoteService/DocumentUploadAudit adapters, scheduler/config and their tests.

- [ ] Implement permanent CDAM and verified filesystem cleanup, metadata-only deletion, current CCD reference
  removal, and bounded multi-pod recovery with owned leases. No best-effort success and no new Restore action.
- [ ] Implement atomic local completion with correctly attributed DOCUMENT_DELETED and one case note containing
  document identity and the entered reason. Audit/note failures leave a recoverable incomplete request.
- [ ] Test 204 success, all ambiguous replay/404 cases, 401/403/outage/5xx, crashes after remote success, lease loss,
  duplicate worker finalisation, forced note/audit failure, and concurrent document removals not reattaching each other.
- [ ] Add configuration/health/monitoring and chart tests/version bump if values change. Keep activation explicit;
  a disabled worker leaves Pending, never a false Completed. Do not tie it to application's cleanup-enabled flag.

### Task 5: Integrate removed-document evidence and application deletion

**Files:** RemovedDocumentRepository, application admission/inventory/finalisation, request/step repositories,
new migrations as needed and existing application deletion/grace/restore/concurrency tests.

- [ ] Refuse application admission while a document request/use outcome is unresolved under the shared lock
  order; prevent cascades or restore from invalidating work and never resurrect a permanently deleted document.
- [ ] Recognise new verified permanent receipts without weakening the existing historical soft-delete purge
  refusal. Retain cleanup evidence and remove unnecessary locators only when safe.
- [ ] Test complete/partial/unknown document deletion then application delete, historical soft-removal tombstones,
  application fence/restore, mixed filesystem/CDAM history, and both operations racing. Grace-period rules remain intact.

### Task 6: Publish staff and judicial HTTP operations

**Files:** caseworker document controller/DTOs, judicial referral controller/DTOs, status services,
WriteEndpointAuditCoverageTest, security/OpenAPI tests and publisher test.

- [ ] Implement the proposed scoped admission/status routes, bounded closed JSON/key/version validation, typed
  conflicts, no-store headers and audit classification; do not leak case IDs/reasons/locators into judicial status.
- [ ] Test all permitted staff roles, authorised judiciary, unauthenticated/citizen/service-only/unrelated judge,
  cross-case/referral guesses, lost-response replay after row removal and access revocation. Scope/auth precedes replay.
- [ ] Publish the complete spec and record SHA/hash/full delta, including documentVersion in staff/judicial
  document projections. Ensure no note-author/audit identity is accepted from the client.

### Task 7: Complete regression evidence, runbook and Admin handoff

**Files:** changed sources/tests, README.md, CLAUDE.md, docs/DOCUMENT-DELETION.md, application deletion guidance, plan.

- [ ] Run forced full build/integration/Checkstyle and changed-code coverage; record actual commands/results and
  failure/race tests. Preserve current audit and Sonar gates; do not equate skipped checks with passed checks.
- [ ] Document access, permanent outcome, reason/audit/note retention, active-use meaning and user wording,
  statuses, retry/investigation, metadata/backups limits, historical soft-removal handling and application interaction.
- [ ] Prepare disposable-fixture rehearsal steps for both storage modes, alert/owner handoff, API-before-Admin
  deployment and activation prerequisites. Prepare artifacts only; no live deletion/deployment in this plan run.

## Success criteria

An authorised user can confirm permanent deletion with a mandatory reason. The original identity/time/reason and
document are recorded in the audit event and case note exactly once. In-use documents receive a typed, friendly
refusal without changes; no new reader races admitted deletion. Completed proves the owned binary and current
application/CCD references are removed, not merely hidden. Unknown/partial outcomes remain recoverable. Other
documents, application restore rules and historical soft-delete evidence remain correct.

## Execution notes

Not started. This plan has been checked structurally, not executed. Record source SHAs, contract proof, migrations,
task validation, spec artifact, review findings and outstanding deployment evidence here. Keep checkboxes inside tasks.

## Post-Completion

External rehearsal/activation evidence is distinct from local completion. Do not mark an unavailable prerequisite
verified or erase real data as part of preparing the plan. Move to completed only after implementation and review.
