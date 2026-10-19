# Plan: an inbound S2S gate for the citizen namespace, ahead of its endpoints

## Overview

`/citizen/**` is the namespace `deed-poll-web` will call (`api/citizen/controller/package-info.java`). It has no
endpoints yet. Today any request there falls through to `securityFilterChain`'s `anyRequest().authenticated()`,
which admits **any IDAM bearer token**, a caseworker's included, and requires no service identity.

`fees-and-pay-1-ways-to-pay-card.md` decision 5 and its Task 1 already require a separate, service-only chain for
`/citizen/**` that accepts only trusted callers and refuses an IDAM token on its own. That plan is held behind its
Task 0 stop gates, which are external Fees & Pay decisions. **This plan delivers that one boundary now**, so the first
citizen endpoint, whenever it lands, is protected by where it lives rather than by somebody remembering.

Why now: Fortify `4AB8588B32600D075862F56DBB4F5934` was marked Not an Issue on the basis that the API will validate
`deed-poll-web`'s `ServiceAuthorization` token. This makes that true before there is anything to call.

No endpoint, DTO, database change, OpenAPI path or chart change is added.

## Context

- `config/SecurityConfiguration.java` has two chains:
  - `ccdPersistenceFilterChain` (`@Order(1)`), matched by
    `PathPatternRequestMatcher.withDefaults().matcher(CCD_PERSISTENCE_PATH)`. It builds the S2S library's
    `ServiceAuthFilter` around `new ServiceAuthTokenValidator(serviceAuthorisationApi)` with the constant allowlist
    `CCD_PERSISTENCE_CALLERS`, adds it before `BearerTokenAuthenticationFilter`, then requires an IDAM user.
  - `securityFilterChain` (no `@Order`, so last), which matches everything else. Its Javadoc says
    `anyRequest().authenticated()` "stays as the fallback for the empty citizen namespace".
  **Read `ccdPersistenceFilterChain` and its Javadoc first: the new chain is its service half, without the user half.**
- `ServiceAuthFilter` answers a missing or unverifiable `ServiceAuthorization` with **401**, and a verified service
  outside its allowlist with **403**, before anything else in the chain runs.
- `config/ServiceAuthConfiguration.java` explains why the library's own filter bean is deliberately not switched on
  (`idam.s2s-authorised.services` is unset), and why the filter is built inside a chain and never declared as a bean
  (Boot would register a `Filter` bean on every path).
- Tests to copy: `config/CcdPersistenceSecurityTest.java` boots the whole context with profiles `config-gen` and
  `test`, no database, `StubIdam` and `auth/StubS2s` as WireMock, and an `@Import`ed stand-in controller to probe a
  path no real controller maps. `StubS2s.token(token, serviceName)` makes a token valid for a service.
- `config/StaffAccessRulesTest.everyRouteIsInAStaffArea` asserts that every mapped route is in a `StaffArea`, is `/`,
  or is in `ANY_AUTHENTICATED_PATHS`.
