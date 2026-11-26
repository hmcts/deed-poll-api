# Plan: permanent document deletion with a reason, audit and case note — API

## Overview

Status: implemented on `plan/permanent-document-deletion`, 2026-11-26 (Tasks 1-7). Not deployed; the AAT rehearsal
and the prod CDAM smoke are prepared, not run.

Steve confirmed the outcome in this conversation:

1. Office staff who can already access an application's documents (caseworkers, Team leaders and Delivery Managers)
   can delete one; no Delivery Manager restriction. **Masters are read-only** and get no deletion (Steve, 2026-11-25).
2. Document deletion is permanent.
3. The user must enter a reason in their own words, for example "Uploaded in error".
4. Record the deletion in the audit table **and as a case note**, identifying the document, actor, time and reason.
5. Refuse deletion while the document is actively in use and explain that refusal in user-friendly language.
6. Show a confirmation page identifying the document and warning that deletion cannot be undone.

**Review by Aspen, Steve's answers 2026-11-25:** (a) staff only, as above; (b) "in use" means **queued or unsettled
work** already recorded in the database, not a lease on every read; (c) deletion runs **while the user waits**, with
a durable request so a crash or timeout is recoverable, not through a switched-off background worker; (d) the CDAM
hard delete is proved by a prod smoke with a disposable application (AAT uses the filesystem store); (e) CLAUDE.md's
"Never send `permanent=true`" rule is rewritten for this one path (Task 7).

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
  `api/judicial/controller/JudicialReferralController.java` and `domain/referrals/JudicialReferralService.java`, only
  to confirm the judicial surface gains no deletion route (Masters are read-only).
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

- Every staff role admitted to `/caseworker/**` (`StaffArea.CASEWORKER`: caseworker, Team leader, Delivery Manager and
  the legacy admin role) can delete a document of an application it may access. **No judicial route**: Masters are
  read-only, and the judicial surface gains nothing. Anonymous, citizen-only, judicial-only and service-only identities
  gain no permission. Re-evaluate access on admission, replay and status; an ID or an old signed page is not permission.
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
| `POST /caseworker/cases/{id}/documents/{documentId}/deletion-requests` | Closed JSON `{ reason, expectedDocumentVersion }`; required UUID `Idempotency-Key`. Runs the deletion **in the request** (below): 200 `{ requestId, status: "Completed" }` when every step is confirmed; 202 `{ requestId, status }` (`InProgress` or `NeedsAttention`) with Location when a step is uncertain or failed and recovery will finish it |
| `GET /caseworker/cases/{id}/document-deletions/{requestId}` | Read only, authorised and case-scoped; `InProgress`/`NeedsAttention`/`Completed` plus typed failure, timestamps and document ID; no storage locator or free-text reason. Needed only after a 202 or an unknown outcome |

Use typed 409 codes, including `DOCUMENT_IN_USE`, `DOCUMENT_CHANGED`, `DOCUMENT_STORAGE_UNRESOLVED`,
`DOCUMENT_SHARED`, `APPLICATION_DELETION_PENDING`, `KEY_REUSED`; 400 reason/version/key errors; existing 401/403/404
conventions and bounded JSON requests. Never distinguish inaccessible and absent documents more widely than today.
Status resources remain available after the document row disappears, provided the caller still has parent access.
The whole request must answer inside a budget well under the 30 s Application Gateway timeout (target 20 s,
`DOCUMENT_DELETION_REQUEST_BUDGET`): every remote call carries a bounded timeout, and a step not finished within the
budget returns 202 `InProgress` for recovery rather than letting the gateway answer.
Whole-application deletion may later remove its document status resource; 404 then is not proof of document completion.

Mint the idempotency key when the confirmation renders. Persist a unique request per document with a fingerprint
of the target, reason and reviewed version. Replay looks up the admitted request before requiring the live document;
same key/body returns the original request after deletion, changed body is 409, another key cannot create a second
completion/note or replace the original reason/actor. Different users/tabs must not overwrite that provenance.
Timeout, 5xx or unreadable admission response is an unknown outcome, recoverable by the same request/key only.

### Active use and stale confirmation

"In use" means **queued or unsettled work that is already recorded in the database and will read the document's
bytes** (Steve, 2026-11-25). Task 1 inventories the actual sources against the code; the expected set is:

- an email notification in `Pending`, `Claimed` or `Sending` whose frozen personalisation carries this document's id
  (`EmailTemplate.documentKeys()`), including an uncertain send not yet settled;
