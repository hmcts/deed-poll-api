# Plan: application deletion grace period and restore

## Overview

Status: planned, 2026-11-19, by Aspen with Steve. An addendum to
`docs/plans/completed/20261118-dm-application-deletion.md`, on the same unmerged branch `feat/dm-application-deletion`
(PR #175).

Steve's decisions (2026-11-19):

1. A confirmed deletion stays a **soft delete for a grace period**: the case is hidden at once (the V48/V49 fence, as
   built), and nothing permanent happens until the period ends. Then the worker and Retain and Dispose do the hard
   delete exactly as built.
2. The grace period defaults to **14 days** and is a **Delivery Manager process setting**, edited on the existing
   process settings screen (admin's screen is the admin plan's job; this plan adds the api setting).
3. During the grace period a **Delivery Manager alone** (`DELETE_APPLICATION`) can **restore** the application.

Because a deletion is now recoverable, the stale-confirmation check goes back to the simple time comparison, and V50's
commit-time marker trigger is removed. Birch's review of 7bd8967 reproduced a lock-order deadlock between that trigger
and Gazette settlement versus the referral queue send / `unassignOverdue`; with restore available, the few-millisecond
window the time comparison leaves is acceptable (Steve, 2026-11-19).

**No migration on this branch has reached any environment.** Both PR-175 Jenkins builds (110bf84, 7bd8967) failed in
`./gradlew check` at `:test` (OutOfMemoryError, fixed by 4f5d3b9) before helm, so V48, V49 and V50 may still be edited.
Do not add V51: fold every schema change into V50.

## Context

Read `CLAUDE.md` in full, `docs/APPLICATION-DELETION.md`, the completed plan above, and:

- `src/main/resources/db/migration/V48__…`, `V49__…`, `V50__…`, `V40__…`/`V42__…` (process settings).
- `domain/deletion/` (`ApplicationDeletionService`, `DeletionCleanup`, `DeletionProgress`, `DeletionStatus`,
  `DeletionRequest`), `repository/{DeletionRequestRepository,DeletionWorkRepository,DeletionAdmissionRepository,
  CaseLastChanged,RemovedDocumentRepository}.java`.
- `domain/settings/{ProcessSettings,ProcessSettingsService}.java` and the process settings controller, DTOs, OpenAPI
  bodies and tests: how V42's six keys were added (optional on PUT, one history row per changed field).
- `api/caseworker/controller/CaseworkerApplicationDeletionController.java` and its tests; `config/OpenAPI*` and the
  path-count constants (`DELETION_PATHS`).
- `docs/deletion/confirm-cdam-purge.sql`, `docs/deletion/requeue.sql` and the tests that run them verbatim.
- Git history: `git show ac90738` (the time-based `CaseLastChanged` this plan returns to) and `git show 7bd8967`.

## Development Approach

- **Grace period frozen at admission.** Admission stores `restorable_until = requested_at + grace` (V50 column) and
  sets `next_attempt_at` to it. A later change to the setting affects only new requests, so the date a Delivery
  Manager was shown never moves. The worker claims nothing before `restorable_until`, enforced in `claimNext`'s SQL
  as well as by `next_attempt_at`, so an operator requeue (`requeue.sql`) cannot bypass the grace period.
- **Restore deletes the request.** `POST /caseworker/application-deletions/{requestId}/restore`, `DELETE_APPLICATION`
  (403 before the id is read, like the status GET). Allowed only while the request is `Pending`, unclaimed
  (`owner IS NULL`), never attempted, and `now < restorable_until`; otherwise 409 with a typed refusal
  (`RESTORE_WINDOW_CLOSED`, `CLEANUP_STARTED`). Unknown or already restored: 404. In one transaction: lock the
  **case row first, then the request** (admission's order, so the two cannot deadlock), re-check the conditions under
  the locks, `DELETE` the request (its steps cascade), and record `CASE_DELETION_RESTORED` under the restoring
  Delivery Manager with the request id and reason in the allowlisted detail (never the note). V48's trigger guards
  `UPDATE` only; confirm `DELETE` is allowed and pin it in a migration test. The fence lifts because it is `EXISTS`
  on the request. `@AuditedAs(CASE_DELETION_RESTORED)`. Response 200 `{ caseId, status: "Restored" }` with
  `no-store`. Nothing outside this database is touched before the grace period ends, so nothing remote needs undoing.
  Assert that in a test (no CDAM/CCD/filesystem call between admission and restore).
- **Published dates.** Admission and status responses add `restorableUntil`. While `Pending` before that time, the
  status description says the application can be restored. No new status member.
- **The setting.** `deletionGraceDays`, integer 1–90, NOT NULL DEFAULT 14, on the process settings singleton (V50).
  `GET /caseworker/process-settings` returns it; `PUT` accepts it as an **optional** key (absent leaves it alone,
  as V42's keys), `MANAGE_PROCESS_CONFIG`, one `deedpoll_process_settings_change` row when it changes.
  `ProcessSettingsService.deletionGracePeriod()` returns a `Duration`, read on every admission, no cache, and a
  missing row fails the caller, as the other getters do.
- **Stale check back to time.** Restore `CaseLastChanged` to its ac90738 behaviour: the later of `modifiedOn` and the
  newest non-read audit event's `occurred_at`. Remove V50's `lastChangedOn` column, `deedpoll_mark_case_changed` and
  its trigger, the backfill, `CaseChangeMarkerTest`, and the docs/Javadoc that describe the marker. Keep the
  published `lastChangedOn`, `expectedLastChangedOn` and the comparison under the case lock. Document the residual
  window plainly and that restore is the remedy. Also state which case-row writes are deliberately not changes (the
  CCD binding, the read-model refreshes `currentReferralState`/`awaitingConfirmationSince`).
- **Keep** V50's removed-document tombstones and `CDAM_REMOVED_DOCUMENT` handling as built.
- **Fix `confirm-cdam-purge.sql` (Birch):** it must mark only the CDAM ids the operator lists (an explicit id list
  variable), check before writing that the listed ids equal the request's Pending `CDAM_REMOVED_DOCUMENT` locators
  and refuse (write nothing) if not, and record an audit event (`DELETION_CDAM_PURGE_CONFIRMED`, system actor or
  the operator named in a variable, field names only) in the same statement. Keep running it verbatim in its test.
- No new AM roles (only `DELETE_APPLICATION`, Delivery Manager). No admission on/off switch (Steve, 2026-11-18).

## Validation Commands

Run from this repository root, **in the foreground** (never background a Gradle command). They must be green after
every task. Gradle Checkstyle crashes with SIGILL in this devcontainer, so it is disabled locally by the init script
and the same pinned Checkstyle runs directly. Baseline at `4f5d3b9`: green (Aspen, 2026-11-19).

```bash
export TESTCONTAINERS_RYUK_DISABLED=true JAVA_TOOL_OPTIONS=-XX:UseSVE=0
./gradlew build -I /Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/no-checkstyle.gradle --console=plain --no-watch-fs
/Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/checkstyle.sh .
git diff --check
```

Each task also runs its focused suites. The final gate forces the tests (`./gradlew test --rerun integration --rerun`)
and records counts from the XML in `build/test-results/{test,integration}`.

## Implementation Steps

### Task 1: Remove the commit-time marker and fix the purge script

- [x] Restore `CaseLastChanged` to the time comparison (ac90738), remove V50 part 1 and `CaseChangeMarkerTest`,
  and update every test that relied on the marker. Keep the tests proving a child write after review is stale and a
  read is not.
- [x] Rewrite `confirm-cdam-purge.sql` as above, with tests: listed ids match (confirms and audits), a missing or
  extra id (writes nothing), a second run (writes nothing), a non-NeedsAttention request (writes nothing).
- [x] Update `docs/APPLICATION-DELETION.md` and `CLAUDE.md` for both.

### Task 2: Add the grace period setting

- [x] V50: `deletionGraceDays` on the process settings table, with its CHECK and default; entity, service getter,
  GET/PUT DTOs, OpenAPI body, history row, tests (range edges, absent key, history, 403 without
  `MANAGE_PROCESS_CONFIG`).

### Task 3: Freeze the grace period at admission and make the worker wait

- [ ] V50: `restorable_until timestamp(3) NOT NULL` on `deedpoll_deletion_request`, added to V48's immutable-fields
  trigger (V48 may be edited; see Overview). Admission sets it and `next_attempt_at`.
- [ ] `claimNext` requires `restorable_until <= now`. Tests: nothing is claimed one millisecond before; a requeue
  before the time claims nothing; a setting change after admission leaves the request's date alone.
- [ ] Publish `restorableUntil` on the admission and status responses; OpenAPI and contract tests.

### Task 4: Restore

- [ ] The endpoint, service and repository as above, with `CASE_DELETION_RESTORED`, `@AuditedAs`, OpenAPI,
  path counts and `WriteEndpointAuditCoverageTest`.
- [ ] Tests over HTTP and Postgres: each non-DM role 403 before reading the id; unknown/after-restore 404; restore
  inside the window lifts the fence (case visible in the list, detail and JPA reads again, child writes allowed);
  after `restorable_until` 409; once claimed 409; concurrent restore vs worker claim (exactly one wins, with no
  deadlock); concurrent restore vs a second admission; no external call between admission and restore; deleting the
  case again after a restore admits a new request.

### Task 5: Document and hand off

- [ ] `docs/APPLICATION-DELETION.md`, `CLAUDE.md`, README: the grace period, the setting, restore, the time-based
  stale check and its window. Note for the admin plan: process settings screen field, `restorableUntil` on the
  status page, a Restore button for Delivery Managers while restorable.
- [ ] Full forced validation with counts from the XML; record them here. Move this plan to `completed/`.

## Success criteria

A Delivery Manager's deletion hides the application at once and changes nothing permanent for the configured grace
period (default 14 days), during which a Delivery Manager can restore it whole. After the period the hard delete runs
as before. The setting is a Delivery Manager process setting. No commit-time trigger locks case rows. All tests green.
