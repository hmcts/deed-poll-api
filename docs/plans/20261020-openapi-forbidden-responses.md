# Plan: document authorization refusals in the API contract

## Overview

Document the existing HTTP 403 responses on permission-protected deed-poll API operations, add regression
coverage against the generated OpenAPI document, and refresh admin's copied specification with provenance.
This addresses the Fortify low finding that an operation in `openapi.json` inherits authentication but
omits its 403 response. The report supplied no operation name, instance ID or rule ID; do not invent them.

This is a specification completeness change. Source inspection found controlled authorization-error
handlers; the missing declaration does not establish sensitive-information leakage. Neither implementing
this plan nor passing its tests constitutes a successful Fortify rescan.

## Context

Primary repository: `deed-poll-api`. Companion repository: `deed-poll-admin`.
Paths below are relative to the named repository.

Read each repository's `CLAUDE.md` and applicable `AGENTS.md` before working there. Use isolated worktrees:
the original API clone contains substantial unrelated staged work, and existing worktrees may belong to
other tasks. Do not reset, stash, commit or rebase another agent's changes.

Planning references, not instructions to reset to these commits:

- Local API `origin/master`: `93c2dd370cb4d63f51375da155818ea452dcc9da`.
- Local admin `origin/master`: `649c30e01870abe2b57be43d6c17a02fbfffad72`.
- The admin copy inspected on 19 October was generated from API PR #117's `34d4bd3`, with a relative `/`
  server. Check current merge state and provenance before selecting execution bases. Do not regress this
  server declaration by refreshing from an older API branch.

Relevant API files:

- `src/main/java/uk/gov/hmcts/reform/deedpoll/config/OpenAPIConfiguration.java`
- `src/main/java/uk/gov/hmcts/reform/deedpoll/config/OpenAPIWriteBodies.java`
- `src/main/java/uk/gov/hmcts/reform/deedpoll/config/SecurityConfiguration.java`
- `src/main/java/uk/gov/hmcts/reform/deedpoll/config/StaffArea.java`
- `src/main/java/uk/gov/hmcts/reform/deedpoll/config/StaffAccessDeniedHandler.java`
- `src/main/java/uk/gov/hmcts/reform/deedpoll/api/DomainErrorHandler.java`
- `src/main/java/uk/gov/hmcts/reform/deedpoll/api/ApiError.java`
- `src/test/java/uk/gov/hmcts/reform/deedpoll/config/StaffAccessRulesTest.java`
- `src/test/java/uk/gov/hmcts/reform/deedpoll/api/DomainErrorHandlerTest.java`
- `src/integrationTest/java/uk/gov/hmcts/reform/deedpoll/openapi/OpenAPIPublisherTest.java`

Relevant admin files:

- `libs/deed-poll-api-client/openapi.json`
- `libs/deed-poll-api-client/SPEC-PROVENANCE.md`
- `libs/deed-poll-api-client/src/api/contract.test.ts`

Observed behavior to preserve:

- The specification declares global bearer authentication.
- Caseworker and judicial operations can refuse authorization with 403. Both the staff filter handler
  and domain handler use the application error envelope, with an `error` string.
- `GET /me` accepts any authenticated caller so it can report that caller's lack of permissions.
- Public operations such as `GET /` override global security with `security: []`.
- Some resource-visibility refusals intentionally return 404; documenting 403 must not change those.
- CCD persistence uses a separate security chain. Do not assume its response body matches staff errors
  or add undocumented SDK endpoints to the published contract.

## Execution split (amended 2026-10-20, Fern with Steve)

Ralphex runs **only in the API worktree**: `.worktrees/deed-poll-api-openapi-403`, base `origin/master` `93c2dd3`.
Its task agents must not create or edit anything in deed-poll-admin. **The admin refresh (the original Task 3's
admin half) is done by hand by the operator after the run** (see Post-Completion), from the producer commit and
artefact Task 3 records. So the API tasks end by producing and recording the generated spec, not by copying it.

