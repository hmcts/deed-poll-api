# Plan: a 2,000-character limit on the three case narrative fields, and no blank witness fields

## Overview

The api half of admin review finding **R15** (admin PR hmcts/deed-poll-admin#269, plan
`docs/plans/completed/20261022-r12-r15-gtm-off-and-input-gaps.md` there). Steve asked on 2026-10-22 for api to match
what admin now enforces:

1. **`returnReason`, `referralNotes` and `masterDirections` are capped at 2,000 characters** on case create and
   update. Today `DeedPollSchemas` gives all three `optionalText()` (no cap) and the columns are `text`, so any
   client can store any length. Admin's edit form advertises and now enforces 2,000.
2. **A witness's `witnessName`, `witnessAddress` and `witnessOccupation` are trimmed and must not be blank** on
   create, and on update for any field present. Today `WitnessSchemas` accepts `""` and whitespace, and a blank
   witness counts towards the enrolment-witness threshold.

## Context

Baseline: api `origin/master` `ebe9843`. Read `AGENTS.md`/`CLAUDE.md` and the class docs of the files below first.
Record the execution baseline and recheck both gaps there.

- `src/main/java/uk/gov/hmcts/reform/deedpoll/domain/deedpoll/DeedPollSchemas.java`: the field table
  (`schema.put("returnReason", optionalText())` and the other two, around lines 489-497), `optionalTextMax(int)`,
  and `text(field, value, maxLength)`, which measures `string.length()` and refuses with
  `"<field> must be <n> characters or fewer"`. The name fields already use `optionalTextMax(NAME_MAX_LENGTH)`.
- `src/main/java/uk/gov/hmcts/reform/deedpoll/config/OpenAPIWriteBodies.java` publishes each field's `maxLength`
  from `DeedPollSchemas.writeBodyFields()`, and the witness caps from `WitnessSchemas`' constants. So the new cap
  reaches the spec without a second number; check the spec tests that pin the published bodies.
- `src/main/java/uk/gov/hmcts/reform/deedpoll/domain/witnesses/WitnessSchemas.java`: `parseCreateWitness` and
  `parseUpdateWitness`, a port of the vendored specification `src/test/resources/reference/ts/witnesses/schemas.ts`,
  whose test blocks `WitnessSchemasTest` ports. **Never edit `src/test/resources/reference/`** (its `README.md`).
  Admin changed that specification in hmcts/deed-poll-admin#269 (trim and require the three text fields), and the
  re-vendoring commit has **already been made before this run**: it re-copies admin `origin/master`'s
  `libs/deed-poll/src/witnesses/schemas.ts` and `schemas.test.ts` verbatim and does nothing else. So the port now
  disagrees with its specification, and Task 2 brings it back into line: port the changed schema and **every** test
  block in the refreshed `schemas.test.ts` (including any that admin added before #269, such as at-limit
  boundaries). Rewrite `WitnessSchemas`' class doc, which justifies not trimming and accepting blanks by parity with
  the old reference.
- **The narrative cap is not a port.** Admin enforces 2,000 in its edit-form layer
  (`libs/deed-poll/src/deed-poll/form-input.ts`, `TEXTAREA_MAX_LENGTH`), not in its domain schema, so the vendored
  `ts/deed-poll/schemas.ts` still says `optionalText` for the three fields and stays untouched (it has also drifted
  from admin for unrelated reasons; do not re-vendor it). Document the cap in `DeedPollSchemas` as api's own rule
  matching admin's form, the way the repo marks other non-port behaviour.
- **Only the caseworker request DTOs call these schemas** (`CreateCaseRequest`, `UpdateCaseRequest`,
  `CreateWitnessRequest`, `UpdateWitnessRequest`). Confirm that no import, migration, seed or CCD path parses through
  them before relying on it, because a cap there would refuse legacy data.

### How admin counts, which api must match

Admin trims a textarea, then measures it with each line break counted **once** (`\r\n` and `\r` read as `\n`),
because a browser's `maxlength` counts that way. But it **sends** the text as submitted, with CRLF line breaks. So a
2,000-character return reason with 10 line breaks arrives at api as 2,010 UTF-16 units. If api counted
`string.length()` raw it would refuse text admin accepted, and the caseworker would get admin's generic "could not be
saved" page instead of a field error. **The three narrative fields must therefore be measured with CRLF and CR
counted as one character**, otherwise as `String.length()` (UTF-16 code units, which is what admin and browsers count).
Leave the measurement of every other text field as it is today.

Existing rows: a stored value over 2,000 is untouched. A patch that omits the field is accepted as today; a patch
that carries it over the cap is refused with the field-named 400, which admin renders against the field.

## Development Approach

1. **The cap.** One constant (for example `NARRATIVE_MAX_LENGTH = 2000`) in `DeedPollSchemas`, applied to the three
   fields on both create and update through the field table, so the published spec carries `maxLength: 2000` for
   them on `CreateCaseRequest` and `UpdateCaseRequest`. The line-break rule above applies only to these three. The
   refusal is the existing `FieldRejected` message shape, attributed to the field. An explicit `null` still clears.
   Since the spec's `maxLength` counts a CRLF as two, add a short note to the published field description (or the
   existing body note) that line breaks count as one character, so a client reading the spec is not misled.
