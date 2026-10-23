# Plan: authorized document preview rendering for the admin viewer

## Overview

Supply read-only PDF preview streams for every allowed supporting-document format: PDF, JPEG, PNG,
multi-page TIFF and DOCX. The full-page admin viewer consumes one rendering format while retaining the
original file for download. Preserve caseworker scoping, judicial referral visibility and caller-based
CDAM access. Preview generation must not replace, attach, publish or edit an original document.

This is the producer companion to the admin
[full-page viewer plan](../../../deed-poll-admin/docs/plans/20261023-full-page-document-viewer.md).
Implement/review this repository independently and publish its OpenAPI artifact before the admin adapter
task. The approved visual reference lives in the admin repository; its sample pages are not conversion code.

## Decisions (Steve, 2026-10-23)

These supersede anything below that says otherwise.

1. **DOCX converts through Docmosis Tornado's `/rs/convert`**, bytes in and PDF out, with `DOCMOSIS_ACCESS_KEY`
   and a `https://docmosis.<env>.platform.hmcts.net` endpoint as configuration. It is an established HMCTS pattern:
   `em-stitching-api` (`docmosis.convert.endpoint`, `DocmosisConverter`) and FPL (`DocumentConversionService`) both
   call it directly. `dg-docassembly-api`'s `POST /api/convert/{documentId}` was considered and not chosen, because
   it fetches from dm-store and so needs CDAM, which is still off by default here.
2. **The request budget is 25 seconds, not 45**, conversion included. Both Application Gateways cut a request off at
   30 s (admin review finding R03), so a longer budget ends on the gateway's error page instead of this service's
   fixed `PREVIEW_UNAVAILABLE`. Set connect/read deadlines inside 25 s.
3. **Phased release is acceptable.** PDF, JPEG, PNG and TIFF previews are rendered in this service and may be
   released first. DOCX ships in the same code, and answers the fixed 503 `PREVIEW_UNAVAILABLE` until the converter
   is provisioned; the original download keeps working. Releasing does not wait for converter provisioning.
4. **A preview is audited as its own action.** Add `AuditAction.DOCUMENT_PREVIEWED`, recorded through `ReadAudit`
   exactly as `DOCUMENT_DOWNLOADED` is (fail-closed, once per preview request, before bytes are opened), so the
   trail distinguishes a preview from a download. `AuditAction` is a vocabulary with no database check constraint,
   so this needs no migration; never rename an existing member.
5. **A few conversions per pod, not one.** Default `3` concurrent non-PDF conversions per API pod, configurable. The
   chart sets no resources, so pods get the HMCTS Java chart defaults (about 2 GiB, heap about 65 % of it); within the
   pixel and output limits below, three concurrent conversions fit. Record the worst-case heap estimate in the
   execution notes, and reduce the default if a test shows otherwise.

## Context

Reviewed API baseline: `3891c2ad77557b1f156ff88c4c4235e275e8b663`; admin baseline:
`e9dd6a1dfbf9a3a831168d3f04ccdbf8e852ec24`. Recheck the execution baseline, record its SHA for Ralphex review
and use an isolated feature worktree. The old `docs/cdam-03b-callers-plan` checkout contains unrelated
staged work: do not implement or commit on it. Read `CLAUDE.md` in full before execution.

Java paths below are relative to `src/main/java/uk/gov/hmcts/reform/deedpoll/`:

- `api/caseworker/controller/CaseworkerDocumentController.java` serves original files.
- `api/judicial/controller/JudicialReferralController.java` and
  `domain/referrals/JudicialReferralService.java` resolve documents through a visible referral.
- `domain/documents/DocumentService.java` checks readiness/ownership, records the download read and
  opens the configured store outside a database transaction.
- `storage/CdamDocumentStore.java` reads staff documents with the actual caller's JWT; internal
  correspondence has a distinct system-reader path. It is not a fallback for refused staff access.
- `api/DocumentFileResponses.java` contains the original response's MIME/disposition/sandbox policy.
- `domain/audit/ReadAudit.java` fails closed before a document is opened if its audit cannot be committed.
- `domain/documents/UploadPolicy.java`, `UploadContentPolicy.java` and `DocxContentPolicy.java` define
  current allowed formats and input checks. Existing MIME acceptance does not provide a preview renderer.

The existing HMCTS media viewer is Angular and is not an admin dependency. Its convertible-content
client calls `/doc-assembly/convert/{documentId}`; that consumer route is not evidence of a conversion
endpoint in CDAM or Document Store. Local Document Store source has no general DOCX-to-PDF operation.

Read-only implementation references in the workspace, if available:

- `apps/em/em-stitching-api/.../conversion/DocmosisConverter.java`: configured converter endpoint,
  multipart `accessKey`, `outputName` and file, returning PDF. Do not copy its provider-body/filename logging
  or unbounded `response.body().bytes()` behaviour.
- `apps/em/em-stitching-api/.../conversion/ImageConverter.java`: PDFBox image wrapping. It produces a
  single page, so copying it would silently drop additional TIFF frames.

These are references, not runtime source dependencies. The implementation and tests must work without
this workspace layout or those sibling repositories.

## Dependencies and scope

Use the established platform Docmosis byte-in/PDF-out conversion interface for DOCX, with endpoint and
credential supplied by configuration. Establish its exact request shape from the maintained service/source
and record a sanitized contract fixture before implementing the client. No new external SaaS, public Office
viewer, browser Word layout engine or unauthenticated Document Store URL is part of this plan.

Environment access, approved credential/vault mapping, font availability, input-size capability and actual
conversion-service acceptance are deployment prerequisites, not facts established by local mocks. If that
interface cannot satisfy the DOCX requirement, report the dependency and revise the plan with Steve before
substituting a new conversion engine. Do not silently remove DOCX support or reduce the upload allowlist.

Do not modify platform clones, infrastructure or admin source while executing this API plan. Record the
required environment variables/secret names and hand off the corresponding infrastructure changes.
No live CCD/CDAM/IDAM/Docmosis calls in automated tests; use WireMock, local files and the existing local-only
cftlib conventions. No accepted document, original binary, CCD attachment or notification is migrated here.

## Development Approach

1. Work test first, complete/validate each task before the next and preserve others' changes. Keep the
   original `/file` contract intact. Local task commits and independent review bases are required; publishing,
   merging and deployment are Post-Completion actions. Do not run Ralphex as part of authoring this plan.
