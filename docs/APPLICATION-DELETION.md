# Application deletion — ownership, contracts and cleanup order

Status: Tasks 1 to 6 of [the plan](plans/20261118-dm-application-deletion.md), 2026-11-18. This records what an
application owns, what the platform deletes for us and how it signals it, and the order cleanup must take, and
describes admission, the fence, the external and binary cleanup and local finalisation as built. The HTTP routes and
the rollout are later tasks; nothing here authorises deletion in any environment.

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
`expectedModifiedOn`), the case's existing request (a replay, answered even once the case is gone), the case lock
(`DeletionAdmissionRepository.lock`, `FOR UPDATE`) and the existing request again under it, then 404, then the
refusals. Each refusal is a `DeletionConflictError` carrying a `DeletionRefusal` code, and writes nothing:

| Code | When |
|---|---|
| `STALE_CONFIRMATION` | the case's `modifiedOn`, to the millisecond, is not the one the Delivery Manager reviewed |
| `RETAINED_PERMANENTLY` | `retainPermanently` is set |
| `GAZETTE_EVIDENCE` | Gazette notice batch membership or request evidence |
| `RETAINED_DEED_PAYLOAD`, `RETAINED_STAGED_UPLOADS` | the existing retained-bytes guards |
| `DOCUMENT_STORAGE_UNRESOLVED` | a document `UploadPending`, `Uploaded`, `AttachUncertain`, or `UploadUncertain` with a send left |
| `CCD_CREATION_UNRESOLVED` | an unlinked case's attempt is `Claimed`/`AwaitingConfirmation`, or may have reached CCD without proof nothing was created |
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

## Audit-value disposal permissions

`deedpoll_audit_dispose_case_values(case_id)` is `SECURITY DEFINER`, owned by the migration login (today the api's
own login), with `EXECUTE` revoked from `PUBLIC`. The api can therefore call it, and must, inside finalisation's
transaction; nothing else may set `deedpoll.audit_disposal`. It deletes every value row of every event for that
case, leaving the events (names of fields, actor, time). `deedpoll_audit_dispose_events_before` is not used here.
The new events must be value-free: `CASE_DELETION_REQUESTED`/`CASE_DELETED` carry typed `deletionReason`,
`deletionNote` and `deletionRequestId` detail and no before/after values, so disposal has nothing to remove from them.
The note survives with the event for its 7 years, which is why it must not hold applicant details.

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