**API PR #117 is merged** (`21c6740`): master publishes the relative `/` server, and admin's copy was regenerated
from it. API PR #119 (the `/citizen/**` S2S gate) is open, unmerged, and adds no OpenAPI path. Do not base on it,
and do not account for it.

## Development Approach

1. Fix the producer, then regenerate the consumer copy. Do not hand-edit admin's JSON to silence Fortify.
2. Prefer one small application-owned OpenAPI customizer for the existing staff route families, reusing
   existing route classification where practical. Avoid repetitive annotations across dozens of methods
   and avoid a generic response-policy framework or new dependency.
3. Add an explicit `403` response with a meaningful description and the actual JSON error schema.
   A component definition alone is insufficient: affected operations must reference or contain it.
   Resolve schema references completely; register the existing error model using the project's current
   springdoc/swagger tooling. Do not invent a different error envelope.
4. Preserve any existing operation-specific 403 declaration. Respect effective security, including
   operation overrides. Do not equate authentication with an authorization refusal on every operation.
5. Keep changes to documentation generation, tests, and the generated copy/provenance. Preserve security
   rules, runtime handlers, client behavior, success responses, existing error responses/headers,
   operation IDs, request schemas, server declarations and authentication requirements.
6. Scope is 403. Record missing 401 or other response documentation as follow-up work rather than
   expanding this into an error-handling rewrite. If runtime response leakage is discovered, report it
   separately instead of claiming this documentation change fixes it.
7. Complete task validation before proceeding. Local commits are appropriate; this plan does not
   authorize pushes, PR publication, merges, deployments, live authentication calls or a Fortify rescan.

## Validation Commands

Run from the API worktree, `.worktrees/deed-poll-api-openapi-403`, with Java 21 and Docker. **This section was amended
2026-10-20 (Fern, with Steve)**: the commands the plan was written with start red in this devcontainer.
`./gradlew checkstyle*` and a plain `./gradlew build` crash Gradle's Checkstyle worker with SIGILL, which ignores
`JAVA_TOOL_OPTIONS`. The equivalents below were measured green on the base `93c2dd3`: 4,526 unit and 22 integration
tests, and Checkstyle 0 warnings.

```bash
export TESTCONTAINERS_RYUK_DISABLED=true JAVA_TOOL_OPTIONS=-XX:UseSVE=0
./gradlew test --tests '*DomainErrorHandlerTest' --tests '*StaffAccessRulesTest' --no-daemon
./gradlew integration --tests '*OpenAPIPublisherTest' --no-daemon
./gradlew cleanTest build -x checkstyleMain -x checkstyleTest -x checkstyleIntegrationTest -x checkstyleFunctionalTest -x checkstyleSmokeTest --no-daemon
git diff --check
```

Add any new focused customiser test to the first command. If an existing test name has changed, use the equivalent
test and record the substitution.

**Checkstyle runs directly, not through Gradle.** It must report 0 `[WARN]` and 0 `[ERROR]` lines:

```bash
cat > /tmp/cs-cp.gradle <<'GRADLE'
allprojects { tasks.register('printCheckstyleClasspath') { doLast { println "CSCP=" + configurations.checkstyle.asPath } } }
GRADLE
CP="$(./gradlew -q --no-daemon -I /tmp/cs-cp.gradle printCheckstyleClasspath | grep '^CSCP=' | cut -d= -f2-):$(find ~/.gradle/caches/modules-2 -name 'commons-logging-1.*.jar' | sort | tail -1)"
java -XX:UseSVE=0 -cp "$CP" com.puppycrawl.tools.checkstyle.Main -c build/config/checkstyle/checkstyle.xml \
  src/main/java src/test/java src/integrationTest/java src/functionalTest/java src/smokeTest/java | grep -E '^\[(WARN|ERROR)\]' ; echo "checkstyle done"
```

- **Count results from `build/test-results/{test,integration}/*.xml`, not from the exit code.** The `cleanTest` stops a
  second build from reporting UP-TO-DATE and running nothing.
