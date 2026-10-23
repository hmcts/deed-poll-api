# Plan: a strict TIFF structure check, and no JPEG-compressed TIFF previews

## Overview

A focused Copilot review of the second fix run (workspace `local-notes/deed-poll-api/api-preview-direct-review-3.jsonl`)
found a further **HIGH** defect, the third in the JDK's TIFF handling:

- `ImageRenderer` calls the JDK reader (`getWidth()` and the metadata read) before any of our checks, and the JDK
  materialises a frame's whole directory then. TIFF directory entries may reference the same payload bytes, so
  checking that each field fits inside the file does not bound the total work. Copilot's probe: a 66,427-byte 1×1
  TIFF with 64 aliased `JPEGTables` entries caused 4,195,182 bytes of metadata reads and still decoded. Scaled within
  the 25 MiB input limit, that is terabytes of reads and allocations without a budget checkpoint, while holding a
  conversion permit.

**Decisions (Steve, 2026-10-23), on Arbor's recommendation:**
1. **Our own strict, bounded TIFF structure check runs before the JDK sees the file**, so that every directory
   field's payload must occupy its own bytes of the file and the total work is bounded by the file's size.
2. **JPEG-compressed TIFFs (Compression 6, old-style JPEG, and 7, JPEG) are not previewed.** They answer the fixed
   `PREVIEW_INVALID_DOCUMENT`, so the admin viewer shows its preview-unavailable state with **Download original**
   still working. Uploads are unaffected. This removes the `JPEGTables` path, which is the JDK reader's most complex
   and least trusted, rather than defending it. It is a deliberate, reversible choice: enabling it later is a
   self-contained change once the structure check is in place.

## Context

Branch `feat/document-preview-rendering`. Read `CLAUDE.md`'s preview bullet and the three completed preview plans
(`docs/plans/completed/20261023-document-preview-rendering.md`, `…-review-fixes.md`, `…-review-fixes-2.md`) first.
Files: `src/main/java/uk/gov/hmcts/reform/deedpoll/domain/documents/preview/ImageRenderer.java`, the TIFF-JPEG check
the previous run added (find it from `git log -p` of the previous run), `PreviewProperties`, their tests, fixtures
under `src/test/resources/document-previews/` and their reproducible generator `DocumentPreviewFixtures`. Record the
execution baseline (this branch's HEAD) for Ralphex review.

## Development Approach

1. **A bounded TIFF structure check, in a new small class beside `ImageRenderer`**, reading the spooled file with
   plain bounded reads and no JDK image code. Before any `ImageIO` call on a TIFF it must:
   - read the byte-order mark and magic (42; refuse BigTIFF, 43, unless the JDK reader is shown to accept it, in
     which case bound it the same way), then follow IFD offsets;
   - refuse more than `max-pages` (100) IFDs, a loop in the IFD chain, an IFD offset outside the file, or more than a
     fixed cap of entries per IFD (choose one comfortably above what real scanners write, such as 64, and justify it);
   - for every entry, compute its payload size from type × count with overflow-safe `long` arithmetic, refuse a
     payload that runs past the end of the file, and refuse any **overlap** between out-of-line payloads (and with IFD
     tables), so no bytes are shared;
   - refuse when the sum of all payload sizes exceeds the file's length;
   - refuse sub-IFDs and EXIF/GPS IFD pointers, or bound them identically (state which), and refuse unknown types;
   - refuse Compression 6 and 7 (Decision 2);
   - read only offsets, counts and the handful of scalar tags it needs. Never allocate from a declared count before
     the bounds checks pass.

   A refusal is `PREVIEW_INVALID_DOCUMENT` (`PREVIEW_LIMIT_EXCEEDED` for exceeding `max-pages`, as today). The
   existing tile/strip and pixel checks stay, after this check and before decode.
2. **Remove the TIFF-JPEG header check the previous run added**, if Decision 2 makes it unreachable, rather than keep
   dead defensive code. If any part of it still guards a reachable path, keep that part and say why.
3. **Tests first, with real bytes:** Copilot's aliasing probe as a fixture (many entries referencing one shared
   payload) refused before any `ImageIO` call; overlapping payloads; a payload past EOF; a count that overflows
   `int` × type size; an IFD loop; too many IFDs; too many entries; Compression 6 and 7 refused; ordinary
   uncompressed, LZW, PackBits, Deflate and CCITT Group 4 TIFFs, single- and multi-frame, still render with the right
   page count. Prove "before any `ImageIO` call" with a test seam or reader spy, not by timing. Fixtures are generated
   reproducibly with provenance, like the existing ones.