2. Expose exactly two new authenticated operations:

   ```http
   GET /caseworker/cases/{id}/documents/{documentId}/preview
   GET /judicial/referrals/{referralId}/documents/{documentId}/preview
   ```

   A successful response is `application/pdf`, with a safe `.pdf` preview filename,
   `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` and inert PDF sandbox policy. The judicial
   route carries no case ID; it resolves the case through the existing visibility check.
3. Authorize and open the original through the existing caller-scoped path. Record a `DOCUMENT_PREVIEWED` read audit (Decision 4)
   before opening bytes, once per preview request, with the existing fail-closed behaviour. Thumbnails/page
   changes are browser rendering of that PDF and must not create extra server reads/audit events per page.
   Never use system credentials because a judge/caseworker download was refused.
4. Generate a transient representation on demand. GET changes no business state and writes no derivative to
   CDAM, CCD, the case database or a shared cache. Use private bounded temporary files where appropriate;
   close upstream streams, parser resources and files on completion, client disconnect, timeout and failure.
   Do not persist a preview ledger, introduce a conversion queue or add a migration for this slice.
5. Dispatch only the upload allowlist: PDF passes through after appropriate checks; JPEG/PNG become one
   proportional image page; TIFF becomes one PDF page for **every** frame in source order, respecting
   orientation; DOCX uses the configured converter and preserves its resulting pagination. Validate the
   actual bytes/output rather than trusting the extension or a provider `Content-Type` alone.
6. Use pinned maintained PDF/image dependencies suitable for Java 21. Inspect existing transitive dependencies
   before adding PDFBox and an image codec. Do not edit court Word templates or vendored reference source.
   Preserve image aspect ratio and avoid needless JPEG recompression; no cropping or silent first-frame TIFF.
7. Publish/test bounded engineering defaults: existing 25 MiB input ceiling, 50 MiB preview output ceiling,
   500 pages, 25 million decoded pixels per image frame and three active non-PDF conversions per API pod (configurable,
   Decision 5) without an in-memory queue. Check metadata before allocating raster memory and process TIFF frames sequentially.
   A 25-second request budget (Decision 2) includes conversion; configure connect/read deadlines within it and stop
   further work after cancellation. Keep the concurrency permit until resources/work are actually finished.
   These are preview-resource limits, not changes to upload acceptance or the generated-deed staging cap.
8. Expose resource limits and timeout semantics in OpenAPI (a single `x-document-preview-limits` extension
   is suitable) and README. Bound malformed/decompression-heavy files and converter output; reaching a limit
   returns an explicit preview failure, never a truncated successful PDF or deletion of the original.
9. Missing/hidden referral and wrong-case/missing document retain the existing indistinguishable 404s.
   Authentication/authorization remain 401/403 as appropriate. Define a shared, fixed preview error body
   with codes `PREVIEW_INVALID_DOCUMENT`, `PREVIEW_LIMIT_EXCEEDED`, `PREVIEW_UNAVAILABLE` and `PREVIEW_BUSY`:
   use 422 for invalid/over-budget renderable content, 503 for unavailable conversion and 429 with bounded
   `Retry-After` for exhausted conversion capacity. Publish the exact mapping; do not reinterpret a denied
   source read as a converter failure or disclose document existence before its authorization check.
10. Missing converter configuration affects DOCX preview with a fixed safe 503 after authorization; the service
    still starts and existing original downloads work. Validate configured endpoints as trusted operator
    settings, refuse redirects and never fetch locations supplied by a document or request. Do not log
    original filenames, source/converted bytes, provider bodies, credentials, tokens or temporary paths.

## Validation Commands

Run from this API repository root before implementation and after each implementation task:

```bash
export JAVA_TOOL_OPTIONS=-XX:UseSVE=0 TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build -I /home/hmcts/.claude/jobs/c45dad71/tmp/no-checkstyle.gradle --console=plain
/home/hmcts/.claude/jobs/c45dad71/tmp/checkstyle.sh .
```

`build` runs compile, `test` and `integration`. This devcontainer's JVM needs `-XX:UseSVE=0`, and Gradle's
Checkstyle worker ignores it and crashes with SIGILL, so the init script disables the Checkstyle tasks and
`checkstyle.sh` runs the same jar and config over every source set, failing on any `[WARN]` or `[ERROR]`. Jenkins
runs the real Checkstyle tasks. Do not edit, move or commit either helper. Run every build and test command in the
foreground and wait for it: a backgrounded build ends the session and the next one builds over it.

**Known intermittent test, already on master:** `CcdCreationAttemptRepositoryTest` "an attempt that ran out of tries
on refusals may be retried from scratch, which clears the proof" fails occasionally in a full build. If it fails,
re-run it alone with `--tests '*CcdCreationAttemptRepositoryTest' --rerun`; if it passes, record that and carry on.
Do not change it or anything under `ccd/`.

Use Java 21 and the repository wrapper. Preserve the machine-specific `JAVA_TOOL_OPTIONS=-XX:UseSVE=0`
workaround only where required. Reuse the repo's documented Docker bridge/Testcontainers setup. Run focused
new tests for red/green, then the commands above; record actual counts/skips and pre-existing failures.
At final acceptance run `./gradlew build` and the relevant `./gradlew cftlibTest` document/access journeys.
No test may quietly become a live platform or conversion-service request.

## Implementation Steps

### Task 1: Define the preview contract and resource boundaries

**Files:** create `domain/documents/preview/` policy/error/configuration classes and colocated tests;
modify `src/main/resources/application.yaml`, `README.md`, `build.gradle` as needed.

- [x] Reconcile the source-read, audit, namespace and upload-format inventory on the execution baseline;
      record the reviewed converter protocol reference and the Ralphex review base.
- [x] Write failing tests for allowlist coverage, preview limits, capacity refusal, cancellation/budget
      accounting, error/status vocabulary and unset/invalid converter configuration.
- [x] Implement the small configuration/policy contract and pin required parsing/image dependencies.
      Define fixed operator endpoint/secret settings with non-working credential defaults and no secret logs.
- [x] Document the exact two routes, binary response/headers, errors, budgets and lack of range support;
      keep this a planned contract until producer/controller publication in Task 5.
- [x] Prove the policy does not modify upload acceptance, original downloads or generated-deed staging.
      Run focused tests and all Validation Commands.

