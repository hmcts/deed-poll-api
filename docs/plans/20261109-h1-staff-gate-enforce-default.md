# Plan: enforce the `/caseworker/**` staff gate by default, everywhere (OWASP H1, plan 01 Task 5)

## Overview

`/caseworker/**` is gated on staff membership by `StaffGate` (`config/StaffGate.java`, wired at
`SecurityConfiguration.java:376`). In `report` mode it logs a non-staff caller and lets them through; in `enforce` it
answers 403. Today the default is `report` (`application.yaml`: `staff-gate: ${STAFF_GATE_MODE:report}`), and only
prod enforces, because the chart sets `STAFF_GATE_MODE` to `enforce` for prod and `report` everywhere else
(`charts/deed-poll-api/values.yaml:79`). So in AAT and ITHC any IDAM token, a judge's included, reads and writes
caseworker data. ITHC App Insights shows exactly that: a seeded judge account on `/caseworker/cases` and
`/caseworker/saved-queries` on 2026-11-03.

This plan makes `enforce` the default and removes the chart's per-environment line, so every environment enforces and
none can quietly drift back. **The only production behaviour change is the default.** The `report` mode code stays,
for rollback by environment variable, until plan 01 Task 12 deletes it.

Why it is safe (from the investigation, `owasp-h1-next-steps-2026-11-09.md` in the workspace `.context/`):