4. **Docs:** state in `README.md`'s preview section, `docs/DOCUMENT-PREVIEW-SMOKE.md` and `CLAUDE.md`'s preview
   bullet that TIFF structure is checked before decode, and that JPEG-compressed TIFFs are deliberately not
   previewed (download original instead), citing this plan's `docs/plans/completed/` path.
5. **Out of scope:** PDF, DOCX, PNG, JPEG, ordering, permits, slots, the page limit's value, OpenAPI codes (no new
   code), `src/test/resources/reference/`, migrations, the admin repository. No new dependency.
6. Local task commits; publishing is separate.

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

### Task 1: The TIFF structure check (Decision 1)

**Files:** a new structure-check class and its test beside `ImageRenderer`; `ImageRenderer.java`; fixtures and
`DocumentPreviewFixtures`.

- [x] Record the execution baseline in the execution notes.
- [x] Add the failing tests from the Development Approach (all but the compression ones), then implement and wire it
      ahead of every `ImageIO` call on a TIFF. Run focused tests and all Validation Commands, and record the results.

### Task 2: No JPEG-compressed TIFF previews (Decision 2)

**Files:** the structure check, `ImageRenderer.java`, the previous run's TIFF-JPEG check, their tests and fixtures.

- [x] Add failing tests for Compression 6 and 7, then refuse them in the structure check, and remove or reduce the
      previous TIFF-JPEG check as the Development Approach says. Run focused tests and all Validation Commands, and
      record the results.

### Task 3: Record and hand off

**Files:** `README.md`, `docs/DOCUMENT-PREVIEW-SMOKE.md`, `CLAUDE.md`, this plan.

- [ ] Document both decisions. **Reference this plan by its `docs/plans/completed/` path**, since that is where it
      will be once the run ends.
- [ ] Run `./gradlew cftlibTest --no-watch-fs` and all Validation Commands; record final commits and counts, and hand
      off to the Ralphex review phases.

## Acceptance Criteria

- No `ImageIO` call touches a TIFF until the structure check has passed it; aliased, overlapping, out-of-file,
  overflowing, looping, over-long and over-wide structures are refused.
- JPEG-compressed TIFFs (Compression 6 and 7) answer `PREVIEW_INVALID_DOCUMENT`; ordinary TIFFs still render with
  the right page count.
- All Validation Commands and `cftlibTest` pass with no new failures or skips.

## Execution notes

**Baseline:** `c43cc0ad971272e6f2c29266c21d7e778e1651d2` (branch HEAD before Task 1).

**Task 1.** New `TiffStructure` beside `ImageRenderer`, called from `ImageRenderer.checkedInput` before the
`FileImageInputStream` is opened, so no ImageIO code sees a TIFF before it passes. Plain `FileChannel` reads; the
largest allocation is one directory table, after its entry count is checked. Rules: `II`/`MM` and magic 42 (BigTIFF
43 refused; the JDK cannot read it), at least one directory, more than `max-pages` frame directories is
`PREVIEW_LIMIT_EXCEEDED`, a directory offset named twice (chain loop, or a pointer back to a directory) refused,
1–64 entries a directory, the thirteen TIFF 6.0 types only, out-of-line payloads (type size × count in `long`
arithmetic) inside the file, header + every directory table + every payload disjoint and in total no larger than the
file. **64 entries:** TIFF 6.0 defines 74 tags, many mutually exclusive; the JDK writer puts about 15 in a frame and
scanners/fax/Photoshop 20–40. **SubIFDs (330) refused; EXIF (34665), GPS (34853) and Interoperability (40965)
pointers bounded identically**, because JDK 21's `TIFFIFD.initialize` follows them once `read()` has installed the
default `TIFFImageReadParam` (Baseline, Fax, ExifParent, GeoTIFF tag sets), and a scanner or phone may write an EXIF
directory: LONG/IFD count 1, each tag once a directory, at most two deep, the pointed-to table and its payloads
claimed like any other. Every refusal is an `IIOException` with a fixed sentence, logged by class name and answered
`PREVIEW_INVALID_DOCUMENT` by `ImageRenderer`. Strip and tile *data* are not claimed (only directory fields), so
decode work stays bounded by the existing pixel, tile and decoded-size checks.