- a deed preparation or owned-staging promotion that references the document and has not settled;
- the document's own unsettled storage states (`Uploaded`, `AttachUncertain`, an uncertain upload), which already
  refuse as `DOCUMENT_STORAGE_UNRESOLVED`;
- Gazette request membership or evidence that copies this document's bytes, only if Task 1 finds Gazette does so.

**No lease system and no lock around reads.** A preview, download or stream in progress does not block deletion:
once admission commits, the document is fenced, so new reads, previews, downloads and new email sends naming it are
refused; a read already streaming may finish, and a browser that already has the bytes cannot have them recalled (say
nothing suggesting otherwise). Admission checks the in-use sources **under the case and document row locks**, in the
same lock order application deletion uses (case, then the document's rows), so a new queued use either commits first
(and deletion is refused) or meets the fence. No commit-time trigger that locks the case row: one was built for
application deletion and deadlocked against Gazette settlement and the referral queue.

Admission checks a document-specific monotonic version published in the document list/projection for the
confirmation, not the application's grace-period `lastChangedOn` timestamp. Increment it atomically for metadata,
binary and storage-identity changes that affect the reviewed item (`DOCUMENT_CHANGED`). A refusal creates no deletion
request, completion audit or case note.

For `DOCUMENT_IN_USE`, Admin displays exactly:
"This document is currently being used and cannot be deleted. Try again once the operation has finished."
API provides a stable code, never a provider exception or guessed duration.

### Cleanup, audit and case note

1. **Admission** (its own short transaction, committed before any remote call): lock the case, then the document;
   check access, version, in-use, shared references and the application-deletion fence; persist a durable request
   (`InProgress`) with its cleanup identifiers and the document fence, and record a request audit under the verified
   actor. From this commit the document is unavailable for new reads and uses.
2. **Remote steps, in the same HTTP request, outside any transaction:** remove the document from the **current**
   case/CCD collection if the case is linked, then delete its owned binary permanently through CDAM
   (`permanent=true`, **never a soft delete first**: a permanent delete after a soft delete may not remove the blob,
   `docs/CDAM-RECOVERY.md`) or the filesystem store, recording each step's outcome in its own short commit. A step
   that fails or is uncertain, or the request budget running out, leaves the request `InProgress` (retryable) or
   `NeedsAttention` (needs a person) and answers 202. A **recovery sweep** (bounded, multi-pod-safe claims with owned
   leases on the request row only, **enabled by default**, independent of application deletion's cleanup flag) resumes
   `InProgress` requests whose owner went away; it starts no new deletion. The document list comes from fresh authoritative rows excluding deletion-fenced rows;
   two concurrent document removals must not reattach one another using an old remaining-documents snapshot.
3. **Finalise** in the same request when the remote steps confirmed (or in the sweep later): complete only after all
   required external steps are confirmed. In one local transaction delete remaining local
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
   retries, request/sweep races, crashes and lost responses. A request-audit or NOTE_CREATED event is not a duplicate
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

Run from this repository root, **in the foreground** (never background a Gradle command). They must be green after
every task. Gradle's Checkstyle worker crashes with SIGILL in this devcontainer, so it is disabled locally by the init
script and the same pinned Checkstyle runs directly (`checkstyle.sh`, which fails on any WARN); Jenkins runs the
normal Checkstyle tasks. If `build/config/checkstyle/checkstyle.xml` is missing in a fresh worktree, copy it from
another deed-poll-api worktree before the first run. Baseline at the plan commit: green (Aspen, 2026-11-25).

```bash
export TESTCONTAINERS_RYUK_DISABLED=true JAVA_TOOL_OPTIONS=-XX:UseSVE=0
./gradlew build -I /Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/no-checkstyle.gradle --console=plain --no-watch-fs
/Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/checkstyle.sh .
git diff --check
```

Each task also runs its own focused suites (once they exist; never an empty `--tests` selector as a gate). Use
Postgres/Testcontainers for locks, constraints, fences, finalisation and exactly-once tests; stub all remote work
(WireMock for CDAM and CCD). The final gate forces the tests (`./gradlew test --rerun integration --rerun`, each task
named immediately before its `--rerun`) and records counts from the XML in `build/test-results/{test,integration}`.
Generate the full OpenAPI artifact using OpenAPIPublisherTest and OPENAPI_OUTPUT_PATH after the contract tests pass.

## Implementation Steps

### Task 1: Record the source baseline and concrete ownership/use/deletion contracts

**Read:** all Context paths; pinned CDAM/client/DM Store code; both completed application deletion plans and tests.

- [x] Record execution SHAs and baseline gates; write `docs/DOCUMENT-DELETION.md` with every reader, queued consumer,
  reference owner and writer of a document, including preview streaming lifetime and already-copied provider bytes.
- [x] Specify shared lock order, the version token, the staff-only access matrix (no judicial route), the exact
  in-use sources found in the code, the request budget and per-call timeouts, binary
  ownership, permanent CDAM success/replay/404 semantics and native-SQL/JPA fence points against actual code.
- [x] Add/locate a stubbed proof of permanent CDAM deletion and safe retry. Document external permissions/modes and
  unsupported outcomes as activation blockers. Do not replace permanent deletion with soft deletion or a guessed API.

### Task 2: Add request, reason, version, use and audit persistence

**Files:** new `domain/documents/deletion/` types/repository, next free Flyway migration, document entity/projections,
`health/DeedPollSchema.java`, `domain/audit/AuditEntry.java`, audit vocabulary and migration-order tests.

- [x] Implement durable request/step and use coordination, version, uniqueness, actor/reason/fingerprint and redacted
  toString; fence reads without losing recovery access. Keep request/cleanup inventory until confirmed completion.
- [x] Implement bounded plain-text reason validation and typed document-specific audit metadata. Preserve the
  existing application's reason/note checks. Define minimal completed receipt retention and case-disposal ownership.
- [x] Test schema constraints, rejected reason forms/Unicode, version changes, redaction, migration compatibility
  and uniqueness using real Postgres. Include the schema/SDK migration-order health gates.

### Task 3: Authorised admission with in-use and version checks

**Files:** new deletion service, DocumentService/storage states, the notification, deed-preparation and staging
repositories identified in Task 1, and document tests.

- [x] Enforce staff access (no judicial route), parent/document ownership and deletion fences in the domain;
  implement admission, replay, version and body conflicts. No privileged DELETE_DOCUMENT grant.
- [x] Check the Task 1 in-use sources under the case and document locks; fence reads, previews, downloads and new email
  sends naming the document from admission. No leases on readers.
- [x] Prove the races deterministically across two transactions: admission versus a new email enqueue naming the
  document, versus an upload/replace/attach, and versus a read; a queued send refuses as `DOCUMENT_IN_USE` with no side
  effects; a read after admission is refused; another document on the same application stays usable.

### Task 4: Permanently clean up, recover and atomically record completion

**Files:** new document deletion steps, recovery sweep and finalisation, CdamDocumentStore/permanent seam, CCD document projection,
NoteService/DocumentUploadAudit adapters, scheduler/config and their tests.

- [x] Implement the in-request remote steps within the budget, permanent CDAM and verified filesystem cleanup,
  metadata-only deletion, current CCD reference removal, and the recovery sweep for interrupted requests. No
  best-effort success and no Restore action.
- [x] Implement atomic local completion with correctly attributed DOCUMENT_DELETED and one case note containing
  document identity and the entered reason. Audit/note failures leave a recoverable incomplete request.
- [x] Test 204 success, all ambiguous replay/404 cases, 401/403/outage/5xx, crashes after remote success, lease loss,
  duplicate finalisation by the request and the sweep, forced note/audit failure, and concurrent document removals not reattaching each other.
- [x] Add configuration, health and monitoring, and chart tests plus a version bump if values change. The recovery
  sweep is on by default (it only resumes admitted requests); a disabled sweep leaves `InProgress`, never a false
  `Completed`. Do not tie it to application deletion's cleanup flag.

### Task 5: Integrate removed-document evidence and application deletion

**Files:** RemovedDocumentRepository, application admission/inventory/finalisation, request/step repositories,
new migrations as needed and existing application deletion/grace/restore/concurrency tests.

- [x] Refuse application admission while a document request/use outcome is unresolved under the shared lock
  order; prevent cascades or restore from invalidating work and never resurrect a permanently deleted document.
- [x] Recognise new verified permanent receipts without weakening the existing historical soft-delete purge
  refusal. Retain cleanup evidence and remove unnecessary locators only when safe.
- [x] Test complete/partial/unknown document deletion then application delete, historical soft-removal tombstones,
  application fence/restore, mixed filesystem/CDAM history, and both operations racing. Grace-period rules remain intact.

### Task 6: Publish the staff HTTP operations

**Files:** caseworker document controller/DTOs, status service,
WriteEndpointAuditCoverageTest, security/OpenAPI tests and publisher test.

- [x] Implement the admission (200/202) and status routes, bounded closed JSON/key/version validation, typed
  conflicts, no-store headers and audit classification; no reason or locator in the status.
- [x] Test all permitted staff roles and refusals for a judge, unauthenticated, citizen and service-only callers,
  cross-case/referral guesses, lost-response replay after row removal and access revocation. Scope/auth precedes replay.
- [x] Publish the complete spec and record SHA/hash/full delta, including documentVersion in the staff document
  projection (the judicial projection gains nothing). Ensure no note-author/audit identity is accepted
  from the client.

### Task 7: Complete regression evidence, runbook and Admin handoff

**Files:** changed sources/tests, README.md, CLAUDE.md, docs/DOCUMENT-DELETION.md, application deletion guidance, plan.

- [x] Run forced full build/integration/Checkstyle and changed-code coverage; record actual commands/results and
  failure/race tests. Preserve current audit and Sonar gates; do not equate skipped checks with passed checks.
- [x] Document access, permanent outcome, reason/audit/note retention, active-use meaning and user wording,
  statuses, retry/investigation, metadata/backups limits, historical soft-removal handling and application interaction.
- [x] Rewrite CLAUDE.md's "Never send `permanent=true`" rule (and `docs/CDAM-RECOVERY.md`'s line): only document
  deletion sends it, directly (never after a soft delete), and only after its request is recorded, by Steve's
  decision of 2026-11-25; the internal `removeDocument`, upload clean-up and application deletion stay soft deletes.
- [x] Prepare the rehearsal: filesystem in AAT with a disposable fixture, and the **prod CDAM smoke** with a disposable
  application (a Delivery Manager creates a test application, uploads a test document, deletes it with a reason,
  confirms the note, audit and CDAM 404, then deletes the application with application deletion). CDAM already grants
  `deed_poll_api` `UPDATE` on `DEEDPOLL` (its `service_config.json`), which a permanent delete needs; dm-store has no
  further gate. Prepare artifacts only; no live deletion or deployment in this plan run. (Artifacts prepared in
  `docs/DOCUMENT-DELETION-SMOKE.md`; the rehearsal itself is not automatable here and was not run.)

## Success criteria

An authorised member of staff (not a Master) can confirm permanent deletion with a mandatory reason, and usually sees
it completed in the same request. The original identity/time/reason and
document are recorded in the audit event and case note exactly once. In-use documents receive a typed, friendly
refusal without changes; no new reader races admitted deletion. Completed proves the owned binary and current
application/CCD references are removed, not merely hidden. Unknown/partial outcomes remain recoverable. Other
documents, application restore rules and historical soft-delete evidence remain correct.

## Execution notes

Task 1 (2026-11-26): execution SHA `068e047` on `origin/master` `edb7052`; CDAM clone `fa77104` and `origin/master`
`da4723e` (the latter grants `deed_poll_api` UPDATE on `DEEDPOLL`; the clone does not), dm-store `8b01cdd1` and
`origin/master` `916193a`. Contracts in `docs/DOCUMENT-DELETION.md`; stubbed proof
`storage/CdamPermanentDeleteContractTest` (12 tests: 204 is the only receipt, 404 is never one, a lost answer is
re-sent safely). Gates: build green (test 5682/0 failed/1 skipped, integration 31/0), Checkstyle OK, diff check clean.
Recorded deltas: success is 200 with a body (Task 4's "204" read as that case); open questions for Steve on deleting a
generated deed (the V26 reservation survives it) and on a `Failed` row's unattached CDAM copy.
Task 2 (2026-11-26): V51 (`V51__document_deletion_requests.sql`) adds the version column and trigger, the request
and step tables, the immutability trigger, and the fence (entity restriction, deferred document/email triggers,
V49's function on a new request). `domain/documents/deletion` holds the reason, fingerprint, statuses, failures and
steps; `repository/DocumentDeletionRequestRepository` holds the native reads, including completion's `lockDocumentRecord`.
`AuditEntry` gains typed `documentDeletionReason`/`documentDeletionRequestId` detail, and `AuditAction` gains
`DOCUMENT_DELETION_REQUESTED`. Receipt retention: the reason and name are cleared at completion, and the request
cascades with its case. Tests: `DocumentDeletionMigrationTest` (12, Postgres), `DocumentDeletionReasonTest` (32),
`DocumentDeletionFingerprintTest` (2), `AuditEntryDocumentDeletionDetailTest` (3); `SdkMigrationOrderTest` now covers
V1–V51, `DeedPollSchemaHealthTest` the two tables, and `DeletionFenceTest` the 14 fence triggers. Gates: build green
(test 5731, 0 failed, 1 skipped; integration 31, 0 failed), Checkstyle OK, diff check clean.
Task 3 (2026-11-26): `DocumentDeletionService` (admission and status read in the domain, staff roles only, no new
capability), `DocumentDeletionRefusal`/`DocumentDeletionConflictError`, and
`repository/DocumentDeletionAdmissionRepository` (native document lock, shared-binary and the three in-use
questions). The document fence is asked after the lock through `DeedPollSupportingDocumentRepository.findUnfencedByIdAndDeedPollIdForUpdate`, now used by the storage moves,
deed preparation, staging read-back and `DocumentService.updateDocument`/`removeDocument`. Recorded delta: a second key
with the same body answers the existing request (requester kept); a second key with another body is the document's 404.
Tests: `DocumentDeletionServiceTest` (18, Postgres, including five two-transaction races, stable over three reruns).
Gates: build green (test 5749, 0 failed, 1 skipped; integration 31, 0 failed), Checkstyle OK, diff check clean.
Task 4 (2026-11-26): `DocumentDeletions` (the staff entry point: admission, then the work in the same request),
`DocumentDeletionWork` (claims, leases, the 20 s budget, step verdicts, completion under case, document, request),
`DocumentDeletionCompletion` (row delete, `DOCUMENT_DELETED` and the note under the requester, the receipt),
`DocumentDeletionRecovery` and its timer (on by default, `DOCUMENT_DELETION_RECOVERY_ENABLED`), `DocumentDeletionProperties`,
`storage/CdamPermanentDelete` (the only `permanent=true` sender: CDAM's client built with its own 2 s/8 s timeouts, no
resend), `storage/PermanentDocumentRemoval` (CCD list read fresh before the event; verified file removal) and the
`deedpoll.documents.deletion.requests{status}` gauge. Recorded deltas: success is `Completed` (Task 6's 200, as Task 1
recorded); a receipt is recorded even after a lost lease; no V50 tombstone for a permanent deletion. No chart value
changed, so no chart bump. Tests: `DocumentDeletionWorkTest` (18, Postgres), `CdamPermanentDeleteTest` (14, WireMock),
`PermanentDocumentRemovalTest` (4), `DocumentDeletionPropertiesTest` (6), `DocumentDeletionRecoveryTimerTest` (3), two chart
conformance tests. Gates: build green (test 5798, 0 failed, 1 skipped; integration 31, 0 failed), Checkstyle OK, diff
check clean.
Task 5 (2026-11-26): application admission refuses the new `DeletionRefusal.DOCUMENT_DELETION_UNRESOLVED` while a
document request is `InProgress` or `NeedsAttention` (`DeletionAdmissionRepository.hasUnresolvedDocumentDeletion`,
under the shared case `FOR UPDATE` lock); finalisation asks again under its case lock (`DeletionFailure.
DOCUMENT_DELETION_UNRESOLVED`, an escalation), so its cascade only ever takes `Completed` receipts. Inventory skips a
tombstone whose id has a `Completed` receipt (`permanentlyDeletedDocuments`); historical V50 tombstones are unchanged.
No migration was needed: the receipt holds no reason or locator and cascades with the case. Restore leaves a
permanently deleted document deleted. Docs: `APPLICATION-DELETION.md` *Permanently deleted documents* (plus the
refusal, finalisation and recovery tables, and `requeue.sql`'s comment), `DOCUMENT-DELETION.md` *Application
deletion*. The CLAUDE.md "a new path that deletes a document row must write one [tombstone]" sentence is left for
Task 7's CLAUDE.md rewrite. Tests: `DocumentDeletionApplicationDeletionTest` (8, Postgres: InProgress, partly done and
NeedsAttention refusals; completed CDAM and metadata-only receipts with mixed live/tombstone history; a receipt
overriding a tombstone; fence and restore; both admissions racing in each order, stable over three reruns) and
`DeletionFinalisationTest.unresolvedDocumentDeletionRefuses`. Gates: build green (test 5807, 0 failed, 1 skipped;
integration 31, 0 failed), Checkstyle OK, diff check clean.
Task 6 (2026-11-26): `CaseworkerDocumentDeletionController` publishes `requestDocumentDeletion` (POST
`/caseworker/cases/{id}/documents/{documentId}/deletion-requests`, `@AuditedAs(DOCUMENT_DELETION_REQUESTED)`; 200
`Completed`, else 202 with the status `Location`) and `getDocumentDeletion` (GET
`/caseworker/cases/{id}/document-deletions/{requestId}`: requestId, documentId, status, requestedAt, completedAt,
failure; no reason, name, requester or locator), both `no-store`. The body is closed (`DocumentDeletionBodies`: reason
a JSON string, version a JSON integer), bounded at 16 KB through `api/BoundedJsonBodies` (application deletion's reader,
extracted and shared), 415 for forms or non-JSON; 409s carry `DocumentDeletionError {error, code}`. `DocumentResponse`
gains `documentVersion`; `JudicialDocument` does not. Recorded deltas: access is the `/caseworker/**` chain plus the
service's role check (no new capability), so the controller reads the body before the service, unlike application
deletion's capability-first order; a malformed status id is the service's 404 after its case check; the
`requestId`-publishing ArchUnit exemption in `NotificationSecurityTest` (and CLAUDE.md's sentence) now names the two
document deletion DTOs. Spec: base (HEAD `5ecc056`) sha256 `8be45c77…4a62`, new sha256 `48dc8026…c2ee`; full delta:
paths added are the two above, schemas added are `CreateDocumentDeletionRequest`, `DocumentDeletionAdmissionResponse`,
`DocumentDeletionError` and `DocumentDeletionRequestResponse`, `DocumentResponse` gains `documentVersion`, and nothing
else changed. Tests: `CaseworkerDocumentDeletionControllerTest` (15, full context and Postgres: every staff role, judge,
other-service, citizen, S2S-only and unauthenticated callers, validation, 413/415, `DOCUMENT_CHANGED`, cross-case 404s,
replay by a second user after the row is gone, access revocation, 202 `InProgress`/`NeedsAttention`, reason never
logged), `OpenAPIPublisherTest.documentsTheDocumentDeletion`, `WriteEndpointAuditCoverageTest` now 39/35/4. Gates:
build green (test 5822, 0 failed, 1 skipped; integration 32, 0 failed), Checkstyle OK, diff check clean.
Task 7 (2026-11-26): forced gate `./gradlew build test --rerun integration --rerun jacocoTestReport -I
…/no-checkstyle.gradle` at `7316940`: BUILD SUCCESSFUL in 4m 45s, test 5822 (0 failed, 0 errors, 1 skipped),
integration 32 (0 failed), from the XML; Checkstyle OK; diff check clean. Changed-code line coverage (JaCoCo, lines
added under `src/main/java` since `edb7052`): 744/794, 93.7%, over 51 files. Sonar and Jenkins' Checkstyle tasks were
not run locally (Jenkins' gates, unchanged); `cftlibTest` was not run. Docs: `DOCUMENT-DELETION.md` gains *HTTP
operations*, *Using it*, *Retention*, *Runbook* (inspection queries, per-code investigation) and the final evidence;
new `DOCUMENT-DELETION-SMOKE.md` (AAT filesystem rehearsal, prod CDAM smoke, not run); README *Deleting a document*;
CLAUDE.md's `permanent=true` rule rewritten (one sender, `CdamPermanentDelete`, after admission, never after a soft
delete), a V51 fence bullet, the tombstone exception and the layout line; `CDAM-RECOVERY.md`, `CDAM-SMOKE.md` and
`APPLICATION-DELETION.md` aligned. Finding recorded for follow-up: no reviewed route settles a `NeedsAttention`
document request (it stays fenced and blocks application deletion); the runbook says so rather than improvising one.
Previously: this plan had been checked structurally, not executed. Record source SHAs, contract proof, migrations,
task validation, spec artifact, review findings and outstanding deployment evidence here. Keep checkboxes inside tasks.

## Post-Completion

External rehearsal/activation evidence is distinct from local completion. Do not mark an unavailable prerequisite
verified or erase real data as part of preparing the plan. Move to completed only after implementation and review.
