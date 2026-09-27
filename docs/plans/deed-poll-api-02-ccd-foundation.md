# Plan 02 — minimal decentralised CCD foundation

Status: **restructured for ralphex and reviewed with Steve 2026-09-27; all four flags settled, ready to run.** No
implementation tasks completed. The 2026-09-26 draft's design is unchanged; what changed is the shape — onboarding moved out of the task list into
[Dependencies](#dependencies--outside-this-plan-and-not-ralphex-work), the SDK work split into tasks one
iteration can finish, and a Spring Boot bump added in front of it.

## Overview

Create one genuine CCD case for an existing deed-poll record, store its reference, and read the minimal case
projection through CCD. The relational deed-poll record remains authoritative. This is the shared foundation
for payment, document handling and migration — see the
[programme overview](../../../deed-poll-documentation/plans/active/ccd-and-shared-services.md).

**This plan is buildable before onboarding.** Every task runs on the existing `DEED_POLL_PLACEHOLDER` case type
and a placeholder jurisdiction, against WireMock and — in Task 8 only — the in-process cftlib stack. It proves
the code; it does not prove onboarding, and nothing in it registers, imports or deploys anything outside this
repository. Swapping in confirmed identifiers is configuration, not a code change, and is the first thing the
AAT smoke procedure (Task 9) needs.

## Decisions taken — implement these, do not revisit

1. **Relational stays authoritative.** CCD holds a minimal case linked to the `deedpoll_deedpoll` row. The
   `CaseView` composes from our entities; nothing reads case data back out of `ccd.case_data.data` as truth.
2. **Decentralised, via the SDK.** `hmcts.ccd.sdk` plugin plus `com.github.hmcts:decentralised-runtime`,
   versioned by the plugin's BOM — never a version on the runtime dependency. Pin the plugin to **7.1.0**, the
   version `pcs-api` runs on Spring Boot 3.5.16 / Java 21. **Confirmed by Steve 2026-09-27** over 7.2.0, the
   latest tag: the proven version, not the newest. Moving to 7.2.0 later is its own change.
3. **No search or indexing.** `ccd-runtime-indexing` is **not** declared on any configuration, including
   `cftlibImplementation`. The admin queue reads the local database; nothing in this release searches CCD.
   Adding it later is a dependency line plus `ELASTIC_SEARCH_HOSTS`, and is a separate decision.
4. **No Service Bus, no NoC, no Work Allocation.** `ccd-servicebus-support` is not declared;
   `ccd.decentralised-runtime.noc.enabled` stays at its default `false`, so `/noc/**` is not registered.
5. **The identifiers are configuration and are never invented.** Case type id stays the one key
   `deedpoll.ccd.case-type-id: DEED_POLL_PLACEHOLDER`; add its sibling `deedpoll.ccd.jurisdiction-id`
   with the same literal. The `CCDConfig` reads both. A grep for `DEED_POLL_PLACEHOLDER` must still list every
   external identifier this service is waiting on.
6. **The SDK owns its migrations; we do not define a `FlywayMigrationStrategy`.** The SDK's
   `DecentralisedFlywayAutoConfiguration` runs its `ccd`-schema migrations first, with its own history table,
   then ours. Defining our own strategy bean would silently stop the `ccd` migrations — so no task may add one,
   and a test fails the build if one appears.
7. **The CCD reference is nullable, unique when present, a string at every boundary, and never patchable.**
   Column `ccdCaseReference` (mixed case, per the schema's convention). It is not writable through
   `DeedPollPatch`/`DeedPollFields`, and in this plan it is **not published** in any DTO — settled 2026-09-27;
   the payment plan publishes it (Flag 3).
8. **Correlate on the deed-poll UUID, never the display reference.** `KBDP2026-00001`'s prefix can change;
   the UUID cannot. The display reference is carried as a field for humans only.
9. **Commit locally before calling CCD; never hold a lock across the network.** CCD calls back into
   `/ccd-persistence/cases` on a separate request that must read and lock the same row.
10. **The handler binds atomically, idempotently, and never overwrites.** Inside the SDK's event transaction:
    load and lock the record by UUID, bind the supplied reference; the same reference again is a no-op; a
    different reference for an already-linked record is a conflict.
11. **One API-owned linking operation**, used later by online submission and migration alike. It starts and
    submits through CCD's public case/event API (`CoreCaseDataApi`), never by calling our own persistence
    controller to manufacture a case.
12. **A null reference is not a state.** It must not hide a case from the queue, change any count, or read as
    "unpaid online". Existing list, count and filter behaviour is unchanged by every task.

## Dependencies — outside this plan, and not ralphex work

This was Task 0 of the draft. It is Steve's, it runs in parallel with Tasks 1–8, and only Task 9 waits on it.

- Confirmed **jurisdiction** and **case type ID**, the first target environment, and who owns the
  definition import there.
- **IDAM:** the system account the linking operation acts as, its roles, and who owns the credential. The
  definition's access profiles and IDAM roles to grant (`caseworker-…` naming is CCD's convention and still
  needs confirming, not assuming).
- **S2S:** `deed_poll_api` registered with `service-auth-provider-app` (vault secret first — it
  auto-deploys to AAT *and* prod), and our secret copied into `deed-poll-aat` by `deed-poll-infra`.
- **CCD routing:** data-store configured to route our case type to our persistence host
  (`ccd.decentralised.case-type-service-urls` — cftlib's `CCD_DECENTRALISED_CASE-TYPE-SERVICE-URLS_*`), and
  `deed_poll_api` allowed as a data-store S2S caller; `ccd_data` allowed as a caller of ours.
- **Network:** data-store → our `.internal` address reachable in AAT (the address exists since
  `azure-platform-terraform#3161`).

## Constraints

- Everything in [`CLAUDE.md`](../../CLAUDE.md), and in particular: never edit a committed migration (the next
  free one is **V21**); no live calls to CCD, IDAM or S2S in any test — WireMock only; no secrets, every
  credential by environment variable with a non-working default.
- **Globally quoted identifiers and `PhysicalNamingStrategyStandardImpl` apply to every JPA entity.** Nothing in
  this plan maps an SDK `ccd.*` table as a JPA entity; read SDK state through the SDK's own services.
- **Docker-free CI does not run container-backed tests.** Every rule proved against real Postgres needs a
  container-free companion where the rule can be expressed without a database, as the Notify plans did.
- The SDK targets PostgreSQL 15 as a baseline; we run 16 (infra `pgsql_version = "16"`, tests
  `postgres:16-alpine`), as does `pcs-api`. Not a blocker; do not downgrade.
- **Run every Gradle build in the foreground, never in the background** (added by Steve 2026-09-27, mid-run).
  Give the Bash call its maximum timeout (`600000` ms); the full build takes about 6–7 minutes. A background
  build ends your session, because you run non-interactively, and the next session starts a second build over
  it, which deletes the first build's `build/test-results` and invalidates every count. That happened three
  times in Task 2. Before starting any build, check that none is already running with
  `pgrep -f 'gradlew|GradleWorkerMain'`. If one is, wait for it to finish rather than starting another. If a
  build genuinely outlives the 10-minute cap, stop and record that; do not fall back to a background build.

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

`cftlibTest` is deliberately **not** here: it boots the whole CCD stack in-process, and making every task pay
for it would make every iteration slow and every environment hiccup a red task. Task 8 runs it; Task 9 records it.

**Count from `build/test-results/`, never from the exit code** — and delete the directory first, because a
Docker-free run leaves the previous run's `integration` files in place. A worker or container startup timeout is
an environment failure, not a code failure: re-run before investigating.

**Baseline measured 2026-09-27 on `master` at `09bab4c`**, counted off `build/test-results/*/TEST-*.xml`:

| Run | `test` | `integration` | Wall clock |
|---|---|---|---|
| `./gradlew build` (Docker) | **2956**, 0 failures / 0 errors / 0 skipped, 363 result files | **13**, all green, 3 result files | 6m 11s (`:test` re-run only) |
| `INTEGRATION_TESTS_ENABLED=false ./gradlew build` (Docker-free, measured by Task 1 on Boot 3.5.5 at `ec4fa2b`) | **1956**, 0 failures / 0 errors / 0 skipped, 256 result files | not run | 1m 1s (`:test` re-run only) |

It took two runs, and the first is the example of the environment failure above: `build --rerun-tasks` executed
all 24 tasks with every test that ran passing, then failed `:test` because `Gradle Test Executor 7` never
connected (120s timeout). That run's 2955/362 was **short by one result file** — the dead worker's class — so a
count from a run that failed is not a baseline even when nothing in it failed. The second, plain `build`
re-executed only `:test` (22 tasks up to date, `integration` included) and went green. The Docker-free count is
not measured here; Task 1 measures it before changing anything.

## Tasks

### Task 1: Upgrade Spring Boot to 3.5.16, alone

- **Before changing anything**, measure the Docker-free baseline (`INTEGRATION_TESTS_ENABLED=false ./gradlew
  build`) and record it beside the Docker one above.
- Bump the `org.springframework.boot` plugin from 3.5.5 to **3.5.16**, matching `pcs-api`. Change nothing else.
- Fix only what the bump breaks; if anything needs more than a mechanical fix, stop and record it here.
- The OpenAPI document must be byte-identical before and after (regenerate and diff, as referrals-api-5 did),
  and the `test`/`integration` counts must equal the baseline exactly.

- [x] Boot is 3.5.16, the published spec is byte-identical, the counts match the baseline, and the build is
      green

Done 2026-09-27. The one-line plugin bump needed no fixes. `/tmp/openapi-specs.json`, regenerated by
`OpenAPIPublisherTest` before and after, is byte-identical (SHA-256 `0b6d6783…fac250e`). Docker `build`: test
2956 / 363 files, integration 13 / 3 files, all green, which equals the baseline. It took 48m 44s because every task
re-executed. Docker-free `build`: test 1956 / 256 files, all green, which equals the 3.5.5 figure above.

### Task 2: Add the SDK plugin and decentralised runtime, with nothing using them

- Apply `id 'hmcts.ccd.sdk' version '7.1.0'` (replacing the commented `6.36.0` line and its comment) and
  declare `implementation 'com.github.hmcts:decentralised-runtime'` without a version. Set
  `ccd { configDir = file('build/definitions') }`.
- Wire `generateCCDConfig` the way `pcs-api` does: a `config-gen` profile with Flyway and any schedulers off,
  and no datasource, so generating a definition needs no database.
- Prove the two migration histories:
  - Container-backed: a full-context test shows the `ccd` schema and its history table exist, `public`'s
    history is unchanged (V1–V20, same checksums), and Hibernate `validate` still passes.
  - Container-free: a context-level assertion that **no bean of type `FlywayMigrationStrategy`** is defined by
    this application (decision 6), so adding one fails the build with a sentence that says why.
- Confirm the SDK adds no route this plan did not intend: with the SDK present, `/noc/**` is not mapped.
  `/ccd-persistence/**` is mapped, and **must be explicitly `denyAll()`** until Task 5 replaces that with the
  real rule. The existing chain is `anyRequest().authenticated()`, not a deny — so without this, any valid IDAM
  token, a caseworker's included, could post a CCD event straight into the SDK the moment the dependency lands.
  Test both, the refusal with a caseworker token.
- Record in this task what the SDK pulled in (dependency diff), and any version it forced.

- [x] the SDK is on the classpath, both Flyway histories run in the right order, no route is newly open, the
      published spec is unchanged, and the build is green

Done 2026-09-27. The plugin lives on the HMCTS Azure Artifacts feed, so `settings.gradle` gained a
`pluginManagement` block listing it after the portal, as `pcs-api` does. **Dependency diff** (`runtimeClasspath`,
resolved): no existing module changed version, so the SDK forced nothing and Boot stays 3.5.16. It adds 38
modules: `decentralised-runtime`, `ccd-config-generator` and `ccd-sdk-bom` 7.1.0, `rse-cft-lib:ccd-lib`
0.19.2186, `core-case-data-store-client` 5.3.0, `idam-java-client` 3.0.5, `ccd-case-document-am-client` 1.59.2,
Guava 33.7.1, Caffeine 3.2.4, OkHttp 4.12 (and Kotlin stdlib 1.9.25), `feign-okhttp`/`feign-httpclient` 13.6,
Apache HttpClient 4.5.14, and `elasticsearch-java`/`elasticsearch-rest5-client` 9.5.4 with Jackson 3.1.0
(`tools.jackson`) and OpenTelemetry. Two things to know:
- The plugin itself declares `elasticsearch-java {strictly [9, 10)}`. That is a client library on the classpath,
  not `ccd-runtime-indexing`; decision 3 still holds, and nothing configures or calls it.
- Boot's BOM holds OpenTelemetry at 1.49.0 where `elasticsearch-java` asks for 1.63.0. Harmless while nothing
  uses the ES client; revisit if indexing is ever adopted.

Two findings shaped the code. `idam-java-client` registers `IdamClient` as auto-configuration and needs an
`IdamApi` Feign client nothing declares, so `application.yaml` excludes it until the SDK's `IdamService` is the
first caller. And the runtime refuses to start without a `CaseView` bean, so `uk.gov.hmcts.ccd.sdk` is **not yet
component-scanned**: `/ccd-persistence/**` is not mapped today, only refused. `SecurityConfiguration.CCD_SDK_PATHS`
denies `/ccd-persistence/**` and `/callbacks/**` to everybody, and `CcdSdkRoutesTest` (Docker-free, under
`config-gen` + `test`) proves a caseworker's real stub-IDAM token gets 403 there, that `/noc/**` is unmapped, and
that every route the SDK maps falls under the refused prefixes — vacuous today, and it starts biting the moment
Task 3 or 6 adds the scan. `ConfigGenProfileTest` boots `config-gen` against an unreachable datasource and fails
if this application defines a `FlywayMigrationStrategy`. `SdkMigrationOrderTest` (container) shows the `ccd`
schema and its own history, `public` at V1–V20 with matching checksums, the SDK migrating first, and `validate`
passing. `generateCCDConfig` runs under `SPRING_PROFILES_ACTIVE=config-gen`; with no `CCDConfig` yet it has
nothing to write. The OpenAPI document is byte-identical to Task 1's (SHA-256 `0b6d6783…fac250e`).

### Task 3: The minimal case definition

- A `CCDConfig` reading the case type and jurisdiction ids from configuration (decision 5), with one technical
  state and one create/link event. Names agreed with Steve 2026-09-27 as **implementation choices, not
  confirmed identifiers** — use exactly these:

  | Thing | Name |
  |---|---|
  | State | `Registered` — "a CCD case exists for this record"; it echoes none of the relational case states |
  | Event | `linkDeedPoll` — links an existing record, it does not originate a case |
  | Fields | `deedPollId` (the UUID, the link) and `deedPollReference` (the `KBDP…` display reference, for humans) |
  | Access profile | `DEED_POLL_SYSTEM` — the one actor the linking operation runs as |

- Platform metadata the import requires, and no more.
- `DEED_POLL_SYSTEM` is granted create and read, and nothing else is granted anything. The IDAM role it maps to
  is configuration with a `DEED_POLL_PLACEHOLDER` default, the same way as the ids, so the confirmed role is a
  config change, not code.
- A test over the generated JSON pins the case type, state, event, the two fields and the grants — and fails if
  anything else appears, since the point is a minimal definition and growth should be deliberate.

- [x] `./gradlew generateCCDConfig` produces the minimal definition from placeholders, a test pins its
      contents, and the build is green

Done 2026-09-27. `CcdProperties` binds `deedpoll.ccd.{case-type-id,jurisdiction-id,system-role}`, each defaulting
to `DEED_POLL_PLACEHOLDER` by environment variable and refusing a blank value. `DeedPollCaseType` is the
`CCDConfig`: state `Registered`, decentralised event `linkDeedPoll` (whose handler throws until Task 6), fields
`deedPollId`/`deedPollReference`, and `DEED_POLL_SYSTEM` granted `CR`. The configured IDAM role maps onto that
profile as `idam:<role>` in `RoleToAccessProfiles`. `omitHistoryForRoles` keeps the SDK's `caseHistory` field
ungranted, because the SDK would otherwise grant it CRU. `CcdConfiguration` registers only the SDK's generator
package, not the whole SDK, so the runtime and `/ccd-persistence/**` still stay out until Task 6.
`DeedPollDefinitionTest` (Docker-free, `config-gen`) holds the whole output against
`src/test/resources/ccd-definition/`, and `SPRING_PROFILES_ACTIVE=config-gen ./gradlew generateCCDConfig` writes
a `build/definitions` identical to it. Docker `build`: test 2977 / 367 files, integration 13 / 3 files, all green.

### Task 4: The CCD reference column and durable creation attempts

- **V21**: nullable `ccdCaseReference` on `deedpoll_deedpoll`, unique when present, validated as a 16-digit CCD
  reference held as a string. Not readable, writable or queryable through `DeedPollFields` (decision 7), not in
  `CaseDetailResponse` or `SavedCaseResponse`. **This collides with `CLAUDE.md`'s "a new case column has to be
  declared in four places" rule**, which exists to make an undeclared column fail the build. Find the tests that
  enforce it and give `ccdCaseReference` one explicit, named exemption with its reason — do not loosen the rule,
  or the next forgotten column passes silently.
- **V22**: a creation-attempt table — deed-poll UUID, operation id, status (`Pending`, `Claimed`,
  `AwaitingConfirmation`, `Linked`, `NeedsRepair`), worker claim with a lease expiry, attempt count, bounded
  failure detail, timestamps. One live attempt per deed poll, enforced by a partial unique index. This is the
  record the migration plan reuses — it must not grow a second retry engine.
- No CCD start-event token is stored in this task. If Task 7 finds it must persist one to survive token expiry,
  that is its decision to record, with the storage protected and never logged.
- Tests: existing case list/count/filter results identical with the column present and null (decision 12);
  the status transitions allowed and refused; concurrent claims on one deed poll yield exactly one winner
  (container-backed) with a container-free companion over the transition rule.

- [x] V21 and V22 exist, the reference cannot be patched or published, one live attempt per case is enforced,
      existing queue behaviour is unchanged, and the build is green

Done 2026-09-27. Finding: nothing enforced the four-places rule from the entity inward. The response-field tests
compare each DTO with `DeedPollFields.readAll`, so a column added to the entity alone failed no test. The new
Docker-free `DeedPollColumnsTest` asserts that every `@Column` of `DeedPoll` is readable through `DeedPollFields`,
apart from named exemptions. Those are the eleven citizen-journey columns, already excluded and now written down,
and `ccdCaseReference`, the one exemption this task adds. The test also checks that every exemption is real,
and that the reference cannot be patched, sorted, queried or found by search. An ArchUnit rule over `api..`
keeps it out of every DTO.

V21 adds `varchar(16)` with a UNIQUE constraint and `CHECK (~ '^[0-9]{16}$')`. `CcdCaseReference.PATTERN` is
held to that CHECK by reading the migration. The entity maps the column `insertable = false, updatable = false`,
so a save cannot write a stale null over it. Its only writer is `DeedPollRepository.bindCcdCaseReference`, which
is conditional on NULL and binds once.

V22 adds `deedpoll_ccdcreationattempt` with the `CcdCreationAttemptStatus` enum and a lease CHECK on `Claimed`.
The unique index `live_key` covers `("deedPollId") WHERE "status" <> 'Linked'`. `NeedsRepair` is live: an
unknown outcome must block a second creation. The transitions are:
- Pending → Claimed
- Claimed → Pending, AwaitingConfirmation, Linked or NeedsRepair
- AwaitingConfirmation → Linked or NeedsRepair
- NeedsRepair → AwaitingConfirmation
- Linked is terminal

Nothing after submission returns to Pending or Claimed. `CcdCreationAttempts` is the single ledger that Task 7
and the migration plan share: `open` (`ON CONFLICT` on the live index), `claim` (conditional, and it counts the
try) and `move` (conditional on `from`, with failure detail bounded to 1000). No start-event token is stored.

Container tests show one winner among 8 concurrent claims and among 8 concurrent opens. They also show the list
filters, finders and paged totals are identical before and after references are bound (decision 12). Docker
`build`: test 3042 / 373 files, integration 13 / 3 files, all green. `DeedPollColumnsTest` imports only the api
package, locally, because a static whole-codebase ArchUnit import exhausted the default 512 MB test heap.

### Task 5: Secure `/ccd-persistence/**`

- Replace Task 2's `denyAll()` — do not add a rule beside it. Inbound S2S for that path only: accept exactly `ccd_data`, refuse every other service — including this
  service's own name — and refuse a missing or malformed `ServiceAuthorization`. Set
  `idam.s2s-authorised.services` for this path's filter only; the caseworker and judicial namespaces must not
  start requiring S2S.
- The forwarded user token must be a valid IDAM token through the existing `IdamJwtDecoder`; the user's roles
  are checked against the definition's grants from Task 3, not re-derived.
- A full-context security test (never a `@WebMvcTest` slice — see `CLAUDE.md`) covers: no S2S, wrong service,
  right service with no user, right service with an unauthorised user, and the success path reaching the SDK
  controller. The existing security tests for the other namespaces pass unchanged.

- [x] only `ccd_data` with an authorised user reaches the persistence API, the other namespaces are unchanged,
      and the build is green

Done 2026-09-27. The path has its own `@Order(1)` chain, `ccdPersistenceFilterChain`, matched by
`PathPatternRequestMatcher`. The main chain's `denyAll()` now names only `/callbacks/**`. Finding: the
`securityMatcher(String)` form builds an `MvcRequestMatcher` at once, and that needs `mvcHandlerMappingIntrospector`,
which every `web-application-type=none` context lacks. It failed 346 tests before the switch.

Deviation: the allowlist is the constant `SecurityConfiguration.CCD_PERSISTENCE_CALLERS` (`ccd_data` alone), not
`idam.s2s-authorised.services`. Setting that property registers the library's `ServiceAuthFilter` as a bean, and
Boot applies a `Filter` bean to every path, so the caseworker and judicial namespaces would start requiring S2S.
The chain builds its own `ServiceAuthFilter` instead, ahead of the bearer filter. It answers 401 for a missing or
unverifiable token and 403 for a verified service outside the list, `deed_poll_api` included, before any user
token is read.

The user token goes through the same `IdamJwtDecoder`. `CcdCallerAuthenticationConverter` reads the raw IDAM roles
(`IdamClaims.roleNames`) because the system role is configuration and not a `Role`. It maps them through the new
`DeedPollRoleMapping`, which `DeedPollCaseType` now uses to write its `RoleToAccessProfiles` row, so the definition
and the chain share one mapping. The chain requires a profile the definition grants something to
(`DeedPollRoleMapping.granted()`).

`CcdPersistenceSecurityTest` (full context, WireMock IDAM and the new `StubS2s`) covers the five cases and more: an
unprefixed service token, this service's own name, a foreign-key user token, the placeholder role, the profile
name used as a role, callbacks still refused, and the caseworker and judicial namespaces needing no S2S. The SDK
controller is not mapped until Task 6 scans the runtime, so the success path reaches a test stand-in under the
path. Task 6 must assert the real route behind this chain. The existing namespace security tests are unchanged.
`CcdSdkRoutesTest` now expects 401 rather than 403 for the persistence routes without S2S. Docker `build`: test
3060 / 375 files, integration 13 / 3 files, all green.

### Task 6: The submit handler and the `CaseView`

- The create/link event's `submitHandler`: resolve the deed poll by the UUID in the payload, `SELECT … FOR
  UPDATE`, and bind the CCD reference (decision 10). An unknown UUID, and a UUID whose creation attempt is not
  `Claimed`/`AwaitingConfirmation` for this operation, are refused — **a UUID in a payload is not permission to
  bind a case**.
- The `CaseView` composes the two fields from our entity through an explicit projection type; no entity crosses
  into the SDK. Exactly one view bean for our case type (the runtime fails fast on none or two).
- Transactions: the handler's JPA write and the SDK's `ccd.case_data`/`ccd.case_event` writes share one
  transaction and roll back together. Prove it against real Postgres by making the handler throw after
  binding.
- Idempotency: a repeated persistence request with the same `Idempotency-Key` replays the stored result,
  including after the local record has since changed.
- Container-free companions for the bind/refuse/conflict rule.

- [x] a `POST /ccd-persistence/cases` for a claimed attempt binds exactly once, a conflicting reference is
      refused, a forged UUID is refused, writes roll back together, and the build is green

Done 2026-09-27. `LinkDeedPollHandler.submit` is `@Transactional(propagation = MANDATORY)`, so it can only join the
SDK's `CaseEventTransactionCoordinator` transaction. It checks the reference with `CcdCaseReference.of`, takes
`findByIdForUpdate`, reads the live attempt and whichever record already holds the reference, and hands those facts
to `CcdLinkRule.decide`. The rule has no database in it. Its outcomes are `BIND`, `ALREADY_BOUND` (a no-op), and five
refusals in fixed prose: `MALFORMED_REFERENCE`, `NO_SUCH_RECORD`, `NO_ATTEMPT_IN_PROGRESS`,
`RECORD_LINKED_ELSEWHERE` and `REFERENCE_TAKEN`. The attempt is checked before the binding. A refusal is a submit
error, so data-store gets a 200 with an `errors` array, and nothing is written. The bind is conditional, and one that
changes no row throws rather than reporting success.

Deviation: the payload carries no operation id, so "for this operation" means the record's live attempt. That
attempt must be `Claimed` or `AwaitingConfirmation`. Task 7 owns which attempt that is.

`DeedPollCaseView` is the only `CaseView` bean. It composes `deedPollId` and `deedPollReference` from the new
`CcdCaseProjection` record, read by `DeedPollRepository.findCcdCaseProjection`, and throws for a reference no record
holds. `ccd.case_data.data` stays `{}`.

Finding: scanning `uk.gov.hmcts.ccd.sdk` component-scanned the SDK's `@AutoConfiguration` classes. That added two
unqualified `ObjectMapper` beans, and Jackson's `@Primary` mapper backed off. `CcdConfiguration` now excludes them
with `AutoConfigurationExcludeFilter`. It also excludes `sdk.json` and `sdk.impl.json` by regex. `JsonCallbackBridge`
needs `RequestMappingHandlerMapping`, which every `web-application-type=none` context lacks, and both packages serve
only JSON-defined configs. The runtime's own `IdamClient` resolves the user for `ccd.case_event`'s audit columns.
That needs `idam-java-client` declared, and `idam.api.url` set (`IDAM_API_URL`, with a localhost default).

`CcdPersistenceSubmissionTest` runs against real Postgres through the SDK's controller, with WireMock IDAM and S2S.
It covers:
- binding a `Claimed` attempt and an `AwaitingConfirmation` attempt;
- one bind across a replay and a new key;
- a replay answering the stored snapshot after the record's display reference changed, while a `GET` answers the
  new value;
- a conflicting reference, a taken reference, and a forged UUID;
- `Pending`, no attempt, and `NeedsRepair`, all refused.

Rollback is proved twice:
- the spied handler throws after its real bind has been observed inside the transaction;
- a trigger refuses the SDK's `ccd.case_event` insert.

Either way no bind, case row or event survives. The container-free companions are `CcdLinkRuleTest`,
`LinkDeedPollHandlerTest` and `DeedPollCaseViewTest`. `CcdPersistenceSecurityTest` now asserts the real SDK route
behind `ccdPersistenceFilterChain`: the chain admits data-store and the controller refuses the missing
`Idempotency-Key` with 400, other services get 403, a caller with no S2S gets 401, and there is exactly one view.

Finding: the first full build died with "Java heap space" inside `NotificationSecurityTest`'s static ArchUnit import.
The four whole-codebase static `JavaClasses` fields are now per-rule `mainClasses()` methods, and the heap is
unchanged. Docker `build`: test 3099 / 379 files, integration 13 / 3 files, all green.

### Task 7: The linking operation and its recovery

- One API-owned service: claim the attempt, commit, then — outside any transaction — obtain the system user's
  IDAM token and an S2S token, `startCase`, `submitCaseCreation` through `CoreCaseDataApi`, then confirm the
  returned reference, case type and UUID match the local binding before marking the attempt `Linked`.
- System user credentials and the CCD URL are environment variables with non-working defaults, following
  `pcs-api`'s `IdamTokenProvider` shape. The existing lazy `ServiceAuthConfiguration` generator is the one
  S2S source; do not add a second.
- Recovery is by **reading, not retrying**: a timeout, lost response, 5xx or 404 after submit moves the attempt
  to `AwaitingConfirmation`, and reconciliation reads the local binding and CCD before doing anything else. It
  never clears a binding and never starts a fresh event blindly, because a fresh start-event token mints a new
  idempotency key and can mint a second case. An expired claim goes to reconciliation, not to a new creation.
  Anything it cannot settle becomes `NeedsRepair`, visibly.
- Record here whether the start-event token must be persisted to survive expiry (Task 4's deferral), with the
  evidence.
- WireMock scenarios for: success; lost response after CCD committed; handler rollback; conflicting existing
  link; token expiry between start and submit; two workers racing on one deed poll.

- [x] the linking operation converges to one confirmed link or a visible repair item in every WireMock
      scenario, never a second case, and the build is green

Done 2026-09-27. `CcdLinkingService.link(deedPollId)` is deliberately not `@Transactional`. It opens or finds the
record's live attempt and claims it through `CcdCreationAttempts`, which commits before CCD is called. It then gets
the system user's token from `SystemUserTokens` and an S2S token from `ServiceAuthConfiguration`'s generator,
through an `ObjectProvider`, so it is still the one source. It calls `startCase` and `submitCaseCreation` on
`CoreCaseDataApi` with `linkDeedPoll`. It marks the attempt `Linked` only when `CcdLinkingRule.confirm` finds CCD's
reference, case type and `deedPollId` equal to the record's committed binding. `reconcileOutstanding()` handles
every expired claim, then every attempt awaiting confirmation. It snapshots both lists first, so a claim it takes
over is read once, not twice.

How each outcome of the submit is classified (`CcdLinkingRule.classify`):
- A 4xx other than 404 or 408 is a refusal. The binding is read before anything else. If it holds a reference, the
  attempt goes to `AwaitingConfirmation` and that reference is confirmed. If it is empty, nothing was created, so
  the attempt is released to `Pending` until `max-attempts` claims have been used, then goes to `NeedsRepair`.
- No response, a 404, a 408, a 5xx, or a response that will not decode is unknown. The attempt goes to
  `AwaitingConfirmation` and is reconciled at once.
- A failure before submission (credentials, IDAM, S2S, or `startCase`) created nothing, and is treated like a
  refusal with an empty binding.

Reconciliation reads the binding, then `getCase` for it. Confirmation gives `Linked`, and a contradiction gives
`NeedsRepair` at once. Nothing bound, or CCD unreadable, waits `settle-after`, then becomes `NeedsRepair`.
Reconciliation never releases an attempt, never clears a binding, and never starts an event. Failure detail is
fixed prose plus an HTTP status or an exception class name, never a message (which for Feign quotes the body) and
never a token.

Decision (Task 4's deferral): the start-event token is not persisted. The evidence, from
`apps/ccd/ccd-data-store-api`:
- `EventTokenService.generateToken` sets no expiry claim, only `issuedAt`, so a token does not go stale on a clock.
- A bad token is `EventTokenException`, which is `@ResponseStatus(FORBIDDEN)`. It is thrown before
  `SubmitCaseTransaction` persists its pointer or calls us, so a 403 created nothing.
- `IdempotencyKeyHolder` derives the key from the token (`UUID.nameUUIDFromBytes`). A persisted token would only be
  worth anything for a resubmission, and the design never resubmits: an unknown outcome is settled by the binding,
  which commits in the same transaction as `ccd.case_data`.

Configuration, each with a non-working default:
- `core_case_data.api.url` (`CCD_DATA_STORE_URL`, local stack's 4452).
- `idam.client.id`/`secret`/`redirect_uri` (`IDAM_CLIENT_*`, empty).
- `idam.system-user.username`/`password` (`DEED_POLL_SYSTEM_USERNAME`/`_PASSWORD`, empty; `SystemUserTokens`
  refuses an empty one before IDAM is asked).
- `deedpoll.ccd.linking.worker`/`lease`/`settle-after`/`max-attempts` (`HOSTNAME`, `CCD_LINKING_LEASE` PT5M,
  `CCD_LINKING_SETTLE_AFTER` PT10M, `CCD_LINKING_MAX_ATTEMPTS` 3). `CcdLinkingProperties` refuses unsafe values.

`CcdConfiguration` names `CoreCaseDataApi` in `@EnableFeignClients`, and `application.yaml` excludes
`CoreCaseDataClientAutoConfiguration`. That auto-configuration scans the client's whole package and adds an
unconditional `CoreCaseDataHealthIndicator`, which would put CCD into readiness. Repository additions:
`findCcdCaseProjectionById`, `findCcdCaseReference`, `findExpiredClaims` and `findAwaitingConfirmation`. No
migration.

`CcdLinkingScenariosTest` runs against real Postgres. Its fake data-store is a WireMock `ResponseDefinitionTransformerV2`
that calls back into `/ccd-persistence/cases` as `ccd_data`, with a per-token `Idempotency-Key`. So every case is a
real binding and a real `ccd.case_data` row, counted from the table. The scenarios:
- Success: as the system user with the leased S2S token.
- Lost response after commit, with the first read answered 503: `AwaitingConfirmation`, then `Linked` on
  reconciliation, with one submit.
- Handler rollback: a trigger on `ccd.case_event` gives a 500. The attempt waits inside the window, then goes to
  `NeedsRepair` once aged.
- Conflicting existing link: an earlier submission lands, the second gets a 422, and the first is confirmed.
- Token refused between start and submit: 403, `Pending`, then one fresh start and one case.
- Two workers racing: one start, one `LINKED`, one case.
- Also an expired claim, reconciled without a start, and a case shown under another case type, which goes to
  `NeedsRepair`.

The container-free companions are `CcdLinkingRuleTest`, `CcdLinkingServiceTest` (one per scenario, over an
in-memory attempt that enforces `canMoveTo`) and `SystemUserTokensTest`. `StubS2s.lease` and
`StubIdam.stubPasswordGrant` are new.

Finding: `CcdCaseReferenceColumnTest.theCaseListIsUnchangedByTheReference` (Task 4) flaked in a full build. The
unpaged finders order by `createdOn` alone, and four fast saves tied. The test now spaces the rows' `createdOn` a
minute apart. The ported ordering is unchanged. Docker `build`: test 3137 / 383 files, integration 13 / 3 files,
all green.


### Task 8: Prove it end to end in cftlib

- Add `com.github.hmcts.rse-cft-lib` at the version `pcs-api` uses and a `cftlibTest` source set, excluded from
  `build` and `check`, with the decentralised routing pointed at this service and `ccd_data`/`deed_poll_api`
  allowed as each other's callers.
- One test: create a deed poll locally, run the linking operation against the in-process data-store, read the
  case back through CCD, and assert the reference, case type and UUID agree on both sides. Placeholders
  throughout.
- **Local only** (settled 2026-09-27): not wired into `build`, `check` or the Jenkinsfile. Where it runs in CI is
  a later decision.
- Memory: Steve's recollection is that the stack needs about **6 GB** — unverified. Run `free -h` before starting
  it and record both that and the peak while it runs, so the figure in this plan is measured, not remembered.
  Stop any other Gradle daemon or ralphex review first.
- If cftlib does not fit this devcontainer (memory, Docker, Elasticsearch it expects), stop and record what it
  needs rather than working around it.

- [ ] `./gradlew cftlibTest` creates, links and reads one case through the real CCD stack, `build` is unchanged
      by the new source set, and the build is green

### Task 9: Full validation and handoff

- Record final `test`/`integration` counts, Docker and Docker-free, against the baseline.
- Write the operator-run AAT smoke procedure — definition import, create/read, authentication with provisioned
  identities — as a document, **not** as evidence: it cannot run until the Dependencies are done.
- Update `docs/RESUME.md`, the `CLAUDE.md` layout and silent-breakage rules (at minimum: decision 6's
  strategy trap, decision 10's no-overwrite rule, and that a new CCD field must be added to the definition, the
  projection and the pinned JSON), and `DEED_POLL_PLACEHOLDER`'s comment in `application.yaml`.

- [ ] counts recorded, the smoke procedure written, RESUME and CLAUDE.md describe the new code, and the build
      is green

## Deliberately not in this plan

- Onboarding of any kind — see Dependencies. No definition import, S2S registration or deployment.
- Documents and CDAM (plan 03), TTL and disposal (retain-and-dispose), the bulk backfill (ccd-migration),
  payment and queue admission (fees-and-pay-1's amendment).
- Calling the linking operation from any real flow. It exists and is proven; online submission and migration
  wire it in their own plans.
- Search, indexing, Service Bus, NoC, XUI and Work Allocation.
- Publishing the CCD reference to admin or web (Flag 3).

---

## Flags for Steve

1. ~~**SDK 7.1.0, not 7.2.0.**~~ **Settled 2026-09-27: 7.1.0**, what `pcs-api` runs — now decision 2.
2. ~~**cftlib may not fit the devcontainer.**~~ **Settled 2026-09-27: try it locally** and see. Task 8 measures
   the memory (Steve recalls ~6 GB) and stops rather than works around a misfit. CI placement is deferred —
   `pcs-api` runs its `cftlibTest` in GitHub Actions, which we no longer have.
3. ~~**The reference is stored but not published.**~~ **Settled 2026-09-27: store only.** Publishing it changes
   the contract and admin's `contract.test.ts` makes every spec refresh atomic, so it moves to the
   [payment plan's amendment](fees-and-pay-1-ways-to-pay-card.md#amendment-2026-09-26--ccd-and-queue-admission)
   (its Task 8), which publishes it with admin's counterpart.
4. ~~**Task 3's names are mine to choose and yours to veto.**~~ **Settled 2026-09-27:** `Registered`,
   `linkDeedPoll`, `deedPollId`/`deedPollReference`, `DEED_POLL_SYSTEM` — recorded in Task 3 as implementation
   choices, so nobody later mistakes them for onboarded identifiers.
