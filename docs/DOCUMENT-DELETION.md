# Permanent document deletion: ownership, use and deletion contracts

Status: Task 1 of [the plan](plans/20261125-permanent-document-deletion.md), 2026-11-26. This records, against the
code as it stands, who reads, queues, references and writes a supporting document, and specifies the contracts the
later tasks implement: the lock order, the version token, the staff-only access matrix, the exact in-use sources, the
request budget and per-call timeouts, binary ownership, CDAM's permanent-delete semantics and the fence points. No
route, migration or production code exists yet for this feature. Nothing here authorises deletion in any environment.

## At a glance

- **Who.** Any member of staff the `/caseworker/**` rule admits (`StaffArea.CASEWORKER`: caseworker, Team leader,
  Delivery Manager and the deprecated admin role), for any application they can open. No new capability. **Masters
  get nothing**: the judicial surface gains no route (Steve, 2026-11-25).
- **What.** One supporting-document row of one case: its local row, its owned binary (filesystem file or CDAM
  document, deleted with `permanent=true`), and its entry in the CCD case's current `supportingDocuments`.
- **How.** In the staff member's own request, with a durable request recorded first. Admission commits (fencing
  the document) before any remote call. The remote steps run outside any transaction, each outcome in its own short
  commit. Local completion commits in one transaction with exactly one `DOCUMENT_DELETED` event and one case note. A
  step that is uncertain, failed or out of budget answers 202, and a recovery sweep finishes it.
- **Refused while in use.** "In use" means queued or unsettled work already in the database that will read the
  bytes. No reader holds a lease, and nothing locks around reads (*In-use sources*).
- **Not promised.** Platform audit or metadata (dm-store keeps the `StoredDocument` row marked
  `deleted`/`hardDeleted`), emails GOV.UK Notify has already accepted, backups, historical CCD event snapshots or
  search history, and bytes a browser already holds.

## Provenance

| Source | Commit | Notes |
|---|---|---|
| deed-poll-api, this branch (`plan/permanent-document-deletion`) | `068e047c4caf058493c590a1efde1278196185ad` | on `origin/master` `edb705272e4840465cf031395a3f6096a4ae3034`, the plan's baseline |
| deed-poll-admin | not cloned in this worktree | plan baseline `43997276fde4d97ec039fe0ef802dcc66b533f69` (#422) |
| ccd-case-document-am-client (jar pinned by `build.gradle`) | `1.59.2` | `CaseDocumentClientApi.deleteDocument(auth, s2s, UUID, boolean permanent)` → `DELETE ${case_document_am.url}/cases/documents/{id}?permanent=…` |
| ccd-case-document-am-api, workspace clone | `fa7710499f70080f64418e1a6cab374cbe627d68` | delete controller and dm-store client read here |
| ccd-case-document-am-api, `origin/master` (fetched 2026-11-26) | `da4723ef03b52e4d41e6c50b43ee03a9a7a19b7b` | delete path unchanged since the clone (only `AuditLogFormatter` differs under `src/main/java`); **`service_config.json` grants `deed_poll_api` `CREATE`, `READ`, `UPDATE` on case type `DEEDPOLL`, jurisdiction `DEEDPOLL`**. The clone's copy does not, so the grant is recent. |
| document-management-store-app, workspace clone | `8b01cdd1c8bdbd7c4ff641a303a37487f5400d42` | as pinned in `src/test/resources/dm-store-contract.md` |
| document-management-store-app, `origin/master` (fetched 2026-11-26) | `916193ae10ee1b09fddb2b68f4ec51d1b72320bd` | delete path unchanged except one 400 response builder in `StoredDocumentDeleteController` |

The deployed CDAM and dm-store versions per environment are not proven by this and must be checked before prod
activation (*Activation blockers*).

### Baseline gates

Run on 2026-11-26 at `068e047` plus this task's two files (this document and the contract test; no production
code), in the foreground, with `TESTCONTAINERS_RYUK_DISABLED=true JAVA_TOOL_OPTIONS=-XX:UseSVE=0`:

| Command | Result |
|---|---|
| `./gradlew build -I …/no-checkstyle.gradle --console=plain --no-watch-fs` | BUILD SUCCESSFUL in 5m 44s; `test` 5682 tests, 0 failures, 0 errors, 1 skipped; `integration` 31, 0 failures (counts from `build/test-results/{test,integration}`) |
| `…/.context/scripts/checkstyle.sh .` | OK (after wrapping one over-long Javadoc line in the new test) |
| `git diff --check` | clean |

