# Permanent document deletion: ownership, use and deletion contracts

Status: implemented on this branch (Tasks 1-7 of [the plan](plans/20261125-permanent-document-deletion.md),
2026-11-26); not deployed and not rehearsed. This is the feature's contract and runbook. It records, against the
code, who reads, queues, references and writes a supporting document, and the contracts the feature keeps: the lock
order, the version token, the staff-only access matrix, the exact in-use sources, the request budget and per-call
timeouts, binary ownership, CDAM's permanent-delete semantics and the fence points. *Using it*, *Runbook* and
*Rehearsal* are for operators; [`DOCUMENT-DELETION-SMOKE.md`](DOCUMENT-DELETION-SMOKE.md) is the rehearsal script.

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

## Fence points

Admission inserts the document deletion request, and the request row **is** the fence for that one document. Other
documents of the same case stay usable.

- **JPA:** `DeedPollSupportingDocument`'s `@SQLRestriction` is `DeletionFenceSql.DOCUMENT_RESTRICTION`: the case's
  fence plus "no document deletion request names this `id`". It hides the row from `findDocument`,
  `readableDocument` (download, preview, judicial, the correspondence reader), `DeedPoll.documents` (case detail),
  `findCcdDocumentProjections` (the CCD view), `findByDeedPollIdOrderByCreatedOnAsc` (email history and compose) and
  `DeedDocumentService.existingDeed`.
- **Native statements that read documents:** application deletion's `DeletionAdmissionRepository.documents` and
  `hasUnresolvedDocumentStorage`, which keep seeing a fenced document (application admission refuses first while its
  request is unresolved, `hasUnresolvedDocumentDeletion`, *Application deletion*, below);
  `DocumentDeletionAdmissionRepository`'s `lockDocument` and its in-use queries (`sharesBinary`,
  `hasPendingEmailNaming`, `hasRetainedDeedPreparation`, `hasPromotingStage`); and completion's
  `DocumentDeletionRequestRepository.lockDocumentRecord`. `DeletionFinalisationRepository.hasUnresolvedDocumentDeletion`
  reads the request table only.
- **Ask after the lock:** every writer that locks a document does it with
  `DeedPollSupportingDocumentRepository.findUnfencedByIdAndDeedPollIdForUpdate`, which repeats the fence after the
  row lock: the storage moves (`DocumentStorageStates`, `attachedOnTheCase` included), `DocumentService`'s update and
  removal, `DeedPreparations.requireCurrent`/`verifyAndRelease` and `OwnedStaging.promote`. An email names only
  documents its JPA reads can see, and V51's notification trigger refuses one that names a fenced document.
- **Backstop at commit:** V51's `DEFERRABLE INITIALLY DEFERRED` constraint triggers refuse an INSERT/UPDATE of a
  `deedpoll_supportingdocument` row a document deletion request names (DELETE allowed), and an INSERT of a
  `deedpoll_notification` whose personalisation names one on the same case. A writer that forgot to ask then fails
  at commit.

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
| any other `2xx` (`200`, `202`) | not dm-store's answer; not a receipt (`CdamPermanentDelete.receiptOnly`) | as unknown, below |
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

## Persistence (Task 2, V51)

- **`deedpoll_document_deletion_request`**: one per document (`UNIQUE (document_id)`), its key unique within the case
  (`UNIQUE (case_id, idempotency_key)`), inserted with `ON CONFLICT DO NOTHING` so a racing admission reads the winner.
  It is `InProgress`, `NeedsAttention` or `Completed`, with V48's claim, completion and failure checks. A trigger
  keeps admission's half fixed: the case, document, key, fingerprint, expected version and requester.
- **The reason** (`DocumentDeletionReason`) is trimmed with `String.strip()` and is 1 to 500 code points. It
  refuses control characters, line and paragraph separators, bidirectional controls and unpaired surrogates. The
  column's check repeats what Postgres can check. Application deletion's `DeletionNote` rule is untouched. Audit
  detail carries the reason under its own typed key, `documentDeletionReason`, beside `documentDeletionRequestId`.
- **Minimal receipt.** The reason and the confirmed document name are held only until completion. The completion
  transaction copies them into `DOCUMENT_DELETED` and the case note, and
  `DocumentDeletionRequestRepository.complete` clears both and erases the steps' locators. It succeeds only when
  every step is `Done`. A `Completed` row is frozen. It keeps what a replay needs: the key, the fingerprint, the
  document id, the requester and the times.
