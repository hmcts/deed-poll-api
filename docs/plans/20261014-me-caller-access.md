# Plan: `GET /me`, the caller's areas and capabilities for the admin app

## Overview

The admin app decides what to show from roles it reads out of IDAM userinfo at login. Since api#103, the api can
also take a caller's roles from AM, and on 2026-10-13 Steve decided that IDAM becomes sign-in only. Admin therefore
can no longer work out from IDAM claims what a caller may do. This plan adds one read-only endpoint that tells
admin exactly what the api will enforce for the caller:

```json
{ "staff": true, "judicial": false, "capabilities": ["OVERRIDE_PRIVACY", "DELETE_NOTE", "ACCESS_GAZETTE"] }
```

Admin will call it at login and on each token refresh, and render from it without working anything out itself.
That's the `SavedQueryResponse.editable` precedent. The admin change is a separate plan that comes after this one
merges.

Nothing about who may do what changes here. `Capability`, `RoleCapabilities`, `Role`, `StaffArea` membership and
`StaffGate` are read, never edited.

## Context

- Decisions taken with Steve on 2026-10-14, one question at a time:
  - A: admin asks the api, rather than calling RAS itself or staying on IDAM claims
  - shape 1: two area flags plus capabilities
  - fetched at login and on refresh
  - `GET /me` outside both staff areas
  - the area flags reflect roles, not the StaffGate mode
  - no identity fields
- `domain/auth/Capability.java` has six values; `RoleCapabilities.forRoles` resolves them. `CurrentUser.can` and
  `require` use the same resolution.
- `config/StaffArea.java`:
  - `CASEWORKER` (`/caseworker`) admits `CASEWORKER`, `TEAM_LEADER`, `DELIVERY_MANAGER` and `DEEDPOLL_ADMIN`
  - `JUDICIAL` (`/judicial`) admits `JUDICIAL`
  - `admits(authorities)` is the predicate `StaffGate` and the judicial rule use
- `config/SecurityConfiguration.securityFilterChain`:
  - `PUBLIC_PATHS` are `permitAll`
  - `/judicial/**` is `hasAnyAuthority`
  - `/caseworker/**` goes through `StaffGate` (default mode `report`)
  - then `anyRequest().authenticated()`
- `config/StaffAccessRulesTest.everyRouteIsInAStaffArea` fails for any mapped route outside a staff area apart from
  `/`, with the message "add it to StaffArea or the allowlist". That's the OWASP H1 hardening. `/me` must go through
  an explicit, tested allowlist. **Do not loosen the check.**
