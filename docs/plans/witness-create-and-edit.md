# Plan: expose witness create and edit

## Overview

**The domain layer already exists. This plan adds only the HTTP surface.**

`deed-poll-admin` can display a case's witnesses and has no way to record one. That is the single place in the
service where data is readable and not writable, and both roles are meant to have it — the business document
([`../Deed Poll Power App Permissions Summary.docx`](../Deed%20Poll%20Power%20App%20Permissions%20Summary.docx))
gives Basic User "Create, view and edit witness records" and the capability matrix marks "Add and edit
witnesses" **✓ for both roles**.

The gap register in [`scope-gap-questions.md`](scope-gap-questions.md) lists this first. What that register does
**not** say, and what materially shrinks this plan, is that everything below the controller is already built and
tested:

| Already present | Where |
| --- | --- |
| `createWitness`, `findWitnesses`, `findWitness`, `updateWitness`, `removeWitness` | `domain/witnesses/WitnessService.java:61,85,96,111,130` |
| Length-validating parsers | `domain/witnesses/WitnessSchemas.java:87,131` — 100 / 250 / 100, matching the source columns |
| `CreateWitnessInput`, `UpdateWitnessInput` | `domain/witnesses/` — `Optional`-based, so PATCH's absent/value split already works |
| Entity, repository, response DTO | `entity/DeedPollWitness.java`, `repository/DeedPollWitnessRepository.java`, `api/caseworker/dto/WitnessResponse.java` |
| Tests | `WitnessServiceTest` 13, `createWitnessSchema` 8, `updateWitnessSchema` 6 — **27 already green** |

**What is missing is a controller.** There is no `CaseworkerWitnessController`; `WitnessService` is currently
called by nothing but its own test. So this is a wiring plan, not a build.

### Why this is its own plan

It unblocks `deed-poll-admin`'s witness UI, which cannot start until these routes exist — that repo's
`libs/deed-poll/src/witnesses/service.ts` says so in its own docblock: *"Restoring any of them is a change in api
first."*

**Deletion is deliberately a separate plan** ([`witness-delete.md`](witness-delete.md)) and should land second.
It carries a role gate and a live defect that this plan must not silently inherit — see constraint 4.

## Decisions taken — implement these, do not revisit

1. **Do not gate create or edit.** Both roles have them. Gating in the tidy-minded direction would remove a
   capability caseworkers are meant to have — the same trap [`completed/gate-note-deletion.md`](completed/gate-note-deletion.md)
   called out for note editing.
2. **Mirror `CaseworkerNoteController` exactly.** Same package, same shape: `@RequestMapping`
   (`/caseworker/cases/{id}/witnesses`), `@PathVariable`, request DTOs with a `toInput()`, `WitnessResponse`
   out. Four routes: `GET` (list), `POST`, `GET /{witnessId}`, `PATCH /{witnessId}`.
3. **No author is recorded, and that is correct.** Notes take a `CurrentUser` because the note table stores an
   author; the witness table does not. Dataverse's `CreatedBy` is one of the 21 platform system columns
   deliberately not ported (`conformance/DataverseSchemaConformanceTest.java:52-75`). `WitnessService` already
   stamps `createdOn` / `modifiedOn` through `AuditClock`. **Do not add a caller parameter to create or update**
   — there is nothing for it to do, and adding one invites the false impression that a role check happens.
4. **Do not touch `domain/witnesses/`.** It is complete and covered. If a controller test wants a behaviour the
   service does not have, that is a finding to report, not a licence to edit the domain in this plan.
5. **New request DTOs, one per operation** — `CreateWitnessRequest`, `UpdateWitnessRequest`, both under
   `api/caseworker/dto/`. Never accept or return the entity (`CLAUDE.md`).
6. **`declarant` is required on create and optional on patch.** All four witness columns are `Required: Yes` in
   the Dataverse export and `nullable = false` on the entity, so create must set all four.
   `UpdateWitnessInput` is already `Optional` throughout, which is the patch semantics — absent leaves alone.

## Open questions — flagged, NOT answered here

Neither is invented and neither blocks this plan. Both are recorded so the reviewer sees they were considered.

- **Is there a minimum number of witnesses a case must retain?** **No artefact states one.** Checked:
  `DeedPollValidation.java`, `reference/ts/deed-poll/deed-poll-validation.ts`, and
  `reference/dataverse_tables/deedpoll/business_rules.md` (zero mentions of "witness"). This plan therefore
  enforces **no minimum**. It matters more for [`witness-delete.md`](witness-delete.md) than here.
- **Is there a cardinality rule on `declarant`** — exactly one declarant per case, or any number? **No artefact
  states one.** This plan enforces **no rule**: `declarant` is a free boolean per witness. If the business wants
  "exactly one", it is a service-level rule and a separate change.