### Task 2: Implement PDF and image preview rendering

**Files:** preview renderer classes/tests under `domain/documents/preview/`;
synthetic fixtures under `src/test/resources/document-previews/`.

- [x] Create real synthetic PDF, JPEG, PNG and two-/three-frame TIFF fixtures, with expected page counts,
      sizes, orientation and distinct frame content. Keep fixture generation/provenance reproducible.
- [x] Test and implement valid PDF passthrough and one-page JPEG/PNG wrapping without changing originals.
- [x] Enumerate and render every TIFF frame in order; cover portrait/landscape/rotated frames and supported
      compression modes. Verify actual output page count and distinguishable content on later pages.
- [x] Refuse damaged inputs, unsupported historical MIME, oversized dimensions/output and over-page limits
      with the contract's errors. Probe metadata before allocation; release each decoded frame promptly.
- [x] Verify temporary-file/parser/stream cleanup on success, parse failure, disconnect and cancellation;
      do not let a cancelled worker release its permit while still running. Run Validation Commands.

### Task 3: Implement the DOCX conversion client

**Files:** DOCX renderer/client and tests under `domain/documents/preview/`;
WireMock mappings and synthetic DOCX/PDF fixtures in the existing test structure.

- [x] Pin the verified platform multipart protocol in a WireMock contract test. Use a generic source name
      and fixed safe output name; source document bytes and the configured access key travel only in the
      approved request body. Use no third-party URL from document content.
- [x] Implement bounded byte-in/PDF-out conversion with redirects disabled and configured connect/read/total
      deadlines. Accept only a complete valid PDF; no provider body or original name enters diagnostics.
- [x] Cover multi-page and table/image DOCX examples using genuine fixture bytes. Test the client protocol
      and returned PDF pagination locally; record that mocked PDF output does not prove Word layout fidelity.
- [x] Cover blank credentials, rejected credentials, provider 4xx/5xx, non-PDF/empty/truncated responses,
      excess output, timeout, cancellation and malformed active/encrypted DOCX. No automatic retry on an
      ambiguous remote conversion and no fallback to a public or browser-based Word renderer.
- [x] Assert fixed safe errors and cleanup/capacity accounting. Keep originals unchanged and ensure no
      external call occurs before source authorization. Run Validation Commands.

### Task 4: Coordinate scoped preview reads and audit

**Files:** create the preview service; modify `domain/documents/DocumentService.java` and
`domain/referrals/JudicialReferralService.java` only where a shared scoped-read seam is needed;
update `domain/audit/ReadAudit` tests and relevant service tests.

- [x] Test both caller contexts: ordinary authorized staff and a judge allowed to see an Open referral or
      their own Claimed/Decided referral. Preserve refusal after closure/withdrawal and for another judge.
- [x] Open the source with existing ownership/store/readiness checks and the actual reader's authority.
      Record one existing document-read audit before bytes are opened; failed audit opens/converts nothing.
- [x] Run store reads/conversion outside a database transaction. Share the scoped document read without
      duplicating role rules or entering the internal correspondence/system-reader path.
- [x] Prove wrong-case/unknown documents, null owners and missing sources cause no converter request or
      new disclosure. Source authorization errors keep their original status and meaning.
- [x] Test source/preview cleanup, interrupted transfers and concurrent capacity; verify no derivative
      attachment, notification, metadata mutation or persistent cache is created. Run Validation Commands.

### Task 5: Publish protected HTTP endpoints and OpenAPI

**Files:** caseworker/judicial document controllers, `api/DocumentFileResponses.java` or a dedicated
preview response helper, preview error DTO/handler, `OpenAPIPublisherTest` and namespace/security tests.

- [x] Write full-context HTTP tests before adding the two handlers. Preserve the repo's namespace separation
      and add the routes to the staff-area coverage inventory; do not rely on a `@WebMvcTest` slice.
- [x] Serve complete PDF previews with safe filenames, `no-store`, `nosniff` and inert sandbox policy.
      Keep the original MIME/disposition policy intact and ensure closing the response releases resources.
- [x] Publish exact statuses, fixed error schema, binary media type, headers and the resource-limit extension.
      Describe the preview as a read-only rendition; do not expose CDAM IDs/hash tokens, paths or converter URLs.
- [x] Verify anonymous/wrong-area/wrong-parent/hidden-referral refusals and all five format successes over HTTP;
      cover unsupported Range requests as a full bounded 200 response, not partial/truncated content.
- [x] Extend the OpenAPI path/operation assertions and generate the producer artifact with the existing
      publisher test. Record its source SHA/checksum and structural delta. Run Validation Commands.

### Task 6: Verify the complete preview boundary

**Files:** service/HTTP/cftlib preview tests and fixtures; production code only to address findings.

- [x] Run the all-format matrix with actual parsers and original byte/checksum assertions, including TIFF
      later frames, converted DOCX PDF pages, one-page images and original-download regression checks.
- [x] Extend local cftlib document journeys to demonstrate caller-authorized CDAM reads feeding previews,
      refused readers/wrong cases, judicial ownership and preserved system correspondence behaviour.
- [x] Exercise converter outage, missing configuration, capacity, parser/output bounds, cancellation and
      fail-closed audit; prove unauthenticated/denied requests do not reach the conversion client.
- [x] Run full build, Validation Commands and relevant cftlib journeys. Record counts/skips, limits and
      the review-base/handoff notes for Ralphex's subsequent review phases. Do not start those phases in
      this task; append their findings and revalidation later. Live converter validation remains external.

### Task 7: Document the producer handoff and rollout checks

**Files:** `README.md`, a preview operator section in `docs/CDAM-SMOKE.md` or a dedicated procedure,
`CLAUDE.md` for new invariants only, and this plan's Execution notes.

- [x] Record source/spec artifact SHAs, actual format/permission evidence, budgets, environment variables,
      approved converter protocol and test results. Provide the generated OpenAPI artifact to the admin plan.
- [x] Document synthetic acceptance steps for page/text/layout fidelity, original equality, staff/judicial
      denial, multiple replicas, a restart and converter failures. Identify required endpoint/key/font
      provisioning without claiming it has happened or embedding credential values.
- [x] Review documentation links/commands and relevant configuration/contract tests. Run Validation Commands
      after code/test corrections; keep manual provisioning, review and deployed checks outside tasks.