## Inventory: who touches a supporting document

Paths are relative to `src/main/java/uk/gov/hmcts/reform/deedpoll/`.

### Readers (open the bytes, or show the row)

| Reader | What it reads | Authority | Lifetime of the read |
|---|---|---|---|
| `GET /caseworker/cases/{id}` (`CaseDetailResponse` ← `DeedPoll.documents`) | every row of the case, metadata only | staff | none: no bytes |
| `GET /caseworker/cases/{id}/documents/{documentId}/file` (`DocumentService.downloadDocument(…, reader)`) | the bytes | caller's own token (CDAM) or the pod's file; `DOCUMENT_DOWNLOADED` fail-closed before `store.open` | filesystem: streamed from an open file handle (an unlink during the stream does not cut it on Linux); CDAM: the client **buffers the whole body in memory** before the response starts, so once `open` returns, the bytes are this pod's |
| `GET …/documents/{documentId}/preview` (`DocumentPreviewService.caseworkerPreview`) | the bytes, spooled whole into a `preview-*` scratch directory, then a PDF rendition (DOCX through Docmosis) | caller's own token; `DOCUMENT_PREVIEWED` fail-closed | the scratch directory lives until the response's scratch is deleted (≤ the 25 s preview budget plus the write); the DOCX body goes to Docmosis once and is not kept there |
| `GET /judicial/referrals/{referralId}` (`JudicialReferralService` → `DocumentService.readableDocuments`) | the case's servable rows, metadata only | judge the referral is visible to | none |
| `GET /judicial/referrals/{referralId}/documents/{documentId}/file` and `/preview` | as the caseworker download and preview | judge's own token | as above |
| CCD (`/ccd-persistence`, `DeedPollCaseView` ← `findCcdDocumentProjections`, JPQL) | the case's `Attached` rows as `supportingDocuments` (id, CDAM id, filename; never the hash token) | CCD system / data-store | each callback composes it from the rows **at the time of the callback**; the SDK's event snapshot keeps what it composed |
| Email drain (`notify/DocumentUploads` → `CorrespondenceDocumentReader.forNotification`) | the bytes of each id under a template's `documentKeys()` (today only `deed_document`) | system user (`CdamDocumentStore.openForService`) | read whole, uploaded to Notify as a time-limited download link; Notify keeps the file for `notify.document-retention-weeks` (1–78) and nothing recalls it |
| Deed read-back (`DeedPreparations.verifyAndRelease`) | the generated deed's bytes, compared with the retained payload | system user | one read, outside a transaction |
| Owned staging promotion read-back (`OwnedStaging.promote`) | the promoted document's bytes | system user | one read |
| Email history (`CaseEmailService.correspondence`) | row names for ids in frozen personalisation | staff | none; an id with no row is silently omitted (`CorrespondenceDocument.from`) |
| Application deletion (`DeletionAdmissionRepository.documents`, `hasUnresolvedDocumentStorage`; native) | every row's locator | Delivery Manager admission | none |
| `DocumentStorageMetrics` | counts by state (JPQL) | none | none |

### Queued consumers (work recorded now that reads the bytes later)

| Source | Table / predicate | Reads the bytes when |
|---|---|---|
| An email whose frozen personalisation names the document | `deedpoll_notification`, `state = 'Pending'` (claimed or not, send started or not), `personalisation ->> k = documentId` for `k` in its template's `documentKeys()` | at drain, by `DocumentUploads` |
| A deed preparation whose payload is still retained | `deedpoll_deedpreparation."documentId" = documentId` **and** a `deedpoll_stagedpayload` row with that id | at the next forms send's `continuePrepared` / `verifyAndRelease` |
| An owned-staging promotion not yet consumed | `deedpoll_ownedstage.state = 'Promoting' AND "documentId" = documentId` | at the promotion's resume and read-back |
| The document's own unsettled storage | `storageState IN ('UploadPending','Uploaded','AttachUncertain')`, or `'UploadUncertain'` with `storageUploadAttempts < 2` (application deletion's predicate) | at the upload/attach's own retry |
| Gazette | **none**. No Gazette table references a supporting document (V31–V35), and Gazette sends only its own spreadsheet | never |

