# Plan: fix the four review findings on document preview rendering

## Overview

A direct Copilot review of the completed preview work
(`docs/plans/completed/20261023-document-preview-rendering.md`) found four defects, confirmed against the code
(workspace `local-notes/deed-poll-api/api-preview-direct-review.jsonl`):

1. **HIGH:** `ImageRenderer` reads PNGs with `ignoreMetadata=false`. The JDK's `PNGImageReader` inflates compressed
   `zTXt`/`iTXt` text chunks with no bound, so a small PNG of permitted dimensions can exhaust the heap. None of the
   input, pixel or scratch limits sees the inflated text.
2. **HIGH:** `PdfRenderer` parses every PDF original and every Docmosis output with PDFBox, only to count pages.
   PDFBox 3.0.8 decodes compressed object streams into heap buffers regardless of the temp-file stream cache, and
   its page iterator collects the page tree recursively before the loop's checks run. A compressed-object bomb or a
   deep or oversized page tree can exhaust heap or stack. PDFs take no conversion permit, so concurrent requests
   amplify it.
3. **MEDIUM:** `PreviewRenderer.render` acquires the conversion permit before `source.open()`, and the source read
   has no deadline (CDAM buffers the response under an inactivity timeout). A slowly trickling read keeps a permit
   and can outlast the 25 s budget and the 30 s gateways.
4. **MEDIUM:** the capacity refusal (429 `PREVIEW_BUSY`) and the unconfigured-converter refusal (503
   `PREVIEW_UNAVAILABLE`) are decided before `source.open()`, which is where CDAM checks the caller. So a caller CDAM
   would refuse can receive 429/503 instead of the download's own 403/404.

**Decisions (Steve, 2026-10-23):**
- Fix all four through this plan.
- **The page limit becomes 100**, down from 500. No uploaded document is expected to come near 50.
- api stops parsing PDF originals and Docmosis output (finding 2). So the 100-page limit is enforced by api only for
  the previews api builds itself (JPEG, PNG and TIFF frames). For PDF and DOCX previews it is enforced by the admin
  viewer, which reads `numPages` through PDF.js before rendering (the admin plan's concern, not this repository's).
  The published `x-document-preview-limits` extension must say which limit api enforces for which formats.

## Context

Branch `feat/document-preview-rendering`, which carries the completed preview plan. Read `CLAUDE.md`, the completed
plan, `README.md`'s preview section and `docs/DOCUMENT-PREVIEW-SMOKE.md` first. Main sources are under
`src/main/java/uk/gov/hmcts/reform/deedpoll/domain/documents/preview/`: `PreviewRenderer` (ordering, spool, permit),
`ImageRenderer` (finding 1), `PdfRenderer` (finding 2, also used for the DOCX output's validation through
`DocxRenderer`), `ConversionCapacity`, `PreviewProperties` (`max-pages`) and `config/OpenAPIDocumentPreviews`.

