# Plan: refuse enrolment for a case without two witnesses

## Overview

A deed poll needs **two witnesses**. Decided by the business 2026-09-13. Today nothing enforces it: witnesses
can be added and deleted freely, and a case with none can be enrolled.

**The check goes on the enrolment transition, not on witness writes** — the business's own framing, and it is the
right one. A case is created before its witnesses exist, so it necessarily passes through zero and one; refusing a
write that leaves fewer than two would make it impossible to add the first. Gating the *transition* instead means
a case with one witness is simply a case that is not ready for enrolment yet, which is what it actually is.

### Which transitions

From the state cascade (`reference/dataverse_tables/deedpoll/business_rules.md:67-101`, ported in
`domain/deedpoll/CaseState.java:61-84`), two dates carry the enrolment meaning:

| Date set | Derived state | Business phrase |
| --- | --- | --- |
| `signatureReceivedDate` | Awaiting Enrolment | "sent for enrolment" |
| `dateEnroled` | Enroled | "enrolled" |

**Both are gated.** Blocking only `dateEnroled` would let a case be queued for enrolment it can never complete.

### This rule is an addition, not a port — say so in the code

`business_rules.md` mentions witnesses **zero times**, and the Power App has no such rule in any artefact we
hold. This is a new rule the business gave us on 2026-09-13, so it must be marked as an addition exactly the way
`CaseState.SIGNATURE_ORDER_MESSAGE` is — *"NOT a port -- the source has no such rule"*. **A future reader
comparing this service against the reference must be able to tell which rules are ported and which are ours.**
Getting this wrong quietly corrupts the conformance story, which is the most valuable thing this repo has.

## Decisions taken — implement these, do not revisit

1. **Gate both `signatureReceivedDate` and `dateEnroled`.** See above.
2. **Migrated records are subject to the rule.** Confirmed by the business 2026-09-13: *"For existing cases the
   two witness rule will still apply."* **This is a deliberate departure from the pattern** — the Power App
   relaxes every per-type mandatory field when `Migrated Record = Yes`
   (`FieldRulesConformanceTest$MigratedRecords`), and this rule does not follow it. Record the reasoning at the
   check, or someone will later "fix" it into consistency with the others.
3. **A hard error, not a warning.** The business said it *cannot* be sent for enrolment. Use
   `InvalidInputError`, which is what the write path already throws for cross-field violations, so
   `deed-poll-admin` renders it against the field with no new plumbing.
4. **Address the error to the date field being set**, not to a witnesses field. There is no witness control on
   the case form, so an error addressed to one would have nowhere to render.
   `deed-poll-admin/src/pages/deed-polls/[id]/edit/[tab].ts:140` builds the error summary by mapping the field
   name to a control on that tab — an unmappable field breaks the summary link, not just the styling.
5. **Check on the write path in `DeedPollService`, not inside `CaseState`.** `CaseState.collectDateRuleErrors`
   is pure and takes only the eight dates (`CaseStateInput`); it cannot see a witness count and **must not be
   given one** — it is pinned against the reference by `StateCascadeConformanceTest`, and widening its input to
   carry a non-ported concern would blur exactly the line decision 3 above is protecting. The natural home is
   beside the existing call at `DeedPollService.java:324`.
6. **Count the witnesses already on the record.** `DeedPoll` has
   `@OneToMany(mappedBy = "deedPoll", fetch = FetchType.LAZY) List<DeedPollWitness> witnesses`
   (`entity/DeedPoll.java:390`). It is **LAZY**, so the count must be taken inside the transaction; the write
   path is `@Transactional`, so this is a note rather than a problem — but do not move the check somewhere that
   is not.
7. **The message names the shortfall.** A caseworker needs to know what to do, so say how many are recorded and
   how many are needed, in the house voice — compare `CaseState.CONTRADICTION_MESSAGE`, which tells the reader
   what to remove and why.

## Open question — assumed, and the one thing to confirm

- **At least two, or exactly two?** The business said *"There must be two witnesses"* and *"if an application
  doesn't have 2 witnesses it can't be sent for enrolment"*. **This plan implements *fewer than two* as the
  refusal** — i.e. two or more passes — because "doesn't have 2" reads as a shortfall rather than a cap, and
  because a maximum would need enforcing at witness *creation*, which is a different rule in a different place
  and was not asked for.
  **If the business means exactly two, this plan is still correct as far as it goes** and a maximum becomes a
  separate change to `WitnessService.createWitness`. Flagged rather than guessed at, and cheap to change: the
  comparison is one operator.

## Constraints

- **Java 21, `-Werror`, checkstyle on all five source sets.**
- **`export TESTCONTAINERS_RYUK_DISABLED=true`**, or container-backed tests fail in a static initialiser.
- **Do not touch `CaseState` or `CaseStateInput`.** Decision 5. `StateCascadeConformanceTest` (11 tests) pins
  them against the rules file.
- **Do not edit `src/test/resources/reference/`.** It is the vendored specification. If this rule seems to
  contradict it, that is because the rule is an addition — which is the point, not a discrepancy to reconcile.
