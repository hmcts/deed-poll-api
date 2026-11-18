# Plan: Delivery Manager permanent application deletion — API

## Overview

Status: implemented locally, Tasks 1–9 (2026-11-18); in review, not deployed or rehearsed. No environment deletion
is authorised by this file; the rehearsal and activation in Post-Completion are operator evidence still to come.

Steve's requirement, confirmed 2026-11-18: a Delivery Manager can **permanently delete an application created in
error or duplicated**, with an audit entry stating why. This is not a void, archive or withdrawal. It supersedes
the September planning restriction that reserved manual hard deletion for data-protection erasure requests.
It does not introduce routine retention disposal or change the unpaid-online seven-day policy.

**Steve's answers, 2026-11-18 (review by Aspen):** (1) case creation is re-enabled in prod (api#174), and this is for
applications **created in error**, so the full Delivery Manager feature is wanted, not a one-off clean-up. (2) CCD
deletion goes through CCD **Retain and Dispose**, which the service will be onboarded to. (3) Documents are deleted
**permanently**. (4) A **mandatory free-text reason** is required as well as the reason code, so the user confirms what
they intend. (5) No re-import protection is needed: the cases are new ones created in error, not Power App imports.

This is the API half. The consumer plan is `deed-poll-admin:docs/plans/20261118-dm-application-deletion.md`.
Run each plan from its own repository in a separate worktree. API owns permissions, eligibility, deletion and audit;
Admin owns the confirmation and progress pages. Merge and deploy API before exposing the Admin control.

Success means the application and its owned operational data are permanently removed from this service's active
stores, including documents and CCD/SDK data, and cannot be recreated by queued work. An append-only audit event
retains the application identifier, actor, time and reason. An accepted request is not completed deletion.

## Context

Planning baseline: API `origin/master` `35bf235`; Admin `origin/master` `17b28c7`. Fetch and record execution SHAs
before implementation; do not reset another worker's checkout. Read `CLAUDE.md` in full and applicable local rules.

Read these existing files before introducing a second mechanism:

- `src/main/java/uk/gov/hmcts/reform/deedpoll/domain/deedpoll/DeedPollService.java`: `removeDeedPoll` already locks
  and deletes, refusing retained deed/staging payloads and any Gazette request/batch membership. It has no caller
  permission and audits as the system account. It does not coordinate remote or SDK cleanup.
- `api/caseworker/controller/CaseworkerCaseController.java` under the same Java root: deliberately no delete route.
  `domain/auth/{CurrentUser,Capability,RoleCapabilities}.java`: capabilities come from verified roles.
- `domain/audit/{AuditAction,AuditEntry,AuditActor,AuditTrail}.java`, migrations `V36__audit_trail.sql`,
  `api/WriteEndpointAuditCoverageTest.java`, `domain/audit/RemainingWriteAuditTest.java` under test sources.
  Audit event detail currently has an identifier-only allowlist; before/after values survive ordinary case deletion.
- `repository/DeedPollRepository.java`; migrations V3, V6, V11, V22, V26, V30, V31, V34, V36 and V44. Database
  cascades remove local child rows, not stored files, CCD records or SDK snapshots. Gazette evidence restricts deletion.
- `domain/documents/{DocumentService,DocumentStore,RemoteDocumentUploads}.java`,
  `storage/{CdamDocumentStore,LocalFilesystemDocumentStore,StagedPayloads}.java`, `domain/staging/OwnedStaging.java`.
  Ordinary document removal is best effort after commit and CDAM soft deletion: neither is this feature's completion contract.
- `ccd/{CcdLinkingService,CcdCreationAttempts,CcdAttemptRepairs,CcdLinkingSweep,LinkDeedPollHandler}.java`,
  `notify/NotificationDeliveryTransactions.java`, `domain/masters/{MasterEmailTransactions,MasterEmailDrain}.java`.
  Trace the actual SDK persistence, linking, send and recovery paths, not just foreign keys.
- `docs/plans/retain-and-dispose.md`, `docs/CDAM-SEQUENCE.md`,
  `docs/plans/completed/gazette-1c-request-ledger-and-guards.md`,
  `docs/plans/completed/20261015-audit-trail.md` and the existing OpenAPI publisher test.

Except where a full path is given, Java paths above are relative to
`src/main/java/uk/gov/hmcts/reform/deedpoll/`; test paths mirror that package under `src/test/java/`.

## Development Approach

### Confirmed requirements and implementation defaults

Implement the permanent-delete outcome and reason audit; do not substitute a retained voided application.
The following are design defaults for this plan, not additional business decisions attributed to Steve:

1. Add `DELETE_APPLICATION`, granted only to `Role.DELIVERY_MANAGER`. Team leader, legacy admin, caseworker and
   judicial roles do not gain it. Check it inside the admission service and every status read before looking up data.
2. Require a structured reason code, `CREATED_IN_ERROR` or `DUPLICATE`, **and a mandatory free-text `note`** (Steve,
   2026-11-18): trimmed, 10–500 characters, refusing control characters other than ordinary spaces. Store the code and
   note on the request and as typed `deletionReason`/`deletionNote` audit detail. The note survives the deleted case
   for the audit's retention, so the API documents (and Admin's copy tells the user) that it must say *why* and must
   not include the applicant's personal details; never log it. No mandatory duplicate target.
3. Preserve the existing Gazette-evidence and unresolved-storage guards. Also refuse `retainPermanently` rather
   than silently overriding it. A completed/paid/migrated case is not automatically ineligible just because of that
   label; its actual obligations and external records must be covered by the inventory and deletion contract.
   Do not invent retention periods, refund money or delete financial records governed separately.