## Acceptance Criteria

- Both protected operations publish real complete PDF previews for every allowed source MIME type.
- TIFF retains every page and DOCX page counts come from the converted PDF; originals remain unchanged.
- Staff/referral scope, caller-based CDAM permission and the fail-closed document-read audit are preserved.
- Conversion does not hold a database transaction or create durable derivatives/business mutations.
- Input/output/raster/page/concurrency/time bounds and cleanup are tested, and failures are explicit.
- OpenAPI and the producer artifact accurately describe routes, errors, headers and limits for the admin adapter.
- Automated tests are local; actual DOCX fonts/layout and converter access are separately accepted before rollout.

## Execution notes

Implemented on `feat/document-preview-rendering` (Tasks 1–7 up to `e16631b`, then review fixes); not pushed,
not live-verified. Post-Completion items are open. The earlier AAT document-store repair and its file-download
smoke are not evidence that document previews exist.

### Task 1 (2026-10-23)

- **Execution baseline / Ralphex review base:** `c3287d515bfafc9e1fa091a7fa96cd00d1fc605a` on
  `feat/document-preview-rendering` (the reviewed `3891c2a` plus the two plan commits), in the isolated
  `.worktrees/deed-poll-api-document-preview` worktree.
- **Inventory reconciled on that baseline, unchanged from Context:** `CaseworkerDocumentController` serves
  `GET /{documentId}/file`; `JudicialReferralController` serves `/{referralId}/documents/{documentId}/file`;
  `DocumentService.readableDocument` is the shared ownership/readiness/store check and
  `downloadDocument(…, CurrentUser)` records `ReadAudit.documentDownloaded` before `store.open`;
  `CdamDocumentStore.open` reads under the caller's token with no system fallback (the system reader is separate);
  `AuditAction` has `DOCUMENT_DOWNLOADED` and no `DOCUMENT_PREVIEWED` yet (Task 4 adds it);
  `UploadPolicy.ALLOWED_UPLOAD_MIME_TYPES` is exactly PDF/JPEG/PNG/TIFF/DOCX at 25 MiB.
- **Converter protocol reference:** `apps/em/em-stitching-api` at `94f456e7827b4717f69701cc10811aaa1f59ae64`,
  `DocmosisConverter`: `POST {DOCMOSIS_ENDPOINT}` (`https://docmosis.aat.platform.hmcts.net/rs/convert`),
  `multipart/form-data` with parts `accessKey`, `outputName` and `file`, `Accept: application/pdf`, PDF body back.
  Here the setting is the base (`DOCMOSIS_URL`) and `/rs/convert` is appended.
- **Dependencies:** nothing brought PDFBox before; `org.apache.pdfbox:pdfbox:3.0.8` (latest on Central) is pinned.
  No image codec added: JDK 21 ImageIO reads JPEG, PNG and multi-image TIFF (orientation is read from the TIFF tag
  by the Task 2 renderer, since ImageIO does not apply it).
- **Contract:** `domain/documents/preview/` — `PreviewFormat` (one per allowlist type; only PDF takes no permit),
  `PreviewFailure`/`PreviewError` (four codes, fixed sentences, no cause), `PreviewProperties`
  (`deedpoll.documents.preview`, defaults 25 s / 50 MiB / 500 pages / 25 M pixels / 3 permits / 5 s Retry-After;
  converter url+key unset by default, a set non-https base refuses startup, key redacted from `toString`;
  connect+read must fit the budget), `PreviewBudget` (deadline, cancellation, `deadlineFor`),
  `ConversionCapacity` (non-queuing semaphore, idempotent permit), `PreviewConfiguration`.
  `PreviewBoundaryTest` asserts nothing in documents/staging/deeds/storage depends on the preview package.
- **Validation:** focused preview tests 29/29; `./gradlew build` (no-checkstyle init) successful in 4m39s — test
  4611 run, 0 skipped, 0 failed; integration 23 run, 0 skipped, 0 failed; `checkstyle.sh` OK. The known
  intermittent `CcdCreationAttemptRepositoryTest` did not fail.

### Task 2 (2026-10-23)

- **Renderers:** `domain/documents/preview/` — `PreviewRenderer` (the facade: checkpoint, permit, then open and
  spool the source under the 25 MiB ceiling, render, check pages/size, hand back a `RenderedPreview`; the permit is
  released only in the worker's own `finally`), `PdfRenderer` (header `%PDF-`, PDFBox open, refuses encryption,
  counts pages by walking the tree rather than trusting `/Count`, then serves the stored bytes unchanged),
  `ImageRenderer` (JPEG/PNG one page, TIFF one page per frame in order; A4 portrait/landscape to the frame as seen,
  fitted, centred, never cropped; the reader must recognise the bytes; dimensions checked before decoding; JPEG is
  decoded to its raster only to prove the data is whole — a truncated JPEG warns rather than throws — then embedded
  as stored, DCTDecode, not recompressed; PNG/TIFF frames embedded losslessly), `Orientation` (tag 274, all eight
  values, drawn by PDF matrix with no rotated copy), `ExifOrientation`, `PreviewWorkspace` (an owner-only
  `preview-*` directory per request holding the spool, the PDF and PDFBox's bounded on-disk scratch; deleted whole
  on close/failure/cancellation) and `PreviewSource`. DOCX answers `PREVIEW_UNAVAILABLE` until Task 3.
- **Beyond the plan:** JPEG EXIF orientation is applied too, because phone photographs taken on their side are
  common. ImageIO's JPEG metadata throws when an EXIF APP1 comes before the JFIF APP0, so orientation is read by a
  bounded walk of the raw segments and JPEG pixels are read with metadata ignored; a test covers both orders.
- **Error mapping:** parser `IOException`/`RuntimeException` → `PREVIEW_INVALID_DOCUMENT` (class name logged at
  INFO, never the message); a failing write into the bounded scratch or output → `PREVIEW_LIMIT_EXCEEDED`; a source
  read failing mid-stream or a scratch-directory failure → `PREVIEW_UNAVAILABLE` (the store's message may hold a
  path, so it is dropped); exceptions from opening the source (e.g. `NotFoundError`) propagate unchanged.
