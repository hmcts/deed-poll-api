# CDAM AAT round trip — operator procedure

Prepared for plan 03a. **Not run.** Local cftlib evidence does not establish AAT onboarding or permissions.
Use synthetic cases and a harmless PDF file. Record the deployed API commit, definition version, CCD/CDAM/dm-store
versions, date, expected bytes and observed status codes. Keep tokens out of the record.

## Preconditions

1. Complete [CCD-SMOKE.md](CCD-SMOKE.md), including confirmed jurisdiction, case type, system role, S2S identity,
   system user, definition import, decentralised routing and the linking sweep. Never substitute invented IDs.
2. Register the API's S2S identity on CDAM's allow-list and grant CREATE, READ and UPDATE for the confirmed case
   type/jurisdiction. Data-store needs ATTACH and READ. It attaches documents before our persistence callback;
   this API does not call `attachToCase` directly.
3. Confirm the caseworker's CCD profile grants READ on the case type, state and `supportingDocuments` field.
   The current definition grants only the system profile. The local test grants that existing profile to a
   synthetic caseworker; this is not an instruction to grant system privileges to real caseworkers. Provision
   the agreed staff access model before testing real staff downloads.
4. Set `DOCUMENT_STORE=cdam`, `CASE_DOCUMENT_AM_URL` to the confirmed endpoint, and `DOCUMENT_URL_BASE` to a base
   accepted by data-store's document URL pattern. Configure all CCD identifiers and system credentials together.
   Restart and confirm readiness. Keep ordinary traffic away during this controlled test: generated deeds and
   Notify retrieval are still plan 03b, and existing filesystem-document migration is plan 03c.
5. Arrange with the dm-store operator to capture the actual **response bodies** for the synthetic transaction
   below. CDAM's public response is not a dm-store capture. Do not enable unrestricted HTTP body/token logging.

## Journey

1. Authenticate through the normal staff client. Create a synthetic case using `POST /caseworker/cases`; save its
   local UUID. Call `GET /caseworker/cases` with linking enabled and confirm a single binding via an authorised
   database read or the CCD attempt operations. The case API deliberately does not expose the CCD reference.
2. Upload a known PDF file using `POST /caseworker/cases/{id}/documents`, multipart parts `file` and
   `documentTypeId`. Expect 201, the local document ID, and `hasFile: true`. The response must expose no CDAM ID,
   URL or hash token. Record wall-clock latency.
3. Verify the local row is `Attached`, `cdamDocumentId` is set and `cdamHashToken` is NULL. Read the CCD case through
   data-store: its document collection must contain the reference and no hash. Ask the dm-store operator to
   confirm the document's metadata has `case_id`, `case_type_id`, `jurisdiction`, and its TTL is cleared.
4. Download `/caseworker/cases/{id}/documents/{documentId}/file` as the authorised caseworker. Expect 200 and
   byte-for-byte equality. Restart the API or route the next download to another replica; repeat the comparison.
5. Repeat the download as a synthetic staff user admitted by persistence but without CCD READ on the case type.
   Expect 403/404 and no bytes; confirm CDAM performed the denial. An entirely unmapped staff user currently
   gets 502 through the upstream error wrapping described in the recovery guide, still with no bytes. Record
   that separately rather than counting it as proof of CCD's READ check. Request the same local document ID under a different case UUID: expect
   404 before CDAM is called. Upload against an unlinked case: expect 409 and no remote upload.
6. Delete through `DocumentService.removeDocument` using an approved operator/test harness. **There is no public
   HTTP document-delete endpoint; do not add one or delete the database row by SQL to simulate this step.**
   Confirm the local row and CCD document reference are gone, then request the remote binary through CDAM using
   an authorised user and service token: expect 404. Confirm dm-store received `permanent=false`.

## Captures and drift guard

Capture upload HAL, metadata before and after attachment, bulk-patch acknowledgement, binary response headers,
TTL-patch response if exercised, and deletion/post-deletion responses. Sanitise user IDs, case/document IDs,
filenames, dates and content consistently; remove authentication headers and all CDAM hash tokens. Keep field
names, types, null/absent distinctions and HAL structure unchanged.

Replace the source-derived shapes in `StubDmStore` with the sanitised evidence and update
`src/test/resources/dm-store-contract.md` with date, versions and capture provenance. Recheck the recorded
required-header discrepancies with the dm-store team. Run `checkDmStoreSpecDrift`, the contract tests, the full
build and `cftlibTest`. A mismatch is a finding to resolve, not a reason to loosen the stub until it passes.

If any step fails, keep CDAM rollout disabled for ordinary traffic and follow [CDAM-RECOVERY.md](CDAM-RECOVERY.md).
Switching back to the filesystem cannot serve documents already stored in CDAM; preserve both storage backends
and their rows until the migration/rollback procedure is agreed.