4. Use durable asynchronous deletion because local/CCD/CDAM work cannot be one transaction. While a request is
   pending, the case may temporarily remain stored but must be fenced against use. This is recovery machinery,
   not a new business state or soft-delete outcome. No `DeedPollState` member is added.
5. Keep reference allocation monotonic. Do not decrement counters, recycle an application ID/reference or delete
   the surviving application when deleting a duplicate. Re-import protection is out of scope (Steve, 2026-11-18).

### Proposed HTTP contract

Implement and publish in API before Admin consumes it. Do not hand-edit Admin's vendored spec.

| Operation | Contract |
|---|---|
| `POST /caseworker/cases/{id}/deletion-requests` | `DELETE_APPLICATION`; required UUID `Idempotency-Key`; closed JSON body with `reason` (`CREATED_IN_ERROR` or `DUPLICATE`), `note` (required free text) and `expectedModifiedOn` (the timestamp read for confirmation); 202 with `{ requestId, status }` and a Location naming the status resource |
| `GET /caseworker/application-deletions/{requestId}` | Same capability; 200 with request ID, case ID, reason, status, requested/completed timestamps and optional allowlisted failure code; no applicant content or storage locators |

Statuses: `Pending`, `InProgress`, `AwaitingDisposal` (a linked case's TTL is set and CCD's disposer has not yet removed
it; the disposer runs nightly, at 22:00 in its chart), `NeedsAttention`, `Completed`. An incomplete/uncertain outcome remains visible;
no response calls it deleted. Polling never starts work. There is no browser-triggered retry with a new key.
Document 400 (invalid body/key), 401, 403, 404 and 409 (guard, concurrent admission or key/body mismatch), and normal
availability errors. Capability checks precede existence and payload validation so forged requests disclose nothing.

Idempotency scope is the operation, case and key. Persist a unique request per case, including its completion
tombstone, without a cascading FK. A repeat of the same key/body returns that request even after the case is gone;
the same key for different content returns 409. A second key must not overwrite the original reason/actor or create
another deletion: return the existing request for the same case/reason, or 409 for a conflicting reason. Authorise
all replays and status reads. Check the durable admission record before treating the missing case as 404.
For first admission only, compare `expectedModifiedOn` under the case lock with the current authoritative
timestamp. A mismatch is 409 with no request/fence/audit write: require a fresh review. A replay of an already
admitted request uses its original fingerprint and never fails merely because cleanup changed or removed the case.

### Deletion and audit invariants

- Admission takes the same case lock as other mutations. In one short transaction validate eligibility, create the
  request/fence, save an immutable inventory of required cleanup identifiers and record `CASE_DELETION_REQUESTED`
  with the verified actor, reason code and request ID. Audit failure rolls all of this back. Store no access tokens.
- Before irreversible work, all writers and background consumers must honour the fence. Cover case/child writes,
  Gazette admission, document uploads/promotions, CCD creation/repair/callbacks, notification claims/delivery and
  Master digests/reminders. Reads, downloads, judicial views, searches, counts and MI must not expose a pending
  application's content. The deletion status resource remains available to authorised staff.
- Resolve in-flight/unknown CCD creation, storage or external sends before accepting, or report a typed 409.
  A worker that copied a payload before the fence is a real race: a row disappearing later does not revoke its
  send authority. Test lock/lease ordering at the last irreversible boundary. Never hold a DB transaction open
  across remote calls. Stop new sends; do not claim to recall email already delivered by Notify.
- A bounded worker uses durable steps, owner/lease and retry policy, retaining identifiers until their cleanup is
  verified. No best-effort log-and-forget cleanup. Missing rows/files count as success only under a proven ownership
  and deletion contract. Auth failures, outages and ambiguous remote results never count as absence.
- Inventory and remove local application/children/outboxes, owned binaries/staging, CCD pointer and service-owned
  SDK case/event snapshots and projections, and applicant-bearing audit value rows via the controlled disposal
  function. Handle shared Master email ledgers without deleting other cases' work. Preserve audit event rows.
- **A CCD-linked case is removed through CCD Retain and Dispose** (Steve, 2026-11-18), never a guessed CCD DELETE
  endpoint or a write to CCD's database. The worker sets the case's `TTL.SystemTTL` to today through a system CCD event,
  then waits: `ccd-case-disposer` deletes the CCD case and, by case reference, its documents (its
  `DisposeDocumentsRemoteOperation`), Elasticsearch entry, role assignments, tasks and hearings. Only when CCD answers
  that the case is gone (404 for the reference, the decentralised contract in the CCD docs) does local finalisation run.
  Do not send CDAM `permanent=true` ourselves, and do not change ordinary document deletion's soft default. An unlinked
  case has no CCD case and no CDAM documents (uploads need a linked case; verify in Task 1), so it skips disposal and its
  local filesystem binaries are removed by the worker.
- On finalisation, in one local transaction remove remaining approved local data, dispose case audit values, record
  `CASE_DELETED` with the **original Delivery Manager**, reason and request ID, and set `Completed`. Failure of any
  local step rolls back finalisation; external completed steps remain recorded for safe resumption. Exactly one
  completion event, also after lost responses, expired leases and multiple workers.
- Extend audit detail with typed, validated `deletionReason` and `deletionRequestId`; do not turn the allowlist into
  arbitrary JSON or add applicant content. Completion records no before/after applicant values. Audit/status can
  identify the deleted application by its internal ID; do not retain a snapshot merely to decorate the UI.
- The completion tombstone retains identifiers, actor, reason, timestamps and step outcome codes only; erase
  document locators and temporary inventory once no longer needed. Preserve it long enough to prevent replay or
  replay under an explicit documented retention rule consistent with the existing seven-year event policy.
  Do not claim existing backups, third-party delivered messages or the source Power App have been erased by this
  operation. Document their separate ownership and restoration protections.