- **Fixtures:** `src/test/resources/document-previews/` (a 3-page PDF, two JPEGs, a PNG, 2- and 3-frame TIFFs; LZW,
  Deflate, PackBits, CCITT T.6, uncompressed, orientation 6), written by `DocumentPreviewFixtures` and regenerated
  with `REGENERATE_DOCUMENT_PREVIEWS=true`; `DocumentPreviewFixturesTest` checks their structure on every build.
  `PreviewRendererTest` also generates every JDK TIFF compression (None, LZW, Deflate, ZLib, PackBits, JPEG, CCITT
  RLE/T.4/T.6), all eight orientations, damaged, mismatched, header-only 100,000² PNG, over-page/pixel/output/input
  and expired-budget cases, and checks rendered page colours and marker corners with PDFBox's rasteriser. A
  deliberate orientation-6 mutation failed 5 tests, so the orientation assertions do discriminate.
- **Worst-case heap per conversion (decision 5):** a 25 M-pixel frame decodes to at most about 100 MB (4-byte ARGB;
  RGB is 75 MB, bilevel about 3 MB), and `LosslessFactory` adds a working copy of comparable size, so about 200 MB.
  A JPEG adds its stored bytes (≤ 25 MiB) and a raster (≤ 75 MB, freed before embedding). PDFBox scratch and the
  spool are on disk. Three concurrent conversions: about 600 MB, inside a heap of about 1.3 GB (65 % of 2 GiB), so
  the default of 3 stands. A PDF passthrough takes no permit and holds only PDFBox's parsed page tree, with
  stream data in the on-disk scratch.
- **Validation:** preview package 85 tests (1 skipped: the opt-in regenerate). `./gradlew build` (no-checkstyle
  init) — test 4667 run, 1 skipped; integration 23 run, 0 failed. The known intermittent
  `CcdCreationAttemptRepositoryTest` "ran out of tries on refusals…" failed once in the second full build and passed
  re-run alone with `--rerun`; the first full build of this task passed. `checkstyle.sh` OK.

### Task 3 (2026-10-23)

- **Client:** `domain/documents/preview/DocxRenderer`, reached through `PreviewRenderer` once the converter is
  configured (unconfigured is still `PREVIEW_UNAVAILABLE` before a permit or a source read). It holds the spooled DOCX
  to `UploadContentPolicy.verify` first, so a malformed, encrypted (OLE), macro-enabled or non-DOCX package is
  `PREVIEW_INVALID_DOCUMENT` and nothing is sent. Then one OkHttp 4.12 POST to `{DOCMOSIS_URL}/rs/convert` (the
  em-stitching-api protocol recorded under Task 1): `multipart/form-data` parts `accessKey`, `outputName=preview.pdf`,
  `file` as `document.docx` under the DOCX type (em-stitching-api labels it `application/pdf`), `Accept:
  application/pdf`. `retryOnConnectionFailure(false)`, redirects off, no proxy; connect, read/write and call
  timeouts are each `PreviewBudget.deadlineFor`/`remaining`, never under 1 ms (OkHttp reads 0 as none). New
  `PreviewBudget.onCancel` registers `Call.cancel`, so a cancelled caller aborts the call on its socket; the permit is
  still released only in the worker's `finally`.
- **Answer acceptance:** 2xx and `application/pdf` (type/subtype), declared length within the output ceiling, body
  streamed into `PreviewWorkspace.boundedOutput`, `%%EOF` within the last 1 KiB after whitespace, then
  `PdfRenderer`'s checks (header, PDFBox load, unencrypted, walked page count 1..limit), whose `INVALID_DOCUMENT` is
  re-mapped to `PREVIEW_UNAVAILABLE` because the bytes are the converter's. **Mapping decided here:** Tornado `413`
  and an over-ceiling or over-page answer are `PREVIEW_LIMIT_EXCEEDED`; the budget ending mid-call is
  `PREVIEW_LIMIT_EXCEEDED` (as every other budget expiry); every other status (rejected credentials 401/403
  included), a read timeout inside the budget, a reset/broken transfer, a redirect and any unusable body are
  `PREVIEW_UNAVAILABLE`. A provider 4xx is not blamed on the document, because the DOCX already passed the upload's
  checks and the body that might say why is not read. Logs carry the status or an exception's simple class name.
- **Fixtures:** `three-pages.docx` (3 pages, hard page breaks) and `table-and-image.docx` (4×3 table, then the
  `scan.png` picture after a page break), written by POI `XWPFDocument` in `DocumentPreviewFixtures`, structure checked
  in `DocumentPreviewFixturesTest`, both accepted by `UploadContentPolicy` and their macro (`/word/vbaProject.bin`)
  and POI standard-encrypted copies refused. The stub's converted PDFs are made by `DocumentPreviewFixtures.pdf(n)`.
  **This proves the protocol, the bounds and that the preview's page count is the converted PDF's; it does not prove
  Word layout or font fidelity, which needs the real converter in AAT (Post-Completion).**
- **Tests:** `DocxRendererTest`, 30 against WireMock: the exact multipart contract (key only in the body, no query),
  pagination for both fixtures, no retry, no redirect, blank key and unset endpoint (nothing opened or sent), a refused
  source (its own exception, nothing sent), unsafe packages, busy, nine refusal statuses, 413, wrong/missing
  content type, seven incomplete/unreadable/encrypted/empty bodies, broken chunked transfer, declared and streamed
  excess output, over-page, read timeout, budget expiry (stops in < 3 s), unreachable converter, mid-call cancellation
  (permit held until the call stopped, scratch empty), and diagnostics (no body, key, endpoint, filename or stack
  trace). Each failure asserts the fixed sentence, no cause, the source closed, all permits back and scratch empty.
  Mutations: dropping the `%%EOF` check failed 1 test; enabling redirects failed 1 test.
- **Heap (decision 5):** a DOCX conversion holds the source (≤ 25 MiB) and POI's buffered package during the check
  (bounded by `DocxContentPolicy` at 50 MiB expanded), then streams the answer to disk; below the image estimate.
- **Validation:** preview package 115 tests (1 skipped: the opt-in regenerate). `./gradlew build` (no-checkstyle
  init) — test 4701 run, 1 skipped, 0 failed; integration 23 run, 0 failed. `checkstyle.sh` OK. The known
  intermittent `CcdCreationAttemptRepositoryTest` did not fail.

### Task 4 (2026-10-23)

