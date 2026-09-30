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
| `UploadUncertain` | Upload response was lost/unreadable | The request retries once while it still holds the bytes, at most two uploads total. After the second uncertain answer there is no automatic retry. Keep the row; the caseworker can upload again as a new document. An unattached remote copy retains its upload TTL. |
| `Uploaded` | Remote ID and transient token recorded; not yet attached | Allow an in-flight request to finish. After interruption, confirm remote metadata and CCD evidence. 03a has no operator resume endpoint; do not issue a fresh binary upload for this row or set it Attached by SQL. |
| `AttachUncertain` | Event submission may have attached the document | The request retries the attach once. A successful handler may settle the row even when the answer is lost. If still uncertain, compare CDAM metadata, CCD's document reference and the local row. Escalate for a reviewed reconciliation; no operator replay endpoint exists in 03a. |
| `Attached` | The document event accepted the reference; hash cleared | Download through the case-scoped API under the caller's token. A 403/404 may be a CCD permission issue; do not fall back to the system user's token. |
| `Failed` | Upload was refused, or the document event was definitely refused before local attachment | Keep the evidence and correct the cause. A fresh user upload creates a new row. A binary may still exist remotely after an attach refusal; Failed does not prove physical absence. |

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

03b still owns generated deeds, Notify retrieval and pre-case staging; 03c owns existing-file migration. The
filesystem default is a development convenience and cannot make ephemeral pod storage durable. Do not treat
03a as a completed production storage cutover. Switching back to filesystem also makes existing CDAM rows
unreadable through this API. Keep the database and both stores intact while a migration/rollback is planned.
