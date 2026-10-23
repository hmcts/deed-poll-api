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

- [ ] Record the execution baseline in the execution notes.
- [ ] Add the failing tests from the Development Approach (all but the compression ones), then implement and wire it
      ahead of every `ImageIO` call on a TIFF. Run focused tests and all Validation Commands, and record the results.

### Task 2: No JPEG-compressed TIFF previews (Decision 2)

**Files:** the structure check, `ImageRenderer.java`, the previous run's TIFF-JPEG check, their tests and fixtures.

- [ ] Add failing tests for Compression 6 and 7, then refuse them in the structure check, and remove or reduce the
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

(Record baseline, red/green evidence and final results here.)

## Post-Completion

Publishing, merging and deployment are separate from this plan. The admin viewer shows `PREVIEW_INVALID_DOCUMENT` as
its preview-unavailable state with the original download.