## Constraints

- **Java 21 toolchain, `-Werror`, checkstyle on `main`, `test`, `integration`, `smoke`, `functional`.**
- **A controller test must boot the whole context.** Per `CLAUDE.md`: a `@WebMvcTest` slice does not get
  `SecurityConfiguration`, so it asserts Boot's defaults rather than ours. Follow whatever
  `CaseworkerNoteControllerTest` does.
- **`export TESTCONTAINERS_RYUK_DISABLED=true`** in this devcontainer, or every container-backed test fails in a
  static initialiser.
- **The spec is generated by springdoc at runtime** (`build.gradle:163`), not committed here. `deed-poll-admin`
  carries a vendored `openapi.json`; refreshing it is that repo's plan, not this one.
- **Check for a stated route or operation count and update it.** The caseworker surface is documented as 13
  operations in more than one place (`CLAUDE.md`, `api/caseworker/**/package-info.java`, `src/pipeline.test.ts`
  in the sibling repo). This plan makes it 17. `completed/gate-note-deletion.md` task 4 had to reconcile exactly
  this kind of stale count; do not leave a new one behind. **Grep for it rather than trusting this list.**

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

`build` is compile plus checkstyle across all five source sets, plus `test`, plus `integration` —
`build.gradle:113` wires `check.dependsOn(integration)`. **Verified green on 2026-09-12 immediately before this
plan was handed over: `test` 1,242 tests across 147 suites and `integration` 5 across 3, with 0 failures and 0
errors in both.**

Deliberately **not** here: test counts, baseline comparisons and `git grep` assertions. Those are only true once
the whole plan is finished, so putting them in a per-task gate would fail every task from the first. They live in
the final task instead.

## Tasks

### Task 1: Request DTOs

Add `CreateWitnessRequest` and `UpdateWitnessRequest` under `api/caseworker/dto/`, each with a `toInput()`
returning the existing domain input record. Mirror `CreateNoteRequest` / `UpdateNoteRequest` for shape and for
how `Optional` fields are expressed on the wire.

### Task 2: The controller

Add `CaseworkerWitnessController` under `api/caseworker/controller/`, `@RequestMapping("/caseworker/cases/{id}/witnesses")`,
delegating each of the four routes to `WitnessService`. No role checks. No caller parameter.

### Task 3: Controller tests

Booting the full context, per the constraint. Cover, at minimum:

- `POST` creates and returns 201/200 as the note controller does, with the four fields round-tripping
- `POST` with a field over its length limit returns the field error the schema produces, addressed to the field
- `POST` missing any of the four required fields is rejected
- `PATCH` with one field set leaves the others alone — the absent/value split
- `PATCH` with an empty body is a no-op rather than an error (`UpdateWitnessInput.EMPTY` / `isEmpty()` already
  exist, so assert the behaviour they imply)
- `GET` list and `GET /{witnessId}`, including a witness id belonging to **another** case answering 404
- **A non-admin caller succeeds on both writes** — this is the regression guard for decision 1

### Task 4: Reconcile the documented operation count

Per the constraint. Grep, then update whatever states it.

### Task 5: Full validation

- [ ] `./gradlew clean build` green — checkstyle across all five source sets, `test` and `integration`
- [ ] **`./gradlew test --rerun`**, never bare `./gradlew test`. A bare run reports `:test UP-TO-DATE` and
      **executes nothing** while still printing `BUILD SUCCESSFUL` — observed again on 2026-09-12
- [ ] **Count from the result XML, not Gradle's summary:**
      `grep -ho 'tests="[0-9]*"' build/test-results/test/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END{print s}'`
      (`bc` is not installed here — do not reach for it)
- [ ] Failures must be empty: `grep -l 'failures="[1-9]\|errors="[1-9]' build/test-results/test/*.xml` → no output
- [ ] **Baseline: 1,242 tests across 147 suites, 0 failures, 0 errors, 0 skipped** — verified green 2026-09-12.
      Expect this plan to raise it, and nothing to be removed
- [ ] Mark completed

> **The suite is container-backed and takes about eight minutes. A single red run is not proof of a break** — one
> `--rerun` failed on 2026-09-12 and the immediate re-run was fully green at 1,242. Re-run once before concluding
> anything, and read the XML rather than the console.

## Deliberately not in this plan

- **Deleting a witness.** [`witness-delete.md`](witness-delete.md) — it needs a role gate and fixes a defect.
- **Any UI.** `deed-poll-admin/docs/plans/05-witness-create-and-edit.md`, which depends on this one merging.
- **A minimum-witness or declarant-cardinality rule.** See the open questions.
- **Soft deletion / `statecode`.** Not ported, deliberately. See [`witness-delete.md`](witness-delete.md).
- **Touching the witness domain layer or its 27 tests.** Constraint 4.