- **The witness write operations stay ungated by count.** Adding and deleting witnesses remains free; only the
  enrolment transition is gated. Do not add a count check to `WitnessService`.

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

`build` is compile plus checkstyle across all five source sets, plus `test`, plus `integration`
(`build.gradle:113`). **Baseline verified green from a cold `clean build` on 2026-09-13, at `051f3b4`: `test`
1,286 across 154 suites, `integration` 5 across 3, 0 failures and 0 errors in both.** Re-take it from your branch
point if anything has merged since.

Deliberately **not** here: test counts and baseline comparisons. They are only true once the plan is finished.

## Tasks

### Task 1: The rule

Add the witness-count check to the case write path in `DeedPollService`, beside the existing date-rule collection
at `:324`. It fires only when the merged record would set `signatureReceivedDate` or `dateEnroled` and the record
carries fewer than two witnesses. Produce a `FieldError` addressed to whichever of the two dates is being set.

Mark it clearly as an addition rather than a port, per decision 3, and record the migrated-record departure per
decision 2.

- [x] Fires on both dates, addressed to the date being set, marked as a non-port addition with the
      migrated-record reasoning recorded at the check

Implemented as `DeedPollService.requireWitnessesForEnrolment`, called from `updateDeedPoll` after
`requireConsistentDates`. Two boundaries settled while building it, both recorded at the check:

- **The MERGED record is what is checked**, as with every other rule on that path — so a patch touching only
  a surname on a case that already carries an enrolment date is refused too, and the escape is the same shape
  as the escape from a contradictory date pair (clear the date, or add the witnesses).
- **`createDeedPoll` is NOT gated.** A witness is a child of a row that must exist first, so a create can
  never carry two; gating it would forbid the state outright rather than gate a transition into it. Every
  refusal test in task 2 is therefore update-shaped.

Four existing tests were fixtures of an enrolled case with no witnesses, and now record two: two blocks of
`DeedPollServiceTest`, `DeedPollConcurrentUpdateTest.derivesStateFromTheCommittedRowRatherThanAStaleRead` and
`CaseworkerCaseControllerTest$UpdateCase.clearsAnExplicitNullAndLeavesAnOmittedFieldAlone`.

### Task 2: Tests

Booting the whole context where the check is exercised over HTTP, and unit-level where it is not.

1. **A case with two witnesses can be sent for enrolment** — `signatureReceivedDate` accepted.
2. **A case with two witnesses can be enrolled** — `dateEnroled` accepted.
3. **A case with one witness is refused `signatureReceivedDate`**, with the error against that field.
4. **A case with one witness is refused `dateEnroled`**, with the error against that field.
5. **A case with no witnesses is refused**, so the boundary is not off by one at zero.
6. **Three witnesses are accepted** — pins the "at least two" reading from the open question, so that if the
   business later says exactly two, this test is the one that changes and it is obvious why.
7. **A migrated record with one witness is refused too** — decision 2. Without this test the departure from the
   migrated-relaxation pattern is invisible and a later tidy-up will remove it.
8. **The other six state transitions are unaffected** — a case with no witnesses can still reach Ready for
   Master, Referred to Master, Awaiting Further Docs and Received. This is the test that stops the gate being
   applied too widely.

- [ ] All eight present, including the migrated-record case and the "other transitions unaffected" guard

### Task 3: Full validation

- [ ] `./gradlew clean build` green — checkstyle across all five source sets, `test` and `integration`
- [ ] **`./gradlew test --rerun`**, never bare `./gradlew test` — a bare run reports `:test UP-TO-DATE`,
      **executes nothing**, and still prints `BUILD SUCCESSFUL`
- [ ] Count from the XML, not Gradle's summary:
      `grep -ho 'tests="[0-9]*"' build/test-results/test/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END{print s}'`
      (`bc` is not installed here)
- [ ] `grep -l 'failures="[1-9]\|errors="[1-9]' build/test-results/test/*.xml` → no output
- [ ] `git diff --name-only master -- src/main/java/uk/gov/hmcts/reform/deedpoll/domain/deedpoll/CaseState.java src/main/java/uk/gov/hmcts/reform/deedpoll/domain/deedpoll/CaseStateInput.java`
      returns **nothing** — decision 5, the cascade is untouched
- [ ] `git status --short -- src/test/resources/reference` clean — the specification was not edited
- [ ] Mark completed

> The suite is container-backed and takes about eight minutes. **One red run is not proof of a break** — re-run
> once and read the XML rather than the console.

## Deliberately not in this plan

- **Any UI.** `deed-poll-admin/docs/plans/07-witness-count-feedback.md`, which depends on this merging. The error
  will surface there without new plumbing; that plan is about telling a caseworker *before* they try.
- **A maximum witness count.** See the open question — a different rule, in `WitnessService`, if wanted at all.
- **Gating witness add or delete by count.** Constraint above. A caseworker must be able to fix a case.
- **Any other state transition.** Only the two enrolment dates are gated, and task 2's eighth test pins that.
- **Backfilling existing data.** Nothing is deployed, so there is no data. When there is, a migrated case with
  fewer than two witnesses becomes visible at the point someone tries to enrol it, which is the right time.