- **Case-disposal ownership.** The request references the case with `ON DELETE CASCADE` and its steps cascade from
  it, so application deletion's finalisation disposes them with the case. The audit events keep the reason for the
  audit's seven years. V49's fence function refuses a new request on a fenced case (insert only).
- **`deedpoll_document_deletion_step`**: at most one each of `CCD_REFERENCE`, `CDAM_BINARY` and `LOCAL_FILE` per
  request. Each step holds its locator until it is `Done`.
- **The version** is `deedpoll_supportingdocument."version"` with its `BEFORE UPDATE` trigger, as specified above.
  Existing rows start at 1, and the entity maps the column read-only.
- **The fence.** The entity restriction is `DeletionFenceSql.DOCUMENT_RESTRICTION`. A deferred
  `deedpoll_document_deletion_fence` trigger refuses an INSERT or UPDATE of a named document, and an email insert
  whose personalisation names one on the same case. Completion reads and locks the row natively through `lockDocumentRecord`.

## Admission (Task 3)

`domain/documents/deletion/DocumentDeletionService.request` admits, and `read` answers the status, both in the
domain; the HTTP routes are *HTTP operations* below.

- **Order.** The staff role (`requireAnyRole` over `StaffArea.CASEWORKER`'s four roles, before anything is read), the
  subject, the body, then the case as the caller sees it (fenced by an application deletion: 404). Then the case
  `FOR UPDATE`, and the application fence again (`APPLICATION_DELETION_PENDING`). Then a replay by key. Then a request
  another key already admitted for the document: the same body is that request, with its requester kept, and any other
  body is the document's 404. Then the document `FOR UPDATE` (`DocumentDeletionAdmissionRepository.lockDocument`,
  native). Then the refusals in this order: `DOCUMENT_CHANGED`, `DOCUMENT_STORAGE_UNRESOLVED`, `DOCUMENT_SHARED`,
  `DOCUMENT_IN_USE`.
- **Writes**, in one transaction: the request, its steps (`CCD_REFERENCE` only for a linked case, then `CDAM_BINARY`
  for `Attached`; `LOCAL_FILE` for `Local` with a key; none for metadata only), and `DOCUMENT_DELETION_REQUESTED`
  under the caller with the request's id and reason and no values. A failed audit insert leaves nothing.
- **Store reach.** `DocumentStore.serves` decides. A `Local` file on a CDAM deployment, or an `Attached` document on a
  filesystem deployment, is `DOCUMENT_STORAGE_UNRESOLVED`.
- **In use.** The keys come from `EmailTemplate.documentKeys()` over every template
  (`DocumentDeletionService.documentKeys`).
- **Fence, asked after the lock.** `DeedPollSupportingDocumentRepository.findUnfencedByIdAndDeedPollIdForUpdate` locks
  the row and then asks `hasDocumentDeletionRequest` in a fresh statement. A locking read that waited re-checks only
  the row in its old snapshot, so the entity restriction alone would let it through. It replaces the plain locking read
  in `DocumentStorageStates` (both moves), `DeedPreparations.requireCurrent`/`verifyAndRelease` and
  `OwnedStaging.verifyAndConsume`. `DocumentService.updateDocument` and `removeDocument` now lock through it too, so
  neither the patch nor the internal soft removal can reach a document whose permanent deletion was admitted.
- **Proof.** `DocumentDeletionServiceTest` (18 tests, Postgres). Its races use two transactions and wait on
  `pg_stat_activity`:
  - an email naming the document that commits first makes admission `DOCUMENT_IN_USE` with nothing written;
  - an email that waits on admission is refused at commit by V51's trigger, while one naming a sibling lands;
  - a document write committed while admission waits is `DOCUMENT_CHANGED`;
  - a replace, a soft removal and a locking move that wait on admission find nothing, and a new upload lands;
  - a read during admission is neither blocked nor refused, and a read after it is a 404.

## Cleanup, recovery and completion (Task 4)

`domain/documents/deletion/DocumentDeletions.delete` is the staff entry point. It refuses an active transaction,
commits admission, then calls `DocumentDeletionWork.carryOut` on the same request within the budget, and answers the
request as it then stands.

- **Claims.** A pass claims the request under a fresh owner with a lease (`recovery.lease`, default 2 min, which must
  exceed the budget). The staff request claims its own request (`claim`). The sweep claims the soonest due one
  (`claimNext`, `FOR UPDATE SKIP LOCKED`). Each claim counts a pass in `attempts`. A try, a failure and a release
  are written only while `holds` (the request locked `FOR UPDATE`, owner matches, lease unexpired). A worker that
  lost its lease stops.