### Platform prerequisite

The supported path is CCD **Retain and Dispose** (workspace `apps/ccd/docs/how-to/enable-retain-and-dispose.md`, and
`apps/ccd/ccd-case-disposer`). Its onboarding is **external and is not a task here**: a LAU PET ticket; DEEDPOLL in the
disposer's `SIMULATED_CASE_TYPES`, then `DELETE_CASE_TYPES` (cnp-flux-config); and the definition carrying `TTL`
imported to each environment. Implement and test everything against the pinned contracts (WireMock for data-store; a
stubbed "case gone" 404). **Until onboarding, a linked case's request correctly stays `AwaitingDisposal`** — never
mark it completed, fall back to local-only deletion, or change this to voiding. Record the onboarding steps and owners
in `docs/APPLICATION-DELETION.md`; Task 1 does not block on them.

## Validation Commands

Run from this repository root, **in the foreground** (never background a Gradle command: a backgrounded build ends
the session, and the restart builds over it). These run after every task, so they must be green after every task.
Gradle's Checkstyle worker crashes with SIGILL in this devcontainer even on untouched master, so Gradle Checkstyle is
disabled locally by the init script and the same pinned Checkstyle runs directly (`checkstyle.sh`, which fails on any
WARN, as the Gradle task does). Jenkins runs the normal Checkstyle tasks. Baseline on untouched `origin/master`
`7fc5671`: green (Aspen, 2026-11-18).

```bash
export TESTCONTAINERS_RYUK_DISABLED=true JAVA_TOOL_OPTIONS=-XX:UseSVE=0
./gradlew build -I /Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/no-checkstyle.gradle --console=plain --no-watch-fs
/Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/checkstyle.sh .
git diff --check
```

Each task also runs its own focused suites (once they exist; never an empty `--tests` selector as a gate). Use
Testcontainers/Postgres for migrations, audit and concurrency, and WireMock for external contracts. The final gate
forces the tests (`./gradlew test integration --rerun`, each task named immediately before its `--rerun`) and records
counts from the XML. If a cftlib test is touched, also compile it (`compileCftlibTestJava`), which `build` does not.
Generate the OpenAPI artifact using the publisher test and `OPENAPI_OUTPUT_PATH`, as the Admin provenance file
documents, after the final contract is tested.

## Implementation Steps

### Task 1: Verify deletion ownership, external contracts and execution baseline

**Read:** all Context sources, `storage/`, `ccd/`, `notify/`, `domain/masters/`, SDK persistence configuration,
the current migrations and the pinned platform contract fixtures.

- [x] Record current source SHAs and baseline build/check results. Inventory every owned application record/blob,
  shared ledger, SDK snapshot, migration mapping and pending/in-flight job, including deployed mode selection.
- [x] Write `docs/APPLICATION-DELETION.md`: the cleanup ordering; the Retain and Dispose contract as the disposer's
  pinned code implements it (what it deletes by case reference, the "case gone" signal, the nightly schedule);
  audit-value disposal permissions; separately retained data; and the **external onboarding checklist** (LAU PET
  ticket, `SIMULATED_CASE_TYPES` then `DELETE_CASE_TYPES`, TTL definition import) with owners. Confirm from the
  upload code that an unlinked case can hold no CDAM document.
- [x] Design the definition change: the platform `TTL` field on the case type and a system event that sets
  `SystemTTL`, granted to `DEED_POLL_SYSTEM` only, with `DeedPollDefinitionTest`'s pinned JSON updated deliberately.
  Establish a runnable stubbed contract proof (WireMock data-store: event submit, then 404 for the reference).

### Task 2: Add durable request storage, reason types and Delivery Manager capability

**Files:** new `domain/deletion/` types/repository, next free Flyway migration, `health/DeedPollSchema.java`,
`domain/auth/{Capability,RoleCapabilities}.java`, `domain/audit/{AuditEntry,AuditAction}.java` and matching tests.

- [x] Add reason/status types, durable request/step inventory and claim fields; enforce case/key uniqueness and
  retain the request across case deletion. No applicant snapshots or cascading request FK.
- [x] Grant `DELETE_APPLICATION` only to Delivery Managers and add typed audit metadata plus request action.
- [x] Test migration constraints, role unions/negative roles, invalid reason/detail rejection and persistence
  across a parent deletion. Check schema/migration-order health tests as well as the task's focused suite.

### Task 3: Admit, deduplicate and audit deletion requests

**Files:** new `domain/deletion/ApplicationDeletionService.java` and tests; `DeedPollRepository.java`,
`domain/audit/AuditTrail.java`, and the existing deletion tests named in Context.

- [x] Implement authorised, locked admission with immutable actor/reason, inventory, fence and transactional
  request audit. Keep Gazette and unresolved-storage guards; add permanent-retention, stale-confirmation and
  unresolved-work refusals. Include the original confirmation timestamp in the idempotency fingerprint.
- [x] Implement the replay rules above, including replay after physical deletion, conflicting bodies/keys and
  simultaneous requests. Close any unguarded production call path to the old short-form deletion method.
- [x] Prove audit failure rolls back admission, no denied request changes data, guards leave the case intact,
  unknown IDs 404 for holders, and another application's ID/duplicate reason never deletes the retained case.

### Task 4: Fence casework, reads, background sends and resurrection paths

**Files:** read/write services under `domain/`, list/query/MI repositories, `ccd/`, `notify/`,
`domain/masters/`, `domain/staging/`, document services and their focused tests.

- [x] Apply the request fence consistently to case/child writes, reads/downloads, worklists/counts/MI, judicial
  reads and mutations, Gazette admission, document promotion and CCD linking/repair/callback paths.
