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

- [x] Record the execution baseline in the execution notes.
- [x] Add the failing fixture test (oversized tile; oversized strip; ordinary tiled and stripped TIFFs still render),
      then fix. Run focused tests and all Validation Commands, and record the results.

### Task 2: Docmosis is sent at most once (finding 2)

**Files:** `DocxRenderer.java` and its tests.

- [x] Add the failing `Retry-After: 0` test and any other follow-up cases found, then fix. Run focused tests and all
      Validation Commands, and record the results.

### Task 3: Record and hand off

**Files:** `README.md` and `CLAUDE.md` only if they state a changed fact, this plan.

- [x] Note the tile/strip bound beside the pixel limit and the one-shot converter request in the existing preview
      docs. **Reference this plan by its `docs/plans/completed/` path**, since that is where it will be once the run
      ends.
- [x] Run `./gradlew cftlibTest --no-watch-fs` and all Validation Commands; record final commits and counts, and
      hand off to the Ralphex review phases.

## Acceptance Criteria

- A TIFF whose declared tile or strip exceeds `max-frame-pixels` is refused before any pixel decode; ordinary TIFFs
  still render.
- A Docmosis `503` with `Retry-After: 0` reaches the converter exactly once and answers `PREVIEW_UNAVAILABLE`.
- All Validation Commands and `cftlibTest` pass with no new failures or skips.

## Execution notes

**Baseline:** `cb236577268f6c0322c992fdd9f656210a9ee19d` (`docs(plan): add the second preview review-fixes plan`),
branch `feat/document-preview-rendering`, clean.

**Task 1 (TIFF tiles and strips).**
- *Where the reader allocates from.* JDK 21's `TIFFImageReader.getTileOrStripWidth/Height` give `TileWidth`, else
  `ImageWidth`; `TileLength`, else `RowsPerStrip` (`-1`, i.e. a LONG `0xFFFFFFFF`, means the height), else the height.
  It builds each tile rectangle from these **unclamped** values, so a `RowsPerStrip` past the height is allocated as
  declared. `ImageReader.getTileWidth(index)`/`getTileHeight(index)` return exactly these two methods, from the
  directory already parsed for `getWidth`, so the check uses the reader's own interpretation rather than a second
  one. Both go through `requireFrame` (below 1 is `PREVIEW_INVALID_DOCUMENT`, a LONG over `Integer.MAX_VALUE` reads
  negative and is invalid too), and the larger of frame and tile/strip feeds the existing decoded-size check.
- *Reading the directory.* `TIFFIFD.initialize` reads a field's value only if `offset + size` lies inside the
  stream, skips counts beyond `int`, clamps ASCII to 64 KB and has no compressed fields, so the parsed directory is
  linear in the file. **But `getImageMetadata(index)`, which the existing orientation read used, is not:** it copies
  the directory through `TIFFFieldNode`, a DOM node with a string attribute per array value, and `setFromTree`
  materialises every node. Measured: an 8 MB, 16×1,000,000 TIFF of a million strips (inside the pixel limit) ran out
  of a 256 MB heap inside `getImageMetadata(0)` (`Long.toString`), and needed between 256 MB and 1 GB. So orientation
  now comes from `TiffOrientation`, which walks the IFD chain as the JDK reader does and reads each entry's 12 bytes
  from a second stream, keeping one value. The same file's `getTileWidth/Height` plus `TiffOrientation` run in a
  64 MB heap. `PreviewBoundaryTest.noImageMetadataTree` fails the build if preview code calls `getImageMetadata`.
  PNG keeps `ignoreMetadata=true`.
- *Fixture.* `oversized-tile.tiff` (162 bytes, committed, made by `DocumentPreviewFixtures.tiledGrey`): 16×16 grey,
  one declared 8192×8192 tile, whose only packet is labelled Deflate and is not a zlib stream. Decoding it is
  `INVALID_DOCUMENT`, so `LIMIT_EXCEEDED` proves the refusal came from the directory before `read(index)`; the same
  packet under a 16×16 tile is `INVALID_DOCUMENT`, proving the packet really is undecodable.
