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
  recomputed from truth" (`refreshCurrentReferralState`), the audit rules, "The referral claim window is a stored
  process setting".
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

Run from the repository root before implementation and after each task:

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
- [ ] Record the execution baseline (commit, highest migration, test counts) in the execution notes.
- [ ] Add the failing `ReferralWindows` unit tests and a mapping test for the new columns.
- [ ] Implement V43, the entity mapping and `ReferralWindows`, run the focused tests and all Validation Commands, and record the results.

### Task 2: Assignment and the cap
- [ ] Add failing tests for `assign`: the rules, the note, the audit, the cap, the concurrency case, create with and without `masterId`, and reassignment over HTTP.
- [ ] Implement, run the focused tests and all Validation Commands, and record the results.

### Task 3: The Master side
- [ ] Add failing tests for visibility, the empty pool, linking's referral side, `touch`, a withdrawn direction and `MINE_ORDER`.
- [ ] Implement, run the focused tests and all Validation Commands, and record the results.

### Task 4: The processing window
- [ ] Add failing tests for `unassignOverdue`, the timer, the go-live cases and the race.
- [ ] Implement, rename every caller, run the focused tests and all Validation Commands, and record the results.

### Task 5: Documentation and final verification
- [ ] Update `CLAUDE.md`: the judicial-surface paragraph (no pool; visibility), the claim-window paragraph (now the processing window and `unassignOverdue`), a new assignment paragraph (cap, windows, notes, go-live), the operation counts.
- [ ] Run the final validation **forced**: `./gradlew cleanTest cleanIntegration build -I <no-checkstyle.gradle> --console=plain`, then `checkstyle.sh`. Record the counts against the baseline. Confirm `git status` is clean apart from the task commits.

## Execution notes
