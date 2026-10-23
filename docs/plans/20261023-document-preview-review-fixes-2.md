# Plan: fix the two findings of the second preview review

## Overview

A second direct Copilot review of the preview branch (workspace
`local-notes/deed-poll-api/api-preview-direct-review-2.jsonl`) confirmed that the four earlier fixes
(`docs/plans/completed/20261023-document-preview-review-fixes.md`) are in the code, and found two new defects:

1. **HIGH:** `ImageRenderer` checks a TIFF frame's `width × height` against `max-frame-pixels`, but the JDK's TIFF
   reader allocates intermediate rasters from the frame's **tile** dimensions (`TileWidth × TileLength`), or for a
   stripped image from `ImageWidth × RowsPerStrip`, which can exceed the image dimensions. A small TIFF declaring a
   huge compressed tile passes the check and can exhaust the heap inside `reader.read(index)`.
2. **MEDIUM:** `DocxRenderer`'s OkHttp client retries a `503` carrying `Retry-After: 0` (OkHttp 4.12's
   `RetryAndFollowUpInterceptor`), independently of `retryOnConnectionFailure(false)`, because the multipart body is
   replayable. That breaks the plan's "no automatic retry on an ambiguous remote conversion" rule.

## Context

Branch `feat/document-preview-rendering`. Read `CLAUDE.md` (its preview bullet) and both completed preview plans
first. Files: `src/main/java/uk/gov/hmcts/reform/deedpoll/domain/documents/preview/ImageRenderer.java`,
`DocxRenderer.java`, their tests, and fixtures under `src/test/resources/document-previews/` with their reproducible
generator (`DocumentPreviewFixtures`). Record the execution baseline (this branch's HEAD) for Ralphex review.

## Development Approach

1. **TIFF tiles and strips.** Before decoding a frame, read its TIFF directory (the reader's image metadata for that
   index, using the JDK's `TIFFDirectory` or the standard metadata tree, with no third-party codec) and refuse with
   `PREVIEW_LIMIT_EXCEEDED` when `TileWidth × TileLength` (tiled) or `ImageWidth × RowsPerStrip` (stripped; an absent
   `RowsPerStrip` means the whole height) exceeds `max-frame-pixels`. Keep the existing `width × height` check. Read
   only the directory, not pixel data, and confirm that reading it does not itself allocate from those values. Use
   `long` arithmetic. A frame whose directory cannot be read is `PREVIEW_INVALID_DOCUMENT`. Keep `ignoreMetadata=true`
   for PNG (the first fix); TIFF needs its directory, so confirm that reading TIFF metadata cannot inflate anything
   unbounded (TIFF has no compressed text chunks; note what the reader reads). Add a reproducible small TIFF fixture
   with valid small dimensions and an oversized declared tile, and prove it is refused before `read(index)`.
2. **One send to Docmosis.** Make the multipart request body one-shot (`RequestBody.isOneShot() = true`, wrapping
   the body) so OkHttp never replays it, and keep `retryOnConnectionFailure(false)` and refused redirects. Add a
   WireMock test that answers the first POST `503` with `Retry-After: 0` and proves exactly **one** request reached the
   stub and the preview is the fixed `PREVIEW_UNAVAILABLE`. Check no other OkHttp follow-up (`408`, `401` with an
   authenticator, `407`, redirects) can resend, and cover any that could.
3. Work test first: each test fails without its fix. Local task commits; publishing is separate.
4. **Out of scope:** everything else, including the page limit, permits, slots, ordering, `src/test/resources/reference/`,
   migrations and the admin repository. No new dependency.

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
`./gradlew cftlibTest --no-watch-fs` (same `JAVA_TOOL_OPTIONS`; it needs port 8080 free) and record its counts.

Run every build and test command in the foreground and wait for it: a backgrounded build ends the session and the
next one builds over it.

**Known intermittent test, already on master:** `CcdCreationAttemptRepositoryTest` "an attempt that ran out of tries
on refusals may be retried from scratch, which clears the proof" fails occasionally in a full build. If it fails,
re-run it alone with `--tests '*CcdCreationAttemptRepositoryTest' --rerun`; if it passes, record that and carry on.
Do not change it or anything under `ccd/`.

## Implementation Steps

### Task 1: Bound TIFF tile and strip allocations (finding 1)

**Files:** `ImageRenderer.java`, its tests, a generated fixture and `DocumentPreviewFixtures`.

- [ ] Record the execution baseline in the execution notes.
- [ ] Add the failing fixture test (oversized tile; oversized strip; ordinary tiled and stripped TIFFs still render),
      then fix. Run focused tests and all Validation Commands, and record the results.

### Task 2: Docmosis is sent at most once (finding 2)

**Files:** `DocxRenderer.java` and its tests.

- [ ] Add the failing `Retry-After: 0` test and any other follow-up cases found, then fix. Run focused tests and all
      Validation Commands, and record the results.

### Task 3: Record and hand off

**Files:** `README.md` and `CLAUDE.md` only if they state a changed fact, this plan.

- [ ] Note the tile/strip bound beside the pixel limit and the one-shot converter request in the existing preview
      docs. **Reference this plan by its `docs/plans/completed/` path**, since that is where it will be once the run
      ends.
- [ ] Run `./gradlew cftlibTest --no-watch-fs` and all Validation Commands; record final commits and counts, and
      hand off to the Ralphex review phases.

## Acceptance Criteria

- A TIFF whose declared tile or strip exceeds `max-frame-pixels` is refused before any pixel decode; ordinary TIFFs
  still render.
- A Docmosis `503` with `Retry-After: 0` reaches the converter exactly once and answers `PREVIEW_UNAVAILABLE`.
- All Validation Commands and `cftlibTest` pass with no new failures or skips.

## Execution notes

(Record baseline, red/green evidence and final results here.)

## Post-Completion

Publishing, merging and deployment are separate from this plan.