- `GET /me`'s `staff` is `StaffArea.CASEWORKER.admits(authorities)` (`CallerAccessResponse.java:57`), the same
  predicate over the same authorities as the gate. Admin already refuses a sign-in whose `/me` is neither staff nor
  judicial (admin#206), so every staff user of admin already passes the gate.
- Judicial pages call only `/judicial/**`, which is enforced already in every environment.
- Pipeline smoke and functional tests in api and admin call only public paths.
- All five staff roles are registered in IDAM for AAT, ITHC and prod.

## Decisions taken — implement these, do not revisit

1. **Default is `enforce`**: `staff-gate: ${STAFF_GATE_MODE:enforce}`. `StaffGate.Mode.of` already treats any value
   other than `report` as `ENFORCE`; do not change it.
2. **The chart sets no `STAFF_GATE_MODE`.** Delete the line and its three-line comment from
   `charts/deed-poll-api/values.yaml`, so the application default applies in every environment. Do not add it to
   `values.aat.template.yaml` or `values.preview.template.yaml`.
3. **Bump `charts/deed-poll-api/Chart.yaml` `version` from `0.0.33` to `0.0.34`.** Flux reconciles on chart version
   (`reconcileStrategy: ChartVersion`), so a values change under an unchanged version never reaches the cluster.
4. **Report mode stays** (rollback is a flux PR setting `STAFF_GATE_MODE: report`). Do not touch `StaffGate`'s logic,
   its log line, `StaffArea` or `SecurityConfiguration`.
5. **A test that relied on the old default is fixed in the test, keeping its intent.** If it is about report mode,
   pin `deedpoll.security.staff-gate=report` on it. If it only needed a caller to get through, give its token a staff
   role (`caseworker-deedpoll`). Never change production code to make a test pass.

## Constraints — read these every task

- **Run every build and test in the foreground. Never background a Gradle command** (no `&`, no `nohup`, no
  `run_in_background`). A backgrounded build ends this session, and the restart builds over it and wipes the results.
- Environment for every Gradle command: `export TESTCONTAINERS_RYUK_DISABLED=true JAVA_TOOL_OPTIONS=-XX:UseSVE=0`.
- **Gradle's own Checkstyle tasks crash with SIGILL in this devcontainer** (the worker ignores `JAVA_TOOL_OPTIONS`).
  They are disabled locally with the init script below, and the same pinned Checkstyle runs directly through
  `checkstyle.sh`, which fails on any `[WARN]` as the Gradle task does. Jenkins still runs the normal tasks. Never
  edit `build.gradle` or the Checkstyle config to work around this.
- Java 21, `-Werror`. No new dependencies. Never edit committed Flyway migrations.
- Keep the diff to the files named in the tasks, plus any test fixed under Decision 5.

## Validation Commands

**These run after every task, so all of them must be green after every task.** Baseline on untouched master
`4c41025`, 2026-11-09: green (counts recorded in Task 4).

```bash
export TESTCONTAINERS_RYUK_DISABLED=true JAVA_TOOL_OPTIONS=-XX:UseSVE=0
./gradlew build -I /Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/no-checkstyle.gradle --console=plain
/Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/checkstyle.sh .
```

## Tasks

### Task 1: Make `enforce` the default, test first

- [x] Failing test first: in `src/test/java/uk/gov/hmcts/reform/deedpoll/config/StaffGateReportModeTest.java`, pin
      report mode explicitly (`@SpringBootTest(properties = "deedpoll.security.staff-gate=report")`, or the
      class's existing property mechanism), and rename `reportsByDefault` to say it reports **when configured to**.
      Its assertions stay as they are.
- [x] Failing test first: a new container-free test, `StaffGateDefaultModeTest` in the same package, built like
      `StaffGateReportModeTest` (StubIdam, MockMvc) but with **no** `staff-gate` property: a role-less token
      (`caseworker-divorce`) on `GET /caseworker/no-such-route` gets **403**, and `StaffGate` logs nothing. A staff
      token (`caseworker-deedpoll`) on the same path gets 404. Run it and see it fail on master's default.
- [x] `application.yaml`: `staff-gate: ${STAFF_GATE_MODE:enforce}`. Rewrite the comment above it: `enforce` is the
      default in every environment (OWASP plan 01 Task 5, 2026-11-09); `report` is a rollback setting only; Task 12
      removes it. Keep the existing first two comment lines' meaning.
- [x] Run the validation commands **in the foreground**. Fix any test failing because a role-less token now gets 403,
      under Decision 5 only. List each one you fixed, and how, in this task's notes.
- [x] Mark completed

Task 1 notes, tests fixed under Decision 5 (full build exit 0, checkstyle.sh OK):

- `auth/StubIdam.stubDefaults`: the shared default userinfo held `caseworker-civil`, which is not staff, so about
  370 caseworker-surface tests got 403. It now holds `caseworker-deedpoll` (a plain caseworker, as its javadoc said).
- `caseworker-civil` meaning "an ordinary caseworker" became `caseworker-deedpoll`: `CaseworkerCaseControllerTest`
  (2 PATCH tests), `CaseworkerNoteControllerTest` (PATCH tests, `refusesBeforeResolving`, `doesNotGateTheAmend`),
  `CaseworkerWitnessControllerTest` (`refusesBeforeResolving`, both `NotGated` tests, renamed "a plain caseworker,
  with no capability beyond membership"), `CaseworkerReferralControllerTest` (withdrawal and 2 closure tests),
  `CaseworkerSavedQueryControllerTest` (`OWNER`/`OTHER`, one display name), `CaseworkerCcdAttemptControllerTest`
  (`caseworkerRefused`), `ReadAuditTest` (2), `JudicialDocumentDownloadTest.asCaseworker`,
  `ReferralLifecycleTimelineTest.asCaseworker`. The two `refusesBeforeResolving` tests still passed with 403, but
  only because the gate refused them, so they no longer pinned the capability check's ordering. Fixed for intent.
- A judge or outsider now meets the gate's 403 before the capability's, so the asserted sentence is the gate's
  (`StaffArea.CASEWORKER.refusal()`): `CaseworkerNoteControllerTest`/`CaseworkerWitnessControllerTest`
  `refusesACaseworkerWithoutTheRole[JUDICIAL]` (a `refusalFor(role)` helper), `CaseworkerCcdAttemptControllerTest.judgeRefused`,
  and `CaseworkerGazetteControllerTest.refusesAuthenticatedNonDeliveryManagers`, split into the staff case
  (`ACCESS_GAZETTE`) and `refusesAuthenticatedNonStaff` (civil, judicial).
- `GazettePreviewApiTest.savedReadyQueryCannotBypass…`: runs over `refusedStaffClaims()`, the refused claims the
  staff area admits. Non-staff claims cannot read the saved query or the case list at all.
- `CcdLinkingScenariosTest`: its one userinfo serves the system user and the case-list caller, so it adds
  `caseworker-deedpoll`.
- `CallerAccessSecurityTest`: `flagsIgnoreTheReportMode` is about report mode, so the class pins
  `deedpoll.security.staff-gate=report`.

### Task 2: Remove the chart's per-environment mode, and pin that

- [ ] Failing test first: in `src/test/java/uk/gov/hmcts/reform/deedpoll/conformance/DeploymentChartConformanceTest.java`,
      replace `prodEnforcesTheStaffGate` with a test that **no** values file sets `STAFF_GATE_MODE`: `values.yaml`,
      `values.aat.template.yaml` and `values.preview.template.yaml` (use the class's existing readers). Its
      `@DisplayName` says why: the application's `enforce` default applies everywhere, and setting it in a chart file
      would let one environment drift back to `report`.
- [ ] Delete the `STAFF_GATE_MODE` line and its three-line comment from `charts/deed-poll-api/values.yaml`.
- [ ] `charts/deed-poll-api/Chart.yaml`: `version: 0.0.34`.
- [ ] Run the validation commands **in the foreground**.
- [ ] Mark completed

### Task 3: Say so in the release notes

- [ ] `docs/STAFF-ROLE-RELEASE.md`: after the paragraph ending "Gazette capability enforcement does not depend on
      that mode.", add one or two sentences: since 2026-11-09 the gate enforces by default in every environment
      (chart 0.0.34); `STAFF_GATE_MODE=report` is a rollback setting only, set by a flux PR, and plan 01 Task 12
      removes it.
- [ ] `git grep -n "STAFF_GATE_MODE\|staff-gate" -- ':!build'` and read every hit: none may still say that a non-prod
      environment reports, or that report is the default. Fix any that does, in the same words as above.
- [ ] Run the validation commands **in the foreground**.
- [ ] Mark completed

### Task 4: Full validation, counted

- [ ] Run the validation commands once more **in the foreground**, then count from the XML, not Gradle's summary:
      `build/test-results/test` and `build/test-results/integration` suites, tests, failures, errors and skipped.
      Failures and errors must be 0. Record the counts here beside the baseline below; the `test` count goes up by
      exactly the new tests, and nothing else changes.
- [ ] `git diff --stat origin/master` names only: `application.yaml`, `values.yaml`, `Chart.yaml`,
      `StaffGateReportModeTest.java`, `StaffGateDefaultModeTest.java`, `DeploymentChartConformanceTest.java`,
      `STAFF-ROLE-RELEASE.md`, this plan, and any test fixed under Decision 5 (listed in Task 1).
- [ ] Baseline (untouched master `4c41025`): `test` 5,465 tests across 589 suites (0 failures, 0 errors, 1 skipped); `integration` 30 across 5 (0/0/0). Gradle build exit 0 with the no-checkstyle init script; `checkstyle.sh` OK.
- [ ] Mark completed

## Deliberately not in this plan

- Deleting report mode (plan 01 Task 12, about two weeks after this is live in AAT with no rollback).
- What the report line logs. It records the JWT `sub`, which in HMCTS IDAM is the email address. After this plan it
  fires only after a deliberate rollback to `report`; Task 12 removes it.
- Admin's `/deed-polls` and global gates (plan 01 Tasks 7–8, a separate admin PR).
- Demo (torn down separately).
