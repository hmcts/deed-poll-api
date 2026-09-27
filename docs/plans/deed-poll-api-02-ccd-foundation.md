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

- [ ] the SDK is on the classpath, both Flyway histories run in the right order, no route is newly open, the
      published spec is unchanged, and the build is green

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

- [ ] `./gradlew generateCCDConfig` produces the minimal definition from placeholders, a test pins its
      contents, and the build is green

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

- [ ] V21 and V22 exist, the reference cannot be patched or published, one live attempt per case is enforced,
      existing queue behaviour is unchanged, and the build is green

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

- [ ] only `ccd_data` with an authorised user reaches the persistence API, the other namespaces are unchanged,
      and the build is green

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

- [ ] a `POST /ccd-persistence/cases` for a claimed attempt binds exactly once, a conflicting reference is
      refused, a forged UUID is refused, writes roll back together, and the build is green

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

- [ ] the linking operation converges to one confirmed link or a visible repair item in every WireMock
      scenario, never a second case, and the build is green

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
