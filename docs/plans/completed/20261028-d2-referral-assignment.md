# Plan D2: referrals are assigned to a Master, not claimed from a pool

## Overview

Master Sullivan's feedback, item D (Steve, 2026-10-27/28). This replaces the shared judicial pool with **assignment
by the caseworker raising the referral**, and adds the rules that come with it:

1. A caseworker chooses the Master when raising a referral (decision D-1). The referral goes straight to that Master.
   There is no pool and no claiming.
2. Any caseworker may reassign a referral that is still waiting for a direction, or assign one that is waiting for
   assignment (D-8).
3. **A per-Master cap per referral window** (D-5): at most `masterWindowCap` (10) referrals counted towards one
   Master's morning window, and the same for the afternoon. A referral raised **outside** the windows is assigned at
   once and counts towards the **next** window (D-6). Windows are weekdays that are not bank holidays (D-7).
4. **The processing window** (D-3): an assigned referral with no direction after `processingWindow` (7 days) is
   unassigned and waits for a caseworker to assign it again. This replaces the claim lapse.
5. **My referrals order** for the Master's grouped page (Steve, 2026-10-28): undirected first, urgent first among them,
   then by the day referred.
6. **Go-live** (D-10): claimed referrals stay with their claimer; open ones wait for assignment.

It builds on **D1** (`docs/plans/completed/20261028-d1-masters-and-process-settings.md`), which added the Masters
list and its linking, the settings (`processingWindow()`, `masterWindowCap()`, `referralWindows()`) and
`BankHolidays.isWorkingDay`. Read D1's Overview and decision table first. Emails are D3.

## Context

Read before changing anything:

- `CLAUDE.md`: the judicial-surface rules ("scoped by visibility, not by the case", `JudicialReferralService.visibleTo`,
  the shared 404), "Ownership is the token's IDAM subject", "A case column that mirrors the referral table is
  recomputed from truth" (`refreshCurrentReferralState`), the audit rules, "The referral process settings are
  stored, and read on every decision".
- `domain/referrals/ReferralService` (`claim`, `touch`, `reclaimStaleClaims` and its class javadoc on the mutating
  read), `JudicialReferralService` (`visibleTo`, `POOL_ORDER`, `MINE_ORDER`, the pool and mine reads),
  `ReferralSchemas.parseCreateReferral`, `ReferralNotes`, `entity/Referral`, `repository/ReferralRepository`,
  `api/caseworker/controller/CaseworkerReferralController`, `api/judicial/controller/JudicialReferralController`, their
  DTOs and `config/OpenAPIWriteBodies`.
- D1's `MasterService` (including `linkIfListed`) and `BankHolidays`.

How the existing states are reused, so no `ReferralState` member is added (it is a Postgres enum):

| State | Before | After |
|---|---|---|
| `Open` | in the shared pool | **waiting for assignment**: raised without a Master, or unassigned at the processing window. Caseworker-visible only |
| `Claimed` | claimed by a judge | **assigned to a Master**. `claimedBy` is the Master's name, `claimedById` their linked IDAM subject or null until linked |
| `Decided`, `Closed`, `Withdrawn` | unchanged | unchanged |

## Development Approach

1. **Migration `V43__referral_assignment.sql`** (or the next free number). The referral table is mixed-case and quoted
   (`"deedpoll_referral"`, `"claimedById"`); follow it. Add `"assignedMasterId" uuid references "deedpoll_master"(id)`
   (check the actual table name D1 used), `"assignedAt" timestamp`, `"windowDate" date`, `"windowSlot" varchar(2)` with
   a check `in ('AM','PM')`, all nullable, and an index on `("assignedMasterId", "windowDate", "windowSlot")`. A check that
   the four are all null or all set except that `"assignedMasterId"` may be null while `"claimedById"` is set (step 8).
   Map them on `Referral`. Do not edit a committed migration.
