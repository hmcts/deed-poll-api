# CDAM storage states and recovery

Plan 03a records remote progress on `deedpoll_supportingdocument`. The API's local document UUID remains its
public identity; `cdamDocumentId` is an internal locator. Never copy `cdamHashToken` into a ticket, log, metric,
case projection or API response. It is a temporary capability, cleared when attachment succeeds or is refused.

`deedpoll_documents_storage{state="…"}` on `/prometheus` counts rows by the seven bounded states. It has no
case, document or user labels. Counts are current database reads; a database outage can produce a failed/NaN gauge,
not proof that the count is zero. Investigate sustained uncertain/pending counts using authorised database reads
of IDs, state, attempt time and attempt count, **excluding the hash-token column**.

| State | Meaning | Safe next action |
|---|---|---|
| `Local` | Filesystem row; pre-V25 rows were backfilled this way | Preserve the original volume. CDAM cannot serve it; migration is 03c. |
| `UploadPending` | Claimed locally; an upload may be in flight | Let the request finish. After a crash, do not assume no upload occurred or reset the row. Inspect the remote evidence. |
| `UploadUncertain` | Upload response was lost/unreadable | The request retries once while it still holds the bytes, at most two uploads total. After the second uncertain answer there is no automatic retry. Keep the row; an ordinary caseworker upload can be retried as a new document. Generated deeds and owned promotions must retain their reserved identity; see below. An unattached remote copy retains its upload TTL. |
| `Uploaded` | Remote ID and transient token recorded; not yet attached | Allow an in-flight request to finish. After interruption, confirm remote metadata and CCD evidence. 03a has no operator resume endpoint; do not issue a fresh binary upload for this row or set it Attached by SQL. |
| `AttachUncertain` | Event submission may have attached the document | The request retries the attach once. A successful handler may settle the row even when the answer is lost. If still uncertain, compare CDAM metadata, CCD's document reference and the local row. Escalate for a reviewed reconciliation; no operator replay endpoint exists in 03a. |
| `Attached` | The document event accepted the reference; hash cleared | Download through the case-scoped API under the caller's token. A 403/404 may be a CCD permission issue; do not fall back to the system user's token. |
| `Failed` | Upload was refused, or the document event was definitely refused before local attachment | Keep the evidence and correct the cause. A fresh ordinary user upload creates a new row; this is not recovery for a prepared deed or owned promotion. A binary may still exist remotely after an attach refusal; Failed does not prove physical absence. |

Do not reset counters or states, fabricate a CCD binding, change a CDAM ID, or re-use a hash with a different case.
There is no background document recovery worker in 03a. Case-link repair operations repair **case creation**,
not document uploads.

**Observed permission limitation (cftlib 0.19.2377):** an authenticated staff user with no mapped persistence
profile reaches CDAM, whose CCD permission lookup calls back into this API. The persistence gate correctly
answers 403; data-store wraps that as 500 and CDAM answers 502. No binary is read, but the user sees an upstream
failure rather than a permission message. This is pinned separately from the genuine CCD READ-denial journey.
Do not reinterpret every CDAM 502 as forbidden or relax the persistence gate to hide it. A production staff
profile and/or upstream error propagation needs separate work.

## Delete failures

`DocumentService.removeDocument` deletes the row in its transaction. After commit it submits the remaining CCD
document collection, then requests a CDAM **soft delete** (`permanent=false`), both best effort. There is no public
document-delete HTTP endpoint. A cleanup failure is logged with identifiers and fixed diagnostic prose; it does
not undo the row deletion. Preserve those IDs in the restricted incident record.

A failed event can leave a stale CCD snapshot/search entry until a later successful event, while the live case
view already excludes the deleted row. A failed remote delete can leave an orphaned binary. Have the platform
operator verify the case and remote metadata before arranging an authorised cleanup; never use `permanent=true`
as a retry. Permanent disposal is the retention plan's work. In dm-store, a permanent delete after a soft delete
does not necessarily remove the blob, because the lookup already hides it.

There is no local undelete and no dm-store undelete API. **Only the dm-store team can arrange restoration**;
restoration of a blob alone does not reconstruct the deleted local row or CCD reference.

## Rollout and rollback

The default is `DOCUMENT_STORE=filesystem`. Enable CDAM only after the [smoke prerequisites](CDAM-SMOKE.md) are met.
`CdamOnboarding` refuses placeholder CCD identifiers and local/default CDAM URLs when CDAM is selected. The `test`
and `cftlib` profiles may use placeholders only with a loopback CDAM endpoint. These profiles are not deployment
profiles. The check validates configuration, not live permissions or connectivity.