- `IdamAuthenticationConverter` (#103) builds both the `CurrentUser` and the `IdamAuthentication` authorities from the
  IDAM roles unioned with the AM roles. So anything read from either already includes AM.
- Controllers read the caller with `CurrentUsers.required()` and document operations with `@Operation(operationId,
  summary, description)`. Copy `CaseworkerGazetteSettingsController`.
- `config/NamespaceSeparationSecurityTest.theNamespacesAreIndependent` slices `api.(*)..`, so `api.caller` becomes a
  fourth slice. It must not depend on `api.caseworker`, `api.judicial` or `api.citizen`. It only needs
  `domain/auth`, `config/StaffArea` and `auth/CurrentUsers`.
- `config/StaffGateReportModeTest` proves report mode admits a roleless caller with
  `GET /caseworker/no-such-route` → 404 (it passed the gate). Use the same probe.

## Decisions: implement these, do not revisit

1. **Route.** `GET /me`, `operationId = "getCallerAccess"`, in a new `api/caller/{controller,dto}` package
   (`CallerAccessController`, `CallerAccessResponse`). It's one operation on one path, with no request body and
   no parameters.
2. **Response.** `CallerAccessResponse(boolean staff, boolean judicial, List<Capability> capabilities)`, serialised
   as exactly those three properties, all required and never null. `capabilities` is in `Capability` declaration
   order with no duplicates, so the same caller always gets the same bytes. In the OpenAPI schema, `capabilities`
   is an array of a string enum listing every `Capability` value.
3. **Area flags reflect roles, whatever the StaffGate mode.** `staff = StaffArea.CASEWORKER.admits(authorities)` and
   `judicial = StaffArea.JUDICIAL.admits(authorities)`, where `authorities` are the authenticated
   `IdamAuthentication`'s. That's the same predicate the gate uses. In `report` mode a roleless caller still reaches
   `/caseworker/**`, but `/me` says `staff: false`. That's intended: admin is right for the day `enforce` arrives,
   and it errs on the strict side.
4. **Capabilities.** `RoleCapabilities.forRoles(CurrentUsers.required().roles())`. It's the same answer
   `CurrentUser.can` gives, and AM roles are already in `roles()`.
5. **Who may call it.** Any authenticated IDAM token gets 200. A caller with no deed-poll role gets
   `{ "staff": false, "judicial": false, "capabilities": [] }`, not 403, so admin can say "signed in, but no access"
   without guessing. Unauthenticated calls get 401, as everywhere else. The answer reveals nothing the caller
   couldn't work out from their own token.
6. **No identity.** No subject, name, email, raw role names, AM assignments, or anything about whether a role came
   from IDAM or AM. Admin already has identity from IDAM. (The Gazette release review's F1 is about a subject
   leaking through attribution, so don't add a second route for that.)
7. **Not cacheable.** The response carries `Cache-Control: no-store`. It's per-user authorisation state.
8. **Security wiring is explicit.**
   - `SecurityConfiguration` gets a named `ANY_AUTHENTICATED_PATHS = { "/me" }`, matched with `.authenticated()`
     ahead of `anyRequest()`. The behaviour is identical, but the intent is stated.
   - `StaffAccessRulesTest.everyRouteIsInAStaffArea` accepts a route in `ANY_AUTHENTICATED_PATHS`.
   - A new test asserts the set is exactly `{"/me"}`, so the next addition is a reviewed change.
   - The CCD persistence chain and `PUBLIC_PATHS` are unchanged. `/me` is **not** public.
9. **No new cost beyond one request.** There's no new cache (the repo's `IdamUserInfoClient` rule). A `/me` call costs
   what any authenticated call already costs: one userinfo call, plus one RAS call when AM is enabled.
10. **Out of scope:**
    - the admin app
    - the process-config screen (session `80`'s plan, later)
    - any new `Capability`
    - the CCD system user's behaviour (it gets whatever its roles say)
    - Gazette

## Development Approach

- Each task: implement, add tests, run the validation commands, commit.
- Run every Gradle command in the foreground only. Never background a build: a backgrounded build ends the
  session, and the restart builds over it.
- Follow the repo's comment and Javadoc style. Doc comments say why, as the auth and config classes do.
- Don't edit `Capability`, `RoleCapabilities`, `Role`, `StaffArea`'s membership or `StaffGate`. If a test seems to
  need it, stop and record why in Execution notes.

## Validation Commands

Run from this worktree with Java 21 and Docker available. Green on the starting commit (`22f3d7f`, #103's merge).
Its code is identical to `7bc10c3`, measured 2026-10-14: build 4,150 unit + 19 integration tests.

```bash
export TESTCONTAINERS_RYUK_DISABLED=true JAVA_TOOL_OPTIONS=-XX:UseSVE=0
./gradlew test --tests '*StaffAccessRules*' --tests '*RoleCapabilities*' --tests '*CurrentUser*' --tests '*SecurityConfiguration*' --no-daemon
./gradlew test --tests '*Security*' --tests '*Staff*' --tests '*Conformance*' --tests '*OpenAPI*' --no-daemon
./gradlew build --no-daemon
git diff --check
```

From Task 1 on, add `--tests '*CallerAccess*'` to the first selector. It matches nothing before Task 1, and Gradle
fails with "No tests found". If Checkstyle's Gradle worker dies with SIGILL or SIGABRT, run Checkstyle directly over
main, test, integrationTest, functionalTest and smokeTest, as recorded in `20261014-am-role-source.md`'s Execution
notes. It must report **0 warnings**, because the CNP build fails on warnings. Record actual test counts, read from
`build/test-results`, for every command.

## Implementation Steps

### Task 1: The endpoint, its response and the explicit allowlist

Files: `api/caller/controller/CallerAccessController.java`, `api/caller/dto/CallerAccessResponse.java`,
`api/caller/{controller,dto}/package-info.java`, `config/SecurityConfiguration.java`,
`config/StaffAccessRulesTest.java`, new `api/caller/dto/CallerAccessResponseTest.java`.

- [x] Add `CallerAccessResponse` with a static factory taking the authorities and the `CurrentUser`, applying
      decisions 2–4. Javadoc says why there's no identity (decision 6) and why the flags ignore the gate mode
      (decision 3).
- [x] Add `CallerAccessController`: `GET /me`, `operationId = "getCallerAccess"`, `Cache-Control: no-store`, and an
      `@Operation` description saying any authenticated caller may call it and what an empty answer means.
- [x] Add `ANY_AUTHENTICATED_PATHS` to `SecurityConfiguration` with `.authenticated()` ahead of `anyRequest()`
      (decision 8), and update the class Javadoc that says every route is in a staff area.
- [x] Update `StaffAccessRulesTest.everyRouteIsInAStaffArea` to accept `ANY_AUTHENTICATED_PATHS`, and add a test that
      the set is exactly `{"/me"}`.
- [x] Unit tests for `CallerAccessResponse` covering:
  - each `Role` alone, with the expected flags and capabilities
  - Team leader plus judicial together
  - an empty role list
  - capabilities in declaration order with no duplicates (for example, Team leader plus Delivery Manager)
  - legacy `DEEDPOLL_ADMIN` getting Team leader's set and `staff: true`
- [x] Mark completed

### Task 2: Prove it through the real filter chain

Files: new `config/CallerAccessSecurityTest.java` (copy `RoleAssignmentSecurityTest`'s stub IDAM and WireMock RAS
set-up) and new `config/CallerAccessOpenApiTest.java` (booting the whole context, as `CLAUDE.md` requires for a
controller test).

- [x] HTTP tests with stub IDAM:
  - a caseworker, a Team leader, a Delivery Manager, a legacy admin, a judge and a roleless token each get the
    expected JSON
  - no token gets 401
  - the response has exactly the keys `staff`, `judicial` and `capabilities`
  - `Cache-Control` contains `no-store`
- [x] With `deedpoll.security.staff-gate=report` (the default): a roleless caller gets `staff: false` from `/me`, and
      the same caller's `GET /caseworker/no-such-route` is 404, so the gate admitted them. Assert both, so decision 3
      is pinned.
- [x] With `deedpoll.roles.am.enabled=true`, an IDAM-only `caseworker-deedpoll` caller and WireMock RAS:
  - a `ctsc-team-leader` + Gazette-skill assignment gives `ACCESS_GAZETTE`
  - RAS answering 500 gives the IDAM-only answer
- [x] OpenAPI: `/v3/api-docs` has `GET /me` with `operationId` `getCallerAccess`, the three required properties, and
      a `capabilities` item enum equal to `Capability.values()`. That's the drift guard for admin's spec refresh.
- [x] Mark completed

### Task 3: Documentation and handoff

Files: `CLAUDE.md`, `README.md`, this plan's Execution notes.

- [x] Add `api/caller/{controller,dto}` to `CLAUDE.md`'s Layout table, with one line on why it sits outside both
      staff areas. Add to the security rules section: the allowlist, decision 3 and decision 6.
- [x] `README.md`: one line on `GET /me` where the other surfaces are listed.
- [x] Record the validation counts, the Checkstyle result and the branch SHA in Execution notes. Don't claim any
      live verification.
- [x] Mark completed

## Execution notes

**Baseline, 2026-10-14 on `22f3d7f`** (code identical to `7bc10c3`, which was measured):
- `./gradlew build` with the Checkstyle tasks excluded: 4,150 unit and 19 integration tests, 0 failures, 0 skipped
- direct Checkstyle: 0 warnings
- first selector (without `*CallerAccess*`): 63 tests, 0 failures
- second selector: 589 tests, 0 failures

**Task 1** (`CallerAccessController`, `CallerAccessResponse`, `api/caller` package docs, `SecurityConfiguration`,
`StaffAccessRulesTest`, `CallerAccessResponseTest`, `OpenAPIPublisherTest`):
- `RoleCapabilities.forRoles` returns `Set.copyOf`, which has no order, so the response re-reads `Capability.values()`
  to get declaration order
- the controller reads authorities from the `SecurityContextHolder` after `CurrentUsers.required()`, so an
  unauthenticated call still fails with that method's explanation
- `OpenAPIPublisherTest.documentsNoUnlistedPath` (integration) refused `/me` as a path outside both surfaces; `/me`
  was added to its known list with the reason, the same deliberate act its Javadoc asks for
- first selector (with `*CallerAccess*`): 73 tests, 0 failures
- second selector: 590 tests, 0 failures
- `./gradlew build` failed only in `checkstyleFunctionalTest` (worker SIGILL/SIGABRT, exit 134); with the Checkstyle
  tasks excluded: 4,160 unit and 19 integration tests, 0 failures, 0 skipped
- Checkstyle run directly over all five source sets: 0 warnings (one long Javadoc line found and fixed first)
- `git diff --check`: clean

**Task 2** (`CallerAccessSecurityTest`, `CallerAccessOpenApiTest`):
- one context for the security test: AM on, the gate mode left unset so the report-mode probe runs on
  `application.yaml`'s default, and RAS answering 404 (no assignments) unless a test stubs otherwise, so the role
  cases are IDAM's word alone
- the legacy admin is stubbed by its claim value rather than `Role.DEEDPOLL_ADMIN`, which is deprecated and would add
  a deprecation note
- body keys are read through `JsonNode.properties()`, because Jackson 2.21 deprecates `fieldNames()`
- the OpenAPI test follows a `$ref` if springdoc emits one, so it holds whether the enum is inlined or a component
- first selector (with `*CallerAccess*`): 85 tests, 0 failures (12 new: 11 security, 1 OpenAPI)
- second selector: 601 tests, 0 failures
- `./gradlew build` failed only in `checkstyleFunctionalTest` (worker SIGABRT, exit 134); with the Checkstyle
  tasks excluded: 4,172 unit and 19 integration tests, 0 failures, 0 skipped
- Checkstyle run directly over all five source sets: 0 warnings (one long line found and fixed first)
- `git diff --check`: clean

**Task 3** (`CLAUDE.md` Layout row and a `GET /me` security section, one `README.md` paragraph), measured on
`feat/me-capabilities` at `5df1039` plus this documentation-only commit:
- first selector (with `*CallerAccess*`): 85 tests, 0 failures
- second selector: 601 tests, 0 failures
- `./gradlew build` failed only in `checkstyleFunctionalTest` and `checkstyleIntegrationTest` (worker SIGABRT,
  exit 134); with every Checkstyle task excluded: 4,172 unit and 19 integration tests, 0 failures, 0 skipped
- Checkstyle 10 run directly over main, test, integrationTest, functionalTest and smokeTest with
  `build/config/checkstyle/checkstyle.xml`: 0 warnings
- `git diff --check`: clean
- review fixes landed at `8024f97` after the documentation commit `3a86707`; the counts above were not re-measured
- no live verification: nothing was called in any deployed environment, and admin has not consumed `/me` yet

**Direct external review and fix, 2026-10-15 (Wren):** the Ralphex external review returned NO ISSUES FOUND in under
two minutes against a stale local `master` (a 321-file diff), so it was not counted. A direct read-only Copilot
GPT-6 Astra review scoped to `22f3d7f..1e40ae4` read 34 files in full (record: `local-notes/deed-poll-api/me-direct-*`).
It found one medium issue: the H1 exemption pinned the path `/me` but not its method, so a later
`@PostMapping("/me")` would pass every guard. Fixed by:
- `StaffAccessRulesTest.theAllowlistedPathIsOneGet`, which requires exactly one handler on `/me`, answering GET only.
  Mutation-checked: adding `@PostMapping("/me")` fails this test alone (`Expected size: 1 but was: 2`).
- `CallerAccessOpenApiTest`, which now requires `/me` to publish only `get`.
- `SecurityConfiguration`, which matches the allowlist as `HttpMethod.GET` (intent only; the tests are the guard).
- `StaffAccessRulesTest.meAnswersARolelessCallerUnderEnforce`, covering decision 3 under `enforce` too. The 401 test
  now also asserts `no-store`.

Measured after the fix: first selector 87, second 604, `./gradlew build` (Checkstyle tasks excluded) 4,175 unit
and 19 integration tests, 0 failures, 0 skipped; direct Checkstyle 0 warnings; `git diff --check` clean.

## Post-Completion

Not Ralphex checkboxes:
- open the PR; tell Gale (`cft-workspace-8b`) when it merges
- the admin plan: rebase `feat/team-leader-delivery-manager`, replace `capabilitiesFor(roles)` with `/me`'s answer
  in the session at login and refresh, key the Gazette gate and the main-list Gazette count on `ACCESS_GAZETTE`,
  and refresh admin's api spec
- session `80`'s process-config plan, which may add a `Capability`; `/me` picks it up with no change