2. **The windows, as one pure function.** `domain/referrals/ReferralWindows` with
   `windowFor(Instant at, ReferralWindowTimes times, Predicate<LocalDate> isWorkingDay)` returning a `ReferralWindow`
   record `(LocalDate date, WindowSlot slot)`. In `Europe/London`: on a working day, inside `[morningStart, morningEnd)`
   is that day's `AM`; inside `[afternoonStart, afternoonEnd)` is that day's `PM`; before `morningStart` is that day's
   `AM`; between the two windows is that day's `PM`; after `afternoonEnd` is the next working day's `AM`. A non-working
   day goes to the next working day's `AM`. Also `closesAt(ReferralWindow)` (the window's end as an `Instant`), which D3
   uses. Unit-test it exhaustively: each boundary to the minute, a weekend, a bank holiday, a Friday afternoon, a
   holiday run (Christmas to the 2nd of January), and both DST changes (a window time that falls in a skipped or
   repeated hour resolves by `ZonedDateTime.of`'s rules, stated in the test).
3. **Assignment, one domain operation.** `ReferralService.assign(deedPollId, referralId, masterId, caller)`, used by
   the create path and by reassignment:
   - Any staff caller (D-8). Locks the referral row, then the Master's row (`SELECT ... FOR UPDATE`, in that order
     everywhere, to avoid deadlocks), so two assignments to one Master in one window serialise and the cap holds under
     concurrency.
   - The Master must exist and be `available`; otherwise a 400 on `masterId` ("Select a Master who is available").
   - The referral must be `Open` or `Claimed`; otherwise 409 ("only a referral waiting for a direction can be
     assigned"). Assigning to the Master it already has is a no-op 200 with no note and no audit.
   - Compute the window for **now**. Count referrals in state `Claimed` with that `assignedMasterId`, `windowDate` and
     `windowSlot`. At or above `masterWindowCap()`, refuse with **409**, the message naming the window in words
     (e.g. "This Master already has 10 referrals for the morning of 28 October 2026. Choose another Master.").
     The cap counts current assignments only: a referral reassigned away no longer counts for the first Master.
   - Set `state = Claimed`, `claimedBy` = the Master's name, `claimedById` = the Master's linked subject (null if not
     yet linked), `claimedAt` = `assignedAt` = now, `lastTouchedAt` = now, `assignedMasterId`, `windowDate`,
     `windowSlot`. Clear nothing else. A reassignment restarts the processing window and the reminder clock.
   - One case note via `ReferralNotes`: "Referral raised on …, reasons: …, assigned to {name}." or "… reassigned from
     {old} to {new}." Never an email address.
   - Audit `AuditAction.REFERRAL_ASSIGNED` (new; names are permanent) with old and new values of `state`,
     `claimedBy`, `assignedAt`. Never the subject as a value.
   - Recompute both read-models (`refreshCurrentReferralState`, `refreshAwaitingConfirmation`) in the same
     transaction.
4. **Create.** `CreateReferralRequest` gains an optional `masterId` (uuid). Present: the referral is created and
   assigned in one transaction (a refused assignment refuses the whole create, nothing written). Absent: created
   `Open`, waiting for assignment — that is what the admin deployed today sends, so it keeps working. Publish the
   field and the 409 cap refusal in the OpenAPI document.
5. **Reassignment HTTP.** `PUT /caseworker/cases/{id}/referrals/{referralId}/assignment`, body `{masterId}` (keyed,
   `masterId` required), `@AuditedAs(REFERRAL_ASSIGNED)`, answers the `ReferralResponse`.
6. **Responses.**
   - `ReferralResponse` gains `assignedMaster` (`{id, name}` or null — never the subject or email), `assignedAt`,
     `windowDate`, `windowSlot` and `directionDueBy` (`assignedAt` + `processingWindow`, computed at read time from the
     setting, null when not assigned).
   - `GET /caseworker/masters` (D1) gains, for each Master, `assignedInWindow` for the **current** window (the window
     `windowFor(now)` gives), and the envelope gains `window` (`{date, slot}`) and `cap`, so a caseworker's form can say
     "3 of 10 for this morning". One grouped query, not one per Master.
   - The judicial DTOs gain `assignedAt` and `directionDueBy` only.
7. **The Master side.** In `JudicialReferralService`:
   - `visibleTo`: an `Open` referral is **no longer visible to any judge**. `Claimed` and `Decided` stay visible to the
     judge whose subject they carry, as today. Every judicial read and write keeps going through `visible`, so an
     unassigned referral is the same 404 an unknown id is.
   - `GET /judicial/referrals` (the pool) now always answers an empty page. Keep the operation for one release, marked
     `deprecated: true` in the OpenAPI document with a description saying referrals are assigned by the office; a
     later contract step removes it. Do not 404 it: today's admin still calls it.
   - `POST /judicial/referrals/{id}/claim`: an `Open` referral is invisible (404) and a judge's own `Claimed` one is
     the existing 409, so no code change is needed beyond `visibleTo`. Mark it deprecated in the document too.
   - `MINE_ORDER` becomes: `Claimed` before `Decided`; **within `Claimed`, urgent first, then oldest `createdOn`
     first**; **within `Decided`, oldest `createdOn` first, urgency ignored** (the Master's page groups directed
     referrals by day only); then `id`. That is not expressible as a `Sort` on columns; write it as a JPQL `ORDER BY`
     with a `CASE` (or a native query), keeping the existing paging and total. Test the exact order with a fixture of
     every combination.
   - Linking (D1's `linkIfListed`) gains the referral side, in the same transaction: when an entry is linked, set
     `claimedById` to the subject on every referral with that `assignedMasterId` whose `claimedById` is null (state
     `Claimed` or `Decided`). And the reverse join for go-live (step 8): set `assignedMasterId` on every `Claimed`
     referral whose `claimedById` equals the subject and whose `assignedMasterId` is null.
   - `touch` no longer extends anything. Leave the operation working (admin calls it) but make it change only
     `lastTouchedAt`; document that it no longer affects the processing window.
   - Withdrawing a direction (`withdrawDirection`) returns the referral to `Claimed` by the same Master as today, and
     **restarts the processing window** (`assignedAt` = now, a fresh window for the cap is not taken: keep its
     `windowDate`/`windowSlot`). Say so in the OpenAPI description.
8. **The processing window replaces the lapse.** `reclaimStaleClaims` becomes `unassignOverdue` (rename it and every
   caller): candidates are `Claimed` referrals whose `coalesce("assignedAt", "claimedAt")` is older than
   `processingWindow()`. Each is released by one conditional `UPDATE` re-checking the state and the cutoff (keep the
   existing race-safety reasoning and its javadoc, updated): `state = Open`, clear `claimedBy`, `claimedById`,
   `claimedAt`, `assignedMasterId`, `assignedAt`, `windowDate`, `windowSlot`. One case note per release:
   "Referral raised on …, reasons: …, returned to the office for assignment: no direction from {name} within 7 days."
   (the window in words from the setting). Refresh the read-models as today. **Also run it on a timer**: a
   `@Scheduled` job (fixed delay, `deedpoll.referrals.unassign-interval`, default 15 minutes, the delay configurable
   and the job switchable off for tests), following `GazetteRecovery`'s pattern. It must run outside any caller
   transaction and never throw out of the scheduler (log a fixed sentence and the exception class only). Keep the
   existing request-time triggers too.
   **Go-live is the migration plus this rule, and needs no data rewrite:** a referral `Claimed` before V43 has a
   claimer subject and no `assignedMasterId`; it stays visible to its claimer, its window counts from `claimedAt`
   (D-10), and linking (step 7) attaches it to the Masters-list entry once that Master is listed and signs in. An
   `Open` referral simply is waiting for assignment. Prove both with a migration-shaped test.
9. **Notes and wording.** Every note this plan writes uses `ReferralReason.label()` folding as `ReferralNotes` already
   does. No note carries an email address or a subject.
10. **Tests first.** Full-context MockMvc against real Postgres for the HTTP layer, pure unit tests for
    `ReferralWindows`:
    - Create with `masterId`: assigned, note, audit, window fields; an unavailable or unknown Master 400 on `masterId`
      with nothing written; at the cap 409 with nothing written; without `masterId`: `Open`.
    - Raised at 10:31 counts towards the afternoon; at 14:31 towards the next working day's morning; on a Saturday
      towards Monday's morning; on a bank holiday towards the next working day.
    - **The cap under concurrency**: with the cap at 1, two assignments to one Master in one window released together
      give exactly one success and one 409 (copy `NotificationOutboxConcurrencyTest`'s way of running two transactions).
    - Reassignment: from `Open` and from `Claimed`; to the same Master is a no-op; from `Decided` 409; the cap is
      checked against the new Master; the old Master's count falls.
    - Visibility: the assigned, linked Master sees it in mine and by id; another judge gets 404; nobody sees an
      `Open` referral; the pool answers an empty page; an assigned referral whose Master is not yet linked is visible
      to nobody until linking, then visible to that Master.
    - Go-live: a `Claimed` referral with a subject and no `assignedMasterId` stays visible to its claimer, is unassigned
      7 days after `claimedAt`, and is attached by linking.
    - `unassignOverdue`: the boundary minute; a reassigned referral's clock restarts; a withdrawn direction's clock
      restarts; the timer and a request racing release once and write one note; the job never throws.
    - `MINE_ORDER`: the full combination fixture, and paging is stable across pages.
    - Responses carry the new fields and never a subject or an email (assert on the JSON).
    - OpenAPI: the new field, operation, 409, the deprecations.
11. **Out of scope:** emails (D3); admin; removing the pool and claim operations (a later contract step); Helm/Flux.
    No new dependency.
12. **Publishing:** local task commits only, no push and no PR.

## Validation Commands

Run the full build from the repository root **twice only**: before implementation (the baseline) and in the
final task, forced. **After each other task**, run only the task's focused tests (`./gradlew test --tests '…'` and,
where the task touched them, `./gradlew integration --tests '…'`), the compile of every source set
(`./gradlew compileJava compileTestJava compileIntegrationTestJava -I <no-checkstyle.gradle>`) and `checkstyle.sh`.
This keeps the night's quality gate (one forced full build at the end) at a fraction of the time. If a focused run
shows something outside the task failing, run the full build then rather than carry it forward. The full build:

```bash
export JAVA_TOOL_OPTIONS=-XX:UseSVE=0 TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build -I /Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/no-checkstyle.gradle --console=plain
/Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/checkstyle.sh .
```

This devcontainer's JVM needs `-XX:UseSVE=0`. Gradle's Checkstyle worker ignores it and crashes with SIGILL, so the
init script disables the Checkstyle tasks, and `checkstyle.sh` runs the same jar and config directly, failing on any
`[WARN]` or `[ERROR]`. Do not edit either helper script or commit them. Record the actual `test` and `integration`
counts from `build/test-results/`, and distinguish baseline failures from regressions.

**Baseline:** whatever D1 finished on, recorded at execution time (D1's final forced run is the expected baseline).

**Known intermittent test:** `CcdCreationAttemptRepositoryTest` "an attempt that ran out of tries on refusals may be
retried from scratch" fails intermittently in a full build. Re-run it alone before treating it as a regression, and
never "fix" it here.

Run every build and test command in the **foreground** and wait for it to finish. A backgrounded build ends the
session, and the next one builds over it, wiping the results.

## Implementation Steps

### Task 1: Migration and the windows
- [x] Record the execution baseline (commit, highest migration, test counts) in the execution notes.
- [x] Add the failing `ReferralWindows` unit tests and a mapping test for the new columns.
- [x] Implement V43, the entity mapping and `ReferralWindows`, run the focused tests, the compile and `checkstyle.sh`, and record the results.

### Task 2: Assignment and the cap
- [x] Add failing tests for `assign`: the rules, the note, the audit, the cap, the concurrency case, create with and without `masterId`, and reassignment over HTTP.
- [x] Implement, run the focused tests, the compile and `checkstyle.sh`, and record the results.

### Task 3: The Master side
- [x] Add failing tests for visibility, the empty pool, linking's referral side, `touch`, a withdrawn direction and `MINE_ORDER`.
- [x] Implement, run the focused tests, the compile and `checkstyle.sh`, and record the results.

### Task 4: The processing window
- [x] Add failing tests for `unassignOverdue`, the timer, the go-live cases and the race.
- [x] Implement, rename every caller, run the focused tests, the compile and `checkstyle.sh`, and record the results.

### Task 5: Documentation and final verification
- [x] Update `CLAUDE.md`: the judicial-surface paragraph (no pool; visibility), the claim-window paragraph (now the processing window and `unassignOverdue`), a new assignment paragraph (cap, windows, notes, go-live), the operation counts.
- [x] Run the final validation **forced**: `./gradlew cleanTest cleanIntegration build -I <no-checkstyle.gradle> --console=plain`, then `checkstyle.sh`. Record the counts against the baseline. Confirm `git status` is clean apart from the task commits.

## Execution notes

**Baseline:** commit `504ce4b` (D1 complete, plus its two review-fix commits), highest migration V42. Forced
`cleanTest cleanIntegration build`: SUCCESSFUL in 5m 50s, `test` 5,138 tests, 0 failures, 1 skipped; `integration`
27 tests, 0 failures.

**Task 1 (migration and the windows):** `V43__referral_assignment.sql` adds `"assignedMasterId"` (uuid, foreign key to
`deedpoll_master`), `"assignedAt"`, `"windowDate"`, `"windowSlot"` (`varchar(2)`, check `AM`/`PM`) and the index on
`("assignedMasterId", "windowDate", "windowSlot")`. **Deviation in the consistency check:** the plan's "all null or
all set, except a null Master beside a subject" would refuse two rows this plan itself writes: a pre-V43 claim that
linking attaches (a Master but no `assignedAt` or window, step 7) and such a claim after a withdrawn direction
restarts its window (`assignedAt` but no window). So V43 states the rules as four named checks instead: the date and
slot are set together; a window implies `assignedAt`; an `assignedAt` belongs to a Master or a claimer subject; and
an `Open` referral holds no Master, `assignedAt` or window. D1's settings record `domain/settings/ReferralWindows`
was renamed `ReferralWindowTimes`, the name the plan's signature uses, so `domain/referrals/ReferralWindows` (the
pure function: `windowFor`, `closesAt`, a next-working-day search bounded at 366 days) does not share its name. New
`ReferralWindow(date, slot)` record and `entity/enums/WindowSlot` (plain `STRING`, not a Postgres enum). Tests:
`ReferralWindowsTest` (35: each boundary to the minute and below, Friday afternoon, weekend, the August bank
holiday, Christmas to 4 January 2027, BST, `closesAt` in both offsets, both 2026 clock changes with
`ZonedDateTime.of`'s gap/overlap rules stated, the bounded search) and `ReferralAssignmentColumnsTest` (9, Postgres:
round trip with the slot stored as its name, a legacy and a linked-legacy claim admitted, each check and the foreign
key refusing by name). Focused run (those two plus `ProcessSettingsServiceTest`, `ReferralRepositoryTest`,
`EntityMappingTest`): 146 tests, 0 failures. Compile of every source set OK; `checkstyle.sh` OK.

**Task 2 (assignment and the cap):** `ReferralService.assign` locks the referral, then the Master
(`MasterRepository.findAssigneeForUpdate`, `FOR UPDATE`), checks in the plan's order (Master exists and is available,
400 on `masterId` "Select a Master who is available"; `Open`/`Claimed` else 409; the same Master a no-op with no note
or audit; the cap via `countByStateAndAssignedMasterIdAndWindowDateAndWindowSlot`, 409 with a `masterId` field error
naming the window), then writes the claim columns, one note (`ReferralNotes.assigned`/`reassigned`), one new
`REFERRAL_ASSIGNED` event (state, claimedBy, assignedAt) and both read-models. The cap message counts what the Master
holds (so a lowered cap reads truthfully) and is singular for one. Create with `masterId` assigns in the same
transaction and writes the create's note and event and then the assignment's (two of each); without it the referral
is `Open`. `PUT /caseworker/cases/{id}/referrals/{referralId}/assignment` (`AssignReferralRequest`, keyed, `masterId`
required). `ReferralResponse` gains `assignedMaster` (`AssignedMasterResponse` id and name), `assignedAt`,
`windowDate`, `windowSlot` and `directionDueBy` (only while `Claimed`, from `assignedAt`, else `claimedAt` for a
pre-V43 claim, plus the processing window read per response). **Deviation:** `GET /caseworker/masters` now answers an
envelope `MasterListResponse {masters, window, cap}` rather than a bare array, each entry
`MasterListEntryResponse` with `assignedInWindow` from one grouped count (`countByMasterInWindow`). D1 is unmerged, so
no deployed client reads the array; create/update keep answering `MasterResponse`. `OpenAPIWriteBodies` publishes
`masterId` on the create, `AssignReferralRequest`, and the 400 and 409 descriptions on both operations. The judicial
DTOs' `assignedAt`/`directionDueBy` are left to Task 3 with the rest of the Master side. To keep V43's
`open_unassigned_check` true before Task 4 renames the sweep, `releaseStaleClaim` already clears the four assignment
columns. `ReferralSecurityTest` admits the two cap counts as unscoped finders on the sweep's terms (they return
numbers, never a referral). `WriteEndpointAuditCoverageTest` now counts 35 writes, 31 audited. Tests:
`ReferralAssignmentTest` (26, unit: order of locks and checks, windows at 10:31, 14:31, a Saturday and a bank holiday,
the cap and its message, the no-op, a pre-V43 claim, create with and without), `ReferralAssignmentConcurrencyTest`
(Postgres, cap 1: one success, the waiter is a 409 and wrote nothing), `CaseworkerReferralAssignmentTest` (10, HTTP:
create assigned with no subject or email in the JSON, 400s and the cap 409 writing nothing, reassignment and the old
Master's count falling, same-Master no-op, Decided 409, wrong case 404, the Masters list window, the OpenAPI document),
plus cases in `ReferralSchemasTest`, `ReferralNotesTest` and `CaseworkerMastersControllerTest`. A wide focused run
(referral, judicial, confirmation, master, OpenAPI, security, audit and architecture tests: 1,334) found only three
`ReferralSecurityTest` reflection rules, since fixed; the final focused run: 540 tests, 0 failures. Compile of every
source set OK; `checkstyle.sh` OK.

**Task 3 (the Master side):** `JudicialReferralService.visibleTo` answers false for `Open` (an assigned, unlinked
referral has no `claimedById` and is visible to nobody until linking). `pool` still gates and sweeps, then answers
`ReferralPool.of(List.of(), paging, 0)` without a query; `findByState` and `POOL_ORDER` are gone. The pool and claim
operations are `@Operation(deprecated = true)` with descriptions saying referrals are assigned by the office.
**`MINE_ORDER` is no longer a `Sort`:** `ReferralRepository.findOwnedBy` carries the order as a JPQL `ORDER BY` with
two `CASE` keys (undirected first; urgent first among the undirected only; then `createdOn`; then `id`), takes
`Urgency.Urgent` as a parameter, and has an explicit `countQuery` repeating the predicate; `mine` passes an unsorted
page. Linking: `MasterService.linkIfListed`, only when its conditional link wrote, calls the new
`ReferralRepository.attachToLinkedMaster` (owner subject on `Claimed`/`Decided` rows of that entry with no owner) and
`attachLegacyClaims` (D-10: `assignedMasterId` on this subject's pre-V43 `Claimed` rows), in the same transaction.
`ReferralSecurityTest` replaces the pool's admitted finder with these two (shape `UUID, String -> int`, and an ArchUnit
rule that only `MasterService` calls them). `touch` is unchanged in code and documented as extending nothing (Task 4
moves the sweep onto `assignedAt`). `withdrawDirection` now sets `assignedAt` = now and keeps the window. The
`directionDueBy` rule moved to `domain/referrals/DirectionDue`, shared by `ReferralResponse` and the judicial DTOs;
`JudicialReferralSummary` and `JudicialReferralDetail` gain `assignedAt` and `directionDueBy`, the processing window
read per response from `ProcessSettingsService` in `JudicialReferralController` (not a method on
`JudicialReferralService`, whose every public method `JudicialSecurityTest` treats as a gated operation).
**Behaviour to know:** the deprecated claim route's sweep runs in the claim's transaction, so its 404 on a released
referral rolls the release back; the next list load releases it (two tests now say so). Fixtures that relied on an
`Open` referral being visible to every judge now hold the referral for the judge (the claim transition, or a direct
`UPDATE` where the audit trail cannot record the subject). `ReferralLifecycleTimelineTest` is now the D2 journey:
raised with `masterId`, linked by the Master's first judicial request (userinfo `email`), directed, actioned.
**Two leftovers from Tasks 1 and 2 fixed here:** `SdkMigrationOrderTest` expected V1 to V42, and
`OpenAPIPublisherTest` did not list the assignment path (now 35 caseworker operations across 22 paths;
`CLAUDE.md`'s counts are Task 5's). Tests: `JudicialReferralServiceTest` (visibility, the empty pool, unsorted mine),
`ReferralRepositoryTest` (every-combination order fixture, stable paging across four pages, both linking
statements), `MasterServiceTest`, `ReferralServiceTest` (touch extends nothing; withdrawal restarts the window),
`JudicialReferralControllerTest` (empty pool, order, `directionDueBy`, 404s for `Open`, the OpenAPI deprecations and
fields), and the judicial download/preview tests. Runs: the whole `test` task once (5,227 tests, 3 failures, all in
`DocumentPreviewServiceTest`'s `Open`-referral fixtures, since fixed and re-run green), the affected classes again
(0 failures), `integration` 23 tests 0 failures (1 skipped), every source set compiles, `checkstyle.sh` OK.

**Task 4 (the processing window):** `reclaimStaleClaims` is `ReferralService.unassignOverdue`, and with it
`ReferralRepository.findOverdueAssignments`/`releaseOverdueAssignment` and the projection `OverdueAssignment` (was
`StaleClaim`; it now carries `createdOn` and `reasons` for the note). Every caller renamed (the three judicial
request-time triggers and the deprecated claim); V16's comment naming the old method is a committed migration and was
left alone. Both statements match `coalesce("assignedAt", "claimedAt") < cutoff`, so an assignment, a reassignment and
a withdrawn direction restart the window, a pre-V43 claim counts from `claimedAt` (D-10), and `lastTouchedAt` no longer
plays any part. The release clears the Master, `assignedAt` and the window too, keeping V43's `open_unassigned_check`.
The note is `ReferralNotes.returnedForAssignment` ("Referral raised on …, reasons: …, returned to the office for
assignment: no direction from {name} within 7 days."), the window in words from the setting (days, else hours, else
minutes), the name bounded as before; `claimLapsed` is gone. **The timer** is `domain/referrals/ReferralUnassignment`,
a `@Component` with `@EnableScheduling`, `@Scheduled(fixedDelayString = initialDelayString =
"${deedpoll.referrals.unassign-interval:PT15M}")`, and `@ConditionalOnProperty("deedpoll.referrals.unassign-enabled",
matchIfMissing = true)`; `application.yaml` binds `REFERRAL_UNASSIGN_ENABLED` (true) and `REFERRAL_UNASSIGN_INTERVAL`
(`PT15M`, an ISO-8601 duration like the other `*-interval` strings would need), and `AbstractPostgresContainerTest` sets
it false. It refuses an active transaction with `IllegalStateException` (as `GazetteRecovery` does), and otherwise
catches every `RuntimeException` and logs one fixed WARN naming the exception class only. The request-time triggers
stay. Tests: `ReferralUnassignmentTest` (6, unit: runs, never throws and never logs the message, refuses a transaction,
the schedule's expression, the property switch through `ApplicationContextRunner`, the yaml defaults),
`ReferralUnassignmentConcurrencyTest` (2, Postgres: a request's sweep holding its release until PostgreSQL shows the
real timer blocked on it, then one release, one note, case column `Open`; and the timer alone), new
`ReferralRepositoryTest` cases (the boundary minute both statements, `assignedAt` before `claimedAt` and touch
extending nothing, the assignment columns cleared and the cap count falling, a linked pre-V43 claim released from
`claimedAt`), `ReferralServiceTest`'s renamed `UnassigningOverdue` (plus window from `assignedAt`, a legacy claim, a
touch extending nothing, the note's exact text), `ReferralNotesTest` (the note, minor folding, no reasons, no name,
bounded length, `inWords`), and `JudicialClaimWindowTest`'s go-live journey (a claim without a Master stays visible to
its claimer by id and in mine, then leaves both once its window from `claimedAt` ends). Fixtures that made a claim stale
through `lastTouchedAt` now backdate `claimedAt` (and `assignedAt` where set). Runs: the focused referral, judicial,
security, repository, architecture, OpenAPI, master, assignment and timeline classes, 377 tests, 0 failures; every
source set compiles; `checkstyle.sh` OK.

**Task 5 (documentation and final verification):** `CLAUDE.md`: the caseworker count is 35 operations across 22
paths (the judicial surface stays 8); the judicial-surface paragraph says there is no pool, `Open` is visible to no
judge, an unlinked assignment to nobody, and the pool and claim are deprecated for one release; the document-access
sentence and the withdrawn-direction paragraph follow (withdrawal restarts the processing window and
`unassignOverdue` returns it to the office); the process-settings paragraph names `unassignOverdue` and says D2 reads
the cap and windows; a new paragraph covers assignment (lock order, check order, cap, windows, notes, audit,
responses, `unassignOverdue`, the timer and go-live). Final forced `cleanTest cleanIntegration build`: SUCCESSFUL in
4m 43s, `test` 5,246 tests, 0 failures, 1 skipped (baseline 5,138, +108); `integration` 27 tests, 0 failures
(baseline 27). `checkstyle.sh` OK. The known intermittent `CcdCreationAttemptRepositoryTest` case passed.
`git status` clean apart from this task's commit.