- **Seam:** `domain/documents/DocumentPreviewRead` (the row, plus one opening of its original) from new
  `DocumentService.previewDocument(caseId, documentId, reader)`: `readableDocument`'s ownership/file/store checks now,
  and on `open()` `ReadAudit.documentPreviewed` (new `AuditAction.DOCUMENT_PREVIEWED`, a read action, fail-closed)
  then `store.open(locator)` -- the download's own store call, so CDAM reads under the caller's token. Opening twice is
  an `IllegalStateException`. `JudicialReferralService.previewDocument` is `document`'s role gate and `visible`
  check, then that seam. No role rule is repeated, and `CorrespondenceDocumentReader`/`openForService` is never on
  the path. The domain/documents package still has no dependency on the preview package (`PreviewBoundaryTest`).
- **Service:** `domain/documents/preview/DocumentPreviewService` -- `caseworkerPreview`, `judicialPreview`, and
  `budget()` (25 s on the system clock; the caller keeps it so that it can cancel). Order: refuse an active
  transaction (`IllegalStateException`, as the sweep and outbox do); upstream scope check (its 404/403 unchanged);
  `PreviewFormat.require(mimeType)` (outside the allowlist is 422 `PREVIEW_INVALID_DOCUMENT`); `PreviewRenderer.render`
  with `read::open` as the source, so the permit comes before the audit and the store.
  **Decision recorded:** the audit is committed when the source is opened, not when the scope check passes. A
  busy (429), unconfigured-DOCX (503), cancelled or invalid-type preview therefore records nothing and reads nothing,
  and a failed audit opens and converts nothing. A source missing after the audit keeps the store's 404 and leaves its
  row, exactly as the download's audit-then-open does.
- **Tests:** `DocumentPreviewServiceTest` (27, Spring context + Postgres + filesystem store + WireMock converter on
  loopback): all five formats with page counts, one `DOCUMENT_PREVIEWED` and no `DOCUMENT_DOWNLOADED`, the original's
  SHA-256 and row unchanged, no new document/notification/audit-write/store file; repeat requests record one each;
  another case's / unknown / file-less document, allowlist-outside type, active transaction, pre-cancelled budget and
  failed audit (256-character subject) each open nothing, record nothing and send nothing; a missing source and a
  `ForbiddenError` store refusal keep their own exception and send nothing; converter 503 is one call and
  `PREVIEW_UNAVAILABLE`; a mid-transfer reset is `PREVIEW_UNAVAILABLE` with the stream closed; cancellation on first
  read stops the work with the stream closed; three concurrent DOCX conversions held by a 1.5 s converter delay
  make a PNG `PREVIEW_BUSY` (Retry-After 5 s, no audit, no read) while a PDF still passes. Judicial: any judge on
  Open, the holder only on Claimed, the directing judge on Decided, nobody once Closed/Withdrawn, nobody on a claim
  whose `claimedById` is NULL, another case's document is the document's 404, a caseworker is the judicial 403, and
  a judge's failed audit opens nothing. After every test: all permits free, no new `preview-*` scratch directory,
  and the correspondence reader never called. `ReadAuditActionsTest` (3) pins the action, its entity and that both
  document reads fail closed. `JudicialSecurityTest`'s operation inventory gains `previewDocument`, asked the same
  gate/visibility questions as `document`. Mutations: opening the store before the audit failed 3 tests; removing the
  transaction guard failed 1.
- **Validation:** `./gradlew build` (no-checkstyle init) -- test 4731 run, 1 skipped (the opt-in fixture
  regeneration), 0 failed; integration 23 run, 0 failed. `checkstyle.sh` OK. The known intermittent
  `CcdCreationAttemptRepositoryTest` did not fail.

### Task 5 (2026-10-23)

- **Routes:** `CaseworkerDocumentController.preview` (`GET /caseworker/cases/{id}/documents/{documentId}/preview`,
  operationId `previewCaseDocument`) and `JudicialReferralController.preview`
  (`GET /judicial/referrals/{referralId}/documents/{documentId}/preview`, `previewReferralDocument`), each one call to
  `DocumentPreviewService` with a fresh `budget()`, then `DocumentFileResponses.writePreview`.
- **Response written, not returned:** a `Resource` body would let Spring answer `Range` with a `206` of the bytes
  asked for (`AbstractMessageConverterMethodProcessor`), so `writePreview` writes the servlet response itself:
  `200`, `application/pdf`, measured `Content-Length`, `inline; filename="document-preview.pdf"` (fixed, so nothing
  the uploader typed reaches the header), the download's `nosniff`, `default-src 'none'; sandbox` and `no-store`,
  plus `Accept-Ranges: none`. It closes the `RenderedPreview` (deleting its scratch directory) in a
  try-with-resources whatever happens. The original's `DocumentFileResponses.of` is unchanged.
- **Errors:** `DomainErrorHandler.preview` maps `PreviewError` to its failure's status with
  `DocumentPreviewError {error, code}`, `no-store`, and `Retry-After` (whole seconds, rounded up) on `PREVIEW_BUSY`.
  **Found while testing:** a client sending `Accept: application/pdf` had its 404 lost to content negotiation and
  rethrown (a 500 in a container), because no converter writes `ApiError` as PDF. The preview handler and the shared
  404/401/403 handlers now preset `application/json`; JSON clients see no difference.
- **OpenAPI:** `config/OpenAPIDocumentPreviews` (springdoc sees a `void` handler) publishes, on both operations:
  `200` `application/pdf` binary with its six headers; `404` `ApiError`; `422`/`429`/`503` `DocumentPreviewError`,
  grouped from `PreviewFailure`'s own statuses with each code's fixed sentence in the description and the `code` enum
  from the enum itself; `Retry-After` on 429; and `x-document-preview-limits` read from the running
  `PreviewProperties` (25 s, 26214400 input, 52428800 output, 500 pages, 25,000,000 pixels, 3 per instance, 5 s,
  `rangeRequests: unsupported`). The 403 is still `OpenAPIForbiddenResponses`'. No converter, CDAM id, hash token or
  storage key is named.
- **Counted apart:** `OpenAPIPublisherTest.PREVIEW_PATHS`, like the CCD attempts, so the caseworker 34 and judicial
  8 stand; `documentsNoUnlistedPath` knows them. `JudicialSecurityTest.ROUTED_OPERATIONS` is 9 (the controller's
  ninth handler reaches the already-gated `previewDocument`). `StaffAccessRulesTest` pins each preview as one GET in
  its own area. `RateLimitRoutesTest` now expects 429 on the six limited routes plus the two previews, and only the
  previews' 429 is `PREVIEW_BUSY`.
