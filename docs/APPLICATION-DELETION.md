# Application deletion — ownership, contracts and cleanup order

Status: Tasks 1 to 9 of [the plan](plans/20261118-dm-application-deletion.md), 2026-11-18. This records what an
application owns, what the platform deletes for us and how it signals it, and the order cleanup must take, and
describes admission, the fence, the external and binary cleanup, local finalisation and the HTTP routes as built, and
the rehearsal, rollout and monitoring procedure (prepared, not run). Nothing here authorises deletion in any
environment.

## At a glance

- **Who.** Only a Delivery Manager: `DELETE_APPLICATION`, granted to `Role.DELIVERY_MANAGER` alone, whether from
  IDAM (`caseworker-deedpoll-delivery-manager`) or from AM (`ctsc-team-leader` with the Gazette skill). Team leader,
  legacy admin, caseworker and judicial roles are refused with 403 before anything is read.
- **Why.** Every request names one of two reasons, `CREATED_IN_ERROR` or `DUPLICATE`, and a mandatory free-text
  `note` (10–500 characters after trimming, no control characters). The note is stored on the request and on the
  `CASE_DELETION_REQUESTED` audit event and outlives the case for the audit trail's seven years, so it must say *why*
  and must not hold the applicant's personal details. It is never logged and never published by the status route. A
  duplicate needs no target, and deleting one never touches the application it duplicated.
- **What happens.** The outcome is **permanent deletion**, not a void, archive or withdrawal: the application, its
  children and outboxes, its filesystem binaries and owned staging, its CCD case and CDAM documents (through CCD
  Retain and Dispose for a linked case), the SDK's copy, and its audit *values* (through
  `deedpoll_audit_dispose_case_values`). The audit *events* stay, with `CASE_DELETED` recorded under the requesting
  Delivery Manager. There is no undo, no cancellation and no unfencing once a request is admitted.