- **Receipts are kept whoever holds the claim.** `stepDone` writes from `Pending` regardless of the lease: a 204
  forgotten would make the next worker re-send, and that second answer (404) proves nothing.
- **Steps, in order, outside any transaction** (`DocumentDeletionRemote`, implemented by `storage/PermanentDocumentRemoval`):
  - `CCD_REFERENCE`: `CaseDocumentsEvent.submit` with the case's other `Attached` rows read through the entity
    restriction just before the call, so any fenced document is absent and two removals cannot put each other
    back. The budget is asked again between start and submit. Failures: data-store's 400, 403, 422 and other
    refusals need attention at once (`CCD_REFERENCE_FAILED`); its 401, 409 (another event on the same case, as two
    staff deleting two of its documents at once cause) and 429, a start that sent nothing, a 5xx and no answer are
    retried, each try starting a fresh event.
  - `CDAM_BINARY`: `storage/CdamPermanentDelete`, the only sender of `permanent=true`. It uses CDAM's own
    `CaseDocumentClientApi`, built with Feign directly: `SpringMvcContract`, `NoConnectionRetry`'s OkHttp,
    `Retryer.NEVER_RETRY`, connect 2 s and read 8 s. Answers are classified as in the table above.
  - `LOCAL_FILE`: `LocalFilesystemDocumentStore.remove`. Deleted and already-absent are receipts. A missing root, an
    I/O failure and a missing store are retried; an invalid key needs attention.
- **Verdicts.**
  - `DONE` moves on to the next step.
  - `NOT_SENT` (the budget) leaves the request `InProgress`, due at once, with no try counted.
  - `RETRY` counts a try and leaves the request `InProgress` after `retry-base` doubled per try (capped by
    `retry-cap`). At `max-attempts` tries the step is `Failed` and the request `NeedsAttention` (`CCD_REFERENCE_FAILED`,
    `CDAM_DELETE_FAILED` or `LOCAL_FILE_UNREMOVED`).
  - `FAILED` needs attention at once (`CDAM_PURGE_UNPROVEN`, `CDAM_PERMISSION`, …).
  - A request needing attention is never claimed again; it keeps every unconfirmed locator.
- **Budget.** `request-budget` (20 s) runs from the start of the staff request. A CDAM call starts only if 10 s
  (connect plus read) is left. A CCD call starts only if `ccd-call-allowance` (15 s) is left, and the submission
  only if 15 s is still left after the start.
- **Completion** (`DocumentDeletionCompletion`, `MANDATORY`) runs once every step is `Done`, in one transaction. It
  locks the case `FOR NO KEY UPDATE`, then the document `FOR UPDATE`, then checks `holds` on the request. It then:
  - deletes the row;
  - writes `DOCUMENT_DELETED` under the original requester (`AuditActor(requestedBySubject, requestedByName)`), with
    the reason and the request id in its detail, `documentTypeId`, and the metadata's last values;
  - writes the note `Document <name> (<id>) permanently deleted. Reason: <reason>` through `NoteService` under the
    requester, which audits `NOTE_CREATED`;
  - writes the receipt (`complete`: `Completed`, reason, name and locators cleared).
  No V50 tombstone is written: nothing is left to inventory, and the request is the evidence. Any failure rolls all
  of it back and is retried. After `max-attempts` failed completions, counted in V52's `completion_attempts` apart
  from the request's claims (which remote retries, budget passes and replays also spend), the request needs
  attention, `LOCAL_COMPLETION_REFUSED`.
- **Recovery sweep.** `DocumentDeletionRecovery`, on `DocumentDeletionRecoveryTimer` every `recovery.interval` (1 min),
  claims at most `recovery.batch-size` (10) requests per run, each within a fresh budget. It is on by default
  (`DOCUMENT_DELETION_RECOVERY_ENABLED`) and independent of `APPLICATION_DELETION_WORKER_ENABLED`. Off, a request
  stays `InProgress` and is never reported `Completed`. Tests switch it off in `AbstractPostgresContainerTest` and
  run it themselves.
- **Monitoring.** The gauge `deedpoll.documents.deletion.requests{status}` (`storage/DocumentDeletionMetrics`).
  Any `NeedsAttention` needs a person; an `InProgress` count that does not fall means the sweep needs looking at.
  Log lines name the request id, the step kind and an allowlisted code, never a locator, a reason or a provider message.
