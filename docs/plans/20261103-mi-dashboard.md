# Plan: Management information, api half (four KPIs from the case dates)

Reviewed with Steve on 2026-11-03 (Wren). It replaces an earlier workspace-local draft (Scout,
2026-10-16) that built its KPIs on states and audit actions this api does not have. The admin half
is `deed-poll-admin` `docs/plans/20261103-mi-dashboard-page.md`. It needs this plan merged and
deployed to AAT first.

## Overview

Delivery Managers and Team leaders want service-level management information (MI): how many
applications arrive, how long each stage takes, what Masters decide, and how long a case takes from
receipt to completion. This plan adds **read-only, aggregate-only** api operations that return those
figures. The admin half renders them as tables with charts, plus CSV downloads.

Scope is **four KPIs**, chosen because the api already holds the data for every case, migrated cases
included:

| # | KPI | Source |
|---|---|---|
| K1 | Volume: cases received per period, and open caseload at the end of each period | `deedpoll_deedpoll` dates |
| K2 | Stage durations: median and 95th percentile days for each milestone-to-milestone interval | `deedpoll_deedpoll` dates, `deedpoll_referral.decisionDate` |
| K3 | Referral outcomes: Master decisions (Approved / Rejected) per period, and applicant confirmation outcomes | `deedpoll_referral` |
| K4 | Time to completion by intake cohort: median and 95th percentile days from receipt to Enroled/Returned, and the share still open | `deedpoll_deedpoll` dates |