- *Red (before the fix):* 9 of the new `PreviewRendererTest` cases and the ArchUnit rule failed: oversized tile and
  oversized strip answered `INVALID_DOCUMENT` (the decoder ran), and the tile at 64×80 and the strip of 257 rows
  against a 4,096-pixel limit were served. (Two more were test errors: commas in CSV names, and a grey sample drawn
  through the grey colour space's tone curve, so the ordinary cases assert black.)
- *Green:* every `documents.preview` test passes (180); ordinary tiled (64×64 tiles), 16-row strips, no
  `RowsPerStrip`, `RowsPerStrip` 2^32−1 and `RowsPerStrip` 65,535 (past the height, within the limit) all render;
  zero-row tiles and strips are invalid.
- *Validation:* `./gradlew build` (init script) passed: `test` 4,801 tests, 0 failed, 1 skipped (the env-gated fixture
  regeneration); `integration` 24, 0 failed. `checkstyle.sh` flagged two single-line Javadocs in the fixtures, fixed;
  then OK, and the fixtures test re-run green.

**Task 2 (Docmosis is sent at most once).**
- *Fix.* `DocxRenderer` wraps the multipart body in `OneShot`, a delegating `RequestBody` whose `isOneShot()` is
  true. OkHttp 4.12's `RetryAndFollowUpInterceptor` returns the response rather than sending a follow-up whose body
  is one-shot, and its connection-failure recovery refuses one-shot bodies too. `retryOnConnectionFailure(false)`,
  refused redirects and `Proxy.NO_PROXY` are kept.
- *Other follow-ups checked.* `408` is already refused by `retryOnConnectionFailure(false)` (and by one-shot); `421`
  is retried only on a coalesced HTTP/2 connection and never for a one-shot body; `401`/`407` use the default
  `Authenticator.NONE` (and `407` without a proxy is a `ProtocolException`); `3xx` is refused by
  `followRedirects(false)`. Each is a parameterised case (`noFollowUp`: 408, 421, 401, 407, 300, 301, 302, 303, 307,
  308) asserting one request and `PREVIEW_UNAVAILABLE`.
- *Red:* only `noReplayOfAnImmediateRetry` (503, `Retry-After: 0`) failed before the fix; the follow-up cases were
  already single sends and now pin it. *Green:* every `documents.preview` test passes.
- *Validation:* `./gradlew build` (init script) passed: `test` 4,812 tests, 0 failed, 1 skipped (the env-gated fixture
  regeneration); `integration` 24, 0 failed. `checkstyle.sh` OK.

**Task 3 (record and hand off).**
- *Docs.* README's preview section cites this plan by its `docs/plans/completed/` path, states the tile/strip bound
  in the `DOCUMENT_PREVIEW_MAX_FRAME_PIXELS` row, and the one-shot body beside "no retry and no redirect followed".
  CLAUDE.md's preview bullet gains the same two facts plus the `getImageMetadata` ban, and `docs/RESUME.md` a line.
  No OpenAPI change, so the admin handoff artifact is unchanged.
- *Validation:* `./gradlew build` (init script) passed: `test` 4,812 tests, 0 failed, 1 skipped (the env-gated fixture
  regeneration); `integration` 24, 0 failed. `checkstyle.sh` OK. `./gradlew cftlibTest --no-watch-fs` passed: 21
  tests, 0 failed, 0 skipped, including all seven `DocumentPreviewCftlibTest` journeys.
- *Commits:* `476cc15` (Task 1), `96516eb` (Task 2), and this task's docs commit on top. Ready for the Ralphex review
  phases from the baseline `cb23657`.

## Post-Completion

Publishing, merging and deployment are separate from this plan.