- **Configuration** (`deedpoll.documents.deletion.*`; env `DOCUMENT_DELETION_*`): `request-budget` (at most 25 s),
  `cdam-connect-timeout`, `cdam-read-timeout`, `ccd-call-allowance`, and `recovery.{enabled, interval, batch-size,
  lease, max-attempts, retry-base, retry-cap}`. The chart sets none of them (`DeploymentChartConformanceTest`), so no
  chart version bump was needed.
- **Proof.** `DocumentDeletionWorkTest` (18 tests, Postgres) covers:
  - success: metadata only, CCD then CDAM, and the filesystem end to end through `DocumentDeletions` with a replay;
  - uncertain answers: unknown then the sweep, a lost answer then a 404, 403, a CCD refusal, exhausted tries;
  - the budget: run out before a step, and out of time between a CCD start and its submission;
  - crashes and leases: a crash after a receipt, a lease lost during an unknown answer, a receipt after a lost lease
    with a single completion, racing sweeps;
  - local completion: a failed note recovered, a failed `DOCUMENT_DELETED` exhausted;
  - two concurrent removals not reattaching each other.

  Alongside it: `CdamPermanentDeleteTest` (WireMock: 204, 200/202 not a receipt, 404, 403, 401, 400, 408/429/5xx, read timeout, reset, no
  tokens, unreachable), `PermanentDocumentRemovalTest`, `DocumentDeletionPropertiesTest`,
  `DocumentDeletionRecoveryTimerTest`, and the chart conformance tests.

## Application deletion (Task 5)

`docs/APPLICATION-DELETION.md`, *Permanently deleted documents*, is the application's side. In short:

- Application admission refuses `DOCUMENT_DELETION_UNRESOLVED` while any of the case's document requests is
  `InProgress` or `NeedsAttention` (`DeletionAdmissionRepository.hasUnresolvedDocumentDeletion`), under the same
  case `FOR UPDATE` lock this admission takes. Whichever admission commits first wins: the other is refused
  (`APPLICATION_DELETION_PENDING` here, `DOCUMENT_DELETION_UNRESOLVED` there). Application finalisation asks again
  under its case lock (`DeletionFailure.DOCUMENT_DELETION_UNRESOLVED`), so its cascade can only ever take `Completed`
  receipts, never a live request, its steps, its lease or its fenced row.
- A completed request has no row and no V50 tombstone, so the application lists nothing for it. Application inventory
  also skips any tombstone whose id has a `Completed` receipt (`permanentlyDeletedDocuments`), so a verified permanent
  deletion is never sent to `CDAM_REMOVED_DOCUMENT_UNPURGED`; historical soft removals are still sent there.
- The receipt holds no reason, name or locator once `Completed`, so cascading it with the case loses nothing the audit
  does not keep. A restored application keeps its permanently deleted documents deleted.

## HTTP operations (Task 6)

`api/caseworker/controller/CaseworkerDocumentDeletionController`, counted apart as `DOCUMENT_DELETION_PATHS`, both
`Cache-Control: no-store`:

| Operation | Answers |
|---|---|
| `POST /caseworker/cases/{id}/documents/{documentId}/deletion-requests`, `@AuditedAs(DOCUMENT_DELETION_REQUESTED)` | 200 `{requestId, status: "Completed"}`; 202 `{requestId, status}` (`InProgress`/`NeedsAttention`) with `Location`; 400 body/key/version/reason; 404 unknown, other case's or fenced document; 409 `DocumentDeletionError {error, code}`; 413 over 16 KB; 415 not JSON |
| `GET /caseworker/cases/{id}/document-deletions/{requestId}` | 200 `{requestId, documentId, status, requestedAt, completedAt, failure}`, never the reason, the name, the requester or a locator; 404 unknown or another case's |

The body is closed `{reason, expectedDocumentVersion}` (`DocumentDeletionBodies`): reason a JSON string, version a JSON
integer, unknown keys refused without echo. `DocumentResponse` publishes `documentVersion`; `JudicialDocument` does not.
The author is always the verified token's subject and name; nothing in the body names one.

## Using it (user guidance for Admin)

- **Who can.** Caseworkers, Team leaders and Delivery Managers (and the deprecated admin role), on any application
  they can open. Masters cannot: their screens are read-only.
