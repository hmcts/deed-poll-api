# Plan 03 — CDAM document storage and case attachment

Status: draft for review, 2026-09-26. Depends on [plan 02](completed/deed-poll-api-02-ccd-foundation.md).

**Decision 2026-09-27 (Steve): link first, not on demand.** Document code only ever handles a record that already
has a CCD case, and refuses one that does not ("case not ready"). It never calls the linking operation itself.
So this plan now also depends on:
- [plan 02b](completed/deed-poll-api-02b-ccd-linking-lifecycle.md). Its sweep links every record with a null reference,
  whether new, existing or imported, and it gives `NeedsRepair` the way out that plan 02 left manual-only.

It does **not** depend on [ccd-migration](ccd-migration.md), which is now Dataverse ingestion only.

Chosen for cleaner code: CCD failures stay out of the caseworker's request path, and they are handled in batch
with an operator view. The rejected alternative was linking on demand inside the upload request: documents
would reach CDAM sooner, but the request path would be messier. Dataverse documents are unaffected by this
choice; they move by this plan's document migration either way.

**Decision 2026-09-28 (Steve): the cftlib tests use a WireMock dm-store, with real CDAM and CCD.** cftlib runs
CDAM in-process but points it at AAT's dm-store (`DM_STORE_BASE_URL` in `LibRunner.java`). Tests make no live
calls, so `DM_STORE_BASE_URL` points at a stub of dm-store's API instead. The hash token, the attach step and the
CCD-backed permission check stay real. Rejected: a containerised dm-store (plus Postgres and Azurite), which is too
heavy on top of cftlib's ~5.1 GB peak, and stubbing CDAM, which would prove none of the attach or permission rules.

The stub encodes our reading of dm-store's contract. The published spec
(`cnp-api-docs/docs/specs/document-management-store-app.json`, "2-beta") types almost every response as a bare
`object`, so the spec alone can't pin the response bodies CDAM reads. Four guards against divergence:
1. The stub records the cftlib version it was built against (`0.19.2377`, which also pins CDAM) and the spec
   commit. A unit test fails when the cftlib plugin version in `build.gradle` changes and the recorded value
   doesn't.
2. A copy of the spec lives in test resources. A unit test checks that every request the stub accepts
   exists in it: method, path, required headers and multipart fields.
3. The operator-run AAT round-trip also captures dm-store's real response bodies, and the stub is built from
   those captures, not from guesswork.
4. A manual Gradle task compares the copy of the spec with `cnp-api-docs` and reports differences. It isn't
   scheduled, because api has no nightly pipeline. A `Jenkinsfile_nightly` would be separate work.

**Decision 2026-09-28 (Steve): split into three plans, each mergeable on its own.** 03a is written: [deed-poll-api-03a-cdam-core-round-trip.md](deed-poll-api-03a-cdam-core-round-trip.md).
- **03a, core round trip:** the `DocumentStore` seam, document states, the projection field and document event,
  upload, attach and download, the WireMock dm-store and its guards, and config and the runbook.
- **03b, callers:** generated LOC020/LOC022 deeds, Notify's drain-time retrieval, and pre-case staging, retry and
  expiry.
- **03c, migration:** the bounded migration of existing documents. It's kept separate because it is coupled to
  Dataverse ingestion timing.

**Decision 2026-09-28 (Steve): build 03a on `DEED_POLL_PLACEHOLDER`, as plan 02 was.** The jurisdiction, case
type and S2S identity are still unconfirmed. Onboarding goes in a Dependencies section, not the task list, and only
the operator-run AAT round-trip, which is also the capture of dm-store's real responses, waits on it. In cftlib the
placeholder has to be added to CDAM's service-to-case-type permissions as well as CCD's. Onboarding gains a
CDAM permissions entry. The "real confirmed" wording in the next section applies to that round-trip, not to the
build.

**Decision 2026-09-28 (Steve): a caseworker's delete is a CDAM soft delete.** The row and the case's document
reference go, and after the commit the api calls CDAM's `DELETE /cases/documents/{id}` with `permanent=false` (the
default, `CaseDocumentAmController.java:353`). A failed call is logged with both ids and does not fail the delete,
the same as today's best-effort blob cleanup. `permanent=true` is never sent: permanent removal belongs to the
[retention plan](retain-and-dispose.md). Recovery is laborious, since only the dm-store team can restore a
soft-deleted document, but it is more than the current filesystem delete allows. A task must prove that a
soft-deleted document can no longer be downloaded. Rejected: keeping the row as a local soft delete with our own
restore path. Nobody has asked for undelete, so that's a new state every document read must filter, built ahead of
need.

## Outcome

