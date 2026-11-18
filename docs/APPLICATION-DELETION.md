# Application deletion — ownership, contracts and cleanup order

Status: Task 1 of [the plan](plans/20261118-dm-application-deletion.md), 2026-11-18. This records what an
application owns, what the platform deletes for us and how it signals it, and the order cleanup must take. Nothing
here is implemented yet beyond the stubbed contract proof; nothing here authorises deletion in any environment.

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
   original Delivery Manager, reason and request id, request `Completed`. For a linked case this is `policy.dispose`,
   inside the SDK's transaction, so the `ccd.case_data` delete and the completion commit or fail together. An unlinked
   case is finalised by this service's own worker after step 3.

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

## Definition change (designed here, built in Task 5)

Add to `DeedPollCaseType`, following the SDK's own e2e `SimpleCaseConfiguration` and its `retain-and-dispose.md`:

- **The platform `TTL` field.** On `DeedPollCaseData`: `@JsonProperty("TTL") @CCD(label = "Time to live",
  typeOverride = FieldType.TTL, access = TtlAccess.class) private TTL timeToLive;` — the id must be exactly `TTL`
  (data-store's `TTL.TTL_CASE_FIELD_ID`), and its sub-fields are referenced, not redefined. `TtlAccess` grants CRU to
  `DEED_POLL_SYSTEM` only. `DEED_POLL_READER` gets nothing, and no human role touches it: there is no
  `TTL_profile`/`idam:cft-ttl-manager` mapping, because nothing here suspends or overrides.
- **State `PendingDisposal`** (`DeedPollCaseState`), with `builder.grant(PendingDisposal, CRU, DEED_POLL_SYSTEM)`
  stated explicitly for the reason the existing `Registered` grant is: the SDK derives no grant for a destination
  state, and without R the SDK's visibility check aborts every run. `DEED_POLL_READER` gets R so a staff document
  read during the wait answers as CCD does for `Registered`; whether it should instead get nothing is a Task 4
  fencing decision.
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
