# Plan: Delivery Manager process settings, starting with the referral claim window

## Overview

Steve asked on 2026-10-14 for a screen where a Delivery Manager adjusts the service's business process values while
the service runs, so the people using the tool can change how the process works, with no release needed. This plan is the api half of the
**first phase**, decided on 2026-10-26:

1. **A process-settings store**: one row, revisioned, attributed and audited, with its own change history. It is the
   shape of the Gazette recipient settings (V33, `GazetteSettingsService`), deliberately.
2. **One live value: the referral claim window**, how long a judge's untouched claim survives before
   `ReferralService.reclaimStaleClaims` returns it to the pool. Today it is `deedpoll.referrals.claim-expiry`
   (`DEEDPOLL_REFERRAL_CLAIM_EXPIRY`, default `PT2H`). After this plan the stored value is the only source.
3. **A new capability, `MANAGE_PROCESS_CONFIG`**, granted to Delivery Managers only, gating both operations.

The other six values Steve accepted on 2026-10-14 (applicant confirmation window, missing-document chase timing,
escalation threshold, per-judge referral cap, reminder frequency, auto-closure) are **out of scope**. Nothing in api
acts on any of them today, so each is a later plan that builds its behaviour and its setting together. Do not add
columns, fields or placeholders for them here.

The admin half is `deed-poll-admin` `docs/plans/20261026-dm-settings-page.md`. It needs this plan merged and deployed,
and the refreshed `openapi.json`, first.

## Context

Baseline: api `origin/master` `d40b070` (V39, 2026-10-26). **Rebase the branch onto current `origin/master`
before starting**, re-record the baseline there, and re-check the next free migration number. Read `AGENTS.md`/`CLAUDE.md` first, especially the audit
trail and Flyway rules, and record the execution baseline. **Recheck the highest migration at execution time**: V39
is the latest at writing, so this plan's migration is V40 only if nothing has landed since. Take the next free number.

Decisions (Steve, 2026-10-26; the source is `cft-workspace/.context/deed-poll-admin/dm-config-plan-relevance-2026-10-26.md`):

| # | Decision |
|---|---|
| D1 | Phase 1 = the settings store plus the claim window live. The other six values come later, each with its behaviour. |
| D2 | New `Capability.MANAGE_PROCESS_CONFIG`, granted to `DELIVERY_MANAGER` only, not to Team leaders or legacy admin. |
| D3 | The migration seeds 2 hours. Afterwards only the stored value is read. `deedpoll.referrals.claim-expiry` and `DEEDPOLL_REFERRAL_CLAIM_EXPIRY` are removed. (Checked 2026-10-26: no chart, Flux or other hmcts repo sets the variable; GitHub code search finds only `application.yaml` and `ReferralPropertiesTest`.) |
| D4 | The claim window may be set from 30 minutes to 24 hours, in whole minutes. |
| D5 | Every change is audited: who, when, the old and new value. |
| D6 | A change takes effect immediately for every case. No cache. |

Files to read before changing anything:

- **The pattern:** `src/main/resources/db/migration/V33__gazette_recipient_settings.sql` (a singleton `id = 1` row
  seeded by Flyway, a `revision`, attribution columns with a check that ties them to `revision > 0`, and a
  `_change` history table keyed on revision); `repository/GazetteSettingsRepository.java` (JdbcTemplate, compile-time
  SQL, `read(lock)` with `FOR UPDATE`, `change(previous, next, subject)`); `domain/gazette/GazetteSettingsService.java`
  (closed key set, field-named `InvalidInputError`, `ConflictError` on a stale revision, no-op on an unchanged value,
  `AuditTrail.record` with field **names only**); `domain/gazette/GazetteSettings.java`;
  `api/caseworker/controller/CaseworkerGazetteSettingsController.java` (`@AuditedAs`, operation ids);
  `api/caseworker/dto/GazetteSettingsResponse.java` and `UpdateGazetteSettingsRequest.java` (`KeyedRequestBody`).
- **Why values go in the history table and not the audit trail (D5):** `AuditTrail` refuses values on an entity with
  no case (CLAUDE.md, "A new write path must call `AuditTrail`"), because `deedpoll_audit_event_value` is disposed
  with a case. So, as for Gazette settings, the audit event names the field, and this plan's own history table holds
  the before and after values with attribution.