- **Statuses.** `Pending` (admitted and fenced; nothing removed yet), `InProgress` (the worker holds or has held it:
  files being removed, or a linked case being offered to Retain and Dispose, or finalisation due), `AwaitingDisposal`
  (CCD has the case in `PendingDisposal` with its TTL set; CCD's disposer, nightly at 22:00, has not yet removed it),
  `NeedsAttention` (stopped with an allowlisted `failure` code; see *Failure and recovery*) and `Completed` (every
  in-scope copy removed and `CASE_DELETED` recorded). Only `Completed` means deleted; a 202 does not.
- **What refuses.** Gazette evidence, `retainPermanently`, retained deed or staged bytes, unresolved document storage,
  an unresolved CCD creation, an email send in flight, a stale confirmation (`expectedLastChangedOn` not the case's
  current `lastChangedOn`), and replays that disagree (`KEY_REUSED`, `CONFLICTING_REASON`). Each is a typed 409 that writes
  nothing (table under *Admission*).
- **What it does not do.** The reference counter is never decremented, and no application id or reference is reused.
  Email GOV.UK Notify already accepted is not recalled (new sends stop at the fence). Backups, Log and Audit's own
  record, the legacy Power App source and CDAM documents a caseworker had already soft-deleted are outside it (see
  *Separately retained data*). Financial records governed separately are not touched; the case's refusal rules decide.
- **History.** This supersedes the September 2026 planning restriction that reserved manual hard deletion for
  data-protection erasure requests (Steve, 2026-11-18); that decision is kept as history in the plans that record it.
  It introduces no routine retention disposal and does not change the unpaid-online seven-day policy.

## Provenance

| Source | Commit |
|---|---|
| deed-poll-api (this branch, `feat/dm-application-deletion`) | `18aaf0d` on `origin/master` `7fc5671` |
| ccd-config-generator / decentralised-runtime (pinned by `hmcts.ccd.sdk` 7.1.0) | tag `7.1.0` (`a90227f5`); jar `decentralised-runtime-7.1.0.jar` |
| ccd-case-disposer (workspace clone) | `f7e9bbd0791376a547717f513079a502047ad61b` |
| ccd-data-store-api (workspace clone) | `7d0306e77906cce75c33f572fe144409e7ef574c` |
| ccd-case-document-am-api (workspace clone) | `fa7710499f70080f64418e1a6cab374cbe627d68` |
| document-management-store-app (workspace clone) | `8b01cdd1c8bdbd7c4ff641a303a37487f5400d42` |
| deed-poll-admin | not cloned in this workspace; the plan's planning baseline is `17b28c7` |

The workspace clones are what was read; the deployed disposer, data-store and dm-store versions per environment are
not proven by this and must be checked during onboarding (below).

## The finding that shapes the design: the SDK already runs Retain and Dispose

The pinned SDK (decentralised-runtime 7.1.0) ships the decentralised half of Retain and Dispose in
`uk.gov.hmcts.ccd.sdk.retention`, off by default (`ccd.decentralised-runtime.retain-and-dispose.mode: off`, and the
auto-configuration needs exactly one `RetainAndDisposePolicy` bean). It is the supported "garbage-collection cron"
the CCD docs ask a decentralised service to build, so this service should implement a policy rather than a second
mechanism:

| SDK piece | What it does |
|---|---|
| `RetainAndDisposePolicy` (`uk.gov.hmcts.ccd.sdk`) | The service says which case types it governs (`caseTypes()`), which references are eligible now (`findCandidatesForDisposal()`), and what local data to delete (`dispose(reference)`). Constants: event `MarkForDisposal`, event `ConfirmDisposal`, state `PendingDisposal`. |
| `RetainAndDisposeTask` (cron, default `0 0 2 * * *` UTC; one pod at a time by a Postgres advisory lock) | 1. Resolves candidates still not in `PendingDisposal` with a null `resolved_ttl`, and applies a circuit breaker (default: abort the whole run if more than 5% of a case type/state population are candidates, once there are at least 10). 2. Fires `MarkForDisposal` for each and requires CCD to answer `PendingDisposal`. 3. For each local `PendingDisposal` case with a null `resolved_ttl`, reads it from CCD as the system user (a 404 here aborts the run as a permission fault) and fires `ConfirmDisposal`. 4. For each local `PendingDisposal` case whose `resolved_ttl < current_date`, reads it from CCD: still readable means retain; **404 means gone**, and then, in one transaction with `ccd.audit_disabled` set, it calls `policy.dispose(reference)` and deletes the local `ccd.case_data` row (exactly one row, or the transaction fails). |
| `CoreCaseDataRetainAndDisposeClient` | The system user's password grant (cached 30 minutes), `startEventForCaseWorker`/`submitEventForCaseWorker` with the start's own data, and `getCase`. Only `FeignException.NotFound` reads as absent; every other failure propagates and is logged, so nothing is deleted. |

Failures marking, confirming or deleting one case are logged and the next case is tried; nothing is retried within a
run, and the next run starts again from the database's state. The task records no outcome anywhere, so this
service's request status has to be derived from state it can read (below).

`ConfirmDisposal` is what sets the date. Data-store's `TimeToLiveService.updateCaseDetailsWithTTL` applies an event's
`TTLIncrement` to the submitted data (`SystemTTL = today + increment`, creating the `TTL` value with `Suspended = No`
if it is missing), but only when the case type has a field with id `TTL`. With an increment of 0, `SystemTTL` is
today. `CreateCaseEventService` computes `resolvedTTL` from that data (`OverrideTTL` beats `SystemTTL`, `Suspended =
Yes` gives null), sends it to `/ccd-persistence` as `DecentralisedCaseEvent.resolvedTtl` (the SDK writes it to our
`ccd.case_data.resolved_ttl`), and stores it on data-store's own pointer row through `SynchronisedCaseProcessor`.
Setting `SystemTTL` is never held by the TTL guard; only `Suspended`/`OverrideTTL` changes are.

## The Retain and Dispose contract, as ccd-case-disposer implements it

- **Selection.** `CaseDataRepository.findExpiredCases`: `resolvedTtl < CURRENT_DATE` and case type in the
  configured lists, newest TTL first. `DELETE_CASE_TYPES` (`deletable.case.types`) deletes; `SIMULATED_CASE_TYPES`
  (`simulated.case.types`) only logs; both default to empty, and a type in both aborts the run. The comparison is
  strict, so a `SystemTTL` of today is first eligible on the **next** day's run.
- **Schedule.** A Kubernetes CronJob, `schedule: "0 22 * * *"` (22:00 UTC) in `charts/ccd-case-disposer/values.yaml`,
  bounded by `CCD_DISPOSER_REQUEST_LIMIT` (default 1000) and `CCD_DISPOSER_CUT_OFF_TIME` (default 06:00).
- **Linked cases.** All-or-none across the `case_link` graph. Deed poll cases have no case links, so this does not bite.
- **What it deletes, by case reference** (`CaseDeletionService.deleteCase`, then `RemoteDisposeService` runs every
  `DisposeRemoteOperation` before the local rows go):
  - documents: `DisposeDocumentsRemoteOperation` POSTs `{caseRef}` to dm-store's `/documents/delete`, which finds
    every stored document with metadata `case_id = caseRef` **and `deleted = false`**, sets its `ttl` to now and
    soft-deletes it. dm-store's own TTL batch (`BatchConfiguration`, `toggle.ttl` / `ENABLE_TTL`) later hard-deletes
    documents whose `ttl` has passed. A non-2xx answer fails that case's deletion.
  - Elasticsearch, AM role assignments, WA tasks (optional, off by default), hearing recordings, by reference;
  - data-store's `case_event`, `case_event_significant_items`, `case_link` and `case_data` (the pointer) rows;
  - then a Log and Audit notification. A LAU failure is rethrown; any other failure is logged and that case is
    recorded as failed.
- **The "case gone" signal.** Once the pointer row is deleted, data-store answers `GET /cases/{reference}` with 404.
  The disposer sends no callback and `/ccd-persistence` has no DELETE, so this service has to ask. A 404 is also what
  data-store answers when the caller cannot read the case, which is why the SDK first proves the system user can read
  `PendingDisposal` cases (step 3 above) before it ever trusts a 404, and why `DEED_POLL_SYSTEM` must hold R on that
  state.

The stubbed proof is `src/test/java/uk/gov/hmcts/ccd/sdk/retention/RetainAndDisposeContractTest.java`. It drives the
SDK's own client over a real Feign `CoreCaseDataApi` against WireMock: both events are started and submitted as the
system user and must land in `PendingDisposal`; any other state is refused; 200 is present; **404 is gone**; 400, 401,
403, 409, 500, 502, 503 and a dropped connection are failures, never absence. Run it with
`./gradlew test --tests "uk.gov.hmcts.ccd.sdk.retention.RetainAndDisposeContractTest"` (container-free).

### Timing

With the SDK's default 02:00 cron: admitted on day D → marked and confirmed at 02:00 on D+1 (`SystemTTL` = D+1) →
first eligible at the disposer's 22:00 run on D+2 → local finalisation at 02:00 on D+3. Running the SDK task hourly
(`ccd.decentralised-runtime.retain-and-dispose.cron`) brings that to roughly D+1 22:00 for disposal and the next hour
for finalisation. The advisory lock already prevents overlapping runs. The SDK confirms with an increment of 0, so
the date cannot be brought earlier than today without leaving the supported path.

## Inventory: what one application owns

"Cascade" means an `ON DELETE CASCADE` foreign key to `deedpoll_deedpoll`; "restrict" means a plain foreign key that
refuses the delete; "none" means no foreign key, so a local delete leaves the row behind.

### This service's tables

| Table (migration) | Link to the case | Applicant data | On deletion |
|---|---|---|---|
| `deedpoll_deedpoll` (V2, V21 `ccdCaseReference`) | the case | yes | delete |
| `deedpoll_notes`, `deedpoll_witness` (V3) | cascade | yes | cascade |
| `deedpoll_supportingdocument` (V3, V25 storage state) | cascade | file names, `storageKey`, `cdamDocumentId`, transient hash token | cascade, **after** the binaries are dealt with (below) |
| `deedpoll_referral` (V6, V8, V14, V38, V41, V43, V46) | cascade | directions text | cascade |
| `deedpoll_notification` (V11, V13, V19, V28) | cascade (case and referral) | recipient, personalisation, rendered subject/body | cascade, after the outbox is quiesced |
| `deedpoll_ccdcreationattempt` (V22–V24) | cascade | none | cascade, after linking is resolved |
| `deedpoll_deedpreparation` (V26, V27, V30) | cascade, but V30's trigger refuses while its payload exists | file metadata | payload first, then cascade |
| `deedpoll_stagedpayload` (V26) | none (by `id` = a preparation's `documentId` or an owned stage's `id`) | file bytes | delete explicitly |
| `deedpoll_ownedstage` (V29) | none (`ownerKind = 'RECORD'` and `ownerId`, or `caseId`) | original file name, subject | delete explicitly (immutable-evidence trigger: needs a reviewed disposal path in Task 6) |
| `deedpoll_gazette_notice_batch_case` (V31), `deedpoll_gazette_request_case`, `deedpoll_gazette_reservation` (V34) | restrict | Gazette file values | **refuse**: any membership or request evidence keeps the case (existing guard) |
| `deedpoll_audit_event` (V36) | none (`case_id`) | none by design | **keep** (7 years); new `CASE_DELETED` with the original actor and reason |
| `deedpoll_audit_event_value` (V36) | via event `case_id` | before/after values | dispose by `deedpoll_audit_dispose_case_values(case_id)` |
| `deedpoll_master_email` (V44–V46) | none (`referral_ids uuid[]`, shared across cases) | none (ids only) | keep the row; a `Pending` row re-reads its referrals at send time and skips those no longer `Claimed`, so a deleted referral drops out; never delete another case's email |
| `deedpoll_referencecounter` (V3) | none | none | never decrement (plan decision 5) |
| `deedpoll_request_budget` (V37), saved queries (V5), settings, Masters, bank holidays | none | none | untouched |

There is no migration-mapping table: `migrated` is a column on the case, and the Power App source is out of scope
(Steve, 2026-11-18).

### The SDK's `ccd` schema (in this service's database, linked cases only)

`ccd.case_data` (keyed by the CCD `reference`; `data` holds `{}` for our cases, `resolved_ttl`), and everything that
cascades from it: `case_event` (each event's `data` snapshot: the record UUID, the display reference and the
supporting-document file names and URLs), `case_event_audit`, `case_event_significant_items`, `es_queue`,
`message_queue_candidates`, `task_outbox`/`task_outbox_history` and `audit_log` (V0026, via `case_event`); V0003
dropped `submitted_callback_queue`. `completed_events` holds idempotency UUIDs only. The SDK's step 4 deletes `ccd.case_data` and so
all of these; `policy.dispose` runs first in the same transaction. An unlinked case has no `ccd` rows.

### Binaries, by store — which is not the same as linkage

The store is chosen per deployment (`DOCUMENT_STORE`; the chart sets `cdam` in prod and `filesystem` elsewhere), and a
row's `storageState` says which store holds it.

- **CDAM (`storageState` `Uploaded`, `AttachUncertain`, `Attached`).** `CdamDocumentStore.save` starts with
  `DocumentStorageRule.requireCaseReady(ccdCaseReference)`, a `ConflictError` when the record has no CCD case, so
  **an unlinked case can hold no CDAM document** (confirmed). An `Attached` document carries dm-store metadata
  `case_id` and is soft-deleted by the disposer, then hard-deleted by dm-store's TTL batch. A document uploaded but
  never attached has no `case_id`; CDAM gave it a dm-store `ttl` of `DOCUMENT_TTL_IN_DAYS` (default 1) at upload, and
  the same TTL batch removes it.
- **Filesystem (`storageState` `Local`).** Non-production only (`DocumentStoreEnvironmentGuard`). Nothing on the
  platform knows these files exist, **linked or not**, so the worker must delete them itself and verify absence
  before finalisation. A linked case in AAT can hold filesystem documents.
- **Database payloads.** Deed preparation and staging bytes are `deedpoll_stagedpayload` rows, deleted locally.

### Work that can touch a case after admission, and what switches it

| Work | Trigger | Switch (default; chart) | What the fence must stop |
|---|---|---|---|
| Case outbox drain (`NotificationDeliveryTransactions`) | `GET /caseworker/cases` and each email send (`outbox.drain()`) | none; a template on `DEED_POLL_PLACEHOLDER` writes nothing sendable | claims of the case's `Pending` rows; a row already `sendStartedAt` is past recall |
| CCD linking sweep (`CcdLinkingSweep`) | `GET /caseworker/cases`, after the drain | `CCD_LINKING_SWEEP_ENABLED` (false; not set in the chart) | new claims; an in-flight attempt is resolved before admission |
| CCD repairs (`CcdAttemptRepairs`) | `/caseworker/ccd-attempts` (REPAIR_CCD) | none | retry/reconcile on a fenced case |
| CDAM upload and attach (`CdamDocumentUploads`), deed generation | the request that uploads or sends | `DOCUMENT_STORE` (`cdam` in prod, `filesystem` elsewhere) | new uploads, and `updateSupportingDocuments` events |
| Owned-staging cleanup (`StagingConfiguration`) | timer | `DOCUMENT_STAGING_ENABLED` (false) | none needed: it only expires `Staged` rows, which hold no case |
| Master emails drain and reminders (`MasterEmailDrain`, `MasterReminderTimer`) | timers, `PT1M`/`PT15M` | `MASTER_EMAILS_ENABLED` (true) | none needed: rows re-read their referrals at send time and drop any not `Claimed` |
| Referral unassignment (`ReferralUnassignment`) | timer, `PT15M` | `REFERRAL_UNASSIGN_ENABLED` (true) | its conditional `UPDATE` on a fenced case's referral |
| Gazette recovery (`GazetteRecovery`) | timer, every minute | none (independent of `GAZETTE_ENABLED`, false) | none: a case with Gazette evidence is refused at admission |
| SDK Retain and Dispose (`RetainAndDisposeTask`) | cron, `0 0 2 * * *` UTC | `CCD_DECENTRALISEDRUNTIME_RETAINANDDISPOSE_MODE` (off; not in the chart) | it is the deletion path itself |

## Cleanup order

1. **Admit** (one short transaction under the case lock): capability, eligibility guards, the request and its fence,
   the immutable inventory (document ids, storage keys, CDAM ids, the CCD reference), and `CASE_DELETION_REQUESTED`.
2. **Quiesce**: no new writes, sends, uploads, Gazette admission or linking for the fenced case; wait out any
   in-flight claim or lease (outbox, Master emails, uploads, creation attempts) or refuse admission with a typed 409.
   An unknown CCD creation outcome (`Claimed`/`AwaitingConfirmation`, or `submissionMayHaveReached`) is resolved by
   reconciliation before the case can be classed as unlinked.
3. **Local binaries and payloads**: filesystem files (verify each is absent), staged/preparation payloads.
4. **Linked case only — CCD Retain and Dispose**: the policy offers the reference; the SDK marks and confirms it
   (`AwaitingDisposal` once `resolved_ttl` is set); the disposer deletes the pointer and soft-deletes CDAM documents;
   the SDK sees 404 and calls `dispose`.
5. **Finalise** (one transaction): remaining local rows, `deedpoll_audit_dispose_case_values`, `CASE_DELETED` with the
   original Delivery Manager, reason and request id, request `Completed`. As built in Task 5, `policy.dispose` does
   not finalise: inside the SDK's transaction it records the CCD step (and the CDAM documents the disposer removed
   with it) `Done` and makes the request due, so the `ccd.case_data` delete and that record commit or fail together.
   Finalisation is then the worker's, for linked and unlinked cases alike, once every external step is `Done`
   (Task 6).