- `config/NamespaceSeparationSecurityTest` asserts the citizen package shares no DTO with the others. It stays as is.
- A `@WebMvcTest` slice does **not** get these chains (see `SecurityConfiguration`'s Javadoc). Security tests boot the
  whole context.

## Decisions: implement these, do not revisit

1. **Path.** `static final String CITIZEN_PATH = "/citizen/**";` in `SecurityConfiguration`, matched with
   `PathPatternRequestMatcher.withDefaults().matcher(CITIZEN_PATH)`, as the CCD chain matches its path.
   `deed-poll-web`'s contract currently uses `/applications…`. Moving it under `/citizen` is the web cutover's job
   (`fees-and-pay-1` decision 5 is settled on `/citizen/**`), not this plan's.
2. **A new chain, `citizenFilterChain`, `@Order(2)`.** It's after the CCD chain and before `securityFilterChain`,
   which must stay last. The CCD and citizen matchers do not overlap.
3. **Service identity only. No IDAM user.** The citizen is anonymous, so this chain has **no
   `oauth2ResourceServer`** and never reads `Authorization`. An IDAM bearer token alone must get 401 here, not entry.
4. **Allowlist is a constant:** `static final List<String> CITIZEN_CALLERS = List.of("deed_poll_web");`, for the
   same reason `CCD_PERSISTENCE_CALLERS` is a constant (read its Javadoc): it belongs to one path, and widening it is
   a reviewed code change. `deed_poll_api` (this service) is deliberately absent.
5. **The S2S filter is the gate.** Build `new ServiceAuthFilter(new ServiceAuthTokenValidator(serviceAuthorisationApi),
   CITIZEN_CALLERS)` inside the chain, never as a bean, and add it before the authorisation filter. After it, the
   chain's authorisation is `anyRequest().permitAll()`: the request can only reach that point with a verified,
   allowlisted service. **Say so in a comment**, so nobody reads `permitAll()` as an open door. The tests in Task 1
   prove the refusal.
6. **Same hardening as the other chains:** stateless sessions; CSRF, HTTP Basic, form login and logout disabled;
   the error dispatch permitted as `securityFilterChain` permits it. Do not add `RateLimitFilter`: it keys on an IDAM
   `sub`, which this chain has none of, and citizen rate limiting belongs to the endpoints' plan.
7. **Refusals are logged.** Use the same `LoggingAccessDeniedHandler` and logging entry point pattern as the CCD chain
   where they apply. Do not log the token. If `ServiceAuthFilter` answers before Spring's handlers run (it does for
   the CCD chain; see `loggingEntryPoint()`'s Javadoc), record that in the Javadoc rather than adding new logging.
8. **`StaffAccessRulesTest`'s route guard admits `/citizen/**` routes**, as a third accepted case beside staff areas
   and the allowlist, with the assertion message updated. That way, adding the first citizen endpoint doesn't need
   this guard edited, and nothing outside `/citizen/**` gains an exemption.
9. **Update the words that describe the old behaviour:** `securityFilterChain`'s Javadoc ("fallback for the empty
   citizen namespace"), the class Javadoc's "Two chains" paragraph (now three), `api/citizen/controller/package-info.java`
   ("authenticated by default"), and `ServiceAuthConfiguration`'s Javadoc ("The one inbound check").
10. **`fees-and-pay-1`:** in Task 1, tick the "separate service-only security chain" checkbox and append
    "(delivered by `docs/plans/completed/20261019-citizen-s2s-gate.md`)". Change nothing else in that plan.
11. **Out of scope:** any citizen endpoint, DTO, OpenAPI path or security scheme; chart and flux values;
    registering `deed_poll_web` with S2S (already raised as `service-auth-provider-app#1140`); and `deed-poll-web`.

## Development Approach

- Each task: write the failing tests first, implement, run the validation commands, commit.
- **Run every Gradle command in the foreground with a 600000 ms timeout. Never background a build.** Before starting
  one, check `pgrep -f 'gradlew|GradleWorkerMain'`, and wait if another is running.
- Follow `SecurityConfiguration`'s Javadoc style: doc comments say why.
- Don't change `ccdPersistenceFilterChain`'s or `securityFilterChain`'s rules, apart from the Javadoc in decision 9.

## Validation Commands

Run from this worktree with Java 21 and Docker. **Green on the starting commit `93c2dd3`** (run 2026-10-19):
focused run 238 tests in 39 classes; build 4,526 unit tests in 495 classes plus 22 integration tests, 0 failures,
0 errors, 0 skipped; Checkstyle 0 warnings.

```bash
export TESTCONTAINERS_RYUK_DISABLED=true JAVA_TOOL_OPTIONS=-XX:UseSVE=0
./gradlew test --tests '*Security*' --tests '*Staff*' --tests '*ServiceAuth*' --tests '*Namespace*' --no-daemon
./gradlew cleanTest build -x checkstyleMain -x checkstyleTest -x checkstyleIntegrationTest -x checkstyleFunctionalTest -x checkstyleSmokeTest --no-daemon
git diff --check
```

**Checkstyle runs directly, not through Gradle.** Gradle's Checkstyle worker crashes with SIGILL here, and ignores
both `JAVA_TOOL_OPTIONS` and `_JAVA_OPTIONS`. This was measured on the baseline. Run it with Gradle's own resolved
Checkstyle classpath, plus commons-logging, which Gradle normally supplies itself. **It must report 0 `[WARN]` and
0 `[ERROR]` lines:**

```bash
cat > /tmp/cs-cp.gradle <<'GRADLE'
allprojects { tasks.register('printCheckstyleClasspath') { doLast { println "CSCP=" + configurations.checkstyle.asPath } } }
GRADLE
CP="$(./gradlew -q --no-daemon -I /tmp/cs-cp.gradle printCheckstyleClasspath | grep '^CSCP=' | cut -d= -f2-):$(find ~/.gradle/caches/modules-2 -name 'commons-logging-1.*.jar' | sort | tail -1)"
java -XX:UseSVE=0 -cp "$CP" com.puppycrawl.tools.checkstyle.Main -c build/config/checkstyle/checkstyle.xml \
  src/main/java src/test/java src/integrationTest/java src/functionalTest/java src/smokeTest/java | grep -E '^\[(WARN|ERROR)\]' ; echo "checkstyle done"
```

Count results from `build/test-results/test/*.xml` and `build/test-results/integration/*.xml`, not from the exit
code. The `cleanTest` in the build command stops a second run from reporting UP-TO-DATE and running nothing.

## Implementation Steps

### Task 1: The citizen chain, proved in both directions

Files: `config/SecurityConfiguration.java`, a new `config/CitizenSecurityTest.java` (modelled on
`CcdPersistenceSecurityTest`), and `config/StaffAccessRulesTest.java`.

- [x] Write `CitizenSecurityTest` first. Boot the whole context (profiles `config-gen`, `test`, no database), with
      `StubIdam` and `StubS2s`, and an `@Import`ed stand-in controller mapped at `/citizen/stand-in` (GET and POST)
      that echoes 200. Cases:
  - no `ServiceAuthorization` → **401**, and the stand-in is not reached
  - an S2S token `StubS2s` doesn't know → **401**
  - a valid S2S token for a service not in `CITIZEN_CALLERS` (`xui_webapp`, and `deed_poll_api` itself) → **403**
  - a valid `deed_poll_web` token → **200**, for both GET and POST
  - a valid caseworker IDAM bearer token and no S2S → **401** (decision 3)
  - a valid `deed_poll_web` S2S token **plus** a caseworker bearer → **200**, and the stand-in sees no authenticated
    IDAM user (the bearer is ignored, not used)
  - an unknown path under `/citizen/**` with no S2S → **401**, not 404 (authorisation before routing)
  - **Unchanged elsewhere:** `/caseworker/…` with a `deed_poll_web` S2S token and no bearer → 401;
    `/ccd-persistence/…` with a `deed_poll_web` token → 403, its own allowlist; `/health` still public
- [x] Run it and record which cases fail before the chain exists (expect: the S2S-only `deed_poll_web` case and the
      wrong-service cases. Today an IDAM bearer admits and S2S is ignored).
- [x] Implement `citizenFilterChain` per decisions 1–7. Add `CITIZEN_PATH` and `CITIZEN_CALLERS` beside the CCD
      constants, each with a Javadoc saying why.
- [x] In `StaffAccessRulesTest.everyRouteIsInAStaffArea`, admit routes under `/citizen/` (decision 8). Add a test
      that `CITIZEN_CALLERS` is exactly `["deed_poll_web"]`, so widening it is a reviewed change.
- [x] **Mutation check, then restore:** temporarily add `"xui_webapp"` to `CITIZEN_CALLERS` and confirm the 403 case
      fails. Temporarily remove the `@Order(2)` chain bean and confirm the S2S-only and bearer-only cases fail. Record
      both in Execution notes. Restore before committing.
- [x] Run the validation commands. Record counts.

### Task 2: Words and the cross-reference

Files: `config/SecurityConfiguration.java` (Javadoc only), `config/ServiceAuthConfiguration.java` (Javadoc only),
`api/citizen/controller/package-info.java`, `docs/plans/fees-and-pay-1-ways-to-pay-card.md`, `CLAUDE.md`.

- [ ] Update the four Javadoc passages in decision 9 so they describe three chains and the S2S-only citizen boundary.
- [ ] `fees-and-pay-1`: decision 10, and nothing more.
- [ ] `CLAUDE.md`: in the security/auth section, add one short paragraph:
  - `/citizen/**` has its own chain;
  - only `deed_poll_web` S2S tokens are admitted, and IDAM bearers are ignored;
  - the allowlist is a reviewed constant;
  - the first citizen endpoint needs no extra security wiring, but still needs its own rate limiting and
    OpenAPI scheme in the endpoints' plan.
- [ ] Run the validation commands.

### Task 3: Handoff

- [ ] Record in Execution notes: the starting and final commits, the changed files, each task's red and green
      evidence, the mutation checks, and test counts from `build/test-results` after `cleanTest`. Note that the
      Fortify `4AB8588B…` justification depends on this gate.
- [ ] Run the validation commands one last time.

## Execution notes

Planning 2026-10-19 (Fern, with Steve). Base `origin/master` `93c2dd3`. Baseline measured before execution: see
Validation Commands. `./gradlew build` with Checkstyle included fails on the baseline at `checkstyleMain`/
`checkstyleFunctionalTest` (worker SIGILL), an environment fault that the direct Checkstyle run replaces.

Task 1, 2026-10-19:
- Red, before the chain: `CitizenSecurityTest` 12 tests, 8 failed. deed_poll_web S2S-only GET/POST and unprefixed
  gave 401; `xui_webapp` and `deed_poll_api` gave 401, not 403; a caseworker bearer alone gave 200; S2S plus
  bearer gave 200 as the IDAM subject; an unknown path with deed_poll_web gave 401, not 404. `/health` gave 503:
  config-gen has no database, so the case now asserts "not 401/403" (the chain let the probe answer). Passing
  already: no S2S, unverifiable S2S, `/caseworker` and `/judicial` with S2S only, `/ccd-persistence` 403.
- Green: `citizenFilterChain` (`@Order(2)`), `CITIZEN_PATH` and `CITIZEN_CALLERS`. The filter is placed before
  `AuthorizationFilter`, and `permitAll()` carries the comment. The chain has no resource server. Logging handlers
  are set, but the S2S filter answers first.
  `StaffAccessRulesTest` admits `/citizen/` routes and pins `CITIZEN_CALLERS` to `["deed_poll_web"]`.
- Mutation 1, `CITIZEN_CALLERS` plus `"xui_webapp"`: the `xui_webapp` 403 case failed (200), and so did the pin test.
  Mutation 2, `@Bean` removed from `citizenFilterChain`: 7 of 12 failed, including S2S-only (401) and
  bearer-only (200). Both were restored.
- Validation: the focused run had 251 tests in 40 classes; the build had 4,539 unit tests in 496 classes plus
  22 integration tests in 4. All had 0 failures, 0 errors and 0 skipped. Direct Checkstyle reported 0 WARN and
  0 ERROR, and `git diff --check` was clean.

## Post-Completion

Steve runs the ralphex `-r` review separately, because of memory limits. Use `--base-ref 93c2dd3`, so the review diffs
this change rather than an empty or unrelated one. Publishing and merging are Steve's. Move the plan to
`docs/plans/completed/` when the tasks are complete. A later review fix that changes code reruns the validation.