- **What happens.** The document is removed from the application, from the CCD case's current documents and from
  storage. It cannot be undone, and there is no restore, unlike a whole-application deletion.
- **The reason.** Required, in the user's own words, up to 500 characters (for example "Uploaded in error"). Hint:
  say why, and avoid unnecessary personal details of the applicant. It is kept in the audit trail for seven years and
  in a case note that follows the case's own note rules.
- **Confirmation.** Name the document and say the deletion cannot be undone. The page mints the `Idempotency-Key`
  once and sends the version it showed.
- **Refusals, in words.**
  - `DOCUMENT_IN_USE`: exactly "This document is currently being used and cannot be deleted. Try again once the
    operation has finished." It means an email waiting to go that carries the document, a deed still being prepared,
    or a document still being moved into storage. A preview or download in progress does not count.
  - `DOCUMENT_CHANGED`: the document changed since the page was shown; review it again.
  - `DOCUMENT_STORAGE_UNRESOLVED`: the document's upload has not settled, or it is on a store this service cannot
    reach; try later or ask for support.
  - `DOCUMENT_SHARED`: another document uses the same file; ask for support.
  - `APPLICATION_DELETION_PENDING`: the application itself is being deleted.
  - `KEY_REUSED`: the page sent a different body under the same key; start again from the document list.
- **Outcomes.** 200 `Completed` is done. 202 `InProgress` means the service is still finishing and will do so on its
  own; the document is already hidden. 202 `NeedsAttention` means support has to look at it; the document stays
  hidden and nothing is reported as deleted.
- **What it does not reach.** A copy a person already downloaded or previewed, an email GOV.UK Notify has already
  accepted (its download link lasts up to `notify.document-retention-weeks`), backups, earlier CCD event history and
  search history, and dm-store's own record that the document existed (kept, marked `hardDeleted`).

## Retention

| Record | Holds | Kept |
|---|---|---|
| `DOCUMENT_DELETION_REQUESTED` audit event | requester, time, request id and reason in typed detail, no values | seven years (`deedpoll_audit_dispose_events_before`, not yet scheduled) |
| `DOCUMENT_DELETED` audit event | the original requester, the time, request id, reason, `documentTypeId`, and the document's last values as audit values | the event seven years; its value rows are disposed with the case (`deedpoll_audit_dispose_case_values`), so the reason survives in the event detail |
| The case note (`NOTE_CREATED` audited) | `Document <name> (<id>) permanently deleted. Reason: <reason>`, authored by the requester | as any note: today's edit/delete rules, disposed with the case |
| `deedpoll_document_deletion_request` and steps | until `Completed`: the reason, the name and every unconfirmed locator; after: key, fingerprint, document id, requester, times | cascade with the case (application deletion) |

## Runbook

**Watch.** `deedpoll.documents.deletion.requests{status}`. Any `NeedsAttention` is a person's work. An `InProgress`
count that does not fall means the sweep is off, or failing every pass. Log lines carry the request id, the step kind
and a code, never a locator or the reason.

**Inspect** (read-only; never select `reason` or `locator` into a ticket):

```sql
SELECT r.id, r.case_id, r.document_id, r.status, r.failure, r.attempts, r.requested_at, r.next_attempt_at,
       r.lease_until IS NOT NULL AS claimed
  FROM deedpoll_document_deletion_request r
 WHERE r.status <> 'Completed'
 ORDER BY r.requested_at;

SELECT s.kind, s.state, s.outcome, s.attempts, s.completed_at
  FROM deedpoll_document_deletion_step s
 WHERE s.request_id = :request_id
 ORDER BY s.kind;
```

**`InProgress`.** Nothing to do while the sweep runs (`DOCUMENT_DELETION_RECOVERY_ENABLED`, default true): it claims
due requests every minute, resends only an unconfirmed step, and keeps every receipt. With it off, a request waits
`InProgress` and is never reported `Completed`. Never clear `owner`/`lease_until` by hand; a lease expires on its own
after `DOCUMENT_DELETION_RECOVERY_LEASE` (2 min).

**`NeedsAttention`, by code.** The request is never claimed again, the document stays fenced, and an application
deletion of the case is refused (`DOCUMENT_DELETION_UNRESOLVED`) until it is settled.