Fixtures: committed `aliased-payloads.tiff` (66,327 bytes; 56 JPEGTables entries, 64 in the directory, naming one
64 KiB payload), written field by field by `DocumentPreviewFixtures.aliasedPayloads`; `DocumentPreviewFixturesTest`
proves the JDK alone reads ≥ 56 × 64 KiB from it. Other malformed structures are built in the tests with
`DocumentPreviewFixtures.entry`/`withLeadingEntries`. `PreviewRendererTest.structureRefusedBeforeImageIo` proves
"before any ImageIO call" with `mockStatic(ImageIO)` + `mockConstruction(FileImageInputStream)` (no interactions, no
stream constructed) and a positive control that a passing TIFF does reach ImageIO. `jpegBehindAnUnknownType` now
expects `PREVIEW_INVALID_DOCUMENT`: the structure check refuses the unknown type first.

Red: `TiffStructureTest` + `PreviewRendererTest` + `DocumentPreviewFixturesTest` before wiring — 120 tests, 2
failed (`structureRefusedBeforeImageIo`, `jpegBehindAnUnknownType`). Green after wiring: all pass
(`TiffStructureTest` 20, `PreviewRendererTest$Tiff` 52). Validation: `./gradlew build` (init script) passed — test
4,852 (1 skipped, the opt-in fixture regeneration), integration 24, 0 failures; `checkstyle.sh` OK.

**Task 2.** `TiffStructure` refuses every Compression entry, in every directory it walks, that is not one SHORT or
LONG ("TIFF Compression of an unexpected type or count"), and one whose value is 6 or 7 ("TIFF JPEG compression"),
so a second or mistyped entry cannot leave one the JDK reads unchecked. Both are `PREVIEW_INVALID_DOCUMENT` through
`ImageRenderer`'s existing `IIOException` path. **`TiffJpegPackets` is deleted whole:** every check in it (old-style
refusal, per-packet SOF bounds, JPEGInterchangeFormat fallback, JPEGTables content and total) ran only for a frame
whose Compression was 6 or 7, and no such frame now reaches ImageIO, so no part guards a reachable path. Its calls
in `ImageRenderer` (tiled and stripped) went with it. `TiffDirectories` stays: `TiffOrientation` still uses it. Its
eight-byte step for an unknown type is now unreachable too (`TiffStructure` refuses unknown types), but it is the
JDK's reading, kept so the orientation read stays faithful if that rule is ever relaxed. Tests removed with it: the
nine JPEG-in-TIFF renderer tests and their helpers, `DocumentPreviewFixtures.withStripOffsetsAs`, and `JPEG` from
the supported-compressions list. Added: `TiffStructureTest.jpegCompression` (6 and 7; SHORT, LONG, an extra entry,
and the second frame of a JDK-written big-endian TIFF), `jdkJpeg`, `mistypedCompression` (UNDEFINED, count 2, and a
LONG 1 that passes); `PreviewRendererTest.jpegCompressed`; and Compression 6 and 7 in
`structureRefusedBeforeImageIo`, so they are proven refused with no ImageIO interaction and no stream opened.

Red: `TiffStructureTest` + `PreviewRendererTest` — 102 tests, 6 failed (the new ones). Green: the preview tests,
224 (1 skipped). Validation: `./gradlew build` (init script) passed — test 4,845 (1 skipped, the opt-in fixture
regeneration), integration 24, 0 failures; `checkstyle.sh` OK after one line wrap (re-run of `PreviewRendererTest`
green). Docs still name `TiffJpegPackets` (`CLAUDE.md`, `README.md`); Task 3 rewrites them.

## Post-Completion

Publishing, merging and deployment are separate from this plan. The admin viewer shows `PREVIEW_INVALID_DOCUMENT` as
its preview-unavailable state with the original download.