**Out of scope, deferred until the business defines them** (Scout's KPIs 4, 5 and 6):

- **SLA compliance.** There are no SLA targets anywhere: process settings hold only the referral claim
  window, referral windows and bank holidays. We would have to invent a target, which isn't a measure.
- **Escalation rate.** Nothing in the domain is called an escalation.
- **Re-submission rate.** No link exists between a returned case and a later application by the same person.

Each comes back as its own plan once someone defines it. Do not add placeholders, columns or enum
values for them here.

## Why not the audit trail

Scout's plan derived everything from `deedpoll_audit_event`. That doesn't work, for three reasons, all
checked against api master `74804bd` (2026-11-02):

1. **No audit action records a state change.** State is *derived* from the case's dates
   (`domain/deedpoll/CaseState.deriveState`); a state change is just a `CASE_UPDATED` carrying the
   changed date field.
2. **The audit trail only exists since V36 (2026-10-15).** The ~500 Dataverse records go into prod by
   direct SQL (go-live item #11) and get **no audit rows**, so an audit-based dashboard would show only
   a few days of activity in prod.
3. **The states Scout used don't exist.** The real `DeedPollState` values are Received,
   AwaitingFurtherDocs, ReadyForMaster, ReferredToMaster, AwaitingSignature, Enroled and Returned
   (plus a derived AwaitingEnrolment).

The case's own milestone dates exist for migrated and new cases alike, so this plan uses them.

**The one cost:** staff can edit these dates, so a figure reflects the dates as they stand now, not a
history of when someone typed them. The page says so (admin half, Task 3).

## Context

Baseline: api `origin/master` `74804bd` (2026-11-02, highest migration **V46**). Rebase onto current
`origin/master` before starting and re-record the baseline. Read `AGENTS.md`/`CLAUDE.md` first.

Files to read before changing anything:

- `entity/DeedPoll.java` (table `deedpoll_deedpoll`): `dateReceived`, `awaitingFurtherDocsInfo`,
  `dateReadyForMaster`, `dateReferred`, `signatureRequestedDate`, `signaturerecieveddate` (sic, the
  column name), `dateEnroled`, `dateReturned`, `masterDecision`, `masterDateOfDecision`, `type`,
  `migratedRecord`, `state`.
- `entity/Referral.java` (table `deedpoll_referral`): `state` (Open / Claimed / Decided / Closed /
  Withdrawn), `decision` (`MasterDecision`: Approved / Rejected), `decisionDate`, `createdOn`,
  `confirmationOutcome` (Confirmed / Withdrawn), `confirmationRequestedAt`, `confirmationReceivedAt`,
  `migrated`, `urgency`.
- `domain/deedpoll/CaseState.java`: how state is derived. It is the definition of "completed"
  (Enroled or Returned).
- The JdbcTemplate repository pattern: `repository/GazetteSettingsRepository.java` (compile-time SQL,
  no string-built queries).
- Capabilities: `domain/auth/Capability.java`, `RoleCapabilities.java`, `CallerAccessOpenApiTest`,
  `RoleCapabilitiesTest`.
- An existing caseworker read controller for route, OpenAPI and error conventions:
  `api/caseworker/controller/CaseworkerProcessSettingsController.java` (`/caseworker/...`).

## Decisions

Proposed by Wren; confirmed or amended by Steve on 2026-11-03 (see Open decisions):

| # | Decision |
|---|---|
| D1 | Four KPIs (K1 to K4) only. SLA, escalation and re-submission are deferred. |
| D2 | Source = case and referral columns, not the audit trail. Migrated cases are included and can be filtered out (`includeMigrated`, default true). |
| D3 | New capability `VIEW_MANAGEMENT_INFORMATION`, added to the `TEAM_LEADER` set (Delivery Managers inherit it). Caseworkers and judicial users don't get it. |
| D4 | Durations are **calendar days**, as whole days from date to date. **Cast every value to `date` before subtracting**: 20 of AAT's 63 `dateReceived` values carry a time of day (pre-flight, 2026-11-03), so a timestamp difference would give fractional days. |
| D5 | Aggregates only. No response contains a case id, reference, name or any free text. That keeps privacy-flagged cases safe without per-case checks, and means no `CASE_VIEWED`-style read audit. |
| D6 | **Percentiles are always published, for transparency (Steve, 2026-11-03), with the bucket's `count` beside them.** A bucket with no intervals has `count` 0 and `null` percentiles: there is nothing to compute, which is not the same as zero days. |
| D7 | Periods: ISO week (Monday start), calendar month or calendar quarter, bucketed on the stored value cast to `date` (no time-zone conversion; some values carry a time of day, see D4). A request may span at most 2 years. |
| D8 | One api operation per KPI. **CSV is built by admin from the same JSON**; the api has no export endpoint. |
| D9 | No new migration unless Task 4's measurements show a query over 2 s at 10× prod volume. |

## KPI definitions (the contract)

All four operations take `from` and `to` (inclusive ISO dates, required, `to - from` ≤ 2 years),
`granularity` (`WEEK` | `MONTH` | `QUARTER`), and `includeMigrated` (boolean, default `true`). Every
response echoes the resolved parameters and an `excluded` block counting the rows left out and why, so
nothing vanishes silently.

### K1 Volume — `GET /caseworker/management-information/volume`

- **received[period]** = count of cases whose `dateReceived` falls in the period. Split by `type`
  (Adult, AdultTransgender, Minor, MinorTransgender, DeedOfRelinquishment, Unknown).
- **openAtEnd[period]** = count of cases with `dateReceived ≤ period end` and neither `dateEnroled` nor
  `dateReturned` on or before the period end.
- `excluded.noDateReceived` = cases with a null `dateReceived` (expected for some migrated rows;
  the pre-flight check measures how many).

### K2 Stage durations — `GET /caseworker/management-information/stage-durations`

Each interval is bucketed by the period its **end** date falls in. Per interval and period:
`count`, `medianDays`, `p95Days` (D6: always published, with `count`).

| Interval | From | To |
|---|---|---|
| `RECEIVED_TO_READY_FOR_MASTER` | `dateReceived` | `dateReadyForMaster` |
| `READY_TO_REFERRED` | `dateReadyForMaster` | `dateReferred` |
| `REFERRED_TO_DECISION` | `dateReferred` | `deedpoll_referral.decisionDate` of the case's referral (see note) |
| `SIGNATURE_REQUESTED_TO_RECEIVED` | `signatureRequestedDate` | `signaturerecieveddate` |
| `SIGNATURE_RECEIVED_TO_ENROLED` | `signaturerecieveddate` | `dateEnroled` |

- An interval with either end null is not an interval. It isn't counted and isn't reported as excluded.
- An interval with **end before start** is counted in `excluded.negativeInterval[interval]`, never in
  the figures. These are data-quality problems the DM may want to see.
- **AwaitingFurtherDocs has no duration**: `awaitingFurtherDocsInfo` records when documents were asked
  for, and nothing records when they arrived. State this in the OpenAPI description; don't approximate it.
- **Note on `REFERRED_TO_DECISION`:** a case can have more than one referral (5 cases in AAT). Use
  the referral with the latest `decisionDate` that is not null. **If the case has no decided referral
  row but `deedpoll_deedpoll.masterDateOfDecision` is set, use that instead.** Apply this always, not
  only to migrated cases: it is right either way, and it means the figure doesn't depend on how the
  Dataverse migration fills referrals (that can only be checked in prod after the migration). Say so
  in the OpenAPI description, and include a fixture for it.

### K3 Referral outcomes — `GET /caseworker/management-information/referral-outcomes`

- **decisions[period]**: referrals with a non-null `decisionDate` in the period, counted by `decision`
  (Approved / Rejected), with `urgent` as a sub-count (`urgency`).
- **confirmations[period]**: referrals with a non-null `confirmationReceivedAt` in the period, counted
  by `confirmationOutcome` (Confirmed / Withdrawn: the applicant's response to the Master's directions).
- **Referral withdrawals (staff withdrawing a referral) are NOT reported.** `deedpoll_referral` has no
  withdrawal timestamp (checked 2026-11-03: only `modifiedOn`, which any later write overwrites, and
  the `REFERRAL_WITHDRAWN` audit event, which only exists since 2026-10-15). Reporting them would mean
  either a wrong date or an incomplete history. Steve decided not to track them (O4).
- Percentages are left to admin; the api returns counts only.

### K4 Time to completion by cohort — `GET /caseworker/management-information/completion`

- Cohort = the period `dateReceived` falls in.
- Per cohort: `received`, `completed` (cases with `dateEnroled` or `dateReturned`), `stillOpen`,
  `enroled`, `returned`, and `medianDays` / `p95Days` from `dateReceived` to the earlier of the two
  completion dates (D6: always published, with `count`). A case with both dates set is a contradiction (`CaseState.hasContradiction`)
  and goes in `excluded.contradiction`.
- Recent cohorts will look fast because their slow cases are still open. The `stillOpen` count is how
  the page shows that; admin must display it next to the percentiles.

## Development Approach

- Tests first in every task: write the failing test, then the code.
- Real Postgres for every query test (the repository's Testcontainers setup). Fixtures should be
  hand-built cases whose expected figures can be worked out by hand and are written in the test.
  Include: a case in each `type`; a migrated case; null and negative intervals; a case with two
  referrals; a case with `masterDateOfDecision` and no decided referral row; a contradiction; an empty bucket (`count` 0, percentiles null), a bucket of 1 and a bucket of 2 (percentiles still published; check `percentile_cont`'s interpolation by hand);
  period edges (Sunday 23:59 / Monday 00:00, 31 Dec / 1 Jan, quarter boundaries); a start at 23:00 and
  an end at 09:00 the next day, which must be **1** day (D4).
- SQL in Postgres with `percentile_cont`, `date_trunc` and `FILTER`, as compile-time constants with
  bound parameters. Never build SQL by string concatenation, even for `granularity`: map the enum to
  one of three fixed queries or bind the `date_trunc` field.
- The operations are reads. `WriteEndpointAuditCoverageTest` must still pass unchanged; if it flags
  the new controller, the controller is in the wrong place.
- Run every build in the **foreground**.

### Database access is pinned to the environment (Steve, 2026-11-03)

- **Local development (ralphex, every task below): Testcontainers Postgres only.** The executing agent
  must not connect to any deployed database, and must not run `az`, `kubectl`, `psql` against a
  remote host, or read any Key Vault, for any reason. If a task seems to need real data, stop and
  report. The pre-flight figures recorded below are all the real data this plan uses.
- **Deployed environments: manual, outside ralphex**, run by Wren with Steve, each over its own
  route (see "Pre-flight data check").

## Pre-flight data check (deployed environments; manual, NOT a ralphex task)

Run before ralphex starts, and record the results under "Pre-flight results" below. ralphex reads the
results; it never re-runs this.

**Queries** (read-only `select`s only, against database `deed-poll`):

1. Cases with a null `dateReceived`, split by `migratedRecord`.
2. Negative intervals, one count per K2 interval.
3. Cases with more than one row in `deedpoll_referral`.
4. Migrated cases with `masterDateOfDecision` set but no `deedpoll_referral` row with a `decisionDate`
   (decides the K2 `REFERRED_TO_DECISION` fallback).
5. Total cases, split by `migratedRecord`, and cases received in the last 12 months (sets Task 4's volume).

**Route per environment:**

| Environment | When | Route |
|---|---|---|
| AAT | Before ralphex starts | Over the VPN to `postgres-host` from Key Vault `deed-poll-aat`, as `postgres-user` / `postgres-password` (the module's admin user: **AAT has no reader role until deed-poll-infra#47 merges**). Every session is forced read-only with `PGOPTIONS='-c default_transaction_read_only=on'`. Once #47 is applied, use its reader role instead. AAT is shared with the api's PR previews; read only, and never during a preview's Flyway run. |
| Prod | After the Dataverse migration, re-check only | The `DTS JIT Access deed-poll DB Reader SC` group (azure-access#8522) through its access package and the prod bastion (azure-access-packages#158), as the reader role from deed-poll-infra#47. Needs go-live #21 and #22 done. Never the writer group. |

**If any result contradicts a definition above, stop and take it to Steve before ralphex starts.**
If the prod re-check contradicts one after merge, that is a follow-up fix, not a reason to revert.

### Pre-flight results

**AAT, 2026-11-03 (Wren, read-only session, admin user; no reader role yet):**

| Query | Result |
|---|---|
| Q1 null `dateReceived` | 1 of 63 (not migrated) |
| Q2 negative intervals | 0 for all five K2 intervals, 0 for received to completion |
| Q3 cases with more than one referral | 5 (the K2 "latest decided referral" rule is needed) |
| Q4 decision only on `masterDateOfDecision` | 0 |
| Q5 totals | 63 cases, 60 received in the last 12 months, **0 migrated**; earliest `dateReceived` 1896-08-11 (test data) |
| Extra | 0 Enroled+Returned contradictions; 20 of 63 `dateReceived` carry a time of day (led to D4's date cast) |

AAT has no migrated cases, so Q1 and Q4 for migrated data are first checked in prod (see the route table).

**Rehearsal data:** not used (Steve, 2026-11-03). Migrated-data coverage comes from the prod re-check after the migration, and K2's decision-date fallback is unconditional so nothing depends on it.

**Task 4 volume:** 10,000 cases (AAT is too small to extrapolate from; revise if the prod re-check suggests a larger prod).

## Validation Commands

Run from the repository root before implementation and after each task:

```bash
export JAVA_TOOL_OPTIONS=-XX:UseSVE=0 TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build -I /Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/no-checkstyle.gradle --console=plain
/Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/checkstyle.sh .
```

Same rules as `docs/plans/completed/20261026-dm-process-settings.md`: don't edit or commit the helper
scripts; record `test` and `integration` counts from `build/test-results/`; tell baseline failures
apart from regressions. **Known intermittent:** `CcdCreationAttemptRepositoryTest` "…may be retried
from scratch…". Re-run it alone; never fix it here.

**Baseline on `74804bd` (2026-11-03, Wren): GREEN.** `build` SUCCESSFUL (5m 44s, all 21 tasks executed); `checkstyle.sh`: OK; highest migration V46. From `build/test-results/`: `test` 5,392 tests, 0 failures, 0 errors, 1 skipped; `integration` 27 tests, 0 failures, 0 skipped. Re-record on the execution commit before Task 1 if master has moved. This section must
be green before starting; if it isn't, stop and report. **"Pre-flight results" must be filled in
before Task 1; if it is empty, stop and report.**

## Implementation Steps

### Task 1: The capability

- [x] Record the execution baseline (commit, highest migration, test counts) in the execution notes.
- [x] Failing tests: Team leader and Delivery Manager hold `VIEW_MANAGEMENT_INFORMATION`; caseworker,
      legacy admin and judicial roles don't; `/me` publishes it.
- [x] Add it to `Capability` and the `TEAM_LEADER` set; update `CallerAccessOpenApiTest` expectations.
- [x] Run the focused tests and all Validation Commands; record the results.

### Task 2: The query layer (K1 to K4)

- [x] Failing real-Postgres tests for every definition and edge listed in the Development Approach,
      one test class per KPI, with expected figures written by hand.
- [x] `repository/ManagementInformationRepository.java` (JdbcTemplate) and
      `domain/mi/ManagementInformationService.java` (parameter validation, the empty-bucket rule (D6), the
      `excluded` block). The service returns domain records, not DTOs.
- [x] Run the focused tests and all Validation Commands; record the results.

### Task 3: The HTTP operations

- [x] Failing full-context MockMvc tests: every role (200 vs 403); 400 for a missing `from`/`to`,
      `to < from`, a span over 2 years, and an unknown `granularity`; the response shape matches the
      OpenAPI schema; no response field holds a case identifier (assert on the schema's property names).
- [x] `api/caseworker/controller/CaseworkerManagementInformationController.java` under
      `/caseworker/management-information`, gated on `VIEW_MANAGEMENT_INFORMATION`, with OpenAPI
      operation ids `getMiVolume`, `getMiStageDurations`, `getMiReferralOutcomes`, `getMiCompletion`
      and descriptions carrying the definitions above, including what each KPI does **not** count.
- [x] Run the focused tests and all Validation Commands; record the results.

### Task 4: Performance check

- [x] An integration test seeding **10× prod volume** (the "Task 4 volume" in "Pre-flight results") and asserting each operation
      answers a 2-year monthly request in under 2 s on the Testcontainers Postgres. Record the timings.
- [x] Only if one fails: `EXPLAIN ANALYZE` it, add the narrowest index as the next free migration,
      and re-measure. Otherwise no migration (D9).

### Task 5: Documentation and final verification

- [x] Add the four operations to the api docs that list the caseworker surface, with the KPI
      definitions and their limits (edited dates, no further-docs duration, no referral withdrawals).
- [x] Final validation run **forced**:
      `./gradlew cleanTest cleanIntegration build -I <no-checkstyle.gradle> --console=plain`, then
      `checkstyle.sh`. Record which tasks ran and the counts against the baseline. Confirm
      `git status` is clean apart from the task commits.
## After merge (manual, NOT ralphex)

- Deploy to AAT, then the admin half refreshes its spec (it needs the merged `openapi.json`).
- After the Dataverse migration: the prod re-check in "Pre-flight data check", compared with AAT.

## Open decisions (for Steve)

| # | Question | Wren's proposal |
|---|---|---|
| O1 | Who gets the dashboard? Scout's "Ops/Management" has no role. | **DECIDED (Steve, 2026-11-03): Team leaders and Delivery Managers (D3), for now.** It can be widened later; that is a one-line change to `RoleCapabilities`. |
| O2 | Calendar days or working days? `deedpoll_bank_holiday` would let us do working days. | **DECIDED (Steve, 2026-11-03): calendar days (D4).** |
| O3 | Minimum bucket size for percentiles? | **DECIDED (Steve, 2026-11-03): no minimum; always show them, with the count (D6).** |
| O4 | Report staff referral withdrawals? It needs a new `withdrawnAt` column (a migration plus a write-path change), and the history would only start from the day it ships. | **DECIDED (Steve, 2026-11-03): no. There's no value in tracking them, so no follow-up plan.** |
| O5 | When should this start? | **DECIDED (Steve, 2026-11-03): now, in parallel with go-live. The migration window is not a concern.** The pre-flight check ran against AAT; once the Dataverse records are in prod, it is repeated there read-only and compared. |

## Execution notes

**Task 1 (2026-11-03).** Execution baseline: branch `feat/mi-dashboard` at `31e1cd0` (this plan) on
`origin/master` `74804bd`, which has not moved, so the recorded baseline stands (highest migration V46;
`test` 5,392 / 0 failed / 1 skipped, `integration` 27 / 0). `VIEW_MANAGEMENT_INFORMATION` is the last
`Capability` member. `RoleCapabilities` now keeps the legacy admin on its own set (the Team leader's
six as they stood), because the plan denies it to `caseworker-deedpoll-admin`; the Team leader set is
those six plus the new one, and the Delivery Manager inherits it. Tests: the staff-permission contract
JSON, `RoleCapabilitiesTest` (new per-role test, ordinal pinned after `ALLOCATE_REFERRALS`),
`CallerAccessResponseTest` and `CallerAccessSecurityTest` (`/me` publishes it for Team leader and
Delivery Manager, not legacy admin), `CallerAccessOpenApiTest` (enum ends with it). CLAUDE.md, README
and `docs/STAFF-ROLE-RELEASE.md` updated. Validation: `build` SUCCESSFUL (5m 26s); `checkstyle.sh` OK
after wrapping one long test line; `test` 5,393 / 0 failed / 1 skipped (+1, the new test), `integration`
27 / 0.

**Task 2 (2026-11-03).** `domain/mi`: `MiParameters.of` validates (each missing field named, `to < from`,
`to` after `from` + 2 years; `includeMigrated` null means true) and generates every period, the first and last
clipped to the range, so an open caseload is measured at the clipped end. `MiGranularity` binds the `date_trunc`
field and repeats the truncation in Java so empty periods are still reported. `DurationFigures.NONE` is D6's empty
bucket. `ManagementInformationService` runs each KPI's reads in one read-only repeatable-read transaction and
returns `VolumeReport`, `StageDurationsReport`, `ReferralOutcomesReport` and `CompletionReport`.
`repository/ManagementInformationRepository` holds seven constant statements, binding every value once in a
one-row `p` CTE; ranges compare the uncast column against `from` and `to + 1`, and durations subtract `::date`
values (D4). Choices the plan left open, so Task 3 can state them: a case with no `type` counts as `Unknown`;
`excluded.migrated` counts what `includeMigrated=false` left out (per interval for K2, decisions and confirmations
separately for K3); K3 also counts a `decisionDate` with no `decision` and a `confirmationReceivedAt` with no outcome;
K4 counts a completion before receipt as completed but leaves it out of the percentiles (`excluded.negativeInterval`),
and a contradiction in none of completed/enroled/returned/stillOpen. Tests: `MiParametersTest` (7, no database)
and one real-Postgres class per KPI (Volume 4, StageDurations 2, ReferralOutcomes 2, Completion 2), all figures
by hand. Fixtures are dated 2189/2190 because the container is shared with classes that commit; the
all-time figures (open caseload, no `dateReceived`) are asserted as a delta from a baseline taken in the same
transaction. Validation: `build` SUCCESSFUL (5m 25s); `checkstyle.sh` OK after making 12 Javadoc comments with
inline tags multi-line; `test` 5,410 / 0 failed / 1 skipped (+17), `integration` 27 / 0.

**Task 3 (2026-11-03).** `CaseworkerManagementInformationController` under `/caseworker/management-information`:
`GET volume`, `stage-durations`, `referral-outcomes`, `completion` (`getMiVolume`, `getMiStageDurations`,
`getMiReferralOutcomes`, `getMiCompletion`). The four parameters bind as strings, and
`ManagementInformationService.admit` checks `VIEW_MANAGEMENT_INFORMATION` (403) before
`MiParameters.parse` reads them (400, attributed to the parameter, value not echoed). So the
caller with the wrong role is 403 whatever they sent, as on the referral queue. A malformed date, an unknown or
lower-case `granularity`, and an `includeMigrated` other than `true`/`false` are 400s alongside the plan's four. The
`@Parameter` annotations publish `from`/`to` as required `format: date`, `granularity` as the required enum
and `includeMigrated` as an optional boolean defaulting to true. Each description carries the shared rules (range, clipping, edited
dates, aggregates only), the KPI's definition, and a "Not counted" sentence that includes Task 2's choices.
Responses are `Mi*Response` DTOs; enum-keyed maps are published as ordered lists (`receivedByType`
`[{type, count}]`, `intervals` `[{interval, count, medianDays, p95Days}]`, `excluded.negativeInterval`/`migrated`
`[{interval, count}]`) so the schema names every member; nested records carry `Mi…` schema names, because
`Period`/`Excluded` collided as components (the shape test caught it). Tests: `CaseworkerManagementInformationControllerTest`
(full context, real Postgres, `@Transactional`, one fixture in 2191). It covers 401 without a token; 200 for Team leader and
Delivery Manager on all four; 403 for caseworker, legacy admin, judicial and a foreign role, including with bad
parameters; nine 400 cases on all four; exactly two years admitted; the figures on the wire, with an empty period as zeros
and nulls; each body matched recursively against its published schema (exact property set, all required,
types, enums, nullability); and no property name in any response schema ending in `id` or containing reference/name/
subject/email/note/text/case. `MiParametersTest` +3 for `parse`. `OpenAPIPublisherTest` lists the four as
`MI_PATHS` (counted apart) and documents them. `MiFixtures` is public for the HTTP test. Validation: `build`
SUCCESSFUL (5m 4s); `checkstyle.sh` OK after marking two locals in `parse` final; `test` 5,433 / 0 failed / 1
skipped (+23), `integration` 28 / 0 (+1). `WriteEndpointAuditCoverageTest` unchanged and passing.

**Task 4 (2026-11-03).** `src/integrationTest/.../domain/mi/ManagementInformationPerformanceTest` (`@Transactional`,
rolled back) seeds 10,000 cases (the "Task 4 volume") by one `generate_series` insert over 2180 to 2182: every
`type`, 10% migrated, 1 in 500 with no `dateReceived`, every milestone at varying gaps, `masterDateOfDecision` on
some, contradictions, and about 7,000 referrals (a second on 1 case in 50) with decisions, urgency and both
confirmation outcomes; then `ANALYZE`s both tables. A second test proves the seed reaches every figure (24 periods
received, all five intervals counted, decisions/urgent/confirmations, completion and contradictions), so the timings
are of real work. Each KPI, a 2-year monthly request (2181-01-01 to 2182-12-31) through
`ManagementInformationService`, warmed once and timed as the slowest of three runs: volume 35 ms, stage-durations
22 ms, referral-outcomes 4 ms, completion 3 ms. All far under 2 s, so no `EXPLAIN ANALYZE` and no migration (D9);
highest migration still V46. Validation: `build` SUCCESSFUL (5m 5s); `checkstyle.sh` OK; `test` 5,433 / 0 failed /
1 skipped (unchanged), `integration` 30 / 0 (+2).

**Task 5 (2026-11-03).** README: "The API surface" counts the four reads, and a new "Management information"
section lists them with the shared parameters, each KPI's definition, and what they cannot tell you (the dates as
edited now, no Awaiting further documents duration, no staff referral withdrawals, recent cohorts looking fast,
the deferred SLA/escalation/re-submission KPIs). CLAUDE.md's layout names `/caseworker/management-information`
(counted apart as `MI_PATHS`) and `domain/mi`; `docs/RESUME.md` has the plan's entry. Forced validation:
`cleanTest cleanIntegration build` SUCCESSFUL (5m 3s; `test` and `integration` executed, compilation up to date,
checkstyle tasks skipped by the helper); `checkstyle.sh` OK; `test` 5,433 / 0 failed / 0 errors / 1 skipped
(+41 on the 5,392 baseline), `integration` 30 / 0 (+3 on 27). No new migration (highest V46). `git status`
clean apart from the task commits.