| Code | Investigate | Do not |
|---|---|---|
| `CDAM_PURGE_UNPROVEN` | CDAM answered 404. Ask the dm-store team whether the document id (the `CDAM_BINARY` step's locator, passed to them on a restricted channel) is `hardDeleted`; it may be our own lost 204, or a soft delete by somebody else whose blob remains | treat the 404 as a purge; send a soft delete |
| `CDAM_PERMISSION` | the deployed CDAM's `service_config.json` grants `deed_poll_api` `UPDATE` on `DEEDPOLL` (*Activation blockers*) | retry before the grant is deployed |
| `CDAM_DELETE_FAILED` | CDAM's 400/401 or repeated unknown answers: S2S secret, CDAM health, the id | resend outside the service |
| `CCD_REFERENCE_FAILED` | data-store's refusal of `updateSupportingDocuments` (or tries exhausted on 401/409/429/5xx): case state (`Registered` only), the system user's role, data-store health | edit the CCD case by hand |
| `LOCAL_FILE_UNREMOVED` | `DOCUMENT_STORE_ROOT` mounted, the key valid, file permissions | delete the file by hand and call it done |
| `LOCAL_COMPLETION_REFUSED` | the database refused completion (an audit, note or trigger failure); the remote steps are already `Done` | delete the row by hand: the audit and note would be lost |

**There is no supported resolution route yet.** Unlike application deletion's `confirm-cdam-purge.sql`, no reviewed
operator statement or endpoint moves a `NeedsAttention` document request on, and V51's triggers refuse rewriting
admission's half. A request needing attention stays visibly unresolved with its evidence. Settling one (marking a
confirmed step `Done` and requeueing, with an audited operator event) is follow-up work and needs its own reviewed
script and test; do not improvise one in production. A step reset to `Pending` would resend a delete whose second
answer, a 404, proves nothing.

## Rehearsal

Artifacts only; nothing here has been run against a live environment. The script is
[`DOCUMENT-DELETION-SMOKE.md`](DOCUMENT-DELETION-SMOKE.md):

- **AAT, filesystem store**: a disposable fixture application and document, proving the in-request success, the
  `DOCUMENT_IN_USE` refusal, the replay, the note and both audit events, and the file's absence.
- **Prod, CDAM**: a Delivery Manager creates a disposable test application, uploads a test document, deletes it with a
  reason, confirms the note, the audit and CDAM's 404 for the document, then deletes the application through
  application deletion. AAT cannot prove the CDAM path because it uses the filesystem store.

## Final regression evidence (Task 7)

Run 2026-11-26 at `7316940` plus Task 7's documentation, in the foreground, with
`TESTCONTAINERS_RYUK_DISABLED=true JAVA_TOOL_OPTIONS=-XX:UseSVE=0`:

| Command | Result |
|---|---|
| `./gradlew build test --rerun integration --rerun jacocoTestReport -I …/no-checkstyle.gradle --console=plain --no-watch-fs` | BUILD SUCCESSFUL in 4m 45s; `test` 5822 tests, 0 failures, 0 errors, 1 skipped; `integration` 32, 0 failures (counts from `build/test-results/{test,integration}` XML) |
| `…/.context/scripts/checkstyle.sh .` | `checkstyle: OK` |
| `git diff --check` | clean |
| changed-code line coverage (JaCoCo XML for `test`+`integration`, lines added under `src/main/java` since `edb7052`) | 744 of 794 executable added lines, 93.7%, over 51 files; lowest `CreateDocumentDeletionRequest` 1/2 (a record's accessor), `DocumentDeletionFingerprint` 7/9, `DocumentDeletionWork` 110/123 |

Failure and race suites in that run: `DocumentDeletionServiceTest` 18 (five two-transaction races),
`DocumentDeletionWorkTest` 18 (unknown answers, budget, crashes, lease loss, duplicate finalisation, forced note/audit
failure, concurrent removals), `DocumentDeletionApplicationDeletionTest` 8 (both admissions racing in each order),
`CaseworkerDocumentDeletionControllerTest` 15, `CdamPermanentDeleteContractTest` 12, `CdamPermanentDeleteTest` 14,
`DocumentDeletionMigrationTest` 12, `DocumentDeletionReasonTest` 32, `DeletionFinalisationTest` 10,
`PermanentDocumentRemovalTest` 4, `WriteEndpointAuditCoverageTest` 4, `OpenAPIPublisherTest` 27; all passed, none
skipped. Not run here: Sonar (Jenkins' gate, unchanged) and Jenkins' own Checkstyle tasks; `cftlibTest` is local
and was not run for this feature, so the CCD document event against a real data-store is unproved until the
rehearsal.

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