- **Tests:** `api/DocumentPreviewHttpTest`, 30, full context with the staff gate enforcing, real Postgres, filesystem
  store and renderers, WireMock converter: all five formats on both surfaces (page counts read back with PDFBox,
  every header, one `DOCUMENT_PREVIEWED` and no `DOCUMENT_DOWNLOADED`, the converter called only for DOCX, the
  original still served byte-identical); `Range: bytes=0-99` is a full `200` with no `Content-Range`; anonymous 401,
  judge on the caseworker route and every staff role on the judicial route 403, another case's URL / unknown
  document / another case's document 404, another judge on a claim and closed/withdrawn referrals the referral's
  404 -- each with nothing opened, nothing sent and nothing recorded; legacy `.doc` and PDF bytes behind a PNG row 422
  `PREVIEW_INVALID_DOCUMENT` in a body of exactly two fields; converter 503/500 is `PREVIEW_UNAVAILABLE` with
  nothing of the provider body, host, filename or key; three held permits make a PNG 429 `Retry-After: 5` with no
  audit while a PDF passes; refusals are JSON under `Accept: application/pdf`; a failed audit opens nothing. After
  every test all permits are free and no `preview-*` directory remains. Mutation: not closing the preview in
  `writePreview` failed 14 tests.
- **Producer artifact:** `OpenAPIPublisherTest` (21 tests) wrote `/tmp/openapi-specs.json`, SHA-256
  `86eeee8ad19f8787e29f1ce413ee24d8f62aee412d82c466e0eb2f45c2d7508b`, from the Task 5 commit's source (parent
  `48feecb`). Structural delta: +2 paths, +2 GET operations, +1 schema (`DocumentPreviewError`); now 41 paths, 57
  operations, 61 schemas. No existing operation's contract changed.
- **Validation:** `./gradlew build` (no-checkstyle init) successful -- test 4762 run, 1 skipped (the opt-in fixture
  regeneration), 0 failed; integration 24 run, 0 failed. `checkstyle.sh` OK after line-length/Javadoc fixes (re-ran
  the HTTP test after them: 30/30). The known intermittent `CcdCreationAttemptRepositoryTest` did not fail.

### Task 6 (2026-10-23)

- **Review base for Ralphex's later phases:** `f795067` (the Task 5 commit); this task's commit sits on it.
- **All-format matrix, real parsers:** already in place from Tasks 2-5 and re-run in this build --
  `PreviewRendererTest`/`DocxRendererTest` (every JDK TIFF compression and orientation, later-frame colours read back
  with PDFBox's rasteriser, converted DOCX page counts), `DocumentPreviewServiceTest` (original SHA-256 and row
  unchanged for all five formats) and `DocumentPreviewHttpTest` (both surfaces, original download byte-identical).
  New here: the same matrix through **real CDAM and CCD** in `cftlibTest`.
- **New cftlib journey `DocumentPreviewCftlibTest` (7 tests, only dm-store and the converter stubbed):** five
  uploads over HTTP (2-page PDF, JPEG, PNG, 3-frame TIFF with a portrait middle frame, 3-page DOCX), each preview read
  through CDAM with dm-store's `user-id` equal to **the caller's IDAM id, never the system user's**, every
  protective header, page count from PDFBox, TIFF pages 1-3 rendered in frame colour order, one
  `DOCUMENT_PREVIEWED` per request and the original's SHA-256 unchanged through `/file`. Test 1 runs with the converter
  **unconfigured**: DOCX is the fixed 503 with no source read and no audit, the rest render, the original downloads.
  The context is then replaced with the converter configured (a `DynamicPropertySource` supplier read at context
  start): DOCX converts, the converter receives exactly the original bytes and the key in the body and not the
  filename; `Range` is a full 200 without `Content-Range` for all five. Four caseworker roles preview under their own
  token; anonymous 401 and another case's URL 404 reach neither dm-store nor the converter; the unmapped user keeps the
  download's CDAM 502. Judicial: both judges preview while Open, a caseworker on the judicial route is 403, after one
  judge claims the other gets the referral's 404 with nothing read or sent while the holder previews the converted DOCX,
  and after withdrawal the holder gets 404. Revoking CCD read for `DEED_POLL_READER` makes PNG and DOCX previews CDAM's
  403/404 (never a `PREVIEW_` body), no binary read, no conversion; restoring it restores the preview. A converter
  503 is the fixed `PREVIEW_UNAVAILABLE` after exactly one call, with the original still downloadable. System
  correspondence still reads the same bytes as the system user afterwards. `CftlibConfig` gains a second judge
  (`deed-poll-other-judge@localhost`).
- **Finding, fixed:** the unmapped user's preview answered **500**, not the download's 502. `DomainErrorHandler.
  upstreamFailure` negotiated its body, and a preview asks for `application/pdf` -- the class of bug Task 5 fixed for
  404/401/403. It now presets JSON. `DocumentPreviewHttpTest` gains a store `UpstreamFailureError` under `Accept:
  application/pdf` (502 JSON, nothing sent), which fails without the fix. `CdamDocumentStore.open` throws only
  `NotFoundError`, `ForbiddenError` and `UpstreamFailureError`, all three now preset.
- **Bounds, outage, capacity, cancellation, fail-closed audit, missing configuration:** covered by the existing
  renderer/service/HTTP tests (Task 2-5 notes), plus the unconfigured, outage and denial journeys above through CDAM.
  Limits unchanged: 25 s, 25 MiB in, 50 MiB out, 500 pages, 25 M pixels, 3 conversions per pod, Retry-After 5 s.
- **Validation:** `./gradlew build` (no-checkstyle init) successful in 5m 6s -- test 4763 run, 1 skipped (the opt-in
  fixture regeneration), 0 failed; integration 24 run, 0 failed. The known intermittent `CcdCreationAttemptRepositoryTest`
  did not fail. `checkstyle.sh` OK (after one Javadoc line-length fix, comment only). `./gradlew cftlibTest
  --no-watch-fs`: **21 tests, 0 failed/skipped**, 1m 22s (log `/tmp/preview-task6-cftlib.log`). Gradle's own
  `checkstyleCftlibTest` hits the documented SIGILL; `checkstyle.sh` covers that source set.