Replace the development filesystem store with CDAM-backed document handling. Uploads,
generated deeds and existing documents must be associated with the correct CCD case,
retrievable by authorised callers and covered by coordinated disposal. API callers
continue to use deed-poll document identifiers.

CDAM fronts Document Store; application code calls CDAM. Upload success alone is not
completion: the document must be attached to the case, and its access permissions must
work after attachment. Unattached uploads have a temporary retention lifecycle.

## Decisions and dependencies

- Use the real confirmed jurisdiction/case type and API S2S identity. Record CDAM service
  configuration, operation permissions, file limits and lower-environment provisioning.
- Extend the minimal CCD projection with typed document references and the smallest
  event needed to attach/update them. Validate which stages of the selected decentralised
  event flow verify hashes and attach documents; do not assume a handler-only mutation
  triggers every centralised document hook.
- Preserve document metadata, case ownership, direction and generated-deed identity.
  CDAM IDs/URLs are storage references and never replace the local document's identity.
- The current `DocumentStore.save(byte[])` returns only a storage key and carries no
  filename, content type, caller or case context. Verify and evolve this seam explicitly;
  a drop-in filesystem replacement is not an established design. Keep storage concerns
  out of the deed generator and adapt affected callers, including Notify uploads.
- Test downloads through CCD-backed document permissions for a decentralised case.
  Upload permission or uploader access alone does not establish caseworker access.

## Tasks

- [ ] Record an upload/attach/download sequence against the pinned CDAM client and CCD
      version, including metadata, transient hash token, failure boundaries and auth.
- [ ] Define local document states sufficient to distinguish upload pending, uploaded
      but unattached, attached and failed/uncertain operations. Define safe recovery when
      either remote success response is lost; do not blindly upload duplicate binaries.
- [ ] Extend the definition/projection and add an authorised document event. Update local
      metadata and SDK snapshots consistently. Direct domain writes alone do not refresh
      a previously recorded CCD event snapshot or search index.
- [ ] Implement CDAM upload and case attachment, retaining hash material only as required
      by the documented transient lifecycle. No hash tokens in logs or permanent case views.
- [ ] Implement case-scoped streaming download and permitted document deletion. Verify
      both local parent ownership and remote permission; do not accept arbitrary URLs/IDs
      from a caller as authority to fetch another case's document.
- [ ] Adapt supporting-document upload, generated LOC020/LOC022 deeds and Notify's
      drain-time file retrieval. Preserve generate-once rules, content and failure handling.
- [ ] Define which pre-submission uploads are supported. A document awaiting a CCD case
      needs an explicit staging/retry/expiry path; do not silently keep local pod storage.
- [ ] Build a bounded document migration process preserving source/local document identity,
      names, MIME types, lengths and content checksums. Record progress separately from
      case-link progress. Retain the source until verified upload, attachment and read-back.
- [ ] Add validated configuration, secret/chart mappings, safe metrics and a recovery
      runbook. Configure deployed environments to require the intended storage backend.

## Acceptance and validation

- [ ] Upload → attach → authorised download returns the original bytes, including after
      process restart and from a different API replica.
- [ ] Wrong-case access, missing permissions, unsupported files and invalid hashes fail
      safely. Missing/wrong S2S and user credentials are rejected.
- [ ] Lost upload/attachment responses can be reconciled. An expired unattached upload
      and an interrupted migration do not become a falsely successful local document.
- [ ] Existing generated deeds are reused, and Notify resolves attached document bytes
      without changing its idempotency, case scope or transaction behaviour.
- [ ] Repeat document migration produces one attached result per source document with
      verified bytes and observable failures.
- [ ] Unit/full-context contract and Postgres tests use stubbed external services. Run
      repository build gates; prepare an operator-run non-production CDAM/CCD round-trip.

Disposal authority and hard-delete sequencing are owned by the
[retention plan](retain-and-dispose.md). Do not enable blanket document deletion as a
side effect of this storage cutover.

## Sources

- [Documents and CDAM](../../../apps/ccd/docs/explanation/documents-and-cdam.md)
- [Document storage recipe](../../../apps/ccd/docs/how-to/store-a-document.md)
- [Decentralised contract](../../../apps/ccd/docs/reference/decentralised-callbacks.md)
- [Current storage seam](../../src/main/java/uk/gov/hmcts/reform/deedpoll/domain/documents/DocumentStore.java)
- [Document service](../../src/main/java/uk/gov/hmcts/reform/deedpoll/domain/documents/DocumentService.java)
- [Notify document retrieval](../../src/main/java/uk/gov/hmcts/reform/deedpoll/notify/DocumentUploads.java)
