# Plan: Delivery Manager permanent application deletion — API

## Overview

Status: planned, 2026-11-18. Planning only; no implementation or environment deletion authorised by this file.

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

- [ ] Record current source SHAs and baseline build/check results. Inventory every owned application record/blob,
  shared ledger, SDK snapshot, migration mapping and pending/in-flight job, including deployed mode selection.
- [ ] Write `docs/APPLICATION-DELETION.md`: the cleanup ordering; the Retain and Dispose contract as the disposer's
  pinned code implements it (what it deletes by case reference, the "case gone" signal, the nightly schedule);
  audit-value disposal permissions; separately retained data; and the **external onboarding checklist** (LAU PET
  ticket, `SIMULATED_CASE_TYPES` then `DELETE_CASE_TYPES`, TTL definition import) with owners. Confirm from the
  upload code that an unlinked case can hold no CDAM document.
- [ ] Design the definition change: the platform `TTL` field on the case type and a system event that sets
  `SystemTTL`, granted to `DEED_POLL_SYSTEM` only, with `DeedPollDefinitionTest`'s pinned JSON updated deliberately.
  Establish a runnable stubbed contract proof (WireMock data-store: event submit, then 404 for the reference).

### Task 2: Add durable request storage, reason types and Delivery Manager capability

**Files:** new `domain/deletion/` types/repository, next free Flyway migration, `health/DeedPollSchema.java`,
`domain/auth/{Capability,RoleCapabilities}.java`, `domain/audit/{AuditEntry,AuditAction}.java` and matching tests.

- [ ] Add reason/status types, durable request/step inventory and claim fields; enforce case/key uniqueness and
  retain the request across case deletion. No applicant snapshots or cascading request FK.
- [ ] Grant `DELETE_APPLICATION` only to Delivery Managers and add typed audit metadata plus request action.
- [ ] Test migration constraints, role unions/negative roles, invalid reason/detail rejection and persistence
  across a parent deletion. Check schema/migration-order health tests as well as the task's focused suite.

### Task 3: Admit, deduplicate and audit deletion requests

**Files:** new `domain/deletion/ApplicationDeletionService.java` and tests; `DeedPollRepository.java`,
`domain/audit/AuditTrail.java`, and the existing deletion tests named in Context.

- [ ] Implement authorised, locked admission with immutable actor/reason, inventory, fence and transactional
  request audit. Keep Gazette and unresolved-storage guards; add permanent-retention, stale-confirmation and
  unresolved-work refusals. Include the original confirmation timestamp in the idempotency fingerprint.
- [ ] Implement the replay rules above, including replay after physical deletion, conflicting bodies/keys and
  simultaneous requests. Close any unguarded production call path to the old short-form deletion method.
- [ ] Prove audit failure rolls back admission, no denied request changes data, guards leave the case intact,
  unknown IDs 404 for holders, and another application's ID/duplicate reason never deletes the retained case.

### Task 4: Fence casework, reads, background sends and resurrection paths

**Files:** read/write services under `domain/`, list/query/MI repositories, `ccd/`, `notify/`,
`domain/masters/`, `domain/staging/`, document services and their focused tests.

- [ ] Apply the request fence consistently to case/child writes, reads/downloads, worklists/counts/MI, judicial
  reads and mutations, Gazette admission, document promotion and CCD linking/repair/callback paths.
- [ ] Quiesce pending jobs and coordinate the in-flight claim/delivery boundary; account for Master emails
  spanning deleted and surviving cases. Preserve the service's unknown-send handling and do not resend.
- [ ] Add deterministic concurrency tests for admission versus update/upload/Gazette/send/link, workers holding
  copied data, and a late CCD callback after completion. Prove no new external work escapes the fence.

### Task 5: Implement permanent external and binary cleanup with recovery

**Files:** `domain/deletion/` worker/steps, `storage/`, CCD integration, document stores, scheduler properties,
`src/main/resources/application.yaml`, chart values/Chart.yaml if changed, and stubbed external tests.