## Admission (Task 3)

`domain/deletion/ApplicationDeletionService.request` is step 1. Its order is fixed: `DELETE_APPLICATION` (403 before
anything is read), the caller's subject (401), the body (400 per field: `Idempotency-Key`, `reason`, `note`,
`expectedLastChangedOn`), the case's existing request (a replay, answered even once the case is gone), the case lock
(`DeletionAdmissionRepository.lock`, `FOR UPDATE`) and the existing request again under it, then 404, then the
refusals. Each refusal is a `DeletionConflictError` carrying a `DeletionRefusal` code, and writes nothing:

| Code | When |
|---|---|
| `STALE_CONFIRMATION` | the case's `lastChangedOn`, to the millisecond, is not the one the Delivery Manager reviewed: the later of its `modifiedOn` and its newest audited write (a note, witness, document, referral or email; reads excluded), read in its own statement after the case lock (`repository/CaseLastChanged`). A child write that commits after that read and before admission commits is not seen, because child writes do not lock the case. Nor is one in flight when the case was reviewed: its time is stamped before it commits, so if a newer write was already shown the maximum does not move (pods' clock skew does the same). Both windows are one child write's transaction; closing them needs a value every commit moves (a count of audited writes) or child writes that lock the case, and neither is done |
| `RETAINED_PERMANENTLY` | `retainPermanently` is set |
| `GAZETTE_EVIDENCE` | Gazette notice batch membership or request evidence |
| `RETAINED_DEED_PAYLOAD`, `RETAINED_STAGED_UPLOADS` | the existing retained-bytes guards |
| `DOCUMENT_STORAGE_UNRESOLVED` | a document `UploadPending`, `Uploaded`, `AttachUncertain`, or `UploadUncertain` with a send left |
| `CCD_CREATION_UNRESOLVED` | an attempt is `Claimed`/`AwaitingConfirmation` (linked or not: the binding commits before the attempt reads `Linked`), or an unlinked case's attempt may have reached CCD without proof nothing was created |
| `EMAIL_SEND_UNRESOLVED` | a `Pending` email with its send started, or under a live claim |
| `KEY_REUSED`, `CONFLICTING_REASON` | a replay: the same key with another body, or another key with the other reason |

An uncertain upload that has used both sends is settled (nothing resends it, and an unattached copy expires on CDAM's
upload TTL), as are `Local`, `Attached` and `Failed`. Admission then writes the request, its inventory
(`LOCAL_DOCUMENT` per filesystem document with its storage key, `CDAM_DOCUMENT` per attached document, `OWNED_STAGE`
per owned stage naming the case, `CCD_DISPOSAL` with the linked reference) and `CASE_DELETION_REQUESTED` under the
caller with the reason, note and request id and no values, in one transaction. A second key for the same reason
answers the first request with its first Delivery Manager and note. `DeedPollService.removeDeedPoll` refuses a case
with a request, and `RemoveDeedPollCallersTest` holds that no production class calls it.

## The fence (Task 4)

From the moment admission commits, a deletion request is a fence: the case and everything it owns read as gone to
every caller but the deletion itself, and nothing may write to them. Three layers, so that no single forgotten writer
is enough to escape it:

1. **Entity restrictions** (`entity/DeletionFenceSql`). `DeedPoll`, `Referral`, `DeedPollNote`, `DeedPollWitness`,
   `DeedPollSupportingDocument`, `Notification` and `CcdCreationAttempt` carry a Hibernate `@SQLRestriction` that
   excludes a row whose case a request names. Every JPA read (by id, `findByIdForUpdate`, entity graphs,
   specifications, counts, joins and the referral and judicial lists) therefore answers as for an unknown id: the case
   view, the list and its counts, saved queries, notes, witnesses, documents and their downloads and previews, the
   referral queue, the judicial lists and views, Master email referral lists, the outbox candidates and the CCD repair
   view. A 404 in an unknown case's words, so nothing is disclosed.
2. **Native statements say it themselves.** Management information reads `DeletionFenceSql.LIVE_CASES` in place of
   the case table, so a pending application is in no figure (and the figures do not move again on completion). The
   linking sweep's due list, the Master reminder claim and outstanding list, the overdue-assignment release and the
   Master linking/unlinking statements carry the predicate, so a background job neither selects nor writes a fenced
   row. A bulk statement that wrote one would be refused at commit by layer 3 and fail for every other case too.
3. **V49's deferred constraint triggers** refuse, at commit, any `INSERT` or `UPDATE` of the case row and of its notes,
   witnesses, documents, referrals, CCD creation attempts, deed preparation, owned stages and Gazette membership, and
   any `INSERT` of a notification, for a fenced case. `DELETE` is never refused: removing the case is the deletion's
   own work.

**Lock, then look.** A check in the statement that waits for admission's `FOR UPDATE` lock uses a snapshot taken
before the wait, so it cannot see the request; neither can an entity restriction. Every place that matters asks after
the lock is held, in a statement of its own: `DeletionFence.admits` (a `FOR KEY SHARE` on the case, then the request),
`DeedPollRepository.findUnfencedByIdForUpdate` (the patch path) and `lockUnfencedForDeedPreparation` (email send, deed
preparation and storage, owned staging admission and promotion). The triggers lock the case `FOR KEY SHARE` before they
look, for the same reason. Either the request committed before the writer held the lock, and the writer is refused, or
admission started later and waits, and then reads what the writer committed (an unsettled send or a document in flight
is then one of its own refusals).

### The last irreversible boundaries