- **Environment note:** cftlib's bundled dg-docassembly binds port 8080, which an existing
  `python3 -m http.server 8080 --directory /tmp/deed-poll-viewer-prototype` held. It was stopped for each cftlib run
  and restarted with the same command afterwards.
- **Not proven here:** live Docmosis access, fonts and Word layout fidelity, AAT CDAM permissions (Post-Completion).

### Task 7 (2026-10-23)

- **Handoff page:** new `docs/DOCUMENT-PREVIEW-SMOKE.md` (prepared, **not run**): the producer artifact for admin,
  the provisioning nobody has done yet, AAT preconditions, a nine-step synthetic journey, results and rollback.
  README's Document previews section links to it and says why the chart has no Docmosis entry yet; `docs/RESUME.md`
  gains an entry; `CLAUDE.md` gains the `domain/documents/preview` layout line and one invariant (the fixed
  scope/allowlist/permit/audit/open order, caller authority only, permit released in the worker's `finally`, no
  stored derivative, written rather than `Resource` responses because of `206`, preset-JSON refusal handlers, no
  chart secret ahead of the vault).
- **Artifact for the admin plan:** regenerated at `bde3172` with `OPENAPI_OUTPUT_PATH=/tmp/preview-task7/openapi-bde3172.json
  ./gradlew integration --tests '*OpenAPIPublisherTest'`. SHA-256 `86eeee8ad19f8787e29f1ce413ee24d8f62aee412d82c466e0eb2f45c2d7508b`,
  103,978 bytes, **byte-identical to Task 5's**, so Task 6's handler fix changed no contract. 41 paths, 57
  operations, 61 schemas; both previews publish `200`/`403`/`404`/`422`/`429`/`503`, six `200` headers,
  `Retry-After` on `429` and `x-document-preview-limits` (25 s, 26214400, 52428800, 500, 25000000, 3, 5 s,
  `rangeRequests: unsupported`), checked by parsing the file. Admin regenerates from that commit through its own
  `SPEC-PROVENANCE.md` recipe; this repository did not touch admin.
- **Environment variables / secrets recorded:** `DOCUMENT_PREVIEW_*` (six, defaults above), `DOCMOSIS_URL` (base;
  `/rs/convert` appended), `DOCMOSIS_ACCESS_KEY` (proposed vault secret `docmosis-access-key` in `deed-poll-{env}`,
  as em-stitching-api names its own), `DOCMOSIS_CONNECT_TIMEOUT`/`DOCMOSIS_READ_TIMEOUT` (3 s / 20 s). The chart is
  deliberately unchanged: a `keyVaults` entry for an absent secret fails the deploy. Endpoint access, key issue,
  fonts, 25 MiB acceptance and data retention on Tornado are listed as provisioning for their owners, not done.
- **Review of links/commands:** every relative link in the new page, README and RESUME resolves; the regeneration
  command was the one run; the README environment table matches `application.yaml`'s `deedpoll.documents.preview`
  keys and defaults; the CLAUDE.md invariant was checked against `DomainErrorHandler` (404/401/403/502/preview preset
  JSON; preview ids are text, so a malformed one is the ordinary 404, not a negotiated 400) and `PreviewRenderer`.
- **Validation:** no code or test changed. `./gradlew build` (no-checkstyle init) successful: `integration` re-ran,
  24 run, 0 failed; `test` was up to date against unchanged inputs, its results Task 6's 4763 run, 1 skipped, 0
  failed. `checkstyle.sh` OK.

### Review iteration 1 (2026-10-23)

- **Cancellation removed, not wired.** No production code ever called `PreviewBudget.cancel()`: the request thread
  is the worker, so a servlet cannot notice a disconnect mid-conversion until it writes. `cancel`/`onCancel` and
  `PreviewCancelledException` are gone. A disconnect during the write still deletes the scratch directory
  (`DocumentFileResponsesTest`), and abandoned work runs on to completion or to the 25 s budget, holding its permit
  throughout. This revises Development Approach 4/7's "client disconnect" for the conversion phase. A real
  mid-conversion cancel needs async request handling and would be a separate plan.
- **Decoded bytes bounded.** The pixel limit alone allowed 16-bit RGBA (8 B/px) or float TIFF (16+ B/px) frames
  to decode past the heap estimate below. A PNG/TIFF frame's raw sample layout is now held to four bytes a pixel of
  `maxFramePixels` before decoding (`LIMIT_EXCEEDED`). JPEG is unaffected, since the JDK decodes it at ≤ 4 B/px. The
  estimate below therefore holds as written.
- **OpenAPI publishes `502`** (`ApiError`, the store failure `/file` also returns) on both previews. New artifact
  SHA-256 `74c83e760cb18e8ff803216eff26f4c12f75f90f0043c2c6a1e4d234497d1dd6`, 104,422 bytes, 41 paths / 57
  operations / 61 schemas. It supersedes `86eeee8a…`.
- **Smaller fixes:** scratch-deletion failure now logs a fixed WARN and no longer masks a refusal or fails a sent
  response; the unused `requireInputBytes` and `ImageRenderer`'s redundant format guard and page re-check are gone;
  `ExifOrientationTest.hostile`'s offsets are corrected (the old ones mutated the wrong bytes) and asserted exactly;
  added tests for big-endian/multi-entry EXIF, exactly-at-limit pages (PDF and TIFF), `Retry-After` rounding and
  the decoded-size limit. The rate-limit and audit docs now name the preview permit and `DOCUMENT_PREVIEWED`.

## Post-Completion

Have platform owners provision the approved converter endpoint/access key and fonts through the appropriate
vault/Flux infrastructure, confirming data-handling and maximum valid upload-size support. Validate in AAT
with synthetic PDF/JPEG/PNG, a true multi-frame TIFF and representative generated DOCX; compare converted
layout/fonts/page count to the original's expected print layout. Exercise original downloads, access
denials, separate replicas, a restart and converter outage/limits. Keep the original download available on
preview failure. Per Decision 3, a DOCX gap does not block releasing PDF/image/TIFF previews. Publish/review/merge/deploy
separately from local execution; deploy the producer and converter before the consumer. Move this plan to
`docs/plans/completed/` after implementation and review.