- [ ] Implement the Retain and Dispose steps: set `SystemTTL` through the system event, move to `AwaitingDisposal`,
  and poll CCD for the reference until it is gone (bounded, on the worker's timer, never per request). Remove local
  filesystem binaries for unlinked cases. Capture identifiers before cascades remove the only lookup paths.
- [ ] Add bounded multi-pod claiming/recovery and persisted outcomes. Keep transient and uncertain failures
  incomplete, expose allowlisted status codes, and retain enough information to resume without guessing.
- [ ] Test partial remote completion, timeout before/after remote success, 401/403/404/5xx, lease loss and restart
  at each step, two workers, missing binaries and already-completed operations. Ordinary document removal stays soft.
- [ ] If worker config changes chart values, bump the chart version and cover chart selection. Keep activation
  disabled until Task 9's evidence is recorded; disabling the worker must leave pending cases fenced and recoverable.

### Task 6: Finalise local deletion and retain the reason audit

**Files:** `domain/deletion/` finalisation, `DeedPollService.java`, audit disposal adapter, local/SDK repositories,
new migration only if required for permissions, and Postgres integration tests.

- [ ] Implement atomic local finalisation only after verified external steps. Remove applicant-bearing values
  through the existing controlled function, preserve event history and record one correctly attributed completion.
- [ ] Retain the minimal idempotency tombstone and remove no-longer-needed cleanup inventory; leave
  sequence counters unchanged. Shared records for other applications survive.
- [ ] Test complete populated applications, unlinked/linked/migrated cases, retained audit rows/reason/actor,
  deleted audit values, binary and SDK absence, audit failure rollback and safe retry after external completion.

### Task 7: Expose and publish the deletion contract

**Files:** new caseworker controller/DTOs, `api/DomainErrorHandler.java` only as required, OpenAPI/security tests,
`api/WriteEndpointAuditCoverageTest.java`, `auth/` capability response tests and publisher test.

- [ ] Add the proposed admission and status routes with capability-first checks, closed reason body, required
  idempotency header, typed statuses/errors, Location and no-store responses. Classify request writes with `@AuditedAs`.
- [ ] Test real HTTP validation/authentication, every non-DM role, header/body mismatch, 202 replay after deletion,
  status visibility across authorised Delivery Managers, and no content/locators in responses or logs.
- [ ] Generate/publish the complete OpenAPI artifact and record source SHA/hash/full delta for Admin. Run the
  existing write-body, forbidden-response and endpoint-audit conformance tests.

### Task 8: Complete regression proof and implementation documentation

**Files:** all changed production/test files, `README.md`, `CLAUDE.md`, `docs/APPLICATION-DELETION.md`, this plan.

- [ ] Run the forced full build, integration and Checkstyle gates; record commands/counts, changed-code coverage
  and meaningful race/failure evidence. Do not weaken coverage/audit guards or mark failed checks passed.
- [ ] Document the role, two reasons, status meanings, permanent outcome, existing refusal rules, audit retention,
  no reference reuse, non-recallable delivered email and failure/recovery procedure. Amend stale current guidance
  that says no delete consumer exists; preserve the superseded September decision as history.
- [ ] Record API commit/spec provenance and the Admin handoff. Leave deployment evidence separate from local
  completion; do not run Ralphex in the Admin checkout or move the plan to completed before review finishes.

### Task 9: Prepare the concrete rollout and recovery evidence

**Files:** `docs/APPLICATION-DELETION.md`, chart configuration/tests if required, execution notes below.

- [ ] Produce a non-production rehearsal script/checklist using disposable fixtures for linked/unlinked cases,
  documents, failure/restart and audit verification; use no real applicant data and include restoration safeguards.
- [ ] Produce the API-before-Admin rollout order, worker activation prerequisites, pause/resume procedure,
  incomplete-request monitoring and owner handoff. No cancellation/unfencing after irreversible cleanup begins.
- [ ] Record which external checks remain outstanding; automated tests must keep completion/activation impossible
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

## Post-Completion

API deployed and compatible on every replica before Admin activation. Rehearsal/activation results are operator
evidence, recorded separately; never tick a missing external proof as a successful rehearsal. This feature does not
complete `retain-and-dispose.md` or erase copies in backups, delivered correspondence or the legacy source system.