A `Failed`/`TECHNICAL` notification with `sendStartedAt` (an unknown send, ADR-0007) is settled: nothing resends it,
so it never reads the bytes again, and it does not block. `Sent` and other `Failed` rows are history.

### Reference owners (rows that name the document or its binary)

| Owner | Column | Kind |
|---|---|---|
| `deedpoll_supportingdocument` (the row itself) | `id`; `storageKey` (Local: the file's key; Attached: also the CDAM id); `cdamDocumentId` | owner |
| another `deedpoll_supportingdocument` row, any case | the same `storageKey` (both Local) or the same `cdamDocumentId` | **shared binary**: no unique index stops it; `updateDocument` can patch `storageKey`, though no route exposes it (below) |
| `deedpoll_notification.personalisation` | document id under a document key | queued (Pending) or history (Sent/Failed) |
| `deedpoll_deedpreparation` (V26) | `"documentId"`, **deliberately no FK**: "the identity survives document removal so removal cannot silently grant permission to generate another legal instrument" | generate-once reservation (*Open questions*) |
| `deedpoll_stagedpayload` | `id` = a preparation's `documentId` | retained bytes |
| `deedpoll_ownedstage` (V29) | `"documentId"` (Promoting/Consumed) | queued (Promoting) or history (Consumed) |
| `deedpoll_removed_document` (V50) | `id`, `locator` | tombstone of an earlier, soft removal |
| `deedpoll_audit_event` / `_value` (V36) | `entity_id`, field values | history |
| CCD: the SDK's event snapshots in our `ccd` schema, data-store's search index | the CDAM URL and filename | history; only the **current** collection is in scope |
| dm-store | the `StoredDocument` and its content versions | the binary's real owner |

### Writers of the row

| Writer | Locks |
|---|---|
| `DocumentService.uploadDocument` → filesystem `storeAndRecord` / `RemoteDocumentUploads.upload` (`DocumentStorageStates.claim`) | insert (an FK key-share on the case) |
| `DocumentStorageStates` moves (`uploadUncertain`, `uploaded`, `failed`, `attachUncertain`, `attached`) | the document row only, `FOR UPDATE` |
| `SupportingDocumentsHandler` → `DocumentStorageStates.attachedOnTheCase` (data-store's callback) | case `FOR KEY SHARE` (`DeletionFence.admits`), then the document `FOR UPDATE` |
| `DeedPreparations.prepare` (CDAM forms send) | case `FOR NO KEY UPDATE`, inserts the deed row |
| `DeedDocumentService.storeDeed` (filesystem forms send) | inserts the deed row |
| `OwnedStaging.promote` → `CdamDocumentUploads.continuePrepared` | case `FOR NO KEY UPDATE`; then state moves |
| `DocumentService.createDocument`, `updateDocument`, `removeDocument` | **no production caller**: `CaseworkerDocumentController` exposes only upload, file and preview, and says so; these are reached only from tests |

`removeDocument` is the existing system-attributed, best-effort soft removal (V50 tombstone, row delete, then after
the commit the CCD event and `CdamDocumentStore.delete`, which is `permanent=false`). It stays as it is for internal
compatibility. The new feature never calls it, and never sends a soft delete first.

## Access matrix

Decided in the domain, again at admission, replay and status. An id or an old confirmation page is not permission.

| Caller | Admission | Replay of its key | Status |
|---|---|---|---|
| caseworker, Team leader, Delivery Manager, deprecated admin (any one suffices) | yes, for a case and document it can open | yes | yes, while the case can be opened |
| any of those whose case is fenced by an application deletion request | 404 (the case reads as gone; the fence) | 404 | 404 |
| judge only (`caseworker-deedpoll-judicial`) | 403 (`/caseworker/**` rule) | 403 | 403 |
| no token | 401 | 401 | 401 |
| S2S-only service (`/citizen/**` chain) | no route on that chain | — | — |
| CCD system user (`/ccd-persistence/**`) | no route on that chain | — | — |

- **No new capability, no uploader ownership.** Every case a staff member can open is one whose documents they can
  delete. The request row records the actor's subject and display name from the verified token. The client never
  supplies an author.
- **Document scope.** A row of the named case, unfenced, of any provenance the case detail lists: uploaded, generated
  (deeds), or metadata-only. An unknown document, a document of another case, and a fenced document all answer the
  existing 404 in the same words.
- **Status is case-scoped** (`GET /caseworker/cases/{id}/document-deletions/{requestId}`), and survives the row's
  removal. It never carries the reason or a locator.

## Binary ownership and what each row needs removed

| Row state | Store this deployment serves? | Remote work | Otherwise |
|---|---|---|---|
| `Local`, key present | filesystem | `LocalFilesystemDocumentStore.remove(key)`, verified | under CDAM: refuse `DOCUMENT_STORAGE_UNRESOLVED` (the file is on a store this pod cannot reach; 03c migration territory) |
| `Local`, no key | either | none: metadata only | — |
| `Attached` | CDAM | the CCD document event (if the case is linked), then CDAM `DELETE ?permanent=true` | under filesystem: refuse `DOCUMENT_STORAGE_UNRESOLVED` |
| `Failed`; `UploadUncertain` with both sends used | either | none: metadata only. A copy that landed was never attached and expires on CDAM's upload TTL, the assumption application deletion makes | — |
| `UploadPending`, `Uploaded`, `AttachUncertain`; `UploadUncertain` with a send left | — | — | refuse `DOCUMENT_STORAGE_UNRESOLVED` until reconciled |

**Shared binary.** Before admitting, look for any *other* current `deedpoll_supportingdocument` row, in any case and
fenced or not, with the same binary: `storageKey` equal when both are `Local`, or `cdamDocumentId` equal. One found is
`DOCUMENT_SHARED`, and nothing changes. Historical references do not count: a V50 tombstone, a `Consumed` stage,
audit rows, sent emails and CCD history. They neither block deletion nor promise that the binary stays readable.
(Production rows can share only through seeding or migration, because no route patches `storageKey`. The check is
still a query, not an assumption.)

## In-use sources (the exact set)

Checked at admission, **under the case lock and then the document lock** (*Lock order*). Any match refuses with 409
`DOCUMENT_IN_USE`, writes nothing, and Admin shows: "This document is currently being used and cannot be deleted. Try
again once the operation has finished."

1. `deedpoll_notification` where `"deedPollId" = case` and `state = 'Pending'` and, for any template whose
   `documentKeys()` is non-empty, `personalisation ->> key = documentId`. Today that is
   `FORMS_FOR_COMPLETION_ADULT`/`_MINOR` under `deed_document`. Build the key list from `EmailTemplate`, never a
   literal, so a new document-carrying template is covered without anyone remembering.
2. `deedpoll_deedpreparation` where `"documentId" = documentId` **and** `deedpoll_stagedpayload` holds that id (an
   unverified deed; CLAUDE.md: "retain its payload until Attached read-back verifies it").
3. `deedpoll_ownedstage` where `state = 'Promoting' AND "documentId" = documentId`.

The row's own unsettled storage is the separate refusal `DOCUMENT_STORAGE_UNRESOLVED` above. Gazette adds nothing.

**Not in use:** a download or preview in flight, an open email-history page, a `Sent` or settled `Failed` email, a
released deed reservation, a `Consumed` stage. After admission commits, each **new** read is refused by the fence:
download, preview, judicial view, CCD view, email history name lookup, the drain's correspondence read, deed
read-back and promotion read-back. A read already streaming may finish (CDAM buffered it already; a filesystem stream
holds its handle). Nothing suggests to the user that bytes already delivered can be recalled.

## Version token

A document-specific monotonic version, published as `documentVersion` in the staff document projection
(`DocumentResponse`) and nowhere on the judicial surface:

- column `"version" bigint NOT NULL DEFAULT 1` on `deedpoll_supportingdocument`, in the next free migration (V51 at
  the time of writing; never edit V48–V50);
- incremented by a `BEFORE UPDATE` row trigger whenever any reviewed column changes (`name`, `direction`,
  `documentTypeId`, `storageKey`, `originalName`, `mimeType`, `sizeBytes`, `storageState`, `cdamDocumentId`). A
  trigger, so no writer can forget it and no Hibernate `@Version` turns concurrent state moves into optimistic-lock
  failures. It is the row's own trigger and takes no case lock, so it does not repeat the deadlocked commit-time
  case-lock trigger application deletion removed;
- mapped `insertable = false, updatable = false`, read after the write when a response needs it;
- `expectedDocumentVersion` in the admission body is compared under the document lock: unequal is 409
  `DOCUMENT_CHANGED`. The application's `lastChangedOn` is not used.

## Lock order

Every path that takes more than one of these takes them in this order, and never the reverse:

1. **The case row.** Admission: `FOR UPDATE` (as application deletion admission, `DeletionAdmissionRepository.lock`).
   This conflicts with every weaker case lock: `FOR KEY SHARE` (the fence's `shareCase`, child-row FK inserts),
   `FOR NO KEY UPDATE` (deed preparation, staging, confirmation recompute) and `FOR UPDATE` (application deletion).
   Local finalisation: `FOR NO KEY UPDATE`, enough to serialise with admission and with application deletion's
   `FOR UPDATE`.
2. **The document row**, `FOR UPDATE` (`findByIdAndDeedPollIdForUpdate`).
3. **The document deletion request row**, `FOR UPDATE` (with `SKIP LOCKED` where a sweep claims).

Application deletion finalisation locks its own request and then the case, then cascades. That cascade reaches
document requests only after the case lock, and Task 5 makes application admission refuse while a document request
is unresolved, so no cycle exists. Storage-state moves lock only the document row and never ask for the case
afterwards. The audit tables have no FK to the case, so the audit insert after a move takes no case lock.

**Why this closes the races without a lease.** A new queued use (an email enqueue, a deed preparation, a promotion)
either holds a case lock (key share or stronger) or inserts a child row, which takes the FK key share. Admission's
`FOR UPDATE` waits for it to commit and then sees it in a fresh statement (`DOCUMENT_IN_USE`). A use that starts
after admission waits for admission's commit and then has to ask the fence after taking its lock, as V48's fence
already requires (*Fence points*).

## Fence points (Task 3 implements them)

Admission inserts the document deletion request, and the request row **is** the fence for that one document. Other
documents of the same case stay usable.

- **JPA:** extend `DeedPollSupportingDocument`'s `@SQLRestriction` (today `DeletionFenceSql.UNFENCED_BY_DEED_POLL_ID`)
  with "no document deletion request names this `id`". That one change hides the row from `findDocument`,
  `readableDocument` (download, preview, judicial, the correspondence reader), `DeedPoll.documents` (case detail),
  `findCcdDocumentProjections` (the CCD view), `findByDeedPollIdOrderByCreatedOnAsc` (email history and compose),
  `DeedDocumentService.existingDeed` and the storage moves' `findByIdAndDeedPollIdForUpdate`.
- **Native statements that read documents:** only `DeletionAdmissionRepository.documents` and
  `hasUnresolvedDocumentStorage`. They must keep seeing a fenced document, because application deletion has to
  inventory it (Task 5). Recovery and finalisation read their own request table natively, so they never need the
  entity.
- **Ask after the lock:** writers that take the case lock and then ask (`DeletionFence.admits`, `lockUnfenced…`) also
  ask whether the document they are about to use is fenced: `CaseEmailService` before enqueuing a deed id,
  `DeedPreparations.requireCurrent`/`verifyAndRelease`, `OwnedStaging.promote`, `SupportingDocumentsHandler` before
  `attachedOnTheCase`.
- **Backstop at commit:** a V49-style `DEFERRABLE INITIALLY DEFERRED` constraint trigger refusing an INSERT/UPDATE of a
  `deedpoll_supportingdocument` row named by a document deletion request (DELETE allowed), and an INSERT of a
  `deedpoll_notification` whose document key names one. A writer that forgot to ask then fails at commit.

## Request budget and per-call timeouts

The whole request must answer well inside the Application Gateway's 30 s.

| Setting | Default | Meaning |
|---|---|---|
| `DOCUMENT_DELETION_REQUEST_BUDGET` (`deedpoll.documents.deletion.request-budget`) | `20s` | measured from the request's start. A remote step starts only if the budget left covers that call's connect plus read timeout; otherwise the request answers 202 `InProgress` and the sweep resumes it |
| CDAM permanent delete: a dedicated Feign client, contextId `cdam-document-deletion`, with `NoConnectionRetry` | connect `2s`, read `8s` | separate from `case-document-am-client-api`, so upload and download timeouts do not change |
| CCD start/submit (`CoreCaseDataApi`, existing `default`) | connect `5s`, read `10s` | unchanged. A start that leaves too little for the submit stops before submitting |
| IDAM system token (`SystemUserTokens`, cached) and S2S (cached generator) | existing defaults | a fresh grant counts against the budget like any other call |

Feign's retryer is `NEVER_RETRY`, and `NoConnectionRetry` turns off OkHttp's silent resend, so each call sent is one
the deletion decided to send (pinned by `CdamPermanentDeleteContractTest`).

## Remote steps and their receipts

In this order, each outcome committed on its own before the next step starts.

1. **CCD document event** (only for an `Attached` row of a linked case): `updateSupportingDocuments` as the system
   user, with the case's **current** collection minus this document. Build that from the start event's
   `case_details.data.supportingDocuments`, which data-store composed through our view, so the fenced row is already
   absent, rather than from a list read earlier. That is what keeps two concurrent removals from reattaching each
   other: `CdamDocumentUploads.removal` today reads its list inside the delete's transaction and sends it later, and
   the new step must not. Our snapshot cannot regain a fenced row either way, because `DeedPollCaseView` composes
   the field from unfenced rows. Receipt: submit 2xx. Re-sending is safe (another history entry, the same result). A
   refusal is `NeedsAttention`; an unknown outcome is retried.
2. **Binary.**
   - CDAM: `DELETE /cases/documents/{cdamDocumentId}?permanent=true`, system user plus S2S, **never preceded by a soft
     delete**. dm-store's `deleteStoredDocument` hard-deletes only a document it can still find, and
     `StoredDocumentService.findOne` hides a soft-deleted one, so a permanent delete after a soft delete never
     reaches the blob. See the outcome table below.
   - Filesystem: `LocalFilesystemDocumentStore.remove(key)`. True (deleted) or false (the root is present and the
     file is not) both prove absence **on this pod's volume**. A missing root or a file still present is a failure.
     Filesystem stores are non-production only, and the proof assumes the one volume the guard already assumes.
3. **Local completion** (one transaction, no network call): delete the row, reconcile V50 (none is written for a
   permanent deletion; the request row is its evidence, Task 5), insert one `DOCUMENT_DELETED` event and one case note
   under the original requester, and mark the request `Completed`.

### CDAM's answers to a permanent delete

From `CaseDocumentAmController.deleteDocumentByDocumentId` (CDAM): read metadata from dm-store
(`DocumentStoreClient.getDocument`, retried by CDAM on 5xx) → `checkServicePermission(…, UPDATE)` → forward
`DELETE /documents/{id}?permanent=true` to dm-store → `204`. dm-store
(`AuditedStoredDocumentOperationsService.deleteStoredDocument`) marks it `deleted` and `hardDeleted`, deletes every
content version's blob, and audits `HARD_DELETED`.

| Answer | Meaning | Request outcome |
|---|---|---|
| `204` | dm-store accepted and recorded the hard delete | step `Done`; **the only receipt** |
| `404` | CDAM's metadata read found nothing: already hard-deleted (perhaps our own lost 204), **or soft-deleted by somebody else with the blob kept**, or never there. Indistinguishable (proved by the contract test) | `NeedsAttention` `CDAM_PURGE_UNPROVEN`; an operator confirms with the dm-store team that the document is `hardDeleted`, as application deletion's `confirm-cdam-purge.sql` does. Never `Completed` on a 404 |
| `401` | S2S rejected | retry (bounded), then `NeedsAttention` |
| `403` | CDAM's service permission (`deed_poll_api` lacks `UPDATE` on the document's case type) | `NeedsAttention` `CDAM_PERMISSION`; an activation blocker, not a retry |
| `400` | bad id (cannot happen for a stored UUID) | `NeedsAttention` |
| `5xx`, `504`, read timeout, connection reset | unknown: the delete may or may not have landed | `InProgress`; the same permanent delete is re-sent (safe: `204` if it had not landed, `404` → `NeedsAttention` if it had), bounded attempts, then `NeedsAttention` |

**What `204` does not prove.** `BlobStorageDeleteService.deleteDocumentContentVersion` logs and swallows a blob-store
failure other than 404 and still lets the document be marked `hardDeleted`, so a `204` is dm-store's record of a hard
delete, not an independent proof that Azure no longer holds the blob. dm-store also keeps the `StoredDocument`
metadata row. Both are platform limits, recorded here and in the user guidance, and not something this service can
verify.

**Already soft-deleted documents.** No CDAM or dm-store route purges a document that is already soft-deleted: CDAM
404s first, and dm-store's own `findOne` hides it. So this feature does not retroactively purge V50's historical
soft removals, and `CDAM_REMOVED_DOCUMENT_UNPURGED` keeps its manual dm-store-team procedure.

The stubbed proof is `src/test/java/uk/gov/hmcts/reform/deedpoll/storage/CdamPermanentDeleteContractTest.java`. It
drives the pinned client's `CaseDocumentClientApi` over a real Feign client with `NoConnectionRetry`, against a
WireMock CDAM that models the sources above. It pins five behaviours:
- `?permanent=true` is sent with both tokens, and `204` is accepted.
- A replay after a permanent delete is `404`.
- A document soft-deleted elsewhere gives the same `404`.
- A dropped connection or a read timeout is sent once and is not a 404. The retry then deletes the document if the
  first call never landed, and meets the ambiguous 404 if it did.
- 400/401/403/5xx/504 are failures, never absence.

Run it with `./gradlew test --tests "uk.gov.hmcts.reform.deedpoll.storage.CdamPermanentDeleteContractTest"`
(container-free).

## Contract alignment with the plan

The routes sit beside today's resources: `POST /caseworker/cases/{id}/documents/{documentId}/deletion-requests`
mirrors application deletion's `POST /caseworker/cases/{id}/deletion-requests`. The status route is case-scoped
(`GET /caseworker/cases/{id}/document-deletions/{requestId}`) because the case outlives a document deletion, unlike
`/caseworker/application-deletions/{requestId}`. No delta from the plan's table except:

- **Success is 200 with `{ requestId, status: "Completed" }`**, as the contract table says. Task 4's "Test 204
  success" is read as that success case, because a 204 could not carry the request id the companion records. 202
  carries `Location` to the status route.
- The body is closed `{ reason, expectedDocumentVersion }`, read the way `DeletionBodies` reads application
  deletion's: unknown keys are refused without being echoed, and the body is checked only after authorisation. The
  `Idempotency-Key` is required and a UUID.
- Codes: `DOCUMENT_IN_USE`, `DOCUMENT_CHANGED`, `DOCUMENT_STORAGE_UNRESOLVED`, `DOCUMENT_SHARED`,
  `APPLICATION_DELETION_PENDING` (in practice the case is fenced and answers 404; the code covers an application
  request admitted between the read and the lock), `KEY_REUSED`.

## Activation blockers and unsupported outcomes

- **CDAM permission.** `deed_poll_api` needs `UPDATE` on `DEEDPOLL` in CDAM's deployed `service_config.json`. It is on
  CDAM's `origin/master` (`da4723e`) but not in the workspace clone (`fa77104`), so check the deployed CDAM image in
  the target environment first. Without it, every CDAM deletion is `NeedsAttention` `CDAM_PERMISSION` after its
  admission has already fenced the document, so the activation check comes first.
- **dm-store permission.** None beyond CDAM: CDAM calls dm-store as itself.
- **Prod CDAM smoke** with a disposable application (Task 7). AAT uses the filesystem store, so AAT cannot prove the
  CDAM path.
- **Unsupported:** purging an already soft-deleted CDAM document; proving blob removal independently of dm-store's
  `hardDeleted`; recalling Notify copies, backups, historical event snapshots or the search index's history;
  deleting a `Local` row's file from a CDAM deployment (03c). Each is a typed refusal or a `NeedsAttention`, never a
  success.

## Open questions (defaults chosen; Steve to confirm)

1. **Deleting a generated deed.**
   - On CDAM, the V26 reservation survives the document by design, so after the deed is permanently deleted, the next
     forms email for that case refuses with "The existing deed is unavailable or incompatible; it must not be
     regenerated". The case can then never be sent a deed without a reviewed repair.
   - On the filesystem store there is no reservation, so the next send generates a new deed, as `removeDocument` would
     allow today.
   - Default: keep both behaviours, and have Admin's confirmation page warn when the document is a generated deed.
     Not deleting the reservation keeps the generate-once rule intact.
2. **A `Failed` row's unattached CDAM copy** is left to CDAM's upload TTL rather than permanently deleted, matching
   application deletion's inventory. A later change could send `permanent=true` for it too, at the cost of a 404
   that proves nothing once the TTL has run.