- [x] Quiesce pending jobs and coordinate the in-flight claim/delivery boundary; account for Master emails
  spanning deleted and surviving cases. Preserve the service's unknown-send handling and do not resend.
- [x] Add deterministic concurrency tests for admission versus update/upload/Gazette/send/link, workers holding
  copied data, and a late CCD callback after completion. Prove no new external work escapes the fence.

### Task 5: Implement permanent external and binary cleanup with recovery

**Files:** `domain/deletion/` worker/steps, `storage/`, CCD integration, document stores, scheduler properties,
`src/main/resources/application.yaml`, chart values/Chart.yaml if changed, and stubbed external tests.

- [x] Implement the Retain and Dispose steps: set `SystemTTL` through the system event, move to `AwaitingDisposal`,
  and poll CCD for the reference until it is gone (bounded, on the worker's timer, never per request). Remove local
  filesystem binaries for unlinked cases. Capture identifiers before cascades remove the only lookup paths.
- [x] Add bounded multi-pod claiming/recovery and persisted outcomes. Keep transient and uncertain failures
  incomplete, expose allowlisted status codes, and retain enough information to resume without guessing.
- [x] Test partial remote completion, timeout before/after remote success, 401/403/404/5xx, lease loss and restart
  at each step, two workers, missing binaries and already-completed operations. Ordinary document removal stays soft.
- [x] If worker config changes chart values, bump the chart version and cover chart selection. Keep activation
  disabled until Task 9's evidence is recorded; disabling the worker must leave pending cases fenced and recoverable.
  (No chart values changed, so no chart bump: both switches default off in `application.yaml` and no chart sets them.)

### Task 6: Finalise local deletion and retain the reason audit

**Files:** `domain/deletion/` finalisation, `DeedPollService.java`, audit disposal adapter, local/SDK repositories,
new migration only if required for permissions, and Postgres integration tests.

- [x] Implement atomic local finalisation only after verified external steps. Remove applicant-bearing values
  through the existing controlled function, preserve event history and record one correctly attributed completion.
- [x] Retain the minimal idempotency tombstone and remove no-longer-needed cleanup inventory; leave
  sequence counters unchanged. Shared records for other applications survive.
- [x] Test complete populated applications, unlinked/linked/migrated cases, retained audit rows/reason/actor,
  deleted audit values, binary and SDK absence, audit failure rollback and safe retry after external completion.

### Task 7: Expose and publish the deletion contract

**Files:** new caseworker controller/DTOs, `api/DomainErrorHandler.java` only as required, OpenAPI/security tests,
`api/WriteEndpointAuditCoverageTest.java`, `auth/` capability response tests and publisher test.

- [x] Add the proposed admission and status routes with capability-first checks, closed reason body, required
  idempotency header, typed statuses/errors, Location and no-store responses. Classify request writes with `@AuditedAs`.
- [x] Test real HTTP validation/authentication, every non-DM role, header/body mismatch, 202 replay after deletion,
  status visibility across authorised Delivery Managers, and no content/locators in responses or logs.
- [x] Generate/publish the complete OpenAPI artifact and record source SHA/hash/full delta for Admin. Run the
  existing write-body, forbidden-response and endpoint-audit conformance tests.

### Task 8: Complete regression proof and implementation documentation

**Files:** all changed production/test files, `README.md`, `CLAUDE.md`, `docs/APPLICATION-DELETION.md`, this plan.

- [x] Run the forced full build, integration and Checkstyle gates; record commands/counts, changed-code coverage
  and meaningful race/failure evidence. Do not weaken coverage/audit guards or mark failed checks passed.
- [x] Document the role, two reasons, status meanings, permanent outcome, existing refusal rules, audit retention,
  no reference reuse, non-recallable delivered email and failure/recovery procedure. Amend stale current guidance
  that says no delete consumer exists; preserve the superseded September decision as history.
- [x] Record API commit/spec provenance and the Admin handoff. Leave deployment evidence separate from local
  completion; do not run Ralphex in the Admin checkout or move the plan to completed before review finishes.

### Task 9: Prepare the concrete rollout and recovery evidence

**Files:** `docs/APPLICATION-DELETION.md`, chart configuration/tests if required, execution notes below.

- [x] Produce a non-production rehearsal script/checklist using disposable fixtures for linked/unlinked cases,
  documents, failure/restart and audit verification; use no real applicant data and include restoration safeguards.
- [x] Produce the API-before-Admin rollout order, worker activation prerequisites, pause/resume procedure,
  incomplete-request monitoring and owner handoff. No cancellation/unfencing after irreversible cleanup begins.
- [x] Record which external checks remain outstanding; automated tests must keep completion/activation impossible
  without the required configuration. This task prepares reviewable artifacts, not live deletion or deployment.

## Success criteria

Only a Delivery Manager can request deletion, and every accepted request carries one of the two explicit reasons.
Completed means all in-scope operational copies are removed, the reference is never reused, no queued writer can
recreate the case, and a durable audit event states who deleted which application, when and why. Partial or unknown
cleanup never appears completed. Existing Gazette evidence and unresolved obligations are preserved through refusals.
No application is merely voided, and no audit event or unrelated application is lost.

## Execution notes

Baseline (Aspen, 2026-11-18, untouched `origin/master` `7fc5671`): the Validation Commands are green. `./gradlew
build` (Checkstyle disabled by the init script) exit 0; `checkstyle.sh` OK; `git diff --check` clean. From the XML:
`test` 5,453 across 589 suites (0 failures, 1 skipped); `integration` 30 across 5 (0 failures). No implementation,
external contract proof, rollout or live deletion has been performed yet.
Record execution SHAs, Task 1 inventory/contract proof, per-task tests, spec artifact and review findings here.

Task 1 (2026-11-18, branch `feat/dm-application-deletion` at `18aaf0d` on `origin/master` `7fc5671`): source SHAs,
inventory, Retain and Dispose contract, cleanup order, background-work switches, retained data, onboarding checklist
and the definition design are in `docs/APPLICATION-DELETION.md`. **Finding that changes Task 5's shape:** the pinned
SDK (decentralised-runtime 7.1.0) already ships decentralised Retain and Dispose (`RetainAndDisposePolicy`,
`RetainAndDisposeTask`, events `MarkForDisposal`/`ConfirmDisposal` with `TTLIncrement` 0, state `PendingDisposal`, off
by default). It marks and confirms a policy's candidates, reads CCD as the system user, and on a 404 runs
`policy.dispose` in the same transaction that deletes `ccd.case_data`. Recommended: implement a policy rather than a
second worker for the CCD step, with finalisation in `dispose`; Task 5's own worker keeps unlinked cases and local
binaries. Other findings: the disposer's `resolvedTtl < CURRENT_DATE` makes a `SystemTTL` of today eligible the next
night; its document sweep marks only dm-store documents with `case_id` and `deleted = false`, so CDAM documents a
caseworker already soft-deleted are **not** covered (open question for Steve in the document); an unlinked case can
hold no CDAM document (`DocumentStorageRule.requireCaseReady`), but a linked non-prod case can hold filesystem files,
so binary cleanup follows the store, not linkage. Contract proof:
`src/test/java/uk/gov/hmcts/ccd/sdk/retention/RetainAndDisposeContractTest.java`, 13 tests, drives the SDK's own client
over a real Feign `CoreCaseDataApi` against WireMock (both events land in `PendingDisposal`; any other state refused;
404 is gone; 400/401/403/409/500/502/503 and a dropped connection are failures). Gates after Task 1: `./gradlew build`
(init script) exit 0 in 5m23s; `test` 5,466 across 590 suites (0 failures, 1 skipped); `integration` 30 across 5 (0
failures); `checkstyle.sh` OK; `git diff --check` clean. The definition change is designed, not built; Task 5 builds it.

Task 2 (2026-11-18): `V48__application_deletion_requests.sql` adds `deedpoll_deletion_request` (no foreign key to the
case; `UNIQUE (case_id)`, so one deletion per case and case/key unique; closed reason and status checks; the note rule
restated as a check; SHA-256 body fingerprint; claim, completion and failure-only-in-`NeedsAttention` checks; a trigger
refusing any change to admission's half and any change at all once `Completed`) and `deedpoll_deletion_step` (the
inventory: kind, item id, locator held until `Done`, cascading from the request only). Types in `domain/deletion/`
(`DeletionReason`, `DeletionStatus`, `DeletionNote` with the 10–500 code-point, no-control rule and a redacting
`toString`, `DeletionFingerprint`, `DeletionRequest`, `DeletionStep`, the step kinds/states) and
`repository/DeletionRequestRepository` (insert `ON CONFLICT (case_id) DO NOTHING`, reads by id and by case, steps).
`DELETE_APPLICATION` is appended to `Capability` and granted to `DELIVERY_MANAGER` only; that includes an AM-derived
Delivery Manager (`ctsc-team-leader` with the Gazette skill), because the grant follows the role. `AuditAction` gains
`CASE_DELETION_REQUESTED`; `AuditEntry` allowlists typed `deletionReason`/`deletionNote`/`deletionRequestId`, refuses
an untyped value without echoing it, offers `withDeletion`, and its `toString` prints detail keys only. Readiness lists
both tables. Focused suites: `DeletionRequestMigrationTest` 7, `DeletionNoteTest` 37, `AuditEntryDeletionDetailTest` 3,
`RoleCapabilitiesTest` 18, plus updated `AmRolesTest`, `CallerAccess*`, `DeedPollSchemaHealthTest`, `SdkMigrationOrderTest`
(V1–V48). Gates: `./gradlew build` (init script) exit 0 in 5m41s; `test` 5,514 across 593 suites (0 failures, 1 skipped);
`integration` 30 across 5 (0 failures); `checkstyle.sh` OK; `git diff --check` clean.

Task 3 (2026-11-18): `domain/deletion/ApplicationDeletionService` admits under the case's `FOR UPDATE` lock in one
transaction (request, immutable inventory, value-free `CASE_DELETION_REQUESTED` with reason, note and request id), in a
fixed order: capability, subject, body (field errors), existing request (replay, also after physical deletion), lock,
existing request again, 404, refusals. Refusals are `DeletionConflictError` with a typed `DeletionRefusal` code
(`STALE_CONFIRMATION`, `RETAINED_PERMANENTLY`, `GAZETTE_EVIDENCE`, `RETAINED_DEED_PAYLOAD`, `RETAINED_STAGED_UPLOADS`,
`DOCUMENT_STORAGE_UNRESOLVED`, `CCD_CREATION_UNRESOLVED`, `EMAIL_SEND_UNRESOLVED`, `KEY_REUSED`,
`CONFLICTING_REASON`); the guard reads are `repository/DeletionAdmissionRepository`. `read` is the capability-first
status lookup for Task 7. `DeedPollService.removeDeedPoll` now refuses a case with a request
(`DeedPollRepository.hasDeletionRequest`), and `RemoveDeedPollCallersTest` (ArchUnit, local import) holds that no
production class calls it. **Task 4 must note:** a `Pending` email with `sendStartedAt` set blocks admission until it is
settled, so fencing the drain must still let it settle; an `UploadUncertain` document with both sends used is treated
as settled. Focused suites: `ApplicationDeletionServiceTest` 13 (Postgres: inventory and audit, every non-DM role 403
before lookup or validation, field errors, 404s, each refusal writes nothing, settled work admitted, stale
confirmation, audit-failure rollback by trigger, replays including after deletion, duplicate leaves the kept case
alone, short form refused, two simultaneous admissions held on the lock), `RemoveDeedPollCallersTest` 1. Gates:
`./gradlew build` (init script) exit 0 in 5m20s; `test` 5,528 across 595 suites (0 failures, 1 skipped); `integration`
30 across 5 (0 failures); `checkstyle.sh` OK; `git diff --check` clean.

Task 4 (2026-11-18): the fence has three layers, described in `docs/APPLICATION-DELETION.md` ("The fence").
(1) `entity/DeletionFenceSql` `@SQLRestriction`s on `DeedPoll`, `Referral`, notes, witnesses, documents,
notifications and CCD attempts, so every JPA read (case view, list/counts/saved queries, notes, documents and their
downloads/previews, referral queue, judicial lists/views, Master email referral lists, outbox candidates, CCD repair
view) answers as for an unknown id. (2) Native statements carry the predicate: MI reads `LIVE_CASES`; the linking
sweep's due list, the Master reminder claim and outstanding list, the overdue-assignment release and Master
linking/unlinking exclude fenced rows. (3) `V49__application_deletion_fence.sql`: deferred constraint triggers refuse at
commit any INSERT/UPDATE of a fenced case, its children, CCD attempts, deed preparation, owned stages and Gazette
membership, and any notification INSERT; they lock the case `FOR KEY SHARE` before looking; DELETE is never refused.
Writes ask after the lock (`DeletionFence.admits`, `DeedPollRepository.findUnfencedByIdForUpdate`,
`lockUnfencedForDeedPreparation`): patch, email send, deed preparation/storage, owned staging, CCD claim
(`CcdCreationAttempts.claim`), `LinkDeedPollHandler`, `SupportingDocumentsHandler`, and the outbox `claim`/`startSend`
(a fenced row stays `Pending`, is never settled or noted, never resent). Deliberate exceptions: CCD's view and binding
reads are native (Retain and Dispose reads through data-store; a fenced linked record must not read as unbound), and
the view shows no documents for a fenced case; decided that `DEED_POLL_READER` gets nothing on `PendingDisposal`.
**Tasks 5 and 6 must** read the fenced case natively and remove it by DELETE only (V49 refuses UPDATE), and the SDK
events must not write the case row. Existing test change: `ApplicationDeletionServiceTest.replays` now asserts a case
update after admission is refused instead of making one. Focused suite: `DeletionFenceTest` 8 (Postgres: reads as gone
including judicial view and MI, CCD view still answers; writes 404 and linking neither offers nor claims; V49 refuses
note/case/notification/attempt writes at commit and allows DELETE; the outbox refuses a send start on a claim taken
before the fence; Master reminders/outstanding/linking skip a fenced referral and still serve the kept one; a patch, a
note and a CCD claim waiting on admission's lock are refused once it commits; admission waiting on a note sees it and
waiting on a send start refuses `EMAIL_SEND_UNRESOLVED`; a CCD callback for a fenced record, and a late one after
deletion, binds nothing). Updated: `CcdCreationAttemptsTest`, `CcdLinkingServiceTest`, `LinkDeedPollHandlerTest`,
`SupportingDocumentsHandlerTest`, `NotificationOutboxTest`, `OwnedStagingTest`, `DeedPollDefinitionTest`,
`SdkMigrationOrderTest` (V1–V49). Gates: `./gradlew build` (init script) exit 0 in 5m51s; `test` 5,536 across 596 suites
(0 failures, 1 skipped); `integration` 30 across 5 (0 failures); `compileCftlibTestJava` OK; `checkstyle.sh` OK;
`git diff --check` clean.

Task 5 (2026-11-18): the SDK's own `RetainAndDisposeTask` does every CCD call, so this service implements
`ccd/DeedPollRetainAndDisposePolicy` and never calls CCD itself. Definition: the platform `TTL` field (`TtlAccess`,
CRU to `DEED_POLL_SYSTEM` only), state `PendingDisposal` (system CRU, staff nothing), events `MarkForDisposal` and
`ConfirmDisposal` (`TTLIncrement` 0) through `ccd/DisposalEventsHandler`, which admits an event only for a request ready
for CCD; pinned JSON regenerated by `generateCCDConfig` and reviewed (the document event's pre-state is now written
`Registered`, not `*`). **Design change from Task 1's recommendation:** `dispose` does not finalise; it records the
CCD and CDAM steps `Done` (`DISPOSED_BY_CCD`) in the SDK's transaction and makes the request due, and Task 6's
finalisation runs in the worker for linked and unlinked cases alike. Worker: `domain/deletion/DeletionCleanup`
(`deedpoll.deletion.cleanup.*`, off by default; `DeletionCleanupTimer` only when enabled) claims with `SKIP LOCKED`,
an owner and a lease, writes only under its claim, removes filesystem documents with the new verified
`LocalFilesystemDocumentStore.remove` (a missing root is never absence), and derives status in the pure
`DeletionProgress` (`InProgress`, `AwaitingDisposal`, `NeedsAttention` with an allowlisted `DeletionFailure`; never
`Completed`). Statements in `repository/DeletionWorkRepository`. SDK task configuration in `application.yaml`
(`mode` off, cron, the existing system user). Chart unchanged. Focused suites: `DeletionCleanupTest` 14 (Postgres),
`RetainAndDisposeDeletionTest` 11 (Postgres; the SDK's real task from its own auto-configuration, `mode=live` in a child
context, data-store scripted), `DeletionProgressTest` 9, `DisposalEventsHandlerTest` 5, `LocalFilesystemDocumentStoreTest`
(+3), `DeedPollDefinitionTest` 10, `DeedPollCaseViewTest`. A deliberate mutation (dropping the "files before CCD"
clause) is caught by `DeletionCleanupTest.linkedFileStuck`. Gates: `./gradlew build` (init script) exit 0 in 6m0s;
`test` 5,579 across 600 suites (0 failures, 1 skipped); `integration` 30 across 5 (0 failures); `compileCftlibTestJava`
OK; `checkstyle.sh` OK; `git diff --check` clean.

Task 6 (2026-11-18): `domain/deletion/DeletionFinalisation` (statements in `repository/DeletionFinalisationRepository`,
plain JDBC, `DELETE` only) runs in the worker's same pass once every external line is `Done`, in one owned
`MANDATORY` transaction: re-checks the lines and that the SDK's `ccd.case_data` copy is gone (`CCD_COPY_PRESENT`),
locks the case, refuses retained bytes (`RETAINED_PAYLOAD`), deletes the listed owned stages (an unlisted one is
`UNLISTED_OWNED_STAGE`), deletes the case (children by cascade), calls `deedpoll_audit_dispose_case_values`, records one
`CASE_DELETED` under the request's own Delivery Manager with `deletionReason`/`deletionRequestId` and no note or
values, erases every line's locator and item id (kind, outcome and time kept) and sets `Completed`. A named refusal or
a `DataIntegrityViolationException` rolls back to `NeedsAttention` (`LOCAL_FINALISATION_REFUSED` for the latter); any
other failure rolls back to `InProgress` after the poll interval, external lines kept `Done`. No migration (the api
login owns the function). Reference counters, the Master email ledger and other cases are untouched. Focused suites:
`DeletionFinalisationTest` 7 (Postgres: populated migrated unlinked case removed whole with events kept and one
correctly attributed completion, other case and shared Master email untouched, counters unchanged, tombstone codes
only; linked case only after `dispose`, SDK copy absent; SDK copy present refused; audit failure rolled back then
retried to one completion without redoing a removal; database refusal `NeedsAttention`; lapsed lease finalises
nothing; replay after completion). Updated: `DeletionCleanupTest` (journeys now end `Completed`; the done-line case
uses a linked request still waiting on CCD), `RetainAndDisposeDeletionTest.fullJourney` (ends `Completed` with the
DM's `CASE_DELETED`). Gates: `./gradlew build` (init script) exit 0 in 6m2s; `test` 5,586 across 601 suites (0 failures,
1 skipped); `integration` 30 across 5 (0 failures); `compileCftlibTestJava` OK; `checkstyle.sh` OK; `git diff --check`
clean.

Task 7 (2026-11-18): `api/caseworker/controller/CaseworkerApplicationDeletionController` publishes
`POST /caseworker/cases/{id}/deletion-requests` (`@AuditedAs(CASE_DELETION_REQUESTED)`; 202 `{requestId, status}`,
`Location: /caseworker/application-deletions/{requestId}`, `no-store`, for fresh admissions and replays alike) and
`GET /caseworker/application-deletions/{requestId}` (200 `{requestId, caseId, reason, status, requestedAt,
completedAt, failure}`, `no-store`). `DELETE_APPLICATION` is checked before the path id, header or body is read, so the
header and status id are bound as text: a malformed key is the service's 400 on `Idempotency-Key`, a non-UUID status id
the same 404 as an unknown one. `domain/deletion/DeletionBodies` makes the body closed (any other key a 400 that does
not echo it; a wrong JSON type is refused as missing, field named). `DomainErrorHandler.deletion` answers
`DeletionConflictError` as 409 `DeletionError {error, code}` (`no-store`). `OpenAPIWriteBodies.describeDeletionBody`
publishes the closed body (bounds from `DeletionNote`, enum from `DeletionReason`, `additionalProperties: false`,
request body required). **Deliberate test change:** `NotificationSecurityTest`'s rule that no `api` field is named
`requestId` (the email send's key) now exempts exactly `DeletionAdmissionResponse` and `DeletionRequestResponse`, whose
`requestId` is the resource id the plan's contract names; the controller test asserts the key is never echoed.
`WriteEndpointAuditCoverageTest` now counts 37 writes, 33 audited. Docs: `docs/APPLICATION-DELETION.md` ("HTTP
contract"), README, CLAUDE.md layout. Focused suites: `CaseworkerApplicationDeletionControllerTest` 13 (full context,
Postgres: 401 on both routes; five non-DM roles 403 on both routes with valid, forged, keyless and malformed input and
nothing written; 202 with Location, no-store and exactly two fields, `modifiedOn` round-tripped from the case detail;
the status field set without note, subject or key; each 400; the 404s; typed `STALE_CONFIRMATION`; replays,
`KEY_REUSED`, `CONFLICTING_REASON` and a 202 replay after the case row is deleted; a second Delivery Manager reads the
first's request; the note in no captured log), `OpenAPIPublisherTest` 27 (new `documentsTheApplicationDeletion`;
`DELETION_PATHS` counted apart, the caseworker count still 35), `WriteEndpointAuditCoverageTest` 4,
`NotificationSecurityTest`. The existing write-body, forbidden-response (403 added on both by `StaffArea`) and
endpoint-audit conformance tests pass. **Spec artifact for Admin:** generated from this task's commit (parent
`49239c1`) by `TESTCONTAINERS_RYUK_DISABLED=true JAVA_TOOL_OPTIONS=-XX:UseSVE=0
OPENAPI_OUTPUT_PATH=/tmp/deletion-task7/openapi-task7.json ./gradlew integration --tests '*OpenAPIPublisherTest'
--rerun`, raw SHA-256 `4bf5ae7cbb1d2aaf0cfe814d272da40cb0d8d19de50c81e71c7caa503485e91f` (copy in
`build/evidence/task7/`). Baseline at `49239c1` by the same command: `aeea44df884d29605660dfc56854ad767e403b58d9bc3f647a146f7fd4242512`.
Full delta: paths 53 → 55, operations 72 → 74, schemas 98 → 102; added paths `/caseworker/cases/{id}/deletion-requests`
(post) and `/caseworker/application-deletions/{requestId}` (get); added schemas `CreateDeletionRequest`,
`DeletionAdmissionResponse`, `DeletionError`, `DeletionRequestResponse`; no path, schema, component or top-level key
changed or removed. Admin refreshes its vendored copy by its own `SPEC-PROVENANCE.md` recipe; this repo does not edit it.
Gates: `./gradlew build` (init script) exit 0 in 6m25s; `test` 5,599 across 602 suites (0 failures, 1 skipped);
`integration` 31 across 5 (0 failures); `checkstyle.sh` OK; `git diff --check` clean. No cftlib test touched.

Task 8 (2026-11-18, on `0c67924`): forced gate `./gradlew build -I <no-checkstyle.gradle> test --rerun integration
--rerun jacocoTestReport` exit 0 in 5m41s: `test` 5,599 across 602 suites (0 failures, 1 skipped), `integration` 31
across 5 (0 failures). Changed-code coverage (JaCoCo over test plus integration, the 64 main files changed since
`7fc5671` that the report covers): lines 2,591 covered / 114 missed (95.8%), branches 903 / 147 (86.0%). The one
uncovered file, `DeletionCleanupTimer` (0%, off by default), now has `DeletionCleanupTimerTest` (3: absent by default
and when off, present when on and runs only the worker). Lowest remaining: `CcdCreationAttempt` 22% (existing entity
accessors), the two DTOs' unused accessors, `LocalFilesystemDocumentStore` 79% (pre-existing I/O error branches).
Race and failure evidence is the Postgres suites named under Tasks 3 to 7: admission versus patch, note, CCD claim and
send start on the case lock (`DeletionFenceTest`); two workers, lapsed lease, restart after an unrecorded removal
(`DeletionCleanupTest`); lost mark answers before and after data-store's commit, 401/403/5xx/timeout keeping
everything (`RetainAndDisposeDeletionTest`); audit failure rolled back and retried to exactly one `CASE_DELETED`
(`DeletionFinalisationTest`); plus Task 5's deliberate mutation caught by `DeletionCleanupTest.linkedFileStuck`. No
coverage, audit or conformance guard was weakened in Task 8. Docs: `docs/APPLICATION-DELETION.md` gains "At a glance"
(role, reasons, statuses, permanent outcome, refusals, audit retention, no reference reuse, non-recallable email, the
superseded September restriction kept as history), "Failure and recovery" (per status and code, requeue rules,
pause/resume) and "Provenance of the published contract, and the Admin handoff". Stale guidance amended: README's
"no case deletes" sentence and its role table, and CLAUDE.md's Delivery Manager grant. Spec regenerated from the Task 8
tree by the Task 7 command: SHA-256 `4bf5ae7cbb1d2aaf0cfe814d272da40cb0d8d19de50c81e71c7caa503485e91f`, identical to
Task 7's, so Admin's handoff is the Task 7 artifact. No deployment evidence exists; the plan stays in `docs/plans/`
until review. Gates after the change: `./gradlew build` (init script) exit 0 in 5m47s; `test` 5,602 across 603 suites
(0 failures, 1 skipped); `integration` 31 across 5 (0 failures); `checkstyle.sh` OK; `git diff --check` clean.

Task 9 (2026-11-18, on `57200e5`): prepared, not run. `docs/APPLICATION-DELETION.md` gains "Rehearsal, rollout and
monitoring": the two activation switches and what each alone allows; the rollout order (API on every replica with both
off, definition import, AAT unlinked rehearsal, LAU simulation then deletion, Admin only after the API, then per
environment worker → SDK `dry-run` → `live` as separate flux PRs); an AAT rehearsal checklist R1–R7 with disposable
synthetic fixtures only, ids recorded and read back before each POST, short-lived test users, a confirmed
point-in-time restore window and the restore re-check; read-only verification queries; pause/resume (no
cancellation or unfencing); a monitoring query for incomplete requests; owners; and the outstanding external checks
(deploy and import, R1–R7, LAU ticket/simulation/sign-off, dm-store `ENABLE_TTL`, a CDAM-store disposal observed once
because AAT is on the filesystem store, Steve's CDAM soft-deleted residue answer, an alert and named owner, DPIA/OWA,
prod worker quotas). Requeue is now one reviewed file, `docs/deletion/requeue.sql` (four requeueable codes; every
other code an escalation; `UNLISTED_OWNED_STAGE` moved from requeue to escalation in the recovery table). New tests:
`DeletionCleanupTest.requeue` and `requeueRefusesEscalations` run that file verbatim (Postgres; resumes to `Completed`
once the store is back, no-op on a second requeue and after completion, admission fields unchanged, escalation left
alone); `DeploymentChartConformanceTest.noValuesFileActivatesApplicationDeletion` (×3 values files) and
`applicationDeletionDefaultsOff`. No chart change. Gates: `./gradlew build` (init script) exit 0 in 5m22s; `test` 5,608
across 603 suites (0 failures, 1 skipped); `integration` 31 across 5 (0 failures); `checkstyle.sh` OK (after fixing two
warnings in the new test, whose suite was then rerun: 16, 0 failures); `git diff --check` clean. No deployment,
rehearsal or live deletion has been performed.

## Post-Completion

API deployed and compatible on every replica before Admin activation. Rehearsal/activation results are operator
evidence, recorded separately; never tick a missing external proof as a successful rehearsal. This feature does not
complete `retain-and-dispose.md` or erase copies in backups, delivered correspondence or the legacy source system.
