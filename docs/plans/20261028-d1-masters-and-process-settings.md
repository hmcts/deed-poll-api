# Plan D1: the Masters list, the referral process settings and bank holidays

## Overview

Master Sullivan's feedback, item D (Steve, 2026-10-27/28), replaces the shared referral pool with **assignment by
staff**. This plan is the foundation the other two api plans build on. It adds no referral behaviour of its own:

1. **A Masters list kept by the Delivery Manager** (decision D-2): a Master is a name, an email and an available flag.
   It is linked to the Master's IDAM account the first time they use the service, by matching the email IDAM gives for
   them. A Master who has never signed in can already be on the list, and assigned to.
2. **Four more process settings** beside the existing claim window, all Delivery Manager settings (decision D-9):
   - the **processing window**, which the existing claim window becomes: how long a Master has to give a direction
     before the referral is unassigned. Default **7 days**. Range 1 day to 30 days, in whole minutes on the wire;
   - the **reminder interval**: 48 hours by default, 1 to 168 hours;
   - the **per-Master cap** per referral window: 10 by default, 1 to 100;
   - the **two referral windows**, morning 07:00–10:30 and afternoon 14:00–14:30 by default, as local times in
     `Europe/London`. Each window's start must be before its end, and the morning window must end before the afternoon
     one starts.
3. **England and Wales bank holidays** (decision D-7): api ships the GOV.UK dates for 2026, 2027 and 2028 as seed
   data, and a Delivery Manager can add or remove a date. Nothing calls the internet.

D2 (assignment, the windows, the cap and the 7-day unassignment) and D3 (the reminder and digest emails) read these.
Admin's settings page for them is the admin D plan's.

## Context

Read these before changing anything. They are the patterns to copy, not to reinvent:

- `CLAUDE.md`, especially "The referral claim window is a stored process setting", the audit rules ("A new write path
  must call `AuditTrail`"), "Ownership is the token's IDAM subject" and the staff-permissions section.
- `docs/plans/completed/20261026-dm-process-settings.md`: how the claim window became a setting. V40, the singleton
  row with `revision`, `deedpoll_process_settings_change`, `ProcessSettingsService`, `ProcessSettingsRepository`,
  `CaseworkerProcessSettingsController`, `UpdateProcessSettingsRequest` (a `KeyedRequestBody`), the published bounds,
  and the atomicity and concurrency tests. **Every new setting here follows that plan's rules**, including stale
  revision is 409, stale beats no-op, an unchanged value is a no-op with no history and no audit, and process settings
  keep their values in `deedpoll_process_settings_change` and never in the audit trail.
- `domain/auth/CurrentUser`, `auth/IdamAuthenticationConverter`, `auth/IdamUserInfoClient`, `auth/IdamClaims`: how a
  caller is built. The caller carries no email today.
- `health/DeedPollSchema.DEED_POLL_TABLES` and `DeedPollSchemaHealthTest`: every new table is added there.

Decisions (Steve, 2026-10-27/28), recorded so a later reader does not reopen them:

| # | Decision |
|---|---|
| D-1 | Referrals are assigned to a Master by the caseworker raising them. The pool and claiming go (D2). |
| D-2 | The Masters list is kept by the Delivery Manager on the settings page: name, email, available. Linked to IDAM on first sign-in by email. |
| D-3 | After the processing window (7 days) without a direction, a referral is unassigned and waits for a caseworker to assign it again (D2). |
| D-4 | Reminders: one email per Master, every reminder interval (48 h) from a referral's assignment, listing all their undirected referrals (D3). |
| D-5 | The cap is per Master per window, 10. |
| D-6 | A referral raised outside the windows is assigned at once and counts towards the next window, and its digest. |
| D-7 | Windows and digests are weekdays only, skipping England and Wales bank holidays: a built-in list a Delivery Manager can edit. |
| D-8 | Any caseworker may assign and reassign. |
| D-9 | The processing window, reminder interval, cap and both window times are all Delivery Manager settings. |
| D-10 | At go-live a claimed referral becomes assigned to its claimer, its 7 days counted from the claim; an open one waits for assignment (D2). |

## Development Approach

1. **Caller email.** `IdamUserInfoClient` already fetches userinfo for every authenticated request. Read its `email`
   claim (trimmed, lower-cased, ignored if blank or without an `@`) onto `CurrentUser` as a new nullable component
   `email`. Keep the record's existing constructors and factories compiling: add the component last and give every
   existing factory an overload or default that passes `null`, then move callers that build from IDAM to pass it.
   **Never log it, never put it in an audit `detail`, a note or an error message** (CLAUDE.md). It is used only to
   link a Masters-list entry (step 4). The CCD caller converter stays as it is.
2. **Migration `V42__masters_and_referral_process_settings.sql`** (or the next free number):
   - `deedpoll_master`: `id uuid primary key`, `name varchar(200) not null`, `email varchar(320) not null`,
     `available boolean not null default true`, `idam_subject varchar(255)` (nullable, unique where not null),
     `linked_at timestamp`, `created_at`, `created_by_id`, `created_by_name`, `updated_at`, `updated_by_id`,
     `updated_by_name`, `revision integer not null default 0`. A unique index on `lower(email)`. Snake_case, as V31/V33/V40.
   - Extend `deedpoll_process_settings` with `reminder_interval_hours integer not null default 48 check (1..168)`,
     `master_window_cap integer not null default 10 check (1..100)`, `morning_window_start time not null default '07:00'`,
     `morning_window_end time not null default '10:30'`, `afternoon_window_start time not null default '14:00'`,
     `afternoon_window_end time not null default '14:30'`, and a table-level check that each start is before its end
     and the morning end is before the afternoon start.
   - **Widen and re-seed the existing window**: change `referral_claim_expiry_minutes`' check to between 1440 and 43200
     (1 to 30 days), and set the seeded row to 10080 (7 days) — **only if it is still at revision 0** (never overwrite a
     value a Delivery Manager chose; if it has been changed and is below 1440, raise it to 1440 and write a
     `deedpoll_process_settings_change` row attributed to the service account so the history says why). Write that
     rule as SQL in the migration and prove both branches in a migration test (copy
     `ReferralChangesToStandardPublicationMigrationTest`'s pattern: the committed file run against a pre-V42 copy).
   - `deedpoll_bank_holiday`: `holiday_date date primary key`, `name varchar(200) not null`, `created_at`,
     `created_by_id`, `created_by_name`. Seed the GOV.UK **England and Wales** bank holidays for 2026, 2027 and 2028,
     attributed to the service account. Take the dates from the GOV.UK published list
     (https://www.gov.uk/bank-holidays): write them into the migration by hand, as literals, with a comment naming
     the source and the date you copied them. Do **not** fetch the URL at build or run time.
   - Add the new tables to `DeedPollSchema.DEED_POLL_TABLES` and to any migration test that lists tables.
3. **Process settings, extended.** `ProcessSettings` gains `reminderInterval` (`Duration`), `masterWindowCap` (`int`),
   and the four `LocalTime`s. `ProcessSettingsResponse` gains `reminderIntervalHours`, `masterWindowCap`,
   `morningWindowStart`, `morningWindowEnd`, `afternoonWindowStart`, `afternoonWindowEnd` (strings `HH:mm`). The existing
   `referralClaimExpiryMinutes` keeps its wire name: renaming it would break admin. Its description now says it is the
   time a Master has to give a direction before the referral is unassigned. `UpdateProcessSettingsRequest`'s closed
   key set grows by the six new keys. **They are optional on the wire for one release**, so today's admin (which
   sends only `referralClaimExpiryMinutes` and `revision`) keeps working. An absent new key leaves its column
   unchanged. `referralClaimExpiryMinutes` and `revision` stay required. Bounds: minutes 1440–43200, hours 1–168, cap
   1–100, times `HH:mm` on the quarter hour (00, 15, 30, 45), with the ordering rule above. A refusal names the field
   and the bounds. Publish all bounds in the OpenAPI document as the existing field's are. Each changed field writes
   its own `deedpoll_process_settings_change` row; one audit event per PUT with the field names.
   Add service reads for D2 and D3 with **no capability check**: `processingWindow()`, `reminderInterval()`,
   `masterWindowCap()`, `referralWindows()` (a small record of the four times). Same rules as `referralClaimExpiry()`:
   no cache, a missing row throws. Rename `referralClaimExpiry()` to `processingWindow()` everywhere it is called.
4. **The Masters list.** `domain/masters/` with `Master` (record), `MasterRepository`, `MasterService`:
   - `list(CurrentUser)`: any staff caller (caseworker, Team leader, Delivery Manager) — caseworkers need it to choose
     a Master. Ordered by name, then id. Each row: `id`, `name`, `email`, `available`, `linked` (boolean, whether an
     IDAM account is linked), `revision`. **Never the IDAM subject** (CLAUDE.md: ownership ids are never published).
   - `create`, `update` (name, email, available, with `revision`; stale is 409), both `MANAGE_PROCESS_CONFIG`. Email is
     trimmed, lower-cased, must contain one `@` and no whitespace, at most 320; name trimmed, 1–200. A duplicate email
     is 409 naming the email field (never echo the address in the message — say "another Master already has this
     email address"). Changing the email of a linked Master **unlinks** it (clears `idam_subject` and `linked_at`), so
     the new address must sign in to link again; say so in the OpenAPI description. There is no delete: a Master who
     leaves is marked unavailable, because referrals and history refer to them.
   - Audit: `AuditAction.MASTER_ADDED`, `MASTER_CHANGED` on a new `AuditEntities.MASTER`, field names only, no values
     (no case owns a Master, as with process settings). `AuditAction` names are permanent; choose them once.
   - **Linking** (`MasterService.linkIfListed(CurrentUser)`): called once per judicial request, from the one place all
     judicial reads and writes pass through (find it: `JudicialReferralService.visible`, or a filter on the judicial
     chain if that is cleaner). If the caller holds `Role.JUDICIAL`, has an email and a subject, and an **unlinked**
     Masters-list entry has that email, set its `idam_subject` and `linked_at` with one conditional `UPDATE ... WHERE
     idam_subject IS NULL`, so two pods racing link once. If an entry is already linked to a **different** subject,
     do nothing and log a fixed sentence with no email or subject (a WARN: "a judicial caller's email matches a Master
     already linked to another account"). Linking never creates an entry: the list is the Delivery Manager's.
     Write `AuditAction.MASTER_LINKED` with the system actor. D2 adds the referral side of linking.
5. **Bank holidays.** `domain/settings/BankHolidays` with `isWorkingDay(LocalDate)` (Monday–Friday and not a listed
   holiday) and `list()`, and `add(date, name)` / `remove(date)` requiring `MANAGE_PROCESS_CONFIG`, each audited
   (`BANK_HOLIDAY_ADDED`, `BANK_HOLIDAY_REMOVED`, names only). Adding an existing date is 409; removing an absent one
   is 404. No cache: D2 and D3 ask per decision.
6. **HTTP.**
   - `GET /caseworker/masters` (any staff), `POST /caseworker/masters`, `PATCH /caseworker/masters/{id}`
     (`MANAGE_PROCESS_CONFIG`; the PATCH is a keyed body: `name`, `email`, `available`, `revision`, at least one of the
     first three). Responses as step 4.
   - `GET /caseworker/process-settings/bank-holidays` (any staff, ordered by date), `POST` (`{date, name}`),
     `DELETE /caseworker/process-settings/bank-holidays/{date}` (`MANAGE_PROCESS_CONFIG`).
   - Every write handler carries `@AuditedAs(...)` (`WriteEndpointAuditCoverageTest`). Update `CLAUDE.md`'s layout line
     for `api/caseworker` (it counts operations) and the staff-permissions text.
   - The 403s are published by `OpenAPIForbiddenResponses` for `StaffArea` paths; check rather than adding one.
7. **Tests first**, full-context MockMvc against real Postgres for the HTTP layer (as the process-settings plan did),
   unit tests for the rules:
   - Every role against every new operation: who gets 200, who gets 403 before any read.
   - Settings: each new bound accepted at both ends and refused one past each; the ordering rule (morning end equal to
     afternoon start is refused); a PUT with only the two old keys still works and changes nothing else; a stale
     revision beats a no-op; history rows per changed field; one audit event with names only.
   - Migration: fresh database seeds 10080 minutes, 48 h, 10, the four times and the 2026–2028 holidays; the
     pre-V42 copy at revision 0 becomes 10080; at revision 3 with 120 becomes 1440 with one history row; at revision 3
     with 5000 stays 5000.
   - Masters: create, duplicate email (any case) 409 without echoing the address, update with a stale revision 409,
     email change unlinks, unavailable stays listed, the subject never appears in any response (assert on the JSON).
   - Linking: a judge whose userinfo email matches an unlinked entry links it on their first judicial request; a
     second request changes nothing; two concurrent first requests link once; a matching entry already linked to
     another subject is left alone and logs the fixed WARN with no email in the log line (capture the log); a
     caseworker with a matching email links nothing.
   - Bank holidays: the working-day rule for a weekday, a Saturday, a seeded holiday, an added one and a removed one.
   - The OpenAPI document carries every new operation, field and bound.
8. **Out of scope:** any referral behaviour (D2), any email (D3), admin, Helm/Flux. No new dependency.
9. **Publishing:** local task commits only, no push and no PR. The branch sits on top of unmerged api #155 and the
   contract-step branch; Juniper will rebase and raise PRs after review.

## Validation Commands

Run from the repository root before implementation and after each task:

```bash
export JAVA_TOOL_OPTIONS=-XX:UseSVE=0 TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build -I /Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/no-checkstyle.gradle --console=plain
/Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/checkstyle.sh .
```

This devcontainer's JVM needs `-XX:UseSVE=0`. Gradle's Checkstyle worker ignores it and crashes with SIGILL, so the
init script disables the Checkstyle tasks, and `checkstyle.sh` runs the same jar and config over every source set
directly, failing on any `[WARN]` or `[ERROR]` (line length 120, single-line Javadoc with a tag, variable declaration
distance). Do not edit either helper script or commit them. If `checkstyle.sh` fails with a missing class after a
rebuild, run one normal build first to repopulate the Gradle cache. Record the actual `test` and `integration` counts,
failures and skips from `build/test-results/`, and distinguish baseline failures from regressions.

**Baseline on `7286139` (2026-10-28, Juniper): GREEN.** `build` SUCCESSFUL, `checkstyle.sh` OK. `test` 4,968 tests,
0 failures, 1 skipped; `integration` 25 tests, 0 failures. Re-record at execution time.

**Known intermittent test:** `CcdCreationAttemptRepositoryTest` "an attempt that ran out of tries on refusals may be
retried from scratch" fails intermittently in a full build. Re-run it alone before treating it as a regression, and
never "fix" it in this plan.

Run every build and test command in the **foreground** and wait for it to finish. A backgrounded build ends the
session, and the next one builds over it, wiping the results.

## Implementation Steps

### Task 1: Caller email
- [x] Record the execution baseline (commit, highest migration, test counts) in the execution notes.
- [x] Add failing tests: userinfo `email` reaches `CurrentUser` trimmed and lower-cased; blank or malformed is null; it appears in no log line, audit row or response.
- [x] Implement, run the focused tests and all Validation Commands, and record the results.

### Task 2: Migration and settings
- [x] Add failing tests for the seed, the widened window rule (all three migration branches), the new bounds, the ordering rule, old-shape PUTs, history and audit.
- [x] Implement V42, the extended `ProcessSettings`, the service reads and the HTTP changes, run the focused tests and all Validation Commands, and record the results.

### Task 3: The Masters list and linking
- [ ] Add failing tests for every Masters rule and every linking case in the Development Approach.
- [ ] Implement, run the focused tests and all Validation Commands, and record the results.

### Task 4: Bank holidays
- [ ] Add failing tests for the working-day rule and the three operations.
- [ ] Implement, run the focused tests and all Validation Commands, and record the results.

### Task 5: Documentation and final verification
- [ ] Update `CLAUDE.md` (layout counts, staff permissions, a new paragraph for the Masters list and its linking rule, the process settings paragraph) and any doc that calls the window a claim window.
- [ ] Run the final validation **forced**: `./gradlew cleanTest cleanIntegration build -I <no-checkstyle.gradle> --console=plain`, then `checkstyle.sh`. Record the counts against the baseline. Confirm `git status` is clean apart from the task commits.

## Execution notes

**Baseline (Task 1, 2026-10-28):** commit `d11ec44` (plan docs only over `7286139`), highest migration
`V41__referral_changes_to_standard_publication.sql`, so D1's migration is V42. `build` SUCCESSFUL, `test` 4,968
tests, 0 failures, 1 skipped; `integration` 25 tests, 0 failures.

**Task 1 (caller email):** `CurrentUser` gains a last component `email`, normalised in the four-argument
`currentUser` factory (trimmed, lower-cased, null if blank or without an `@`); the old constructor and factory pass
null. `IdamClaims.userFromClaims` reads userinfo's `email`, and `IdamAuthenticationConverter` keeps it through the AM
union. `toString` reports only whether an email is present. Tests: `CurrentUserTest.Email`, two in `IdamClaimsTest`,
one in `IdamAuthenticationConverterTest`, and `CallerEmailPrivacyTest` (full context, real Postgres: `/me`, case
create, note, case view and a 403, with the address absent from every body, every root-logger line, the audit
tables and the notes). Full build: `test` 4,976 tests, 1 failure, 1 skipped; `integration` 25, 0 failures. The one
failure, `CorrespondenceReadTest.preSendFailureCommitsRetryWithoutNotifyOrRollbackPoisoning [tokenFailure=true]`
(attempts 0, expected 1), is outside this change and passed alone (14/14); the machine was heavily loaded (the build
took 20m 52s against the baseline's 4m 48s), so it is recorded as intermittent rather than a regression.
`checkstyle.sh` OK.

**Task 2 (migration and settings):** V42 holds all three D1 tables at once, so Tasks 3 and 4 add no migration:
`deedpoll_master` (unique `lower(email)`, unique subject where not null, subject and `linked_at` set together),
`deedpoll_bank_holiday` with the 24 GOV.UK England and Wales dates for 2026–2028 (copied 2026-10-28, substitute days
named so), and the six new settings columns with bound, quarter-hour and ordering checks. The window's check is
dropped by V40's default name, the row re-seeded to 10080 at revision 0, raised to 1440 with a history row and the
service account as both id and name when chosen and below a day, and otherwise left alone. `ProcessSettings` gains
the six values plus `referralWindows()` and `valueOf(field)`; `ProcessSettingsTimes` owns the `HH:mm` quarter-hour
pattern the service, the DTO and the OpenAPI share. The ordering check runs after the stale check, against stored
values for absent keys, and names `morningWindowEnd`, `afternoonWindowStart` or `afternoonWindowEnd`.
`referralClaimExpiry()` is now `processingWindow()`. Existing tests that leaned on the 2-hour seed now use day-scale
values (claims backdated 8 days; `JudicialClaimWindowTest` uses 1440/2880 with fixtures 25 hours old), and
`SdkMigrationOrderTest` expects V1–V42. New: `MastersAndReferralProcessSettingsMigrationTest` (V40 then V42 in a
scratch schema: revision 0 → 10080, revision 3 at 120 → 1440 with one history row, revision 3 at 5000 kept, 1440
kept, defaults, the fresh seed and the Masters uniqueness), and new cases in the service, persistence, controller
and OpenAPI tests. Full build SUCCESSFUL: `test` 5,032 tests, 0 failures, 1 skipped; `integration` passing.
`checkstyle.sh` OK.