| Boundary | Fence |
|---|---|
| A CCD case is created | `CcdCreationAttempts.claim` asks the fence under the record's lock, so no claim, no submission; `LinkDeedPollHandler` asks again under its lock, so a submission already in flight binds nothing and data-store's transaction rolls back. `CcdLinkingService.link` answers a fenced record `NO_SUCH_RECORD` before opening an attempt. A late callback after completion finds no record. |
| A CDAM upload or attach | The document row is inserted and committed before CDAM is called; V49 refuses that commit for a fenced case. A row committed first is `UploadPending`, which admission refuses. `SupportingDocumentsHandler` attaches nothing on a fenced record. |
| A Notify send | `NotificationDeliveryTransactions.claim` and `startSend` ask the fence under the case's lock, so a claim taken while admission was reading grants no send. A fenced row stays `Pending`, owned by nobody, and is neither settled nor noted (a settlement writes a case note, which the fence refuses); the deletion removes it. Admission refuses a send already started, so the service's unknown-send handling is untouched and nothing is ever resent. An email Notify already accepted is not recalled. |
| A Master email | Its content names no applicant. At send time the referral list is re-read through the restricted `Referral` entity, and the reminder claim and outstanding list carry the predicate, so a fenced referral drops out and an email with none left is `Skipped`. Other cases' referrals on the same email are unaffected. |
| A Gazette request | Admission refuses any case with Gazette evidence; a new admission reads cases through the restriction and V49 refuses the membership rows. |

### The deliberate exceptions

- **CCD's own view** (`DeedPollCaseView`, through the native `findCcdCaseProjection`). The SDK's Retain and Dispose
  reads a fenced case through data-store to mark and confirm it, so the view must still answer. It shows the record
  id and display reference, and no documents (the supporting document entity is restricted). Staff reach documents
  only through this service, which is fenced; data-store access is CCD's own (`DEED_POLL_READER`). Task 5's definition
  should give `DEED_POLL_READER` nothing on `PendingDisposal`.
- **CCD binding reads** (`findCcdCaseReference`, `findCcdCaseProjectionById`, `repairIfUnbound`) are native: a
  binding is a fact about CCD, and through the restriction a fenced, linked record would read as unbound.
- **The deletion's own reads and writes**: the request, its inventory and status, and (Tasks 5 and 6) the worker and
  finalisation. These must read the case natively and remove it with `DELETE` only; an `UPDATE` of the case or a child
  is refused by V49. The SDK events Task 5 adds must not write the case row.

### Disabling and resuming

The fence has no switch. With every worker disabled a fenced case stays stored, invisible and unwritable until the
deletion completes; there is no unfencing, by design.

## Definition change (designed here, built in Task 5)

Add to `DeedPollCaseType`, following the SDK's own e2e `SimpleCaseConfiguration` and its `retain-and-dispose.md`:

- **The platform `TTL` field.** On `DeedPollCaseData`: `@JsonProperty("TTL") @CCD(label = "Time to live",
  typeOverride = FieldType.TTL, access = TtlAccess.class) private TTL timeToLive;` — the id must be exactly `TTL`
  (data-store's `TTL.TTL_CASE_FIELD_ID`), and its sub-fields are referenced, not redefined. `TtlAccess` grants CRU to
  `DEED_POLL_SYSTEM` only. `DEED_POLL_READER` gets nothing, and no human role touches it: there is no
  `TTL_profile`/`idam:cft-ttl-manager` mapping, because nothing here suspends or overrides.
- **State `PendingDisposal`** (`DeedPollCaseState`), with `builder.grant(PendingDisposal, CRU, DEED_POLL_SYSTEM)`
  stated explicitly for the reason the existing `Registered` grant is: the SDK derives no grant for a destination
  state, and without R the SDK's visibility check aborts every run. Task 4 decided `DEED_POLL_READER` gets nothing on
  it: a fenced case is not for staff to read, through this service or through CCD.
- **Event `MarkForDisposal`**: `Registered → PendingDisposal`, CRU to `DEED_POLL_SYSTEM`, no fields. Its decentralised
  submit handler (`MANDATORY`, like `LinkDeedPollHandler`) locks the record and refuses unless the record has an
  admitted deletion request ready for CCD disposal, so a stray policy result or a hand-fired event cannot start a
  disposal.
- **Event `ConfirmDisposal`**: `PendingDisposal → PendingDisposal`, `.ttlIncrement(0)`, CRU to `DEED_POLL_SYSTEM`, no
  fields. Its handler records that the date was confirmed; the SDK persists the resolved TTL itself.
- `updateSupportingDocuments` stays `forState(Registered)`, so no document event can follow `MarkForDisposal`.
- `DeedPollCaseView` does not need to project `TTL`: nothing reads it back, data-store creates it on
  `ConfirmDisposal` if missing, and no event follows in `PendingDisposal`. `DeedPollCaseViewTest` should pin that.
- **Pinned JSON.** `src/test/resources/ccd-definition/DEED_POLL_PLACEHOLDER/` gains the field in `CaseField.json`,
  its grant in `AuthorisationCaseField/DEED_POLL_SYSTEM.json`, the state in `State.json` and
  `AuthorisationCaseState.json`, two `CaseEvent/*.json` and `CaseEventToFields` files, and their
  `AuthorisationCaseEvent` rows, all regenerated by `./gradlew generateCCDConfig` and reviewed line by line before
  `DeedPollDefinitionTest` is allowed to pass. `DEED_POLL_SYSTEM` gains R on a new state and CR(U) on two events,
  which is the deliberate growth that test exists to make visible.
- **Policy** (`RetainAndDisposePolicy` bean): `caseTypes()` is `{CcdProperties.caseTypeId()}`;
  `findCandidatesForDisposal()` is the CCD references of requests that have reached step 4; `dispose(reference)` is
  step 5. Configuration: `CCD_DECENTRALISEDRUNTIME_RETAINANDDISPOSE_MODE` (default `off`; `dry-run`, then `live`),
  the cron, and the existing `idam.system-user` credentials. Leave the 5%/10 circuit breaker at its defaults: ten
  or more deletions in one run should stop and be looked at. Task 5 checks the auto-configuration's bean inputs
  (`IdamClient`, `AuthTokenGenerator`, `JdbcClient`, `TransactionOperations`) exist here. Spring scheduling already
  is enabled.

### Status, derived rather than reported

The SDK writes no outcome, so the request's status comes from what this service can read in its own database:
`AwaitingDisposal` once `ccd.case_data.state = 'PendingDisposal'` and `resolved_ttl` is set; `Completed` only from
finalisation. A request in step 4 whose case is still `Registered` (or `PendingDisposal` with a null TTL) after an
agreed number of runs becomes `NeedsAttention`, because the SDK only logs a failed mark, a failed confirm or a
tripped circuit breaker. **Until onboarding, every linked request correctly stays `InProgress` or
`AwaitingDisposal`**: with the mode `off` nothing is marked, and with the disposer not configured nothing is
removed, so nothing reaches 404.

## External and binary cleanup (Task 5)

Built as designed above, with one change: finalisation is not inside `dispose` (see the cleanup order).

**Definition.** `DeedPollCaseData.timeToLive` is the platform `TTL` field (`TtlAccess`: CRU to `DEED_POLL_SYSTEM`
only); `DeedPollCaseState.PendingDisposal` is granted CRU to `DEED_POLL_SYSTEM` and nothing to `DEED_POLL_READER`;
`MarkForDisposal` (`Registered → PendingDisposal`) and `ConfirmDisposal` (`PendingDisposal → PendingDisposal`,
`TTLIncrement` 0) are CRU to `DEED_POLL_SYSTEM` and carry no fields. `updateSupportingDocuments` now pins its
pre-state as `Registered` (it was `*` while there was one state). All of it is in the pinned JSON and named in
`DeedPollDefinitionTest`.

**`DisposalEventsHandler`** (both events, `MANDATORY`) admits an event only when the case's request is ready for CCD,
under the request's lock: status `InProgress`/`AwaitingDisposal`, its CCD step `Pending`, and every `LOCAL_DOCUMENT`
line `Done` (`DeletionWorkRepository.READY_FOR_CCD`). Anything else is a submit error. Neither writes the record.

**`DeedPollRetainAndDisposePolicy`**: `caseTypes()` is the configured case type; `findCandidatesForDisposal()` is the
same predicate; `dispose(reference)` records `CCD_DISPOSAL` and `CDAM_DOCUMENT` lines `Done` with outcome
`DISPOSED_BY_CCD`, sets the request `InProgress` and due now, and throws (rolling the SDK's delete back) for a case no
request is waiting on. Configuration: `ccd.decentralised-runtime.retain-and-dispose.mode` (default `off`), `.cron`
(default `0 0 2 * * *`) and `.system-user` (the existing `DEED_POLL_SYSTEM_USERNAME`/`_PASSWORD`).
`RetainAndDisposeDeletionTest` builds the SDK's task from its own auto-configuration over this application's beans
(`mode=live` in a child context), so the bean inputs are proven to exist.

**`DeletionCleanup`**, the worker (`deedpoll.deletion.cleanup.*`, `enabled` default false; `DeletionCleanupTimer`
exists only when it is true). A run claims up to `batch-size` due requests one at a time (`FOR UPDATE SKIP LOCKED`,
a fresh owner and a `lease` each; a lapsed lease is taken over), moving `Pending` to `InProgress`. Every later write
is its own short transaction that first locks the request while the claim is still the worker's, so a worker that
lost its lease writes and removes nothing. No transaction is open during a file removal, and the worker never calls
CCD, CDAM or IDAM. For each `LOCAL_DOCUMENT` line it calls `LocalFilesystemDocumentStore.remove`, which deletes and
then checks the file is gone, and treats a missing **root** as unavailable, never as already gone. The status then
comes from `DeletionProgress.decide`, which has no database in it:

| Situation | Status | Failure code |
|---|---|---|
| a filesystem document not yet verified removed, tries left | `InProgress`, retried after `retry-base` doubled per try, capped at `poll-interval` | |
| … after `max-attempts` tries | `NeedsAttention` | `LOCAL_DOCUMENT_UNREMOVED` |
| no filesystem store in this deployment (`DOCUMENT_STORE=cdam`) | `NeedsAttention` | `LOCAL_STORE_UNAVAILABLE` |
| a storage key outside the store's root (line `Failed`, nothing touched) | `NeedsAttention` | `LOCAL_DOCUMENT_INVALID_KEY` |
| linked, files done, case not yet marked and confirmed | `InProgress` (offered to the SDK) | |
| linked, `PendingDisposal` with `resolved_ttl` set | `AwaitingDisposal` | |
| linked, CCD step `Pending` but the local `ccd.case_data` row is gone | `NeedsAttention` | `CCD_CASE_MISSING` |
| CDAM lines with no CCD step | `NeedsAttention` | `CDAM_WITHOUT_CCD_CASE` |
| any other `Failed` line | `NeedsAttention` | `CLEANUP_STEP_FAILED` |
| every external line `Done` | finalised in the same pass: `Completed` (below) | |

Step outcomes are `DELETED`, `ALREADY_ABSENT` (the store was there and the file was not: an earlier, unrecorded try),
`DISPOSED_BY_CCD`, `REMOVAL_FAILED`, `STORE_UNAVAILABLE` and `INVALID_STORAGE_KEY`. A `NeedsAttention` request leaves
the worker's due set with every unverified locator still held; putting it back is an operator step (Task 9). Logs
carry the request id and fixed prose, never a locator. Ordinary caseworker document deletion is unchanged and still
soft in CDAM.

**Not in the chart.** The worker and the SDK task are both off in `application.yaml`, and no chart value sets either,
so the chart is unchanged. Turning either on is a per-environment flux change after Task 9's evidence. With both off,
an admitted request stays `Pending`: fenced, stored and recoverable.

**Proof.** `DeletionCleanupTest` (Postgres, 14): unlinked files removed and verified, already-absent files, an unmounted
root retried then `NeedsAttention` with the key held, no filesystem store, an escaping key, two workers claiming at
once, a lapsed lease taken over (the first worker then writes nothing), a restart after an unrecorded removal, a done
line never redone, the linked journey to `AwaitingDisposal` and `dispose`, a stranger's `dispose` rolled back, a
vanished CCD copy, a stuck file keeping a linked case from CCD, run bounds and the timer's default.
`RetainAndDisposeDeletionTest` (Postgres, 11): the SDK's real task marks, confirms, retains while data-store still
answers, and on 404 deletes its copy and calls `dispose`; 401, 403, 500, 502, 503 and a timeout on that read keep
everything; a 404 on the confirmation's visibility check aborts the run; a mark whose answer is lost before
data-store commits is marked again, and one lost after is confirmed and never marked twice; a request whose files are
not removed is never offered, and a stray event is refused. Plus `DeletionProgressTest`, `DisposalEventsHandlerTest`
and `LocalFilesystemDocumentStoreTest`'s removal cases.

## Local finalisation (Task 6)

`domain/deletion/DeletionFinalisation`, called by the worker in the same pass once `DeletionProgress` says every
external line is `Done`, in **one owned transaction** (the claim's lock is held and the lease checked first, and it is
`MANDATORY`). Its statements are `repository/DeletionFinalisationRepository`'s, plain JDBC by `DELETE` only. In order:

1. Asks again under the lock: every non-owned-stage line `Done` (else `CLEANUP_STEP_FAILED`), and for a linked case no
   `ccd.case_data` row for the reference (else `CCD_COPY_PRESENT`: the CCD step can only be `Done` through the SDK's
   own delete, so a copy still there means a hand edit or a bug).
2. Locks the case `FOR UPDATE` and refuses retained deed or staged bytes (`RETAINED_PAYLOAD`; admission refused them
   and V49 refuses new ones, but it is asked, not assumed).
3. Deletes each listed owned stage (V29 guards only `UPDATE`) and records its line `DELETED` or `ALREADY_ABSENT`; an
   owned stage naming the case that the inventory did not list is `UNLISTED_OWNED_STAGE`.
4. Deletes the case row; notes, witnesses, documents, referrals, notifications, CCD creation attempts and the deed
   preparation go by their cascades. Gazette evidence restricts and a retained deed payload's trigger refuses, either
   of which is `LOCAL_FINALISATION_REFUSED`.
5. `deedpoll_audit_dispose_case_values(case_id)`: every value row of the case's events, and no other case's.
6. `CASE_DELETED` under the **original Delivery Manager** (the request's subject and name, never the worker), with
   `deletionReason` and `deletionRequestId`, no note (it is on `CASE_DELETION_REQUESTED`) and no values.
7. The tombstone: every line keeps kind, state, outcome and completion time and loses its locator and item id; the
   request becomes `Completed` with `completed_at`, no claim and no failure, after which V48's trigger refuses any
   change to it.

A named refusal, or the database refusing a statement (`DataIntegrityViolationException`), rolls all of it back and
releases the request `NeedsAttention` with that code. Any other failure (an outage, the audit insert failing) rolls it
back and leaves the request `InProgress`, due again after `poll-interval`. The external lines stay `Done` either way,
so a retry resumes at finalisation without repeating a removal. A worker whose lease lapsed fails its lease check and
writes nothing; a `Completed` request is outside the set a claim can hold, so there is exactly one `CASE_DELETED`.
A replay of the admission's key afterwards answers the tombstone. Untouched: every audit event, the Master email ledger
(ids only, shared across cases; a pending row drops a referral that no longer exists), the reference counter, and
other cases. There is no migration: the function's `EXECUTE` is the api login's as owner.

**Proof.** `DeletionFinalisationTest` (Postgres, 7): a populated, migrated, unlinked case (note, witness, document and
file, referral, email, creation attempt, owned stage, audit value) removed whole with its events kept, one
`CASE_DELETED` attributed to the Delivery Manager, another case's values, note, referral and the shared Master email
untouched, counters unchanged and the tombstone holding outcome codes only; a linked case completing only after
`dispose`, with the SDK's copy gone; a present SDK copy refused with nothing removed; an audit failure rolled back and
retried to one completion without redoing a removal; a database refusal `NeedsAttention`; a lapsed lease finalising
nothing; and a replay after completion. `DeletionCleanupTest` and `RetainAndDisposeDeletionTest`'s journeys now end
`Completed`.

## HTTP contract (Task 7)

`CaseworkerApplicationDeletionController`, two operations counted apart from the caseworker surface
(`OpenAPIPublisherTest.DELETION_PATHS`). Both check `DELETE_APPLICATION` (Delivery Manager only, IDAM- or AM-derived)
**before** the path id, the header or the body is read, so every other role is 403 whatever it sent; the header and
the status id are bound as text for that reason. Every success and every typed 409 is `Cache-Control: no-store`.

| Method | Path | Answer |
|---|---|---|
| POST | `/caseworker/cases/{id}/deletion-requests` | 202 `{requestId, status}` with `Location: /caseworker/application-deletions/{requestId}`, for a fresh admission and for every replay. `@AuditedAs(CASE_DELETION_REQUESTED)` |
| GET | `/caseworker/application-deletions/{requestId}` | 200 `{requestId, caseId, reason, status, requestedAt, completedAt, failure}`, for any Delivery Manager, whether or not the case still exists |

- **Body**: closed, exactly `reason` (`CREATED_IN_ERROR` or `DUPLICATE`), `note` (10–500 after trimming, no control
  characters) and `expectedLastChangedOn` (the case detail's `lastChangedOn` as published, ISO-8601 local date-time, to the
  millisecond). Any other key is a 400 that does not echo it (`DeletionBodies`); a value of the wrong JSON type is
  refused as missing, with the field named. No body is three field errors.
- **`Idempotency-Key`**: required canonical UUID; missing or malformed is a 400 on that header. The same key and body
  is the same request (202, also after the case is gone); the same key with another body is 409 `KEY_REUSED`; another
  key for the same reason answers the first request, keeping its Delivery Manager and note; for the other reason it is
  409 `CONFLICTING_REASON`.
- **409** is `DeletionError {error, code}`, `code` a `DeletionRefusal` member, and writes nothing.
- **404**: an unknown case with no request; an unknown or non-UUID request id, in the same words.
- **413/415**: a body over 16 KB is 413. A body that is not `application/json` or `application/*+json` is 415, and so is
  a `multipart/*` or urlencoded form body, refused before the stream is read so a form is never answered as three
  field 400s. Both write nothing.
- **Nothing private reaches the wire.** The status publishes no note, requester, key, fingerprint, claim, CCD
  reference or locator, and `failure` is a `DeletionFailure` member only when `NeedsAttention`. The note is in no log
  line (`CreateDeletionRequest.toString` prints keys only). 202 is never "deleted": only `Completed` is, and polling
  starts no work.
- The deletion DTOs' `requestId` is the request's resource id, so `NotificationSecurityTest`'s rule against publishing a
  `requestId` (the email send's key) names those two classes as its only exception.

**Proof.** `CaseworkerApplicationDeletionControllerTest` (full context, Postgres, 13): 401 on both routes; each of
five non-DM roles 403 on both routes with valid, forged, keyless and malformed input, before lookup; 202 with
`Location`, `no-store` and exactly two fields, round-tripping `lastChangedOn` from the case detail; the status field set,
with no note, subject or key; every 400; the 404s; the typed stale 409; replays, `KEY_REUSED`, `CONFLICTING_REASON`
and a 202 replay after the case row is deleted; a second Delivery Manager reading the first's request; and the note in
no log output. `OpenAPIPublisherTest.documentsTheApplicationDeletion` pins the published contract.

## Failure and recovery

Nothing in this service turns an incomplete request into a completed one, and nothing deletes on a guess: an outage,
an auth failure or an ambiguous answer is never read as absence. What an operator sees, and does:

| Situation | Meaning | Operator action |
|---|---|---|
| `Pending` for longer than one worker interval | The worker is off (`APPLICATION_DELETION_WORKER_ENABLED`, default false) or failing to claim | Check the switch and the worker's WARN lines; the case stays fenced meanwhile |
| `InProgress` on a linked case for more than a day | The SDK's task is off (`CCD_DECENTRALISEDRUNTIME_RETAINANDDISPOSE_MODE`), its mark or confirm failed, or its circuit breaker tripped (it only logs) | Read the SDK task's log for the reference's run; fix the cause; the next run retries from the database's state |
| `AwaitingDisposal` for more than two nights | DEEDPOLL not in the disposer's `DELETE_CASE_TYPES`, the disposer failed, or CCD still answers the case | Check the disposer's run and its type lists (onboarding step 3); never complete the request by hand |
| `NeedsAttention` `LOCAL_STORE_UNAVAILABLE`, `LOCAL_DOCUMENT_UNREMOVED` | The filesystem store is absent from this deployment or the file would not go after `max-attempts` tries | Restore the volume or remove the file, then requeue (below) |
| `NeedsAttention` `LOCAL_DOCUMENT_INVALID_KEY` | A stored key escapes the store's root; nothing was touched | Investigate the row; this is a data fault, not something to requeue blindly |
| `NeedsAttention` `CCD_CASE_MISSING`, `CDAM_WITHOUT_CCD_CASE`, `CCD_COPY_PRESENT` | The SDK's copy and the request's inventory disagree | Establish from data-store whether the CCD case exists; escalate before any action |
| `NeedsAttention` `RETAINED_PAYLOAD`, `LOCAL_FINALISATION_REFUSED` | Finalisation was refused and rolled back, external steps kept `Done` | Resolve the named obstacle, then requeue; finalisation resumes without repeating a removal |
| `NeedsAttention` `UNLISTED_OWNED_STAGE`, `CLEANUP_STEP_FAILED` | Something reached the case that the fence or the inventory did not account for, or a line failed for a reason the worker does not retry | Escalate to the service team; not requeueable by the standard statement |

**Requeueing** is a reviewed operator statement, not an endpoint: there is deliberately no browser retry. The
statement is [`docs/deletion/requeue.sql`](deletion/requeue.sql), one atomic statement run with psql's
`\set request_id '…'` and `\i`: it moves one `NeedsAttention` request whose code is `LOCAL_STORE_UNAVAILABLE`,
`LOCAL_DOCUMENT_UNREMOVED`, `RETAINED_PAYLOAD` or `LOCAL_FINALISATION_REFUSED` back to `InProgress`, due at once, with
`failure` null, and its `Pending` filesystem lines' `attempts` back to 0, and answers `requests_requeued` (expect 1;
0 means stop). Every other code is an escalation and the statement leaves it alone. A `Failed` step goes back to
`Pending` only by a separately reviewed statement once its cause is understood. V48's trigger refuses any change to the
admission half (case, key, reason, note, actor, fingerprint, confirmation) and any change to a `Completed` request, so
a requeue cannot rewrite who asked or why, or reopen a completed deletion. `DeletionCleanupTest.requeue` and
`requeueRefusesEscalations` run that file verbatim against Postgres: a request stuck on an unmounted store is requeued,
then completed by the next run once the store is back; a second requeue, and one after completion, change nothing; an
invalid-key escalation is untouched. Pausing is
switching the worker and the SDK task off: every admitted request stays fenced, stored and resumable, and a lapsed claim
is taken over when the worker is on again.

## Rehearsal, rollout and monitoring (Task 9)

Prepared for review, **not run**. No environment has admitted, cleaned or completed a deletion. Every result below is
operator evidence, recorded when it happens beside the plan's execution notes, and a step that was not run is
recorded as not run, never ticked.

### What keeps deletion switched off

Two switches, both off unless an environment's flux sets them, and no chart values file may set either
(`DeploymentChartConformanceTest.noValuesFileActivatesApplicationDeletion` over `values.yaml`, the AAT and the preview
templates, and `applicationDeletionDefaultsOff` over `application.yaml`'s defaults):

| Switch | Default | What it alone allows |
|---|---|---|
| `APPLICATION_DELETION_WORKER_ENABLED` | `false` (`DeletionCleanupTimer` is not created: `DeletionCleanupTimerTest`) | Filesystem removal and local finalisation. With it off nothing is removed and no request completes. |
| `CCD_DECENTRALISEDRUNTIME_RETAINANDDISPOSE_MODE` | `off` | The SDK's mark/confirm/dispose for linked cases. With it off a linked request never leaves `InProgress`, because only `dispose` can record the CCD line `Done`. |

So an unlinked request needs the worker on to complete, and a linked one needs the worker on, the SDK task `live`,
**and** the disposer deleting DEEDPOLL (its `DELETE_CASE_TYPES`, which this repository does not control). `dry-run`
marks nothing and simulation deletes nothing, so in either a linked request stays `InProgress`/`AwaitingDisposal`.
Admission itself has no switch: the routes exist once deployed, gated by `DELETE_APPLICATION`, and Admin's control is
what exposes them to a user. A preview pod runs against AAT's database, which is a further reason neither switch may
be in a chart file.

The worker's other settings, under `deedpoll.deletion.cleanup` (`DeletionCleanupProperties` refuses a duration that
is not positive, a batch size outside 1–100 and fewer than one attempt at start-up). Production values need release
agreement:

| Variable | Default | What it is |
|---|---|---|
| `APPLICATION_DELETION_WORKER_INTERVAL` | `PT5M` | The timer's delay between runs |
| `APPLICATION_DELETION_WORKER_BATCH_SIZE` | `10` | The most requests one run claims |
| `APPLICATION_DELETION_WORKER_LEASE` | `PT5M` | How long a claim lasts before another pod may take the request over |
| `APPLICATION_DELETION_WORKER_MAX_ATTEMPTS` | `8` | Tries a filesystem document gets before the request needs attention |
| `APPLICATION_DELETION_WORKER_RETRY_BASE` | `PT1M` | The first retry's delay, doubled per try |
| `APPLICATION_DELETION_WORKER_POLL_INTERVAL` | `PT1H` | How often a request waiting on CCD or finalisation is looked at; also the retry cap |
| `CCD_DECENTRALISEDRUNTIME_RETAINANDDISPOSE_CRON` | `0 0 2 * * *` (UTC) | When the SDK's Retain and Dispose task runs |

### Rollout order

1. **API merge and deploy** to every environment with both switches off. Check every replica runs the release (V48
   and V49 applied; readiness lists `deedpoll_deletion_request` and `deedpoll_deletion_step`). From here a Delivery
   Manager could admit a request through the API, and it would wait `Pending`, fenced; nothing is removed.
2. **Definition import** (onboarding step 1) to AAT, then to each environment ahead of its step 5.
3. **AAT rehearsal**, unlinked first, below (R1–R6), with the worker switched on for AAT only.
4. **LAU onboarding and simulation** (onboarding steps 2–4); the linked rehearsal (R7) in simulation, then after the
   recorded move to `DELETE_CASE_TYPES`.
5. **Admin** merges and deploys its control only after step 1 is on every replica in the target environment, by its
   own plan. Admin must never be ahead of the API: its client would meet a 404 route.
6. **Per-environment activation**, AAT, then demo/ithc as wanted, then prod: in cnp-flux-config set
   `APPLICATION_DELETION_WORKER_ENABLED=true`, then `CCD_DECENTRALISEDRUNTIME_RETAINANDDISPOSE_MODE=dry-run`, read one
   run's log, then `live` — each a separate PR, each only after the previous one's evidence is recorded. Prod
   additionally needs DEEDPOLL in prod's disposer `DELETE_CASE_TYPES` and the DPIA/OWA update (onboarding step 6).

Prerequisites for switching the worker on in an environment: step 1 deployed there; the system user's secrets mounted
(they already are, for linking); for `DOCUMENT_STORE=filesystem` environments the volume mounted on every replica the
worker can run on; and somebody named to watch the monitoring below for the first week.

### Rehearsal checklist (AAT, disposable fixtures only)

Safeguards, before anything else:
- **No real applicant data.** Every fixture is created for the rehearsal through the caseworker API by the rehearser,
  with an obviously synthetic name (forename `Rehearsal`, surname `Deletion-<date>-<n>`) and synthetic documents (a
  one-page PDF that says "rehearsal fixture"). Record each fixture's case id **before** admission. Never admit a
  deletion for an id not on that list; a second person reads the id back before each POST.
- **Users.** A Delivery Manager and one user for each refused role, created by `/cft-create-test-user` with AAT's
  usual 3-hour lifetime. No personal account.
- **Restoration.** Note the time and confirm AAT's database has a point-in-time restore window covering it before R2.
  A restore would resurrect deleted fixtures (see "Separately retained data"); after any restore, re-check every
  `Completed` request's case id is absent and delete by the same procedure if not. Nothing in the rehearsal edits a
  table by hand except the requeue statement in R5.

| Step | Do | Expect and record |
|---|---|---|
| R1 fixtures | Create: **U** unlinked with two filesystem documents; **D1/D2** a duplicate pair; **K** with `retainPermanently` set; **L** linked (linking sweep as `docs/CCD-SMOKE.md`) with one document | Case ids, `lastChangedOn`, U's two `storageKey`s and L's CCD reference |
| R2 refusals | POST for U as each non-DM role; as DM with a stale `expectedLastChangedOn`; as DM for K | 403 ×N with nothing written; 409 `STALE_CONFIRMATION`; 409 `RETAINED_PERMANENTLY`; no `deedpoll_deletion_request` row for U or K |
| R3 admission, worker off | POST for U and D2 (`DUPLICATE`) as DM; repeat U's POST with the same key; same key with a different note; GET both statuses | 202 with Location; replay 202 with the same `requestId`; 409 `KEY_REUSED`; both `Pending` after more than one worker interval; U and D2 404 on the case routes and absent from the list and MI; D1 untouched |
| R4 unlinked completion | flux: `APPLICATION_DELETION_WORKER_ENABLED=true` for AAT; wait one interval | U and D2 `Completed`; U's two files absent on the volume; queries below show the case gone, two events with the DM's subject, zero value rows; D1 still served |
| R5 pause and restart | Admit a fresh unlinked fixture with the worker off; switch on and `kubectl rollout restart` the deployment during its first interval | One `Completed`, exactly one `CASE_DELETED` (a lapsed lease is taken over, never doubled). Requeue (`docs/deletion/requeue.sql`) is rehearsed live only if a request genuinely reaches a requeueable `NeedsAttention`; it is not induced on shared AAT storage. Its proof otherwise is `DeletionCleanupTest.requeue`, recorded as local-only |
| R6 audit | The audit queries below for U and D2 | `CASE_DELETION_REQUESTED` then `CASE_DELETED`, actor the DM, `detail` with `deletionReason`/`deletionRequestId` (and the note on the request event only); no `deedpoll_audit_event_value` rows for either case |
| R7 linked, after onboarding steps 1–4 | Admit L; SDK mode `dry-run` for one run, then `live`; with DEEDPOLL in `SIMULATED_CASE_TYPES` wait two nights; after the recorded move to `DELETE_CASE_TYPES` wait for the next disposer run and the next SDK run | `dry-run`: still `InProgress`, nothing marked. `live` + simulation: `AwaitingDisposal`, CCD still answers L (**never completed**). L's filesystem document removed before the case is offered to the SDK (files before CCD). After deletion: data-store answers 404 for L's reference, LAU shows the deletion, then `Completed` with the same audit shape. AAT stores documents on the filesystem, so the disposer's CDAM document removal is not rehearsed here; locally it is proven only against WireMock (`RetainAndDisposeDeletionTest`), and it is an outstanding check |

Queries for R4–R6 (read-only, run as the api's database login; substitute the fixture's case id):

```sql
SELECT count(*) FROM deedpoll_deedpoll WHERE id = :'case_id';                         -- 0
SELECT status, failure, completed_at FROM deedpoll_deletion_request WHERE case_id = :'case_id';
SELECT kind, state, outcome, locator IS NULL AS locator_erased FROM deedpoll_deletion_step s
  JOIN deedpoll_deletion_request r ON r.id = s.request_id WHERE r.case_id = :'case_id';   -- all Done, erased
SELECT action, actor_id, detail ? 'deletionNote' AS has_note, detail->>'deletionReason' AS reason
  FROM deedpoll_audit_event WHERE case_id = :'case_id' AND action LIKE 'CASE_DELET%' ORDER BY id;
SELECT count(*) FROM deedpoll_audit_event_value v JOIN deedpoll_audit_event e ON e.id = v.event_id
 WHERE e.case_id = :'case_id';                                                          -- 0
```

### Pause and resume

**Pause** is switching the worker off, and for linked cases the SDK task to `off` (two flux PRs or one). Admission keeps
working; every admitted request stays fenced, stored and resumable. A request that was mid-run keeps its claim until
its lease lapses and is taken over on resume; a removal already made is recognised as `ALREADY_ABSENT` and never
counted as a failure. The disposer is LAU's: pausing it is a request to LAU (remove DEEDPOLL from `DELETE_CASE_TYPES`),
and a case it has already deleted cannot be brought back. **Resume** is switching them on again, in the order of
rollout step 6. **There is no cancellation and no unfencing**, paused or not: once admitted a request only moves
forward, and a mistaken admission is handled as a data-protection incident with the restore safeguards above, not by
editing the request.

### Monitoring incomplete requests

Run daily for the first week after activation in an environment, then weekly; any row is followed up by the
"Failure and recovery" table:

```sql
SELECT id, status, failure, requested_at, next_attempt_at, attempts
  FROM deedpoll_deletion_request
 WHERE status <> 'Completed'
   AND (status = 'NeedsAttention'
        OR (status = 'Pending' AND requested_at < (now() AT TIME ZONE 'UTC') - interval '1 hour')
        OR (status = 'InProgress' AND requested_at < (now() AT TIME ZONE 'UTC') - interval '1 day')
        OR (status = 'AwaitingDisposal' AND requested_at < (now() AT TIME ZONE 'UTC') - interval '3 days'))
 ORDER BY requested_at;
```

Times are UTC (`AuditClock`). The worker logs a fixed WARN with the request id and its failure code when a request
needs attention; there is no alert wired to it yet, which is an outstanding check below.

### Owners and handoff

| Area | Owner |
|---|---|
| The API, the switches in flux, monitoring and requeue | Deed poll service team (Steve to name the on-call engineer) |
| The definition import, the SDK task | Deed poll service team |
| The disposer, its type lists, LAU's deletion record | LAU / CCD platform, via the LAU PET ticket |
| dm-store `ENABLE_TTL`, CDAM residue | dm-store team |
| Database backups and restore | Platform operations |
| The Admin control and its copy | Deed poll Admin, by `deed-poll-admin:docs/plans/20261118-dm-application-deletion.md` |
| A mistaken admission | Delivery Manager raises it; handled as a data-protection incident |

### Outstanding external checks

None of these is done, and automated tests keep completion impossible without the configuration they need:

- Outstanding: API deployed on every replica in AAT (rollout step 1) and the definition imported (onboarding step 1).
- Outstanding: AAT rehearsal R1–R6 run and recorded.
- Outstanding: LAU PET ticket raised and accepted; DEEDPOLL in AAT's `SIMULATED_CASE_TYPES`; R7 in simulation; recorded
  sign-off; DEEDPOLL in `DELETE_CASE_TYPES`; R7 to completion.
- Outstanding: dm-store `ENABLE_TTL` confirmed per environment (onboarding step 4).
- Outstanding: the disposer's removal of a CDAM document observed once against a `DOCUMENT_STORE=cdam` deployment
  with a disposable fixture, before prod activation (AAT runs the filesystem store).
- Outstanding: Steve's answer on CDAM documents already soft-deleted by a caseworker ("Separately retained data").
- Outstanding: An alert on the worker's needs-attention WARN, or on the monitoring query, and a named owner.
- Outstanding: DPIA and Operational Working Agreement updates (onboarding step 6), before prod activation.
- Outstanding: Production quotas: worker interval, batch size and lease agreed for prod (defaults `PT5M`, 10, `PT5M`).

## Provenance of the published contract, and the Admin handoff

The API's contract was first fixed at commit `0c67924` (Task 7) and changed by two review follow-ups: `79d88cc`
refuses a `multipart/*` or urlencoded form body with 415 before the stream is read, and `0aaec7a` renames the body's
`expectedModifiedOn` to `expectedLastChangedOn` and adds `lastChangedOn` to `CaseDetailResponse`. The spec generated by
`OPENAPI_OUTPUT_PATH=<file> ./gradlew integration --tests '*OpenAPIPublisherTest' --rerun` after them has raw SHA-256
`e07f28fac62a194020c8a78c7f5bd30d4ecc90dda1f028cc28041ac1c44fb57c` (the earlier `4bf5ae7c…e91f` is superseded); the
delta against `49239c1` adds the two routes and the schemas `CreateDeletionRequest`, `DeletionAdmissionResponse`,
`DeletionError` and `DeletionRequestResponse`, adds `lastChangedOn` to `CaseDetailResponse`, and removes nothing. Admin's plan
(`deed-poll-admin:docs/plans/20261118-dm-application-deletion.md`) refreshes its vendored copy by its own
`SPEC-PROVENANCE.md` recipe from the merged API commit, in its own worktree; this repository never edits Admin's copy.
What Admin has to honour: send `expectedLastChangedOn` exactly as the case detail published its `lastChangedOn`
(never `modifiedOn`, and never a PATCH response's, which does not carry it: re-read the detail after an edit), mint one
`Idempotency-Key` per confirmation journey and reuse it for every retry of it, show `Completed` as deleted and nothing
else, show `NeedsAttention` with its code and no retry button, and tell the user the note is kept and must not hold the
applicant's details. API merges and deploys on every replica before Admin shows the control.

**Local completion is not deployment evidence.** The gates in the plan's execution notes prove the code locally against
Postgres and WireMock; no environment has run a deletion, and none can complete one until the onboarding below and the
rollout (plan Task 9) are done.

## Audit-value disposal permissions

`deedpoll_audit_dispose_case_values(case_id)` is `SECURITY DEFINER`, owned by the migration login (today the api's
own login), with `EXECUTE` revoked from `PUBLIC`. The api can therefore call it, and must, inside finalisation's
transaction; nothing else may set `deedpoll.audit_disposal`. It deletes every value row of every event for that
case, leaving the events (names of fields, actor, time). `deedpoll_audit_dispose_events_before` is not used here.
The new events must be value-free: `CASE_DELETION_REQUESTED`/`CASE_DELETED` carry typed `deletionReason`,
`deletionNote` and `deletionRequestId` detail and no before/after values, so disposal has nothing to remove from them.
The note survives with the event for its 7 years, which is why it must not hold applicant details.

**The tombstone's retention.** A `Completed` `deedpoll_deletion_request` row is the tombstone: case id, idempotency
key, fingerprint, reason, note, requesting Delivery Manager, timestamps, CCD reference and the steps' outcome codes,
with every document locator and the temporary inventory erased at completion (`eraseInventory`). V48's trigger keeps
it immutable, and it is what answers a replay of the same key with the same request. It follows the audit events'
rule: kept for seven years after `completed_at`, and never disposed of sooner, because without it a replay would be
an unknown case rather than its own completed request. Nothing disposes of it yet; when the seven-year disposal of
`deedpoll_audit_dispose_events_before` is scheduled, the tombstones go by the same reviewed path, at the same cutoff.

## Separately retained data (not erased by this operation)

- Database backups and point-in-time recovery (platform retention); restoring one would resurrect the case, so
  a restore procedure must re-apply completed deletions from the retained audit events.
- Email already accepted by GOV.UK Notify (Notify's own retention, and document download links until their
  `notify.document-retention-weeks` expiry). New sends are stopped; delivered mail is not recalled.
- Gazette publication evidence: such a case is refused, not deleted.
- **CDAM documents a caseworker had already deleted.** `CdamDocumentStore.delete` is a soft delete
  (`permanent=false`), and dm-store's `/documents/delete` sweep skips `deleted = true` documents, so the disposer
  never sets their TTL. They stay soft-deleted in dm-store, recoverable only by the dm-store team, and this service
  no longer holds their ids (the local row is deleted with the document). **Open question for Steve:** accept
  this residue, ask the dm-store team for a case-scoped purge, or keep CDAM ids for soft-deleted documents so the
  worker can purge them; the plan forbids sending `permanent=true` ourselves.
- Logs (fixed prose, no applicant content), Log and Audit's own deletion record, and the legacy Power App source.

## External onboarding checklist (not done here; owners in brackets)

1. [Deed poll service team] Merge and release the definition change; import the definition carrying `TTL`,
   `PendingDisposal`, `MarkForDisposal` and `ConfirmDisposal` to each environment's definition store.
2. [Deed poll service team → LAU] Raise a ticket on the **LAU PET** board: case type `DEEDPOLL` (confirmed for AAT
   2026-10-16), start in simulation, expected volumes (a handful of cases, documents per case, no role assignments),
   the operational owner, and `CFT_SERVICE_LOGS` access to see deletions in Log and Audit.
3. [LAU, cnp-flux-config] Add `DEEDPOLL` to the disposer's `SIMULATED_CASE_TYPES`; review the 22:00 run's logs
   against a disposable AAT case; then, on the service team's recorded sign-off, move it to `DELETE_CASE_TYPES`.
4. [dm-store / platform] Confirm `ENABLE_TTL` is on for dm-store in each environment, or soft-deleted documents are
   never hard-deleted.
5. [Deed poll service team] Enable the SDK task per environment in flux: `dry-run` first, then `live`. The system
   user needs `deed-poll-system-update` and data-store routing already used by linking (`docs/CCD-SMOKE.md`).
6. [Deed poll service team] DPIA and Operational Working Agreement updates the LAU guide asks for.

## Execution baseline

Baseline gates on untouched `origin/master` `7fc5671`: `./gradlew build` (Checkstyle disabled by the init script)
green, `checkstyle.sh` OK, `git diff --check` clean; `test` 5,453 across 589 suites (1 skipped), `integration` 30
across 5 (Aspen, 2026-11-18). This branch adds only the plan, this document and the contract proof; its results are
in the plan's execution notes.
