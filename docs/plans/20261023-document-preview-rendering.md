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
3. Authorize and open the original through the existing caller-scoped path. Preserve the download read audit
   before opening bytes, once per preview request, and the existing fail-closed behaviour. Thumbnails/page
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
   500 pages, 25 million decoded pixels per image frame and one active non-PDF conversion per API pod without
   an in-memory queue. Check metadata before allocating raster memory and process TIFF frames sequentially.
   A 45-second request budget includes conversion; configure connect/read deadlines within it and stop
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
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew test
./gradlew integration
./gradlew checkstyleMain checkstyleTest
```

Use Java 21 and the repository wrapper. Preserve the machine-specific `JAVA_TOOL_OPTIONS=-XX:UseSVE=0`
workaround only where required. Reuse the repo's documented Docker bridge/Testcontainers setup. Run focused
new tests for red/green, then the commands above; record actual counts/skips and pre-existing failures.
At final acceptance run `./gradlew build` and the relevant `./gradlew cftlibTest` document/access journeys.
No test may quietly become a live platform or conversion-service request.

## Implementation Steps

### Task 1: Define the preview contract and resource boundaries

**Files:** create `domain/documents/preview/` policy/error/configuration classes and colocated tests;
modify `src/main/resources/application.yaml`, `README.md`, `build.gradle` as needed.

- [ ] Reconcile the source-read, audit, namespace and upload-format inventory on the execution baseline;
      record the reviewed converter protocol reference and the Ralphex review base.
- [ ] Write failing tests for allowlist coverage, preview limits, capacity refusal, cancellation/budget
      accounting, error/status vocabulary and unset/invalid converter configuration.
- [ ] Implement the small configuration/policy contract and pin required parsing/image dependencies.
      Define fixed operator endpoint/secret settings with non-working credential defaults and no secret logs.
- [ ] Document the exact two routes, binary response/headers, errors, budgets and lack of range support;
      keep this a planned contract until producer/controller publication in Task 5.
- [ ] Prove the policy does not modify upload acceptance, original downloads or generated-deed staging.
      Run focused tests and all Validation Commands.

### Task 2: Implement PDF and image preview rendering

**Files:** preview renderer classes/tests under `domain/documents/preview/`;
synthetic fixtures under `src/test/resources/document-previews/`.

- [ ] Create real synthetic PDF, JPEG, PNG and two-/three-frame TIFF fixtures, with expected page counts,
      sizes, orientation and distinct frame content. Keep fixture generation/provenance reproducible.
- [ ] Test and implement valid PDF passthrough and one-page JPEG/PNG wrapping without changing originals.
- [ ] Enumerate and render every TIFF frame in order; cover portrait/landscape/rotated frames and supported
      compression modes. Verify actual output page count and distinguishable content on later pages.
- [ ] Refuse damaged inputs, unsupported historical MIME, oversized dimensions/output and over-page limits
      with the contract's errors. Probe metadata before allocation; release each decoded frame promptly.
- [ ] Verify temporary-file/parser/stream cleanup on success, parse failure, disconnect and cancellation;
      do not let a cancelled worker release its permit while still running. Run Validation Commands.

### Task 3: Implement the DOCX conversion client

**Files:** DOCX renderer/client and tests under `domain/documents/preview/`;
WireMock mappings and synthetic DOCX/PDF fixtures in the existing test structure.

- [ ] Pin the verified platform multipart protocol in a WireMock contract test. Use a generic source name
      and fixed safe output name; source document bytes and the configured access key travel only in the
      approved request body. Use no third-party URL from document content.
- [ ] Implement bounded byte-in/PDF-out conversion with redirects disabled and configured connect/read/total
      deadlines. Accept only a complete valid PDF; no provider body or original name enters diagnostics.
- [ ] Cover multi-page and table/image DOCX examples using genuine fixture bytes. Test the client protocol
      and returned PDF pagination locally; record that mocked PDF output does not prove Word layout fidelity.
- [ ] Cover blank credentials, rejected credentials, provider 4xx/5xx, non-PDF/empty/truncated responses,
      excess output, timeout, cancellation and malformed active/encrypted DOCX. No automatic retry on an
      ambiguous remote conversion and no fallback to a public or browser-based Word renderer.
- [ ] Assert fixed safe errors and cleanup/capacity accounting. Keep originals unchanged and ensure no
      external call occurs before source authorization. Run Validation Commands.

### Task 4: Coordinate scoped preview reads and audit

**Files:** create the preview service; modify `domain/documents/DocumentService.java` and
`domain/referrals/JudicialReferralService.java` only where a shared scoped-read seam is needed;
update `domain/audit/ReadAudit` tests and relevant service tests.

- [ ] Test both caller contexts: ordinary authorized staff and a judge allowed to see an Open referral or
      their own Claimed/Decided referral. Preserve refusal after closure/withdrawal and for another judge.
- [ ] Open the source with existing ownership/store/readiness checks and the actual reader's authority.
      Record one existing document-read audit before bytes are opened; failed audit opens/converts nothing.
- [ ] Run store reads/conversion outside a database transaction. Share the scoped document read without
      duplicating role rules or entering the internal correspondence/system-reader path.
- [ ] Prove wrong-case/unknown documents, null owners and missing sources cause no converter request or
      new disclosure. Source authorization errors keep their original status and meaning.
- [ ] Test source/preview cleanup, interrupted transfers and concurrent capacity; verify no derivative
      attachment, notification, metadata mutation or persistent cache is created. Run Validation Commands.

### Task 5: Publish protected HTTP endpoints and OpenAPI

**Files:** caseworker/judicial document controllers, `api/DocumentFileResponses.java` or a dedicated
preview response helper, preview error DTO/handler, `OpenAPIPublisherTest` and namespace/security tests.

- [ ] Write full-context HTTP tests before adding the two handlers. Preserve the repo's namespace separation
      and add the routes to the staff-area coverage inventory; do not rely on a `@WebMvcTest` slice.
- [ ] Serve complete PDF previews with safe filenames, `no-store`, `nosniff` and inert sandbox policy.
      Keep the original MIME/disposition policy intact and ensure closing the response releases resources.
- [ ] Publish exact statuses, fixed error schema, binary media type, headers and the resource-limit extension.
      Describe the preview as a read-only rendition; do not expose CDAM IDs/hash tokens, paths or converter URLs.
- [ ] Verify anonymous/wrong-area/wrong-parent/hidden-referral refusals and all five format successes over HTTP;
      cover unsupported Range requests as a full bounded 200 response, not partial/truncated content.
- [ ] Extend the OpenAPI path/operation assertions and generate the producer artifact with the existing
      publisher test. Record its source SHA/checksum and structural delta. Run Validation Commands.

### Task 6: Verify the complete preview boundary

**Files:** service/HTTP/cftlib preview tests and fixtures; production code only to address findings.

- [ ] Run the all-format matrix with actual parsers and original byte/checksum assertions, including TIFF
      later frames, converted DOCX PDF pages, one-page images and original-download regression checks.
- [ ] Extend local cftlib document journeys to demonstrate caller-authorized CDAM reads feeding previews,
      refused readers/wrong cases, judicial ownership and preserved system correspondence behaviour.
- [ ] Exercise converter outage, missing configuration, capacity, parser/output bounds, cancellation and
      fail-closed audit; prove unauthenticated/denied requests do not reach the conversion client.
- [ ] Run full build, Validation Commands and relevant cftlib journeys. Record counts/skips, limits and
      the review-base/handoff notes for Ralphex's subsequent review phases. Do not start those phases in
      this task; append their findings and revalidation later. Live converter validation remains external.

### Task 7: Document the producer handoff and rollout checks

**Files:** `README.md`, a preview operator section in `docs/CDAM-SMOKE.md` or a dedicated procedure,
`CLAUDE.md` for new invariants only, and this plan's Execution notes.

- [ ] Record source/spec artifact SHAs, actual format/permission evidence, budgets, environment variables,
      approved converter protocol and test results. Provide the generated OpenAPI artifact to the admin plan.
- [ ] Document synthetic acceptance steps for page/text/layout fidelity, original equality, staff/judicial
      denial, multiple replicas, a restart and converter failures. Identify required endpoint/key/font
      provisioning without claiming it has happened or embedding credential values.
- [ ] Review documentation links/commands and relevant configuration/contract tests. Run Validation Commands
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

Planning only, 23 October 2026. No preview endpoint, converter integration, configuration change, new test
run or Ralphex execution has been performed by authoring this plan. The earlier AAT document-store repair
and its file-download smoke are not evidence that document previews exist. Append execution evidence here.

## Post-Completion

Have platform owners provision the approved converter endpoint/access key and fonts through the appropriate
vault/Flux infrastructure, confirming data-handling and maximum valid upload-size support. Validate in AAT
with synthetic PDF/JPEG/PNG, a true multi-frame TIFF and representative generated DOCX; compare converted
layout/fonts/page count to the original's expected print layout. Exercise original downloads, access
denials, separate replicas, a restart and converter outage/limits. Keep the original download available on
preview failure and resolve any format gap before releasing the admin viewer. Publish/review/merge/deploy
separately from local execution; deploy the producer and converter before the consumer. Move this plan to
`docs/plans/completed/` after implementation and review.