2. **Witness fields, following the refreshed specification.** Trim each of the three text fields; an empty result is refused as a field error
   (`"<field> must not be blank"` or the file's existing wording style), on create and on update for a present
   field. The trimmed value is what is stored. The caps are measured after trimming. Absent and `null` keep their
   current handling. `declarant` is unchanged. Publish `minLength: 1` on the three in `OpenAPIWriteBodies` beside
   their `maxLength`, with a note that the value is trimmed first.
3. **Tests first**, at the schema level and through the controllers (MockMvc or the repo's existing web tests), plus
   the OpenAPI document tests: 2,000 accepted; 2,001 refused with the field-named 400 and nothing written; 2,000
   characters plus CRLF line breaks accepted; explicit null still clears; an omitted field leaves a long stored value
   alone; blank and whitespace-only witness fields refused on create and update with nothing written; padded values
   stored trimmed; an update that omits a field leaves it unchanged.
4. **Out of scope:** anything under `src/test/resources/reference/` (already refreshed; never edit it), admin's copy of `openapi.json` (admin refreshes it separately, and its contract test compares
   the whole document), existing stored data, other text fields, the referral and note schemas. No new dependency,
   no database migration.
5. Local task commits are appropriate; publishing is separate.

## Validation Commands

Run from the repository root before implementation and after each task:

```bash
export JAVA_TOOL_OPTIONS=-XX:UseSVE=0 TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build -I /home/hmcts/.claude/jobs/c45dad71/tmp/no-checkstyle.gradle --console=plain
/home/hmcts/.claude/jobs/c45dad71/tmp/checkstyle.sh .
```

This devcontainer's JVM needs `-XX:UseSVE=0`, and Gradle's Checkstyle worker ignores it and crashes with SIGILL, so
the init script disables the Checkstyle tasks and `checkstyle.sh` runs the same jar and config over every source set
directly, failing on any `[WARN]` or `[ERROR]`. Jenkins runs the real Checkstyle tasks. Do not edit either helper
script or commit them. Record actual `test` and `integration` counts, failures and skips from
`build/test-results/`, and distinguish baseline failures from regressions.

**Known intermittent test, already on master:** `CcdCreationAttemptRepositoryTest` "an attempt that ran out of tries on refusals may be retried from scratch, which clears the proof" failed once in a full build on 2026-10-22 (line 550, `claim` returned empty) and passed three times out of three alone. If it fails, re-run it alone with `--tests '*CcdCreationAttemptRepositoryTest' --rerun`; if it passes, record that and carry on. Do not change it or anything under `ccd/` in this plan.

Run every build and test command in the foreground and wait for it to finish. A backgrounded build ends the
session and the next one builds over it, wiping the results.

## Implementation Steps

### Task 1: Cap the three narrative fields (gap 1)

**Files:** `DeedPollSchemas.java`, `OpenAPIWriteBodies.java` if the note needs adding, and their tests; the case
controller tests.

- [x] Record the execution baseline in the execution notes.
- [x] Add failing tests as listed in the Development Approach, for create and update, and for the published spec.
- [x] Implement, run focused tests and all Validation Commands, and record the results.

### Task 2: No blank witness fields (gap 2)

**Files:** `WitnessSchemas.java` (code and class doc), `WitnessSchemasTest.java` (port every block of the refreshed
`reference/ts/witnesses/schemas.test.ts`), `OpenAPIWriteBodies.java`, and their tests; the witness controller tests.

- [x] Add failing tests as listed in the Development Approach, for create and update, and for the published spec.
- [x] Implement, run focused tests and all Validation Commands, and record the results.

### Task 3: Record and hand off

**Files:** `AGENTS.md` or `CLAUDE.md` where they describe these schemas (only if they do), this plan.

- [x] Update any repository doc that states witnesses accept blanks or that the narrative fields are unbounded
      (none does; nothing to change).
      **Reference this plan by its `docs/plans/completed/` path**, since that is where it will be once the run ends.
- [x] Record final commits, changed files and test counts in the execution notes.
- [x] Run all Validation Commands and hand off to the Ralphex review phases.

## Acceptance Criteria

- `returnReason`, `referralNotes` and `masterDirections` over 2,000 characters (line breaks counted once) are a
  field-named 400 on create and update, and the spec publishes `maxLength: 2000` for them.
- Witness name, address and occupation are stored trimmed, and blank or whitespace-only values are a field-named 400
  on create and on update when present; the spec publishes `minLength: 1`.
- Nothing else about either schema changes, and all Validation Commands pass with no new failures or skips.

## Execution notes

(Record baseline, red/green evidence and final results here.)

**Baseline (2026-10-22).** HEAD `719bd94` on `fix/textarea-limit-and-blank-witnesses`, over `origin/master`
`ebe9843`. Full build green: `test` 4553 tests, 0 failures, 0 errors, 0 skipped; `integration` 23, 0/0/0;
`checkstyle.sh` OK. Both gaps rechecked there: the three narrative fields were `optionalText()` (no cap), and
`WitnessSchemas` accepted `""` and whitespace. Only `CreateCaseRequest`, `UpdateCaseRequest`,
`CreateWitnessRequest` and `UpdateWitnessRequest` call the schemas (plus `OpenAPIWriteBodies` reading
`writeBodyFields()` and the witness constants); `ccdImport`, `cftlib`, migrations and seeds do not, so the cap cannot
refuse legacy data. Admin `origin/master` `7567b60` `form-input.ts` sends the trimmed text with its CRLFs kept and
measures it with `/\r\n?/g` folded to `\n`, which is what api now does.

**Task 1.** `DeedPollSchemas.NARRATIVE_MAX_LENGTH = 2000` via `narrativeText()` on `returnReason`, `referralNotes`
and `masterDirections`; `Rule` and the public `FieldSchema` gained `lineBreaksCountOnce`, and only those three set
it. The value is stored as sent. `OpenAPIWriteBodies` publishes `maxLength: 2000` plus a field description that a
line break counts as one character. Tests: `DeedPollSchemasTest$NarrativeFields` (5), `CaseworkerCaseControllerTest$NarrativeCap`
(9: create at the cap and with CRLF, create over per field with nothing written and no serial consumed, patch over per
field leaving the stored value, patch with CRLF plus explicit null clearing, omitted field keeping a 5,000-character
stored value), `OpenAPIWriteBodiesTest` and `OpenAPIPublisherTest` spot checks. Red: the new tests did not compile
against the old schema; with the line-break folding switched off, 2 of `NarrativeFields` failed. Green: full build
`test` 4567 (+14), 0 failures, 0 errors, 0 skipped; `integration` 23, 0/0/0; `checkstyle.sh` OK (after one
single-line Javadoc fix, re-run alone).

**Task 2.** `WitnessSchemas.requireText` trims each of the three text fields with a port of JavaScript's
`String.prototype.trim` (zod's `.trim()`; Java's `trim()`/`strip()` strip different sets), refuses an empty result
as `"<field> must not be blank"`, measures the cap after trimming and returns the trimmed value, so that is what is
stored. Absent and `null` keep their messages; `declarant` is unchanged. Class doc rewritten. `OpenAPIWriteBodies`
publishes `minLength: 1` and a "Trimmed first" description on the three fields of both witness bodies, and the create
body's description no longer says values are untrimmed. `WitnessSchemasTest` ports every block of the refreshed
`schemas.test.ts` (at-limit and one-over pairs, blank/whitespace per field, trimmed values, max after trimming, and
the four new update blocks), plus a JavaScript-trim-set test; the old non-reference at-cap and update truthy-string
blocks were folded into their reference counterparts. `CaseworkerWitnessControllerTest`: `doesNotTrim` became
`storesTrimmed`; added blank refusals on create (3, nothing written) and patch (3, row and `modifiedOn` untouched) and
a padded patch stored trimmed. `OpenAPIWriteBodiesTest` and `OpenAPIPublisherTest` assert `minLength: 1`. Red: with
the old `WitnessSchemas`/`OpenAPIWriteBodies` (plus a `trim` stub so the tests compiled), 16 of 77 focused tests
failed. Green: full build `test` 4582 (+15), 0 failures, 0 errors, 0 skipped; `integration` 23, 0/0/0;
`checkstyle.sh` OK after two test-source fixes (line length, variable distance), then those two classes re-run green
alone. The intermittent `CcdCreationAttemptRepositoryTest` did not fail.

**Task 3.** No repository doc (`CLAUDE.md`, `README.md`, `docs/*.md`; there is no `AGENTS.md`) states that witness
fields accept blanks or that the three narrative fields are unbounded, so none changed; the class docs of
`DeedPollSchemas` and `WitnessSchemas` carry the rules. Commits over `origin/master` `ebe9843`: `a9066b1`
(re-vendor), `ebfdaf3` and `719bd94` (plan), `0811510` (Task 1), `aba4912` (Task 2), plus this one. Changed files:
`DeedPollSchemas.java`, `WitnessSchemas.java`, `OpenAPIWriteBodies.java`; tests `DeedPollSchemasTest`,
`WitnessSchemasTest`, `CaseworkerCaseControllerTest`, `CaseworkerWitnessControllerTest`, `OpenAPIWriteBodiesTest`,
`OpenAPIPublisherTest`; the re-vendored `reference/ts/witnesses/schemas{,.test}.ts`; this plan. Final full build:
`test` 4582, 0 failures, 0 errors, 0 skipped (baseline 4553); `integration` 23, 0/0/0; `checkstyle.sh` OK. The
intermittent `CcdCreationAttemptRepositoryTest` did not fail.

## Post-Completion

Publishing, merging and deployment are separate from this plan. Admin refreshes its `openapi.json` afterwards.