- **Capabilities:** `domain/auth/Capability.java` and `RoleCapabilities.java` (`TEAM_LEADER` set, `deliveryManager()`
  adds to it). `GET /me` publishes capabilities in declaration order with no change needed. `CallerAccessOpenApiTest`
  holds the published enum to `Capability.values()`, and `RoleCapabilitiesTest` iterates `Capability.values()` and
  `Role.values()`. AM's gazette-skill mapping yields `DELIVERY_MANAGER` (`domain/auth/AmRoles`), so an AM-sourced
  Delivery Manager gets the new capability with no AM change.
- **The value's consumer:** `domain/referrals/ReferralService.java`. Read the class javadoc on `reclaimStaleClaims`,
  "a mutating read, on purpose. Do not 'fix' it". The sweep runs at the top of the judicial pool, the personal list
  and `claim` (`JudicialReferralService` lines ~249/307/406, `ReferralService` ~464), inside their transactions, and
  computes `cutoff = now.minus(properties.claimExpiry())`. Also read `ReferralProperties.java`, `ReferralConfiguration.java`,
  `ReferralPropertiesTest.java`, and every `new ReferralService(` (`ReferralServiceTest` `serviceWithClaimExpiry`,
  `ConfirmationSecurityTest`, `ReferralSecurityTest`). `NotifyProperties` mentions `ReferralProperties` in a comment.
- **Tests that enumerate paths or Gazette settings**, which a new path must join: `OpenAPIForbiddenResponsesTest`,
  `RoleAssignmentSecurityTest`, `AuditTrailMigrationTest`, `RemainingWriteAuditTest`,
  `WriteEndpointAuditCoverageTest`, `StaffAccessRulesTest`, and in `src/integrationTest`, `OpenAPIPublisherTest` (its
  `known` path list around line 595). Find any others with
  `grep -rn "gazette-settings\|GAZETTE_SETTINGS" src/test src/integrationTest`.

## Development Approach

1. **Capability (D2).** Append `MANAGE_PROCESS_CONFIG` to `Capability`. Appending keeps existing declaration
   order, so `/me` answers grow by one value and change nowhere else. Grant it in `deliveryManager()` only. Update
   the capability matrix in `CLAUDE.md` ("Staff permissions"). Admin's copy of the matrix is admin's change, not this one.
