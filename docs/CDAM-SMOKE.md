# CDAM AAT round trip — operator procedure

Prepared for plans 03a/03b. **Not run.** Local cftlib evidence does not establish AAT onboarding or permissions.
Use synthetic cases and a harmless PDF file. Record the deployed API commit, definition version, CCD/CDAM/dm-store
versions, date, expected bytes and observed status codes. Keep tokens out of the record.

## Preconditions

1. Complete [CCD-SMOKE.md](CCD-SMOKE.md), including confirmed jurisdiction, case type, system role, S2S identity,
   system user, definition import, decentralised routing and the linking sweep. Never substitute invented IDs.
2. Register the API's S2S identity on CDAM's allow-list and grant CREATE, READ and UPDATE for the confirmed case
   type/jurisdiction. Data-store needs ATTACH and READ. It attaches documents before our persistence callback;
   this API does not call `attachToCase` directly.
3. Import the definition with `DEED_POLL_DOCUMENT_READER`: it maps the existing `caseworker-deedpoll-admin`
   role to READ on the case type, state and `supportingDocuments` only. Register that IDAM role in definition
   store at PUBLIC, alongside the system role. Test staff must have their normal admin role, not the system
   role. Generic caseworker and judicial roles remain unmapped; enabling their downloads needs a separate
   access model preserving private-case and referral visibility.
4. Set `DOCUMENT_STORE=cdam`, `CASE_DOCUMENT_AM_URL` to the confirmed endpoint, and `DOCUMENT_URL_BASE` to a base
   accepted by data-store's document URL pattern. Configure all CCD identifiers and system credentials together.
   Restart and confirm readiness. Keep ordinary traffic away during this controlled test: existing
   filesystem-document migration remains plan 03c, and local caller proof does not establish deployed access.
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
   Confirm the staff token contains no system role. Submitting `updateSupportingDocuments` directly through
   CCD as that staff caller must be refused without changing the case or its documents. Receiving a start-event
   token alone is not proof of write access: the local stack defers this refusal until submission.
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

## Forms, attachment and a later Notify drain (03b)

Prepared, **not run against AAT**. Repeat for both LOC020/adult and LOC022/minor using synthetic applicants
and an approved test recipient. Confirm Notify service, each template, reply-to and 1–78-week retention
configuration, plus actual system-user CCD READ and CDAM READ permission. Configure a capacity-approved
`DOCUMENT_STAGING_MAX_TOTAL_BYTES` for durable deed preparation. Owned staging may remain disabled.
Complete the worker-quiescence and migration procedure in [CDAM-RECOVERY.md](CDAM-RECOVERY.md) first.

1. Create/link each synthetic case via the ordinary flow. Preview and availability must not reserve a deed,
   upload bytes or enqueue. Request the appropriate forms email with one recorded compose UUID used as the
   `Idempotency-Key`. Expect 200 only after attachment and enqueue (not proof of delivery).
2. For proof of a **later** drain, use an approved in-process operator/test harness calling the production
   `CaseEmailService.send` coordinator directly, so the controller's immediate drain is not invoked. Isolate
   the test instance from case-list/email traffic until the observation is complete. There is no public
   defer-drain endpoint; do not add one or manipulate notification claim/send-start fields by SQL.
   If no approved harness is available, record this step as pending, not passed by an immediate send.
3. Record the local document identity, Outgoing direction, original filename/MIME, size and SHA-256 in a
   restricted evidence record. Confirm one preparation, Attached local row, CCD reference and latest event
   snapshot, no published hash and released temporary payload after read-back. Ask the dm-store operator
   to confirm attachment metadata/cleared TTL using the response captures described above.
4. Repeat with the same compose key and with a new key for the already-owed same-template email. Expect the
   same notification/document, `enqueued=false`, no extra enqueue note and no second successful binary upload.
   A harmless later case edit must not rewrite the deed or frozen correspondence. Material edits during
   preparation must refuse stale enqueue, not regenerate; use the local fault tests for deterministic races.
5. Restart/replace the API context before draining. In the approved harness invoke the production drain with
   no staff security context. Verify the internal reader obtains fresh system/S2S authority and reads only
   the claimed notification's declared, case-owned document. The send uses its frozen recipient and fields.
   Retrieve the received Notify link through the test recipient's email-confirmation step: compare original
   filename, bytes and SHA-256, and verify the agreed retention. A provider acceptance alone is not this proof.
6. Run staff download/wrong-case/CCD READ refusal checks from the earlier journey separately. Service-reader
   success never proves staff access. If the platform owner can arrange a safe synthetic permission fixture,
   deny system READ before a deferred drain: expect no binary/Notify request, a retryable pre-send failure and
   no send-start. Restore permission before the scheduled backoff retry. Do not revoke a shared production role.
7. Record deployed API commit, definition/platform versions, start/end UTC times, wall-clock prepare/drain/read
   durations, original/received checksums, binary upload/send counts, state transitions and expected/observed
   statuses. Keep identities in restricted evidence, never metric labels. Do not capture tokens/personal payloads.

An uncertain Notify result is an investigation case under ADR-0007, not a reason to repeat the public send with
a different key. Stop the potentially-live worker and preserve evidence. Use local crash tests to prove the
ambiguous-send paths; do not deliberately send duplicate live correspondence to manufacture this evidence.

## Owned staging and expiry (only where a trusted adapter exists)

Agree and record the production values/owners for every staging setting in the README, including per-owner
expiry rationale, shared/owner capacities, scheduler delay and row/byte/time budgets. Verify capacity, backups,
monitoring/alert delivery and operational ownership first. Enable only the isolated approved smoke environment.
No citizen adapter exists in 03b: an approved domain RECORD owner fixture proves the seam, not citizen access.
Where no approved adapter/harness exists, mark these operational checks pending and keep staging disabled.

1. Authenticate the owner through the adapter, admit a harmless file before linking and record its original
   receipt/expiry. Retry the same operation and content, including across restart: same identity/expiry, no
   CDAM traffic. Wrong-owner reads/promotions fail; changed content under the same operation conflicts.
2. Attempt promotion while unlinked: refuse without local document/upload. Complete the application's normal
   linking flow, then promote once. Verify one Incoming local document, CCD attachment/snapshot, original
   bytes through authorised read-back, Consumed state and payload release. Repeat promotion: same document,
   no extra binary. A different target case/type must be refused.
3. Admit a separate disposable stage and leave it untouched until its agreed expiry; traffic must not be
   required for cleanup. Observe Expired plus absent payload with retained operation/expiry evidence; retries
   return the terminal receipt and do not extend expiry. Check a Removed stage similarly via the adapter.
4. Observe multiple replicas respecting row/byte/time limits and aggregate backlog decreasing. Verify errors
   and scheduler inactivity alert through the approved operational test. Do not induce production database
   faults. Protected Promoting rows and generated preparations must not be purged; deterministic crash/lock
   scenarios are local-test evidence until a controlled operational rehearsal is agreed.
5. Record which adapter and authenticated owner model were used. Citizen readiness additionally requires a
   real browser upload-byte transfer and verified draft-to-case mapping; mark those pending for domain fixtures.

Disable the isolated test admission/cleanup only after accounting for retained stages under the recovery
procedure. Do not equate payload expiry with case disposal, remote deletion or backup erasure.