- **Known flake:** `notify/CorrespondenceReadTest.preSendFailure…[tokenFailure=false]` failed once in three full
  builds on 10-19, and passes alone. If it is the only failure, re-run it alone and record that. Don't "fix" it.
- **Run every Gradle command in the foreground with a 600000 ms timeout. Never background a build.** Check
  `pgrep -f 'gradlew|GradleWorkerMain'` first, and wait if another is running.
- **Use full Spring context tests for security boundaries,** not a `@WebMvcTest` slice, which silently omits the
  security configuration.
- **The admin checks are not part of this run** (see "Execution split" below).

For spec generation, `OpenAPIPublisherTest` honours `OPENAPI_OUTPUT_PATH`. Use a unique absolute path in an existing
directory, for example `OPENAPI_OUTPUT_PATH=/tmp/openapi-403/openapi-<commit>.json`. Never reuse a stale artefact.
Record the producer commit and raw SHA-256.

## Implementation Steps

### Task 1: Establish the route and response contract

- [x] Confirm you are in the API worktree `.worktrees/deed-poll-api-openapi-403` on base `93c2dd3`, and record it in
      Execution notes. The admin worktree is the operator's (see "Execution split").
- [x] Confirm the generated spec on this base still publishes the relative `/` server (API #117, merged as `21c6740`),
      and keep it so: no change here may alter `servers`.
- [x] Inventory generated operations, their effective security and existing responses. Record the set
      requiring 403 and the reason for each exception, especially public operations and `GET /me`.
      Do not assume historical operation counts still apply.
- [x] Trace the relevant filter-chain and domain refusals to confirm status, content type and response
      fields. Review existing runtime tests; extend only where needed to prove representative staff and
      domain 403 responses use the documented shape without stack traces or exception details.
      Configure enforcement explicitly in tests if the current staff gate defaults to report mode.
- [x] Run the existing focused checks to establish a baseline. Add failing generated-spec assertions for
      the missing 403 contract, including nonempty operation coverage and the documented exceptions.

### Task 2: Publish accurate 403 responses

- [ ] Implement the small OpenAPI customization described above, selecting applicable secured operations
      and preserving existing declarations. Reuse the actual error model; ensure every reference resolves.
- [ ] Make generated-spec tests assert description, JSON content type and schema for every applicable
      operation. Resolve response/schema references rather than accepting the mere presence of a 403 key.
- [ ] Assert that `GET /me` and public operations do not gain an invented permission refusal and that
      public security overrides remain intact. Ensure new staff operations are covered automatically.
- [ ] If using a customizer, add focused tests for preservation of an existing explicit 403 response and
      honoring an explicit public security override, without adding artificial production routes.
- [ ] Verify existing successes, request contracts, 404 semantics, other errors and headers are unchanged.
      Run API focused checks and record the red/green evidence.

### Task 3: Generate the refreshed specification (API side only)

- [ ] Commit the API producer change locally, generate a fresh spec from that exact commit with a unique
      `OPENAPI_OUTPUT_PATH`, and record the commit, output path, command, raw SHA-256 and operation/path/schema counts.
- [ ] Generate the baseline spec from `93c2dd3` the same way, so the comparison is between two fresh artefacts.
      Compare the parsed documents and explain every difference. The intended delta is the 403 declarations plus the
      reusable response and error schemas they need. Account separately for any upstream drift rather than weakening
      tests.
- [ ] Record, for the operator's admin refresh: the producer commit, the artefact path and hash, the list of
      operations that gained a 403, the new or changed component names, and what admin's `contract.test.ts` must
      now accept (for example, a new error schema in its schema inventory). Do not edit deed-poll-admin.

### Task 4: Final validation and review handoff

- [ ] Run the API final gates (Validation Commands), review the API diff and confirm no runtime authorization or
      error-handling behaviour changed. Record test counts, skips, failures and any limits on the evidence.
- [ ] Prepare Fortify assessment text distinguishing the confirmed documentation omission from an
      unproven information leak. State that scanner closure awaits a rescan of the refreshed artifact.
- [ ] Record the API final commit, changed files and the producer facts the admin refresh needs in Execution notes.
      Leave the implementation ready for the normal Ralphex review phases, which the operator runs separately.
      Do not mark reviews or a Fortify retest complete unless they actually ran.

## Acceptance Criteria

- Every published operation that can return the scoped staff/domain authorization refusal documents 403
  with a description and an accurate, resolvable response schema.
- Public operations and `GET /me` retain their intended security and response contracts.
- Existing runtime authorization, confidentiality-preserving 404s and other response contracts are unchanged.
- API regression tests cover the generated document, and admin checks the actual refreshed copy.
- Admin provenance identifies the exact producer source and reproducible generation evidence.
- Both repositories pass applicable validation, or any blocker is explicitly recorded rather than hidden.
- Fortify retest, publication and deployment remain separately identified follow-up actions.

## Execution notes

Planning only, 2026-10-20. No implementation, new tests, Ralphex execution, publication or rescan has been
performed by creating this plan.

**Baseline, 2026-10-20 (Fern, before the run).** API worktree `.worktrees/deed-poll-api-openapi-403`, branch
`fix/openapi-forbidden-responses`, base `origin/master` `93c2dd3`. Focused unit (`DomainErrorHandlerTest`,
`StaffAccessRulesTest`): 20 tests, 0 failed. `OpenAPIPublisherTest`: 19 tests, 0 failed, writing
`/tmp/openapi-403/baseline-93c2dd3.json`. That baseline spec has **55 operations, 39 paths, 58 schemas, `servers: [{url: "/"}]`,
and no operation declares 403**. Full build on the same commit (10-19): 4,526 unit + 22 integration, Checkstyle 0.
**No upstream drift (corrected 2026-10-20 08:40):** admin `origin/master` `649c30e` (admin #195, merged 07:49Z) already
carries a copy parsed-identical to this baseline (55/39/58). The earlier "52/37/56" figure was an out-of-date reading.
So the intended admin delta is only this plan's 403 changes.

**Task 1, 2026-10-20 (Ralphex iteration 1).**

- Worktree `.worktrees/deed-poll-api-openapi-403`, branch `fix/openapi-forbidden-responses`, `merge-base HEAD origin/master`
  = `93c2dd370cb4` (HEAD was the plan commit `39eaa3c` on top of it). No admin file was touched.
- `servers` on this base is exactly `[{url: "/", description: "Same origin as the served API document"}]`, global
  `security: [{bearerAuth: []}]`. `publishesSameOriginServersForHttpAndHttpsDocuments` already guards it and passes.
- **Inventory (fresh spec from the working tree, parse-identical and byte-identical to `baseline-93c2dd3.json`, SHA-256
  `dba83e5e…1fac4b`):** 55 operations on 39 paths. 53 are inside a `StaffArea` (45 `/caseworker/**`, 8 `/judicial/**`),
  every one inheriting global bearer security with no operation override, and **none declares 403**. Existing non-2xx
  responses are only 409/429/502/503 on six operations, all description-only except the two Gazette POSTs' 409/503,
  which carry `GazetteError` under `*/*`.
  `ApiError`/`FieldError` are not published as schemas. Components hold only `schemas` and `securitySchemes`.
- **Requires 403: all 53 staff operations.** Each can be refused by the chain (judicial area always; caseworker area via
  `StaffGate` in `enforce`, which is the code default though `application.yaml` defaults `STAFF_GATE_MODE` to `report`),
  and by domain capability/role checks (`ForbiddenError`). The spec describes the contract in enforce mode, so the
  report-mode yaml default does not make the 403 inaccurate. **Exceptions:** `GET /` has `security: []` (public, in
  `PUBLIC_PATHS`); nothing can refuse it. `GET /me` is `ANY_AUTHENTICATED_PATHS`, admits every authenticated caller by
  design, and `CallerAccessController` throws no `ForbiddenError`. `/ccd-persistence/**` and `/callbacks/**` are not in
  the published document (separate chain / `denyAll`, SDK packages not scanned) and are out of scope.
- **Trace.** Chain: `LoggingAccessDeniedHandler` -> `StaffAccessDeniedHandler`: 403, `Content-Type: application/json`,
  body `ApiError.of(StaffArea.refusal())` = `{"error": "Requires one of the following roles: ..."}` (non-staff path:
  `"Access is denied"`). Domain: `DomainErrorHandler.forbidden` -> 403, `ResponseEntity<ApiError>` (JSON),
  `{"error": <domain sentence>}`. `ApiError` is `@JsonInclude(NON_EMPTY)`, so `errors` is omitted when empty; both
  refusals carry only `error`. Neither path writes a trace, exception class or framework message.
- **Runtime tests extended (enforce set explicitly where it matters):** `StaffAccessRulesTest.refusesARolelessCaller`
  (already `staff-gate=enforce`) and `CaseworkerNoteControllerTest...refusesACaseworkerWithoutTheRole` (domain
  `Requires capability: DELETE_NOTE`) now also assert a JSON content type and that the body has exactly one key.
  Focused unit run (`DomainErrorHandlerTest`, `StaffAccessRulesTest`, `CaseworkerNoteControllerTest`): 66 tests, 0 failed.
- **Red spec assertion added:** `OpenAPIPublisherTest.documentsTheForbiddenResponseOnEveryStaffOperation` selects
  operations by `StaffArea.of(path)` (so new staff routes are covered automatically), requires each to inherit global
  security and to declare a 403 whose `$ref`s resolve to a non-blank description, `application/json` only, and a schema
  with exactly `error` (string) and `errors` (array of `field`/`message`); requires the operations without 403 to be
  exactly `GET /` and `GET /me`, `GET /` to keep `security: []`, and `/me` to have no override. With failFast lifted by
  an init script (`afterEvaluate { failFast = false }` on `integration`): **20 tests, 19 passed, 1 failed — the new one,
  `[GET /caseworker/cases documents 403]`**. That is the intended red for Task 2. Direct Checkstyle: 0 WARN, 0 ERROR
  (its config is only extracted by running a Gradle checkstyle task once; `checkstyleTest` was run for that and crashed
  as documented). `git diff --check` clean.

Use the workspace `scripts/cft-ralphex` launcher and the operator's selected review profile; do not rewrite
shared Ralphex configuration. The primary execution repository is the isolated API worktree. The admin
companion worktree must be accessible to that execution environment and reviewed separately.

The executor should append baseline/final commits, worktree paths, operation inventory and exceptions,
red/green evidence, generation provenance, semantic comparison, review outcomes and remaining actions here.

## Post-Completion (operator, outside the Ralphex run)

1. Ralphex `-r` review of the API diff against `93c2dd3`, plus a direct scoped review if the external pass is quick.
   Rerun the API validation after any review fix, and regenerate the spec if producer output changed.
2. **Admin refresh**, in a separate admin worktree from current admin `origin/master`:
   - refresh `libs/deed-poll-api-client/openapi.json` from the recorded artefact, applying only repository formatting;
   - update `SPEC-PROVENANCE.md` with the source commit, generation evidence, semantic delta and pending-retest status;
   - extend `contract.test.ts` to check the copied 403 declarations and resolvable error schemas, preserving the `/me`
     and public exceptions, and accounting explicitly for any new error schema without weakening business-schema checks;
   - validate with `yarn vitest run libs/deed-poll-api-client/src/api/contract.test.ts`, `yarn lint`, `yarn build`,
     `npx turbo test test:app --concurrency=1`, and `yarn format` with its diff inspected;
   - review the admin diff separately. An API review does not cover it.
3. Publication, merging, deployment and the Fortify rescan are separate actions, each needing Steve's go-ahead.