2. **Migration (D3, D4, D5).** Name it `V40__process_settings.sql`, or the next free number. Mirror V33:
   - `deedpoll_process_settings`: `id integer primary key check (id = 1)`; `referral_claim_expiry_minutes integer not
     null check (referral_claim_expiry_minutes between 30 and 1440)`; `revision integer not null check (revision >= 0)`;
     `updated_at`, `updated_by_id`, `updated_by_name`, all null at revision 0 and all non-null above it (V33's check).
     Seed `(1, 120, 0)`. One typed column per value, so a later value is a later migration adding a column with
     its own check, never a JSON bag.
   - `deedpoll_process_settings_change`: `revision integer not null`, `field varchar(64) not null`,
     `previous_value text`, `new_value text not null`, `changed_at timestamp not null`, `changed_by_id varchar(255) not
     null`, `changed_by_name varchar(255) not null`, `primary key (revision, field)`. One row per changed field per
     revision, so later values need no history migration.
   - Snake_case names, as V31/V33 use. No foreign key to cases. **Add both tables to
     `health/DeedPollSchema.DEED_POLL_TABLES`** beside the Gazette settings pair, so readiness names them if they are
     missing (`DeedPollSchemaHealthTest`). Teach `AuditTrailMigrationTest`, or any other migration test that lists
     tables, about them if it lists them.
3. **Domain.** Create `domain/settings/ProcessSettings` (a record: `Duration referralClaimExpiry`, `int revision`,
   `LocalDateTime updatedAt`, `String updatedBy`), `repository/ProcessSettingsRepository` (as
   `GazetteSettingsRepository`: `read(boolean lock)`, `change(previous, next, subject, changedFields)`), and
   `domain/settings/ProcessSettingsService`:
   - `get(CurrentUser)` requires `MANAGE_PROCESS_CONFIG`.
   - `update(CurrentUser, Map<String,Object>)` requires `MANAGE_PROCESS_CONFIG`, and `requireUserId()`. The key set
     is closed: `{referralClaimExpiryMinutes, revision}`, both required. The minutes must be an `Integer` from 30 to
     1440; a non-integer, a fraction, a string or an out-of-range value is a field-named `InvalidInputError`, with
     the bounds in the message. The revision must be an `Integer` ≥ 0, read under `FOR UPDATE`; a different revision
     is a `ConflictError`. An unchanged value is a no-op that returns the current settings, with no revision bump,
     no history row and no audit event. Otherwise write the row and one history row per changed field, and
     record `AuditAction.PROCESS_SETTINGS_CHANGED` on `AuditEntities.PROCESS_SETTINGS`, id `"1"`, with the field
     names and no values, in the same transaction.
   - `referralClaimExpiry()`, with **no capability check**, for the sweep. It reads the row without a lock and joins
     the caller's transaction. No cache (D6). A missing row throws, never falls back to a default: the migration
     guarantees the row, and a silent default is what D3 removes.
   - Add the `AuditAction` member and the `AuditEntities` constant. `AuditAction` names are permanent (CLAUDE.md),
     so choose them once: `PROCESS_SETTINGS_CHANGED`, `"ProcessSettings"`.
4. **HTTP.** `api/caseworker/controller/CaseworkerProcessSettingsController` at `/caseworker/process-settings`:
   `GET` (`operationId = getProcessSettings`) and `PUT` (`updateProcessSettings`, `@AuditedAs(PROCESS_SETTINGS_CHANGED)`),
   with DTOs `ProcessSettingsResponse` (`referralClaimExpiryMinutes`, `revision`, `updatedAt`, `updatedBy`; attribution
   null at revision 0) and `UpdateProcessSettingsRequest extends KeyedRequestBody`. Publish `minimum: 30`,
   `maximum: 1440` and `format: int32` on the request field, the same way the repo publishes other write bodies (find
   how `UpdateGazetteSettingsRequest`'s body reaches the spec, and follow that). Descriptions say: Delivery Manager
   only; a stale revision is 409 with no change; the change applies to every live claim from the next judicial pool
   load. The 403 is published by `OpenAPIForbiddenResponses` because the path is in the caseworker `StaffArea`;
   check that rather than adding one. Update `CLAUDE.md`'s layout line for `api/caseworker` (it counts operations).
5. **The sweep reads the stored value (D3, D6).** `ReferralService` takes `ProcessSettingsService` (or the
   repository, if a service dependency would cycle) in place of `ReferralProperties`, and `reclaimStaleClaims`
   computes the cutoff from `referralClaimExpiry()` once per call, keeping the one-cutoff-per-call rule its comment
   states. Delete `ReferralProperties`, `ReferralPropertiesTest`, the `ReferralConfiguration` registration (delete the
   class if that is all it does), and `deedpoll.referrals.claim-expiry` from `application.yaml`. Rewrite the javadoc
   passages that say the window "is a Helm value rather than a release" to say it is a Delivery Manager setting
   (`/caseworker/process-settings`), seeded at 2 hours. Fix the `NotifyProperties` comment. Every
   `new ReferralService(` in tests gets the new dependency; `ReferralServiceTest`'s lapse tests keep proving the
   window, now through a stubbed settings read.
6. **Tests first**: unit tests, plus full-context MockMvc against real Postgres, as
   `CaseworkerGazetteSettingsControllerTest` does (`@SpringBootTest`, `@AutoConfigureMockMvc`, `extends
   AbstractPostgresContainerTest`). There is no separate Gazette settings repository test to copy:
   - Permissions: a Delivery Manager gets 200 on GET and PUT. A Team leader, legacy admin, caseworker and judge get
     403 on both, before any read. An AM-sourced Delivery Manager is admitted (add a `RoleAssignmentSecurityTest`
     case beside the Gazette one).
   - Fresh database: GET answers 120, revision 0, null attribution.
   - PUT 30 and PUT 1440 are accepted. 29, 1441, 0, -5, 90.5, `"120"`, null, a missing field, an unknown key and a
     missing revision are each a 400 that names the field, and nothing is written (row, history and audit unchanged).
   - A stale revision is 409 with nothing written. An unchanged value is 200 at the same revision with no history
     and no audit. **A stale revision beats the no-op**: a PUT carrying an old revision and the value the row now
     holds is still 409, so an out-of-date form is never told it saved.
   - A change bumps the revision, sets attribution, writes exactly one history row with the old and new minutes, and
     records exactly one audit event with field names and no values. **No `deedpoll_audit_event_value` row** is
     written for it (the entity has no case).
   - **Atomicity and concurrency, against real Postgres, in independent transactions** (the Gazette pattern is design
     evidence, not proof of this write path). Use `NotificationOutboxConcurrencyTest` for the repo's way of running two
     transactions at once, and `@MockitoSpyBean` as the notify tests do for a forced failure:
     - two updates from the same revision, released together, give exactly one success and one `ConflictError`
       (409), with one revision increment, one history row and one audit event;
     - `AuditTrail.record` forced to throw: the settings row and the history are both unchanged afterwards;
     - the history insert forced to fail (for example, a history row already present at the next revision, so its
       primary key refuses): the settings row is unchanged and no audit event committed.
   - **The live window, through the real entry points and real Postgres.** Build on `JudicialReferralControllerTest`,
     which already has `claimedBy(...)` and `lapse(...)` fixtures. A released claim becomes `Open` and nothing makes
     it `Claimed` again, so every step uses a claim that is still `Claimed`:
     - Fixture A, `Claimed` and last touched 31 minutes ago. With the stored window at 60, a judicial pool load keeps
       it. PUT 30: **the PUT itself releases nothing**; A is still `Claimed` with no lapse note. The next pool load
       releases A, writes exactly one lapse note and refreshes the case's `currentReferralState`. PUT 60 and load
       again: A stays `Open` (raising the window never restores a released claim).
     - Fixture B, a fresh `Claimed` claim last touched 31 minutes ago, with the window at 60: a pool load keeps it.
       That proves a raised window retains a claim not yet swept.
     - Repeat the release check through `GET /judicial/referrals/mine` and through `claim`, the other two sweep entry
       points (`JudicialReferralService` and `ReferralService.claim`), each with its own still-`Claimed` fixture.
     - **The singleton row deleted** (inside the test only): the pool load fails, and no claim is released, no note is
       written and no read-model changes. The read happens before any release, so there is never a partial sweep.
   - `ReferralServiceTest`'s existing lapse tests stay unit tests with a stubbed settings read. They prove the cutoff
     arithmetic, not D6.
   - `/me` for a Delivery Manager lists `MANAGE_PROCESS_CONFIG`, and for a Team leader does not.
   - The OpenAPI document carries both operations, the bounds and the 403.
7. **Out of scope:** the six later values; admin (separate plan); any scheduler (the sweep stays a mutating read);
   changes to Gazette settings or their gate; Helm, Flux or chart changes (none set the removed variable). No new
   dependency.
8. **Publishing:** local task commits only. The PR and its description are separate, and the PR description must
   say that admin needs an `openapi.json` refresh after merge, that `/me` grows one capability, and that the claim
   window is now a stored value seeded at 2 hours. It must also name the **rolling-deploy window**: until every pod
   is replaced, old pods still read `PT2H` from yaml while new pods read the seeded 120 minutes. The two agree, so
   nothing changes, but a Delivery Manager who saves a new window mid-deploy is ignored by old pods until they go.
   So the PR and `docs/` state, as an activation rule: **nobody edits process settings until every old api pod is
   replaced**, and raising the window cannot restore claims already released. A sweep already running when a change
   commits finishes with the value it read; "no cache" means the next sweep uses the new value, not that a running
   one is cancelled. Sweeps run on the judicial pool, My referrals and claim, not only the pool. Admin must not
   deploy its page until this api is serving on every replica in that environment.
   **Do not open or publish the PR until Scout confirms the ITHC freeze is in**, because any api master merge rolls
   ITHC before the freeze pin lands.

## Validation Commands

Run from the repository root before implementation and after each task:

```bash
export JAVA_TOOL_OPTIONS=-XX:UseSVE=0 TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build -I /Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/no-checkstyle.gradle --console=plain
/Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/checkstyle.sh .
```

This devcontainer's JVM needs `-XX:UseSVE=0`. Gradle's Checkstyle worker ignores it and crashes with SIGILL, so the
init script disables the Checkstyle tasks, and `checkstyle.sh` runs the same jar and config over every source set
directly, failing on any `[WARN]` or `[ERROR]`. Jenkins runs the real Checkstyle tasks. Do not edit either helper
script or commit them. They live in the workspace's `.context/scripts/` (bind-mounted, so they survive a devcontainer
rebuild). `checkstyle.sh` names the Checkstyle 10.24.0 jars by absolute path under `~/.gradle/caches`, so if it
fails with a missing class after a rebuild, run one `./gradlew checkstyleMain --dry-run` or a normal build first to
repopulate the cache. That is tooling, not a regression. Record the actual `test` and `integration` counts, failures and skips from
`build/test-results/`, and distinguish baseline failures from regressions.

**Baseline on `d40b070` (2026-10-26, Juniper): GREEN.** `build` SUCCESSFUL (5m 34s, all 21 tasks executed); `checkstyle.sh`: OK. From `build/test-results/`: `test` 4,870 tests, 0 failures, 0 errors, 1 skipped; `integration` 24 tests, 0 failures, 0 skipped. Re-record at execution time if master has moved.

**Known intermittent test, already on master:** `CcdCreationAttemptRepositoryTest` "an attempt that ran out of
tries on refusals may be retried from scratch, which clears the proof" fails intermittently in a full build. Re-run
it alone before treating it as a regression, and never "fix" it in this plan.

Run every build and test command in the foreground and wait for it to finish. A backgrounded build ends the session,
and the next one builds over it, wiping the results.

## Implementation Steps

### Task 1: The capability

**Files:** `domain/auth/Capability.java`, `RoleCapabilities.java`, `RoleCapabilitiesTest`, `CallerAccessOpenApiTest`
if it needs the new value spelled out, `/me` controller tests, `CLAUDE.md` (Staff permissions).

- [ ] Record the execution baseline (commit, highest migration, test counts) in the execution notes.
- [ ] Add failing tests: Delivery Manager holds `MANAGE_PROCESS_CONFIG`; Team leader and legacy admin do not; `/me` publishes it.
- [ ] Implement, run the focused tests and all Validation Commands, and record the results.

### Task 2: Migration, repository and service

**Files:** `db/migration/V40__process_settings.sql` (or the next free number), `domain/settings/ProcessSettings.java`,
`ProcessSettingsService.java`, `repository/ProcessSettingsRepository.java`, `domain/audit/AuditAction.java`,
`AuditEntities.java`, `health/DeedPollSchema.java`, and their tests, plus the migration, audit and schema-health tests that enumerate tables or actions.

- [ ] Add failing tests for the seed, the bounds, the closed key set, stale revisions (including stale-beats-no-op), the no-op, the history row, the audit event with no value row, the two concurrent updates and the two forced-failure rollbacks.
- [ ] Implement, run the focused tests and all Validation Commands, and record the results.

### Task 3: The HTTP operations

**Files:** `api/caseworker/controller/CaseworkerProcessSettingsController.java`, `api/caseworker/dto/ProcessSettingsResponse.java`,
`UpdateProcessSettingsRequest.java`, the path-enumerating tests listed in Context, `OpenAPIPublisherTest`, `CLAUDE.md` (layout).

- [ ] Add failing tests: full-context MockMvc for every role, the 400/409 cases over HTTP, and the published operations, bounds and 403.
- [ ] Implement, run the focused tests and all Validation Commands, and record the results.

### Task 4: The sweep reads the stored window

**Files:** `domain/referrals/ReferralService.java` (code and javadoc), `ReferralProperties.java` (delete),
`ReferralConfiguration.java`, `ReferralPropertiesTest.java` (delete), `ReferralServiceTest`, `ConfirmationSecurityTest`,
`ReferralSecurityTest`, `application.yaml`, `notify/NotifyProperties.java` (comment).

- [ ] Add the failing real-Postgres live-window tests listed in the Development Approach: fixtures A and B, the three entry points, the PUT releasing nothing, and the missing singleton releasing nothing.
- [ ] Implement, delete the property and its key, run `grep -rn "claim-expiry\|CLAIM_EXPIRY\|ReferralProperties" src docs` and clear every hit or justify it, then run all Validation Commands and record the results.

### Task 5: Documentation and final verification

**Files:** `CLAUDE.md`, `docs/RESUME.md` if it describes referrals or settings, and any `docs/` page naming `DEEDPOLL_REFERRAL_CLAIM_EXPIRY`.

- [ ] Update the docs that describe the claim window or the caseworker surface.
- [ ] Run the final validation **forced**, so nothing is reported from an earlier run: `./gradlew cleanTest cleanIntegration build -I <no-checkstyle.gradle> --console=plain`, then `checkstyle.sh`. Record which tasks executed and which were up to date, and the counts against the baseline. Say that Checkstyle ran through `checkstyle.sh` locally, not through Gradle (Jenkins runs the Gradle tasks). Confirm `git status` is clean apart from the task commits.