Record the execution baseline (this branch's HEAD) for Ralphex review.

## Development Approach

1. **Finding 1:** read PNG with metadata ignored. Only JPEG needs its metadata (EXIF orientation via
   `ExifOrientation`); keep that path and confirm it reads a bounded amount. Add a real PNG fixture with a large
   compressed `zTXt`/`iTXt` chunk (a few KB on disk inflating to well over the heap a test allows, or a
   reader-level assertion that the text is never inflated) and prove it previews or is refused without inflating it.
   The fixture must be generated reproducibly, like the existing ones.
2. **Finding 2:** no PDFBox parse of a PDF original or of Docmosis output. Validate by: size within the input/output
   ceilings, a `%PDF-` header at the start, and a `%%EOF` marker within the last 1,024 bytes. Answer
   `PREVIEW_INVALID_DOCUMENT` (or the DOCX converter's failure, as today) otherwise. Stream the spooled file
   through as the preview, with no page count. Keep encrypted-PDF refusal only if it can be detected without a
   parse; otherwise drop it and note that the browser asks for no password because PDF.js is given none. Keep
   PDFBox for **building** image and TIFF previews, which is api's own content. Remove the page loop. Test with a real
   compressed-object-stream bomb fixture (small on disk) that it is served or refused without parsing, by an
   assertion that PDFBox's loader is never called for PDF/DOCX output, or a bounded-heap test if that is reliable.
3. **Page limit 100:** default `max-pages` (`DOCUMENT_PREVIEW_MAX_PAGES`) becomes 100. It applies to the previews api
   builds (TIFF frames; JPEG/PNG are one page). Update the published limits extension, `README.md`,
   `docs/DOCUMENT-PREVIEW-SMOKE.md` and `CLAUDE.md` if they state 500 or that the limit covers PDF/DOCX.
4. **Findings 3 and 4:** in `PreviewRenderer.render`, open and spool the source **first**, which is CDAM's caller
   check and the `DOCUMENT_PREVIEWED` audit, then check the converter is configured (DOCX) and acquire the
   conversion permit (non-PDF), then render. A source refusal therefore keeps its own status (403/404 as the download
   gives), a busy or unconfigured refusal comes only for a document the caller may read, and a slow read holds no
   permit. Keep: nothing partial returned; workspace closed on every failure; permit released only after work stops.
   The cftlib journey that expects an extra audit row on a CDAM denial must be re-examined against the download
   path's behaviour and made consistent with it. Do not change the download path.
5. **The slow-read deadline is a documented limitation, not a watchdog.** The source read keeps CDAM's existing
   timeouts, as downloads do. Document in `README.md`'s preview section and `docs/DOCUMENT-PREVIEW-SMOKE.md` that the
   25 s budget bounds conversion and rendering, not a stalled store read, and that after the reordering a stalled
   read holds no conversion permit.
6. Work test first: each fix gets a test that fails without it. Local task commits; publishing is separate.
7. **Out of scope:** the download path, upload acceptance, CDAM client timeouts, `src/test/resources/reference/`,
   Flyway migrations, the admin repository. No new dependency.

## Validation Commands

Run from the repository root before implementation and after each task:

```bash
export JAVA_TOOL_OPTIONS=-XX:UseSVE=0 TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build -I /home/hmcts/.claude/jobs/c45dad71/tmp/no-checkstyle.gradle --console=plain
/home/hmcts/.claude/jobs/c45dad71/tmp/checkstyle.sh .
```

`build` runs compile, `test` and `integration`. This devcontainer's JVM needs `-XX:UseSVE=0`, and Gradle's
Checkstyle worker ignores it and crashes with SIGILL, so the init script disables the Checkstyle tasks and
`checkstyle.sh` runs the same jar and config over every source set, failing on any `[WARN]` or `[ERROR]`. Jenkins
runs the real Checkstyle tasks. Do not edit, move or commit either helper. At the end also run
`./gradlew cftlibTest --no-watch-fs` (with the same `JAVA_TOOL_OPTIONS`) and record its counts.

Run every build and test command in the foreground and wait for it: a backgrounded build ends the session and the
next one builds over it.

**Known intermittent test, already on master:** `CcdCreationAttemptRepositoryTest` "an attempt that ran out of tries
on refusals may be retried from scratch, which clears the proof" fails occasionally in a full build. If it fails,
re-run it alone with `--tests '*CcdCreationAttemptRepositoryTest' --rerun`; if it passes, record that and carry on.
Do not change it or anything under `ccd/`.

## Implementation Steps

### Task 1: Order the preview so the source is read before any refusal (findings 3 and 4)

**Files:** `PreviewRenderer.java` and its tests; `DocumentPreviewHttpTest`, `DocumentPreviewCftlibTest` as needed.

- [x] Record the execution baseline in the execution notes.
- [x] Add failing tests: a CDAM-refused source answers the download's status, not 429/503, with every permit held
      and with the converter unconfigured; a slow source read holds no permit (another preview can acquire it
      meanwhile); busy and unconfigured refusals still happen, after the source is read, with nothing partial.
- [x] Reorder, run focused tests and all Validation Commands, and record the results.

### Task 2: PNG previews never inflate text metadata (finding 1)

**Files:** `ImageRenderer.java` and its tests; a generated fixture under `src/test/resources/document-previews/`.

- [x] Add the failing fixture test, then fix; confirm JPEG orientation still works. Run all Validation Commands.

### Task 3: PDF originals and Docmosis output are not parsed, and the page limit is 100 (finding 2)

**Files:** `PdfRenderer.java`, `DocxRenderer.java` where it validates output, `PreviewProperties.java`,
`application.yaml`, `config/OpenAPIDocumentPreviews.java`, `OpenAPIPublisherTest`, and their tests; fixtures.

- [x] Add failing tests: no PDFBox load for a PDF original or DOCX output; header and `%%EOF` checks refuse a
      truncated or non-PDF body; a small object-stream bomb is not parsed; the default page limit is 100 and still
      bounds a TIFF with more frames; the published limits say which formats api counts.
- [x] Implement, run focused tests and all Validation Commands, and record the results.

### Task 4: Record and hand off

**Files:** `README.md`, `docs/DOCUMENT-PREVIEW-SMOKE.md`, `CLAUDE.md` if it states a changed fact, this plan.

- [ ] Document the 100-page limit and where each format's page limit is enforced, the no-parse PDF passthrough,
      the new ordering, and the slow-read limitation. **Reference this plan by its `docs/plans/completed/` path**,
      since that is where it will be once the run ends.
- [ ] Run `./gradlew cftlibTest --no-watch-fs` and all Validation Commands; record final commits, changed files and
      test counts in the execution notes, and hand off to the Ralphex review phases.

## Acceptance Criteria

- A PNG's text chunks are never inflated; JPEG orientation still applies.
- No PDF original or Docmosis output is parsed with PDFBox; truncated or non-PDF bodies are refused.
- The default page limit is 100 and bounds TIFF previews; the published limits are accurate.
- A source refusal keeps its own status; busy and unconfigured refusals follow a successful source read; a slow read
  holds no permit.
- All Validation Commands and `cftlibTest` pass with no new failures or skips.

## Execution notes

(Record baseline, red/green evidence and final results here.)

**Baseline:** `b2d2c9c089e172542bb5476b9fbefc4d898ff2a8` (branch HEAD before Task 1).

**Task 1 (findings 3 and 4).** `PreviewRenderer.render` now creates the workspace and spools the source first (the
`DOCUMENT_PREVIEWED` audit and CDAM's caller check), then picks the renderer (DOCX unconfigured is `UNAVAILABLE`),
then takes the permit for the conversion only. The download path is unchanged; it also records
`DOCUMENT_DOWNLOADED` before `store.open`, so a CDAM-denied preview now leaves one audit row exactly as a denied
download does. `DocumentPreviewCftlibTest` was made consistent: the unconfigured DOCX in test 1 is read as the caller
and recorded once, test 2 expects two recorded previews of the DOCX, and test 5 asserts one recorded attempt per
CDAM denial (test 3 already expected `recorded + 1`).
- Red: the new and changed tests run against the old `PreviewRenderer` gave 12 failures in
  `PreviewRendererTest`, `DocxRendererTest`, `DocumentPreviewServiceTest` and `DocumentPreviewHttpTest` (busy and
  unconfigured decided before the read; a refused source under held permits answered `BUSY`/`UNAVAILABLE`; a slow
  read held the only permit).
- Green: the four focused classes pass; `./gradlew build` (init script) passes with 4769 unit tests (1 skipped, the
  opt-in `DocumentPreviewFixturesTest` regenerator) and 24 integration tests, no failures; `checkstyle.sh` OK.
- `cftlibTest` takes no `--tests` filter, so the changed journey is compiled (`compileCftlibTestJava`) and runs in
  full in Task 4.
- The old "keeps its permit until it has stopped" proof read through the stalled source; that read no longer holds a
  permit, so `DocxRendererTest.permitHeldWhileConverting` now proves it against a stalled converter.

**Task 2 (finding 1).** `ImageRenderer` now sets `ignoreMetadata` for every format but TIFF, whose orientation is
the only thing read from a reader's metadata; JPEG orientation was, and still is, read by `ExifOrientation` from the
bytes already held, and that reader always ignored metadata. Checked on this JDK (21+35) that `PNGImageReader` with
metadata ignored skips text chunks for palette images too, not only truecolour ones. New fixture
`text-chunks.png` (19 KB: `scan.png` plus a `zTXt` and a compressed `iTXt`, each ~8 KB inflating to 8 MiB),
generated by `DocumentPreviewFixtures.withCompressedText` and checked structurally by `DocumentPreviewFixturesTest`.
- Red: `PreviewRendererTest.textChunksNeverInflated` (truecolour and palette) cuts each deflate stream in half,
  asserts that a metadata-reading PNG reader refuses the file, and asserts the preview is still one page; against
  the old code both cases failed with `INVALID_DOCUMENT`, because the renderer inflated the text.
- Green: the fixture, renderer, EXIF and DOCX classes pass (JPEG orientation tests unchanged and passing);
  `./gradlew build` (init script) successful, unit and 24 integration tests with no failures; `checkstyle.sh` OK
  after two Javadoc/line-length fixes in the test sources (comment and wrapping only, re-run focused).
- TIFF still reads metadata for its orientation tag; TIFF directory fields are not compressed and are read from the
  bounded input, so they are outside finding 1.

**Task 3 (finding 2, page limit 100).** `PdfRenderer` no longer imports PDFBox. It reads at most 1,029 bytes: the
`%PDF-` header at offset 0 and a whole `%%EOF` within the last 1,024 bytes (`EOF_WINDOW`); otherwise
`INVALID_DOCUMENT`, which `DocxRenderer` still reports as `UNAVAILABLE` for converter output. The size was already
bounded by the input ceiling (spool) or the output ceiling (converter copy), and `DocxRenderer.requireComplete` (ends
in `%%EOF` after whitespace) is kept ahead of it. The spooled file is served as it is. `Rendition.pages` and
`RenderedPreview.pages` are now `OptionalInt`: empty for PDF and DOCX, the frame count for JPEG/PNG/TIFF, and
`PreviewRenderer` applies `requirePages` only when present. `PreviewFormat.countsPages` (JPEG, PNG, TIFF) is the one
statement of which formats api counts. **Encrypted-PDF refusal is dropped**: it cannot be detected without a parse
(`/Encrypt` lives in a trailer or an xref-stream dictionary that only a parser can find reliably). The viewer gives
PDF.js no password, so the browser never asks for one. An empty-page PDF, and a PDF over `max-pages`, are likewise
served for the viewer to judge. The default `max-pages` (`DOCUMENT_PREVIEW_MAX_PAGES`) is 100 in `PreviewProperties`
and `application.yaml`; no chart sets it. `x-document-preview-limits` adds `maxPagesEnforcedFor` (`image/jpeg`,
`image/png`, `image/tiff`) and `maxPagesEnforcedByViewerFor` (PDF, DOCX), both derived from `countsPages`, and its
description says a PDF or a converted DOCX is served unparsed and uncounted. PDFBox still builds image and TIFF previews.
- Bomb: `DocumentPreviewFixtures.objectStreamBomb(padding)` builds a one-page PDF whose catalog, page tree and
  page sit in a FlateDecode object stream behind `padding` zero bytes, with an xref stream. Under 128 KB on disk
  for 64 MiB inflated. It is built in the test, not committed, and its bytes are deterministic.
  `DocumentPreviewFixturesTest` checks with real PDFBox that a small one is a valid one-page PDF. The proof that
  nothing parses it is a `Mockito.mockStatic(Loader.class)` with `verifyNoInteractions` around every PDF and DOCX
  render (`unparsed(...)` in `PreviewRendererTest` and `DocxRendererTest`). A bounded-heap run was not used, because
  an OOM in the red run would take down the whole test JVM.
- Red: 14 failures against the parsing `PdfRenderer` (PDF passthrough, over-page, encrypted/empty/trailing-junk,
  damaged and bomb cases; DOCX contract, table-and-image, over-page and served-unparsed; both
  `PreviewPropertiesTest` defaults; the 100/101-frame TIFF; and the service test's PDF/DOCX `pages()` rows).
- Green: the preview classes and `DocumentPreviewHttpTest` pass. `./gradlew build` (init script) is successful,
  with 4777 unit tests (1 skipped, the opt-in regenerator) and 24 integration tests (incl. `OpenAPIPublisherTest`
  for the new limits keys), 0 failures. `checkstyle.sh` is OK after Javadoc re-wraps, a `final` and moving an
  overload, all comment or layout changes (compiled again). `compileCftlibTestJava` passes.
- `README.md`, `docs/DOCUMENT-PREVIEW-SMOKE.md` and `CLAUDE.md` still state the old limit or behaviour where they do;
  Task 4 updates them.

## Post-Completion

Publishing, merging and deployment are separate from this plan. The admin viewer plan enforces the 100-page limit for
PDF and DOCX previews through PDF.js.