03b implements generated deeds, Notify retrieval and owned staging locally; 03c owns existing-file migration. The
filesystem default is a development convenience and cannot make ephemeral pod storage durable. Do not treat
03a as a completed production storage cutover. Switching back to filesystem also makes existing CDAM rows
unreadable through this API. Keep the database and both stores intact while a migration/rollback is planned.

## Notification delivery uncertainty (03b Task 5)

The drain commits each claim, send-start and outcome separately. An expired claim with no send-start
can retry within the eight-attempt cap. An expired claim with send-start stops as `Failed`/`TECHNICAL`,
with one case note for investigation; it is not automatically delivered again. A send-start commit can
succeed even if the process dies before sending, so a stopped email may never have left the API.

Preserve the notification row, owner/generation, send-start and provider receipt evidence. Do not clear
markers, reset attempts or create a replacement to bypass uncertainty. There is no operator resend or
provider-reconciliation endpoint in this release. See the [caller contract's upgrade, rollback and
investigation procedure](CDAM-CALLERS.md#upgrade-rollback-and-investigation-boundary) and ADR-0007 linked there.
Old draining instances must be fully stopped before V28/new workers; mixed versions and rolling back to
old workers on this ledger are unsupported. Operational investigation ownership remains a release gate.

## Prepared deeds and owned stages

For CDAM forms, V26/V27 commit one preparation per case: local document ID, template, original filename/MIME,
size, SHA-256 and resolved-input fingerprint. Upload/attachment occur outside that transaction. Only verified
Attached read-back releases its payload; the preparation evidence survives. A failed final email enqueue can
leave a valid attachment and no notification. Retry the same compose key to observe/reuse it. Never generate
another deed from later case data, delete a reservation or treat the document type name as provenance.

For a prepared deed or owned promotion, the ordinary upload advice to create a new document does not apply:

- An unattempted reservation can claim its first upload. After an upload may have started, a retry cannot
  reset the two-send budget or replay an ambiguous upload. `Failed` may still have a remote binary.
- `Uploaded`/`AttachUncertain` left by a crash require reviewed reconciliation of local/CCD/CDAM evidence;
  there is no general operator resume endpoint or expired-hash refresh mechanism.
- `Attached` permits a verification-only retry. Unavailable read-back, wrong checksum or failed payload-release
  commit keeps the bytes. Resolve the cause; never clear the payload manually to bypass verification.
- Material deed-input changes cause a refusal, not regeneration. Harmless edits cannot rewrite frozen bytes.

V29's owned stages bind RECORD or DRAFT owner ID and authenticated subject to an operation UUID. The trusted
adapter must establish that authority before calling `OwnedStaging`; no citizen HTTP adapter exists yet.
`Staged` may become `Promoting`, `Removed` or `Expired`; `Promoting` can only become `Consumed`. Promotion binds
one case/type/document, requires its existing CCD link and never links it. It returns the local document ID
only after verified attachment. Removal/expiry discard only Staged payloads and retain operation tombstones.
Retries retain original expiry; an expired receipt does not grant a fresh admission under the same operation.
Admission and promotion lock the case before quota/stage locks. Case deletion refuses retained RECORD uploads
and retained draft promotions targeting that case; remove unpromoted uploads or resolve storage first.

Only expired Staged rows are selected by the independent bounded cleanup scheduler. Promoting rows and all
generated preparations remain retained, including failed or uncertain storage work. Review stage and document
states together. Cleanup uses row/byte/time budgets and skips locked rows; a failed current item rolls back
without undoing earlier items. A statement/lock timeout returns the committed row count and logs bounded progress.
Other database errors still propagate. Database timeouts bound waits, not all scheduler/connection overhead. Cleanup
is not remote deletion, application disposal, tombstone retention or backup erasure.

## Quiesce, upgrade and rollback procedure

1. The release operator blocks ingress to every old API instance, including case-list and email-send requests,
   stops any operator harness and lets in-flight requests finish. Then scale/stop every old instance and verify
   there are no surviving workers/connections performing application work. There is no email-scheduler switch:
   the drain is traffic-triggered. If a send remains ambiguous, preserve its evidence for investigation.
2. With old workers stopped, take/verify the approved database and filesystem recovery points, apply additive
   migrations V26–V29 and start only compatible binaries. V28 conservatively marks attempted legacy Pending
   notifications as possibly sent; a previous temporary verdict cannot prove the last attempt was refused.
   Restore traffic only after readiness and the controlled smoke. Never run old and new drain binaries together.
3. To withdraw staging, first block new adapter admissions and stop/quiesce promotions, then disable staging
   and restart all instances. This also stops cleanup: record retained backlog and assign an operator until a
   compatible scheduler resumes. Do not drop staged rows/payloads or return them to Staged. Even while staging
   is disabled, CDAM deeds need the shared payload cap. An old binary cannot recover preparations/stages.
4. To roll back application code, quiesce all new workers and retain the ledger/stores. Prefer a forward fix;
   resuming an old drain requires a separately reviewed reconciliation/migration. Restoring a database alone
   does not undo an accepted Notify send or remote attachment. Do not reset claims to make rollback pass.
   `DOCUMENT_STORE=filesystem` cannot serve Attached CDAM rows; `cdam` cannot serve existing Local rows.

These are prepared release steps, not deployment evidence. No environment was upgraded by plan 03b.

## Monitoring and operational ownership

The shipped `/prometheus` document-state gauge is described above. Preparation/staging age/byte gauges and
an expiry-failure counter are **not exported by this release**. Before activation the service operations owner
must wire the following aggregate monitoring (an authorised read-only SQL collector is sufficient for backlog),
choose alert thresholds/cadence and demonstrate alert delivery. This is an open release gate, not local proof.
Use bounded labels only: document state (seven values), stage state (five), owner kind (two), or fixed failure
category. Never label case/document/user IDs, names, tokens, hashes, filenames or payload content.

| Signal | Measurement and action |
|---|---|
| Shared retained bytes | Sum `deedpoll_stagedpayload.sizeBytes`; compare with the configured shared cap and alert with headroom before admission stops. This includes protected/expired payloads. |
| Preparation backlog and oldest age | Join preparation `documentId` to payload `id`; count, sum bytes and age since earliest preparation `createdOn`. Do not count released preparations as backlog. |
| Owned backlog and oldest age | Join owned stage `id` to payload `id`; count, sum bytes and age by ownerKind/state. Promoting age remains visible even after original expiry. |
| Uncertainty | Document gauge's UploadPending/UploadUncertain/Uploaded/AttachUncertain/Failed counts; correlate by restricted reads with preparation/promotion evidence. Aggregate Failed/TECHNICAL notifications retaining sendStartedAt for investigation; it is not a proved-not-sent count. |
| Expiry lag and failures | Count expired Staged rows still holding payloads; measure age beyond oldest expiresOn. Capture scheduled cleanup failures/timeouts as a fixed-category counter in the operations collector and monitor scheduler liveness. Persistent lag can also mean locks/capacity/disabled scheduling, not just exceptions. No built-in success/failure counter exists. |

These read-only queries produce aggregate outputs, never identities or content. Run using UTC with a bounded
statement timeout on the agreed monitoring connection; no permission to modify rows is needed:

```sql
BEGIN READ ONLY;
SET LOCAL TIME ZONE 'UTC';
SET LOCAL statement_timeout = '2s';
SELECT count(*) AS retained_payloads, coalesce(sum("sizeBytes"), 0) AS retained_bytes
FROM deedpoll_stagedpayload;
SELECT count(*) AS preparations, coalesce(sum(p."sizeBytes"), 0) AS retained_bytes,
       coalesce(extract(epoch FROM (CURRENT_TIMESTAMP - min(d."createdOn" AT TIME ZONE 'UTC'))), 0)
         AS oldest_seconds
FROM deedpoll_deedpreparation d JOIN deedpoll_stagedpayload p ON p.id = d."documentId";
SELECT s."ownerKind", s.state, count(*) AS stages, sum(p."sizeBytes") AS retained_bytes,
       extract(epoch FROM (CURRENT_TIMESTAMP - min(s."createdOn"))) AS oldest_seconds
FROM deedpoll_ownedstage s JOIN deedpoll_stagedpayload p ON p.id = s.id
GROUP BY s."ownerKind", s.state;
SELECT count(*) AS expired_backlog,
       coalesce(extract(epoch FROM (CURRENT_TIMESTAMP - min(s."expiresOn"))), 0) AS overdue_seconds
FROM deedpoll_ownedstage s JOIN deedpoll_stagedpayload p ON p.id = s.id
WHERE s.state = 'Staged' AND s."expiresOn" <= CURRENT_TIMESTAMP;
COMMIT;
```

Empty grouped results mean no backlog only after a successful query. A failed scrape/query is unknown, never
zero. Measure payload/table/index/TOAST size, WAL growth, replica lag, backup size/restore duration and peak
admission versus cleanup throughput before agreeing the shared/owner caps. Logical byte quotas are not a
physical database size limit; deleted payloads can remain in backups and require vacuum/capacity planning.
Do not reclaim protected evidence to fit a cap.

Release responsibilities are unassigned until named people/teams accept them in the product
[release checklist](../../deed-poll-documentation/operations/deployment-readiness-checklist.md):

- Service operations/release owner: configuration, quiescence, monitors, backlog triage, smoke evidence and rollback.
- Database/backup owner: peak/WAL/TOAST capacity, backup retention, restore exercise and quota headroom.
- Correspondence business owner plus Notify operator: investigate unknown delivery under
  [ADR-0007](../../deed-poll-documentation/decisions/0007-uncertain-email-delivery-requires-investigation.md).
  Quiesce a possibly-live sender first; correlate retained receipt/reference with available provider evidence.
  No elapsed interval or missing receipt alone permits resend. No resend/reconciliation endpoint is supplied.
- Product retention owner: approve each owner-type expiry duration, rationale and effective configuration under
  [ADR-0008](../../deed-poll-documentation/decisions/0008-staged-upload-expiry-requires-release-agreement.md),
  and cleanup schedule/budgets/responsible team under
  [ADR-0009](../../deed-poll-documentation/decisions/0009-staged-upload-cleanup-runs-on-a-bounded-schedule.md).
- CCD/CDAM/dm-store and identity owners: external onboarding, staff/system permission proof, response captures
  and authorised remote investigation/restoration. Configuration validation does not establish these facts.

## Concrete handoff to 03c and citizen/web work

03c must inventory every Local row and its original file: local document/case/type identity, direction, storage
key, original filename/MIME, actual size and independently computed SHA-256, confirmed CCD binding, duplicate or
missing files and every queued notification's declared frozen reference. Do not infer generated provenance
from a deed type name: distinguish original generated deeds from signed Incoming uploads with reviewed evidence.
V26/V27 preparation checksum/template/fingerprint and V29 consumed document bindings are additional immutable
inputs for new work, not a fabricated backfill for legacy files.

Preserve the source volume and a verified recovery copy. Migration must keep the local document identity and
queued JSON references, upload the exact original bytes (never regenerate old deeds from current case data),
attach through the existing CCD event, verify remote size/checksum and CCD ownership/snapshot, then prove staff
and deferred service-reader access across restart. Record a resumable per-item ledger, ambiguity rules and
cutover/rollback reconciliation before deleting any source. Quiesce conflicting writes/drains during the agreed
cutover; classify uncertain existing email sends before resuming. 03b supplies neither this migration nor mixed
filesystem/CDAM fallback. Missing originals, old deeds, pending emails and Local rows remain cutover blockers.

Fees & Pay/citizen work must supply durable authenticated draft ownership and a verified draft-to-case mapping,
then call the [owned-stage domain contract](CDAM-CALLERS.md#task-6-implementation--owned-staging-2026-10-02).
The web task must actually transfer file bytes/metadata, preserve one operation UUID across request retries,
store opaque stage receipts with their original expiry, and handle quota/expiry/conflict/unknown outcomes.
It must map domain refusals to reviewed HTTP/UI behaviour and test cross-owner denials, changed-content retries,
restart, removal, expiry and one-time promotion together. The application flow owns payment/queue eligibility
and CCD linking; stage admission never makes an unpaid draft queue-eligible. No citizen activation until that
real adapter and web byte journey pass end to end. Domain fixtures and the local cftlib RECORD owner do not
prove citizen authentication, browser transfer or deployed access.

### External review safeguards

Case deletion refuses while a generated deed still has retained payload bytes. V30 also blocks
deleting its preparation through a database cascade, so recovery bytes cannot lose their case or
be silently discarded. Resolve storage and verify read-back before ordinary case deletion; coordinated
disposal remains separate work. Already orphaned payloads, if any, need investigation rather than
automatic deletion because their recovery provenance is missing.

A late Notify outcome rejected by the lease fence emits an error with the notification ID and attempt.
For provider acceptance it also records the provider notification ID and template version. Use these
to investigate with Notify; the worker does not settle the row or authorize a resend. Rendered subject,
body, recipient and request ID are excluded from the log. The correspondence rendering is not recovered
into the row by this diagnostic; provider reconciliation remains separate work.
