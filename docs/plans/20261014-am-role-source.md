# Plan: read staff and judicial roles from AM role assignment

## Overview

Deed poll is onboarding to AM, as Possessions did. In the AM model:

- staff hold CTSC job titles in Caseworker Reference Data (CRD), and ORM turns them into organisational role assignments;
- judges (King's Bench Masters) get theirs from Judicial Reference Data (JRD).

IDAM keeps only the sign-in/base role `caseworker-deedpoll` (idam-access-config #1023). This plan makes deed-poll-api
read a signed-in user's organisational roles from the Role Assignment Service (RAS). It maps them onto the
**existing** `Role` values, so #100's capability matrix, the staff gate and every `can`/`require` check stay
unchanged.

Everything ships **switched off** (`deedpoll.roles.am.enabled=false`). Deploying changes nothing until RD/AM onboarding
is live in AAT and the switch is turned on. No live RAS call, deployment or flux change is part of this plan.

## Context

Background is in `../../local-notes/deed-poll-api/am-onboarding-ticket-draft.md` (workspace root, git-ignored). The
cross-service survey this design follows:
- IA (`apps/ia/ia-case-api`): AM roles unioned with IDAM roles into the security context, mapped to an enum, fall
  back to IDAM-only on RAS failure.
- HMC (`apps/hmc/hmc-cft-hearing-service`, `AccessControlServiceImpl`): filters by jurisdiction, roleType and
  expiry.
- PRL: gated by a feature flag.
- PCS (`apps/pcs/pcs-api`, `am/RoleAssignmentApi.java`, `ccd/service/UserRoleService.java`): `GET
  /am/role-assignments/actors/{id}` with the user's token plus S2S, excluding `SPECIFIC` grants.

Relevant files in this repo:
- `auth/IdamAuthenticationConverter.java`: where the caller is resolved once per request.
- `auth/IdamUserInfoClient.java`: its "not cached, deliberately" doc is the caching rule this plan follows.
- `auth/IdamHttp.java`: the `RestClient` construction to reuse.
- `config/ServiceAuthConfiguration.java`: the `AuthTokenGenerator` bean.
- `domain/auth/{Role,CurrentUser,IdamClaims,RoleCapabilities,Capability}.java`.
- `ccd/{CcdCallerAuthenticationConverter,DeedPollRoleMapping,DeedPollCaseType,DeedPollAccessProfile}.java`.
- Tests: `src/test/.../auth/{StubIdam,StubS2s,IdamUserInfoClientTest}.java` show the WireMock style.
  `conformance/DeploymentChartConformanceTest.java` checks chart values.

## Decisions: implement these, do not revisit

1. **AM is a second source of the existing `Role` values**, unioned with the IDAM-derived roles. No new `Role` or
   `Capability`. Mapping (only assignments that pass decision 2):

   | RAS assignment | Role |
   |---|---|
   | `roleName=ctsc` (any `roleCategory`) | `CASEWORKER` |
   | `roleName=ctsc-team-leader` | `TEAM_LEADER` |
   | `roleName=ctsc-team-leader` AND `authorisations` contains the configured Gazette skill | `DELIVERY_MANAGER` |
   | `roleName=judge` or `roleName=fee-paid-judge` | `JUDICIAL` |

   `hmcts-ctsc`, `hmcts-judiciary` and every other name map to nothing. Unknown names are ignored, never an error.
2. **Filter before mapping:**
   - `roleType=ORGANISATION`;
   - `grantType` not `SPECIFIC`;
   - `attributes.jurisdiction` equals the configured jurisdiction (default `DEEDPOLL`);
   - `beginTime` absent or not in the future, and `endTime` absent or in the future.

   The jurisdiction filter is essential: `ctsc` and `ctsc-team-leader` are shared roles, and a Probate team
   leader must not become a deed poll Team leader.
3. **No cross-request cache.** This follows `IdamUserInfoClient`'s documented reason: a cache of authorisation data
   lets a removed role survive. One RAS call per authenticated request, made once in the converter.
4. **Failure falls back to IDAM roles only**, never to extra privilege:
   - RAS unreachable, any non-2xx, or an unreadable body: log a WARN naming the subject's hash (never the token)
     and the status, then continue with the IDAM-derived user.
   - A 404 for an actor with no assignments is treated as an empty list, with no warning.
5. **Switch and settings:** `deedpoll.roles.am.*` in a new `@ConfigurationProperties` record:
   - `enabled` (default false);
   - `url` (default `http://localhost:4096`);
   - `jurisdiction` (default `DEEDPOLL`);
   - `gazette-skill` (default empty).

   With `gazette-skill` empty, AM never yields `DELIVERY_MANAGER`. With `enabled=false` no RAS call is made, and
   the `RestClient` need not be contacted. Environment variables: `ROLE_ASSIGNMENT_ENABLED`, `ROLE_ASSIGNMENT_URL`,
   `ROLE_ASSIGNMENT_JURISDICTION`, `ROLE_ASSIGNMENT_GAZETTE_SKILL`.
6. **The system user never calls RAS.** If the IDAM roles include the configured `deedpoll.ccd.system-role`, skip
   the lookup.
7. **Endpoint and auth:** `GET {url}/am/role-assignments/actors/{subject}`, with `Authorization: Bearer <the caller's
   own token>` and `ServiceAuthorization: <S2S from the existing AuthTokenGenerator>`. Parse only the fields used
   (`roleName`, `roleType`, `grantType`, `beginTime`, `endTime`, `attributes.jurisdiction`, `authorisations`) and
   ignore the rest.
8. **The `/ccd-persistence/**` chain is out of scope.** `CcdCallerAuthenticationConverter` keeps its IDAM-only
   mapping. CCD document access for AM-only users is a later definition change, after RD/AM onboarding is confirmed.
9. **The chart sets nothing new** except `ROLE_ASSIGNMENT_ENABLED: "false"` in `charts/deed-poll-api/values.yaml`.
   AAT's URL is a later flux change.

## Development Approach

- Each task: implement, add tests, run the validation commands, commit.
- Run every Gradle command in the foreground only. Never background a build: a backgrounded build ends the
  session, and the restart builds over it.
- Follow the repo's comment and Javadoc style. Doc comments say why, as the auth classes do.
- Keep the domain transport-independent: the RAS client lives in `auth/`, and the pure mapping lives in
  `domain/auth/`.

## Validation Commands

Run from this worktree with Java 21 and Docker available. All of these are green on the starting commit
(`be3291a`, #100's merge).

```bash
export TESTCONTAINERS_RYUK_DISABLED=true JAVA_TOOL_OPTIONS=-XX:UseSVE=0
./gradlew test --tests '*CurrentUser*' --tests '*IdamClaims*' --tests '*RoleCapabilities*' --tests '*RoleAssignment*' --tests '*IdamAuthentication*' --no-daemon
./gradlew test --tests '*Security*' --tests '*Staff*' --tests '*Conformance*' --no-daemon
./gradlew build --no-daemon
git diff --check
```

`*RoleAssignment*` matches nothing before Task 1. Gradle then fails with "No tests found", so leave that selector
out until Task 1 adds the tests. If Checkstyle's Gradle worker dies with SIGILL, run Checkstyle directly, as
recorded in the repo's earlier plans. Record actual test counts.

## Implementation Steps

### Task 1: Pure mapping from RAS assignments to roles

**Files:** new `domain/auth/OrganisationalRoleAssignment.java` (a record of the parsed fields) and
`domain/auth/AmRoles.java` (the decision 1 and 2 mapping); new `src/test/.../domain/auth/AmRolesTest.java`.

- [x] Add the record and a pure `AmRoles.rolesFrom(List<OrganisationalRoleAssignment>, String jurisdiction, String gazetteSkill, Instant now)` returning an immutable `Set<Role>`.
- [x] Tests for each mapping row. Also test each filter separately: wrong jurisdiction, `CASE` roleType, `SPECIFIC` grant, future begin, past end, null attributes. Plus an unknown name, an empty `gazetteSkill`, the skill on a non-team-leader `ctsc` assignment (yields no Delivery Manager), and duplicates.
- [x] Assert via `RoleCapabilities` that a `ctsc-team-leader` with the skill has every Team leader capability plus `ACCESS_GAZETTE`.
- [x] Mark completed

### Task 2: RAS client and settings

**Files:** new `auth/RoleAssignmentProperties.java` (decision 5) and `auth/RoleAssignmentClient.java` (decision 7);
`application.yaml`; new `src/test/.../auth/RoleAssignmentClientTest.java` (WireMock, in the style of
`IdamUserInfoClientTest`).

- [x] Implement the client with `IdamHttp`'s `RestClient` construction and the existing `AuthTokenGenerator`. It returns parsed assignments, an empty list for 404, and throws a dedicated exception for other failures.
- [x] Add the `deedpoll.roles.am` block to `application.yaml` with the decision 5 defaults and environment variables.
- [x] WireMock tests: both headers are sent, and the path uses the subject; 200 parsing, 404 empty, 500, a malformed body and a connection refusal; extra unknown fields are ignored.
- [x] Mark completed

### Task 3: Union AM roles into the authenticated caller

**Files:** `auth/IdamAuthenticationConverter.java` and the configuration that builds it (find it with
`git grep -n "new IdamAuthenticationConverter"`); `domain/auth/CurrentUser.java` only if a "with extra roles" factory
is needed; tests alongside the converter's existing tests, or a new `IdamAuthenticationConverterTest`.

- [x] When enabled and the caller isn't the system user, fetch, map and union the roles into the `CurrentUser` and `IdamAuthentication` authorities, one RAS call per conversion. Keep identity (subject, name) from IDAM.
- [x] Apply decision 4's fallback and logging. Never log the token or the raw response.
- [x] Tests:
  - disabled makes no RAS call;
  - the system user makes no call;
  - a `ctsc`-only AM user with only `caseworker-deedpoll` in IDAM has `CASEWORKER`;
  - a team leader with the skill can access Gazette;
  - a RAS 500 falls back to IDAM-only roles, and a 404 gives IDAM-only roles with no warning;
  - an IDAM legacy admin plus an AM ctsc user unions correctly.
- [x] Add an HTTP-level test through the real filter chain, with stub IDAM and stub RAS. A `ctsc-team-leader`+skill user reaches a Gazette endpoint. The same user with RAS failing gets 403 there, but still passes the staff gate if their IDAM roles allow it.
- [x] Mark completed

### Task 4: Chart, documentation and handoff

**Files:** `charts/deed-poll-api/values.yaml`, `DeploymentChartConformanceTest` if it enumerates env vars, `CLAUDE.md`
(the auth section), `README.md` (configuration), and this plan's Execution notes.

- [ ] Add `ROLE_ASSIGNMENT_ENABLED: "false"` to the chart (decision 9), and keep the conformance test green.
- [ ] Document in `CLAUDE.md`: the AM role source, the mapping table, the jurisdiction filter rationale, the no-cache rule and the fallback. Also the activation steps, which are not done here:
  - flux sets `ROLE_ASSIGNMENT_URL`;
  - `deed_poll_api` is added to RAS's `ROLE_ASSIGNMENT_S2S_AUTHORISED_SERVICES`;
  - the Gazette skill code is set once RD allocates the service code;
  - then `ROLE_ASSIGNMENT_ENABLED=true`.
- [ ] Record the validation counts and the branch SHA in Execution notes. Do not claim any live verification.
- [ ] Mark completed

## Execution notes

**Baseline, measured 2026-10-14 on `be3291a`** (Java 21, `JAVA_TOOL_OPTIONS=-XX:UseSVE=0`, Ryuk disabled):
- first selector (without `*RoleAssignment*`): 54 tests, 0 failures
- second selector: 581 tests, 0 failures
- `./gradlew build`: 4,086 unit and 19 integration tests, 0 failures, 0 skipped, about 5 minutes
- `git diff --check`: clean

**Task 1** (`AmRoles`, `OrganisationalRoleAssignment`, `AmRolesTest`):
- first selector (with `*AmRoles*` in place of `*RoleAssignment*`): 87 tests, 0 failures
- `./gradlew test`: 4,119 tests, 0 failures, 0 skipped; `./gradlew integration`: 19 tests, 0 failures
- Checkstyle's Gradle worker died with SIGABRT (exit 134) on every task, so Checkstyle 10.24.0 was run directly
  over main, test, integrationTest, functionalTest and smokeTest with `build/config/checkstyle/checkstyle.xml`
  (the `checkstyle` configuration's classpath plus commons-logging): clean
- `git diff --check`: clean

**Task 2** (`RoleAssignmentProperties`, `RoleAssignmentClient`, `RoleAssignmentUnavailable`, `RoleAssignmentClientTest`):
- the properties are registered on `SecurityConfiguration`; no client bean yet, which Task 3 adds with the converter
- a non-2xx failure keeps no cause, because a response exception's message carries RAS's body
- an unreadable `beginTime`/`endTime` fails the whole answer, so a garbled expiry cannot read as "never ends"
- first selector (with `*AmRoles*` added): 102 tests, 0 failures
- second selector: 581 tests, 0 failures
- `./gradlew build` with the Checkstyle tasks excluded: 4,134 unit and 19 integration tests, 0 failures, 0 skipped
- Checkstyle's Gradle worker died with SIGILL (exit 134), so Checkstyle was run directly over all five source sets,
  on the `checkstyle` configuration's classpath (printed by an init script) plus commons-logging: clean
- `git diff --check`: clean

**Task 3** (`AmRoleSource`, `IdamAuthenticationConverter`, `SecurityConfiguration` beans, `IdamAuthenticationConverterTest`,
`RoleAssignmentSecurityTest`):
- `AmRoleSource` holds the switch, the system-user skip (`deedpoll.ccd.system-role` among the raw IDAM roles) and the
  fallback; the converter now reads IDAM's raw claims once and unions in declaration order, identity from IDAM
- the WARN names the first 12 hex digits of the subject's SHA-256 and RAS's status (or `none`), never the cause,
  because a client exception's message carries the request URL and the URL carries the subject
- S2S is minted through an `ObjectProvider`, so the lazy generator is never created while the switch is off
- the HTTP test sets `deedpoll.security.staff-gate=enforce`, because the default is `report`
- first selector (with `*AmRoles*` added): 117 tests, 0 failures
- second selector: 585 tests, 0 failures
- `./gradlew build` with the Checkstyle tasks excluded: 4,149 unit and 19 integration tests, 0 failures, 0 skipped
- Checkstyle's Gradle worker died with SIGILL (exit 134) again, so Checkstyle was run directly over all five source
  sets as in Task 2: clean
- `git diff --check`: clean

(The run fills in the rest.)

## Post-Completion

Not Ralphex checkboxes:
- open the PR;
- flux: `ROLE_ASSIGNMENT_URL` for AAT, and `deed_poll_api` added to RAS's S2S allowlist;
- set the Gazette skill once RD replies;
- enable in AAT after CRD/JRD profiles exist;
- the admin app's role source, decided separately;
- the CCD definition for AM roles (decision 8).
