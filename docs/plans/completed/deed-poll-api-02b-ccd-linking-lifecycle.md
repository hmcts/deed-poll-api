# Plan 02b — CCD linking lifecycle: link at creation, and a way out of `NeedsRepair`

Status: **complete 2026-09-27 — all six tasks done on branch `deed-poll-api-02b-ccd-linking-lifecycle`, not yet merged; every decision and all three flags settled with Steve beforehand.** Onboarding is still open, so the sweep stays off and every identifier is a placeholder. Depends on [plan 02](deed-poll-api-02-ccd-foundation.md),
which built the linking operation and left it uncalled. Comes before [plan 03](../deed-poll-api-03-cdam-documents.md),
whose "link first" decision (Steve, 2026-09-27) means document code only ever sees a record that already has a
CCD case. It also absorbs the linking half of [ccd-migration](../ccd-migration.md), which is now Dataverse
ingestion only.

## Overview

Plan 02 proved `CcdLinkingService.link()` and `reconcileOutstanding()`, but nothing calls either, and an attempt
that reaches `NeedsRepair` can only be settled by hand-written SQL that nobody has the database access to run. This
plan:

1. Calls them. A **sweep** runs as a side effect of the case list request, the way the outbox drain already does.
   It links a few unlinked records and reconciles outstanding attempts. Caseworker-created records, existing records
   and, later, Dataverse-imported ones are all linked the same way.
2. Adds a safe way out of `NeedsRepair` for admin caseworkers, through api endpoints only.

**It is buildable before onboarding**, as plan 02 was: placeholder identifiers, WireMock and, in one task, cftlib.
The sweep ships **switched off**, and switching it on is part of onboarding.

## Decisions taken — implement these, do not revisit

1. **The trigger is caseworker traffic, not a scheduler** (Steve, Q1 (b)). This service has no `@EnableScheduling`,
   Quartz or db-scheduler, deliberately (`NotificationOutbox`, `ReferralService.reclaimStaleClaims`,
   `CaseworkerReadController.cases`). The sweep hangs off `GET /caseworker/cases`, after the response is computed
   and after `outbox.drain()`, and never inside a transaction (plan 02 decision 9). Adding a scheduler is out of scope.
2. **The create request does not link.** `POST /caseworker/cases` is unchanged and waits on nothing from CCD. The
   new record is linked by the next list request, which in practice is the caseworker returning to the list. This
   keeps CCD latency and CCD failures out of the create, which is why "link first" was chosen.
3. **The sweep is off by default:** `deedpoll.ccd.linking.sweep.enabled: ${CCD_LINKING_SWEEP_ENABLED:false}`.
   Without onboarding, every attempt fails before submission, and an enabled sweep would drive every record into
   `NeedsRepair` within `max-attempts` list requests. While it is off, the list request calls nothing in `ccd/`.
4. **The sweep is bounded and fails fast.** Each run links at most `sweep.max-records` (default **2**) records and
   reconciles at most `sweep.max-reconciliations` (default **5**) attempts. It stops at the first result that
   says CCD is unwell (`RETRY_LATER`, or an exception), so a CCD outage costs one request one failed call, not
   several. It never throws into the list response: a failure is logged in fixed prose and the list is returned
   as computed.
5. **Selection never skips and never blocks on a stuck row.** A sweep selects records with a null reference and
   **no live attempt, or a `Pending` attempt whose `notBefore` has passed**, ordered by `createdOn`, `id`, with no
   offset. `link()` removes a record from that set by claiming it, so "the first N" is always the next work.
   Records with an attempt `Claimed`, `AwaitingConfirmation` or `NeedsRepair` are excluded, so they cannot occupy
   the head of the queue.
6. **A released attempt waits before its next try.** When `nothingCreated` releases an attempt to `Pending`, it sets
   `notBefore = now + retry-after × tries` (`CCD_LINKING_RETRY_AFTER`, default **PT5M**). `link()` on a `Pending`
   attempt that is not yet due returns `RETRY_LATER` without claiming it.
7. **Repair is for admins, through api endpoints only** (Steve, Q2 (a) and Q4 (a)). The endpoints go under
   `/caseworker/ccd-attempts` and are gated on `Role.DEEDPOLL_ADMIN` in the domain, as `Role.JUDICIAL` is gated.
   Anyone else gets a 403. An admin screen in deed-poll-admin is a follow-up plan, not this one.
8. **Repair offers two moves and never touches a binding** (Steve, Q3 (b)).
   - **Reconcile again** (`NeedsRepair → AwaitingConfirmation`): always allowed. It is already legal in
     `CcdCreationAttemptStatus`.
   - **Try again from scratch** (`NeedsRepair → Pending`, with `attempts` reset and `notBefore` now): allowed
     **only when `submissionMayHaveReached` is false**. That column records a fact, not prose (decision 9). It is
     the one new transition, and it keeps plan 02's rule true: nothing that may have reached CCD is ever sent
     again.
   - There is no manual bind. Nothing in this plan writes, clears or replaces `ccdCaseReference`.

   Either move is a conditional `UPDATE` from `NeedsRepair`, so two admins acting at once make one move between
   them and the second gets a 409. The move itself does not call CCD; the next sweep does the rest.
9. **`submissionMayHaveReached` is committed before `submitCaseCreation` is called.** It is a V23 column,
   `boolean NOT NULL`. It is set by a conditional `UPDATE` on the worker's own `Claimed` row after `startCase`
   succeeds and before submission. If that update matches nothing, the claim was lost, and the worker submits
   nothing. Existing rows are backfilled **true**, the conservative value: a row nobody can vouch for is never
   retried from scratch. The column is never reset, not even by a retry from scratch, because "may have reached"
   is about the attempt's history, and a retry is allowed only while it is false.
10. **Repairs are attributed.** V23 adds `repairedById` (the IDAM subject, following V14's `claimedById`) and
    `repairedOn`. Neither is published in a DTO or a log line. No case note is written: this is a technical state,
    not part of the case's timeline (Steve, 2026-09-27). **Every repair is logged** at INFO, in fixed prose: the
    attempt id, the move (`NeedsRepair -> AwaitingConfirmation` or `NeedsRepair -> Pending`) and whether it was
    made or refused (409). The admin's identity is not logged; the attempt id leads to the row, which keeps only the
    latest repairer. A refused repair is logged as well, so two admins racing both appear.
11. **What the view publishes.** The status counts, and for each attempt that is not `Linked`: the attempt id, the
    record id and display reference, status, tries, `failureDetail` (already fixed prose plus a status or class
    name, per plan 02), `modifiedOn`, and `canRetryFromScratch`. It never publishes the worker, the lease, the
    operation id, `submissionMayHaveReached` itself, or the repairer's id. The list is capped and paged the way
    `CasePaging` pages.

## Dependencies — outside this plan, and not ralphex work

- Everything in plan 02's Dependencies. None of it is needed to build this plan; it is needed before anyone turns
  on `CCD_LINKING_SWEEP_ENABLED` in an environment.
- **The admin screen** for `/caseworker/ccd-attempts`: a follow-up plan in deed-poll-admin, after this merges. That
  plan's spec refresh is atomic (`contract.test.ts`), so it carries every api change since the last refresh.

## Constraints

- Everything in [`CLAUDE.md`](../../../CLAUDE.md), in particular:
  - never edit a committed migration (the next free one is **V23**);
  - no live calls to CCD, IDAM or S2S in any test — WireMock only;
  - no secrets;
  - never return an entity from a controller;
  - a controller test boots the whole context;
  - the CCD binding is written once and never overwritten.
- **`CcdLinkingService` stays non-transactional, and no caller wraps it in a transaction** (plan 02 decision 9).
  That includes the sweep and the repair endpoints.
- **Docker-free CI does not run container-backed tests.** Every rule proved against real Postgres needs a
  container-free companion where it can be expressed without a database: the transition rule in
  `CcdCreationAttemptStatus`, the selection and stop rules of the sweep, and the repair eligibility rule.
- **Run every Gradle build in the foreground, never in the background.** Give the Bash call its maximum timeout
  (`600000` ms); the full build takes about 5–7 minutes. A background build ends your session, because you run
  non-interactively. The next session then starts a second build over it, which deletes the first build's
  `build/test-results` and invalidates every count. Before starting any build, check that none is already
  running with `pgrep -f 'gradlew|GradleWorkerMain'`, and if one is, wait for it. If a build genuinely outlives
  the 10-minute cap, stop and record that; do not fall back to a background build.
- The published OpenAPI document changes only in Task 4, and only by the new paths and their DTOs.

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew cleanTest cleanIntegration build
```

`cftlibTest` is deliberately **not** here; Task 5 runs it and Task 6 records it.

**Count from `build/test-results/`, never from the exit code.** `cleanTest cleanIntegration` is there because a
run that skips a source set leaves the previous run's result files in place. A worker or container startup
timeout is an environment failure, not a code failure: re-run before investigating.

**Baseline: `master` at `3430346`** (plan 02 merged). Plan 02's final Docker run, after its review fixes, was
**3150** `test` in 383 files and **13** `integration`, all green. Task 1 re-measures it, Docker and Docker-free,
before changing anything.

## Tasks

### Task 1: V23, and the transition rule

- **Before changing anything**, measure the baseline: `./gradlew cleanTest cleanIntegration build` and
  `INTEGRATION_TESTS_ENABLED=false ./gradlew cleanTest cleanIntegration build`. Record both here.
- Add `V23__ccd_attempt_repair.sql` to `deedpoll_ccdcreationattempt`, with the same comment density as V22:
  - `submissionMayHaveReached boolean NOT NULL`: existing rows backfilled `true`, then a default of `false` for
    new rows;
  - `notBefore timestamp(3)`, nullable, meaning due now;
  - `repairedById varchar(…)` and `repairedOn timestamp(3)`, both nullable, sized as V14's `claimedById`.
- Map the columns on `CcdCreationAttempt`.
- `CcdCreationAttemptStatus`: add `NeedsRepair → Pending` to `successors()`. The rule that it is legal only when
  nothing may have been submitted cannot live in a state-only enum, so add a pure method elsewhere, such as
  `CcdLinkingRule.mayRetryFromScratch(status, submissionMayHaveReached)`, and document in the enum that the
  new edge is conditional and where the condition lives. Update the enum's Javadoc, which currently says
  "never by opening another": retrying the *same* attempt from scratch is still not opening another, and a
  second live attempt stays impossible (V22's partial index).
- Tests:
  - container-free: `CcdCreationAttemptStatusTest` and the new rule, including that `true` refuses;
  - container-backed: the migration against real Postgres, covering the backfill of an existing row to `true`
    and the default for a new row.

- [x] baseline recorded, V23 applied with its backfill proved, the new edge and its condition tested, and the
      build is green

  Baseline, measured 2026-09-27 on this branch before any change: with Docker, **3150** `test` in 383 files and
  **13** `integration`, all green; Docker-free (`INTEGRATION_TESTS_ENABLED=false`), **2103** `test` in 271 files
  and no `integration`, all green. After Task 1, with Docker: **3170** `test` in 384 files and **13**
  `integration`, all green. Also: `CcdCreationAttempts.move` now refuses `NeedsRepair -> Pending`, because its
  statement is conditional on status alone; Task 4's retry needs its own statement conditional on
  `submissionMayHaveReached = false`.

### Task 2: Record the submission, and wait before retrying

- In `CcdLinkingService.claimAndCreate`, after `startCase` succeeds and before `submitCaseCreation`, commit
  `submissionMayHaveReached = true` through a new conditional `CcdCreationAttempts` method. The condition is this
  attempt `Claimed` by this worker. If it matches nothing, return `current(...)` and submit nothing.
- In `nothingCreated`, set `notBefore` when releasing to `Pending` (decision 6), and add `retry-after` to
  `CcdLinkingProperties`, refusing zero or negative values as its siblings do.
- In `link()`, a `Pending` attempt not yet due returns `RETRY_LATER` without claiming. `claim` itself must also
  refuse a row that is not due, so two paths cannot disagree.
- WireMock scenarios, extending `CcdLinkingScenariosTest`:
  - a refused submission is released with `notBefore` set and is not claimed again until it is due;
  - a failure before submission (IDAM, S2S, `startCase`) leaves `submissionMayHaveReached` false;
  - an unknown outcome and a success leave it true;
  - a claim lost between `startCase` and the flag write submits nothing.

  Each needs a container-free companion in `CcdLinkingServiceTest`.

- [x] the flag is committed before every submission and only then, released attempts wait `notBefore`, and the
      build is green

  After Task 2, with Docker: **3184** `test` in 384 files and **13** `integration`, all green. The flag write
  (`CcdCreationAttemptRepository.recordSubmission`) is conditional on `Claimed`, this worker, this claim's try count
  and an unexpired lease. It does not compare `leaseExpiresAt`, which is stored to the millisecond. The try count is
  what tells two claims by the same pod apart. The release is its own statement (`release`), and `move` now refuses
  `Claimed -> Pending` as well, so no release can forget `notBefore`. `CCD_LINKING_RETRY_AFTER` is in the README and
  `CCD-SMOKE.md` tables; the chart lists no linking variables.

### Task 3: The sweep, off by default

- A new `ccd/CcdLinkingSweep` with one method, `sweep()`. It checks the switch, selects due work (decision 5)
  through a new repository query, calls `link()` for each record up to `max-records`, then reconciles up to
  `max-reconciliations`, stopping early per decision 4.
  - Reconciliation is bounded, so `reconcileOutstanding()` either gains a limit or the sweep uses a bounded variant.
    Do not change what an unbounded call does for any existing caller.
  - The selection query is keyset, not offset, and has a real-Postgres test in which linking rows during a sweep
    does not skip a row.
- Configuration, under `deedpoll.ccd.linking.sweep`: `enabled` (`CCD_LINKING_SWEEP_ENABLED`, `false`),
  `max-records` (`CCD_LINKING_SWEEP_MAX_RECORDS`, 2) and `max-reconciliations`
  (`CCD_LINKING_SWEEP_MAX_RECONCILIATIONS`, 5). Refuse values below 1.
- Call it from `CaseworkerReadController.cases`, after `outbox.drain()`. Extend that method's Javadoc with a
  paragraph in the same voice as the drain's, giving decision 1's argument and decision 2's consequence.
- **Latency budget.** Read how `CoreCaseDataApi`'s Feign client is configured today (`CcdConfiguration`,
  `application.yaml`). If connect and read timeouts are unset or longer than 10 seconds, set bounded ones by
  configuration and record the values here. A sweep must not hold a list response for a CCD read timeout of
  minutes.
- Tests:
  - with the switch off, the list request touches nothing in `ccd/` (a spy or zero WireMock calls);
  - with it on, one list request links at most `max-records` new records;
  - a stuck `NeedsRepair` record at the head does not block the next one;
  - a CCD outage stops the sweep after one failure, and the list still returns 200 with the same body;
  - two list requests at once make one case per record.

- [x] the list request sweeps when switched on and never otherwise, bounded, fail-fast, never skipping, never
      breaking the list, and the build is green

  After Task 3, with Docker: **3215** `test` in 386 files and **13** `integration`, all green. Latency budget:
  nothing set Feign timeouts before, so OpenFeign's defaults applied (10 s connect, 60 s read). They are now
  `spring.cloud.openfeign.client.config.default` **connect-timeout 5000 ms, read-timeout 10000 ms**, for every Feign
  client: `CoreCaseDataApi`, `IdamApi` and S2S's `ServiceAuthorisationApi`, which are all on the sweep's path.
  `CcdLinkingSweepTest` holds both at 10 s or less. Reconciliation is bounded without changing
  `reconcileOutstanding()`. `CcdCreationAttempts.outstanding(limit)` returns expired claims first, then attempts
  awaiting confirmation. `CcdLinkingService.reconcileOne` reconciles one of them and, unlike the unbounded pass,
  lets a failure through so the sweep can stop on it. Selection is `DeedPollRepository.findIdsDueForCcdLinking`:
  always the head of `("createdOn", "id")` with `LIMIT` and no offset, which is what "keyset" means here, because
  linking removes a row from the set. The sweep throws `IllegalStateException` if called inside a transaction,
  since that is a wiring fault rather than a CCD failure. The sweep scenarios run in `CcdLinkingScenariosTest`,
  where the switch is on. `CcdLinkingSweepDisabledTest` boots the committed default and spies on the service and
  the ledger. The three new env vars are in the README and `CCD-SMOKE.md` tables.

### Task 4: The repair endpoints

- A domain service in `ccd/` (or `domain/`, if the controller rule requires it), gated on `Role.DEEDPOLL_ADMIN`,
  with three operations:
  - `GET /caseworker/ccd-attempts?status=…&page=…&size=…` returns the counts per status and the page of attempts
    that are not `Linked` (decision 11). An unknown status is a 400.
  - `POST /caseworker/ccd-attempts/{attemptId}/reconciliation` performs `NeedsRepair → AwaitingConfirmation`.
  - `POST /caseworker/ccd-attempts/{attemptId}/retry` performs `NeedsRepair → Pending`. It is a 409 when
    `submissionMayHaveReached` is true, and the message says why in words an admin can act on.

  Both `POST`s are 409 when the attempt is not `NeedsRepair`, and 404 for an unknown id. Each records
  `repairedById` and `repairedOn`, and each attempt, made or refused, is logged per decision 10. Neither calls CCD.
  A test captures the log line and asserts it carries no IDAM subject.
- Separate request and response DTOs under `api/caseworker/dto`. Add the ArchUnit or `noFields()` checks that stop
  decision 11's unpublished fields reaching a DTO, following the `requestId` precedent.
- Full-context controller tests:
  - admin allowed; plain caseworker and judicial callers get 403;
  - both 409s and the 404;
  - two concurrent repairs make one move;
  - the list's paging cap.
- Regenerate the OpenAPI document and confirm the diff is only the new paths and schemas.

- [x] admins can see and safely repair attempts, nobody else can, no binding is written, and the build is green

  After Task 4, with Docker: **3270** `test` in 395 files and **14** `integration`, all green. The service is
  `ccd/CcdAttemptRepairs`, and `CaseworkerCcdAttemptController` is its controller. It checks the role, then the
  subject, then the attempt: a caller who is not an admin gets a 403 before anything is read, and an id that is not
  a UUID is a 404 that is not logged. Each move is one conditional statement in `CcdCreationAttemptRepository`
  (`reconcileAgain`, `retryFromScratch`). The retry's `"submissionMayHaveReached" = false` condition is in the SQL as
  well as in `CcdLinkingRule`. A lost race is answered by reading the attempt again, so the second of two admins
  gets a 409 that says what the attempt is now. `CcdCreationAttempts.move` now refuses any move out of `NeedsRepair`,
  so every exit from it is attributed. The status filter accepts the four live statuses. `Linked` is a 400 rather
  than an empty page. The size is capped by `CasePaging` at 100. The response carries `canRetryFromScratch` in place
  of the flag, and `tries` in place of `attempts`. `CcdAttemptDisclosureTest` holds the unpublished fields out of
  the API surface and has the entity counterpart. `claimedBy` is checked on the repair types only, because a
  referral already publishes a name under it. The OpenAPI diff is exactly the three paths and the three
  `CcdAttempt*Response` schemas. `OpenAPIPublisherTest` counts them in their own `CCD_ATTEMPT_PATHS`, so the
  caseworker count stays 34. The new controller test carries `@DirtiesContext(AFTER_CLASS)`: one more cached context
  exhausted the unit JVM's 512 MB heap in a later ArchUnit import. The unfiltered list query binds `Linked` as a
  parameter, because Hibernate writes a JPQL enum literal as a cast to an unquoted type name that Postgres cannot
  find.

### Task 5: Prove it end to end in cftlib

- Extend `CcdLinkingCftlibTest` (or add a sibling), with the sweep switched on in the cftlib profile only:
  - a record created through `POST /caseworker/cases` is linked by the next `GET /caseworker/cases`, and read
    back through CCD;
  - an attempt forced into `NeedsRepair` with `submissionMayHaveReached = false` is retried from scratch through
    the endpoint, then linked by the next sweep, with exactly one case in `ccd.case_data`.
- Record the wall-clock time the sweep added to that list request.

- [x] both journeys pass in cftlib with one case per record, and the build is green

  After Task 5: `cftlibTest` **3** tests, green, in about 1m 35s warm. The Docker `build` is unchanged at **3270**
  `test` in 395 files and **14** `integration`, all green. Both journeys are in `CcdLinkingCftlibTest` and run over
  HTTP as `CftlibConfig.ADMIN_USER`, a new simulator user holding only `caseworker-deedpoll-admin`. The sweep is on in
  `application-cftlib.yaml` only. The SDK stores `{}` as each case's data, so "one case per record" is checked as
  the placeholder case type's `ccd.case_data` row count growing by exactly one. The retry journey also checks that
  the record kept a single attempt row. The stack's database outlives a run, so each journey first sweeps until
  nothing is due, which makes its own record the head of the queue. **Sweep cost, measured over four runs:** a warm
  list request that links one record takes **190–204 ms**, against **35–42 ms** with nothing to link, so each linked
  record adds about 150–170 ms. The first list request in a fresh JVM takes **2.9–4.3 s**. That figure is cold
  start (first Feign clients, first IDAM token, JIT), not the sweep's steady cost. With `max-records` at 2, a warm
  sweep that links a full batch adds about a third of a second. The timings are printed with `System.out`, because
  this service's log lines do not reach the `cftlibTest` console.

### Task 6: Full validation and handoff

- Record the final `test`/`integration` counts, Docker and Docker-free, and `cftlibTest`, against Task 1's baseline.
- `docs/CCD-SMOKE.md`: plan 02 recorded that create/read "has no trigger in AAT". That trigger is now
  `CCD_LINKING_SWEEP_ENABLED=true` followed by a case list request. Say so, and add the repair endpoints to the
  procedure.
- `CLAUDE.md`, with at least these silent-breakage rules:
  - the sweep is off by default, and why switching it on before onboarding fails every record;
  - `submissionMayHaveReached` must be committed before submission, and never reset;
  - the case list request links records, and a new caller of the sweep must be outside any transaction.
- Update `docs/RESUME.md`, and add the chart's new env vars if the chart lists the linking ones.

- [x] counts recorded, the smoke procedure and CLAUDE.md describe the new code, and the build is green

  Final, measured 2026-09-27 from `build/test-results/`: with Docker, **3270** `test` in 395 files and **14**
  `integration`, all green (5m 53s); Docker-free, **2187** `test` in 278 files and no `integration`, all green
  (1m 17s); `cftlibTest` **3**, green (1m 26s). Against Task 1's baseline (3150 / 383 and 13; Docker-free 2103 / 271;
  `cftlibTest` 1), that is **120** new `test` tests in 12 files, **84** of them Docker-free in 7 files, one new
  `integration` test and two new `cftlibTest` journeys. `CCD-SMOKE.md` step 3 now has the sweep as its trigger and
  a repair procedure. `CLAUDE.md` has the three rules. `RESUME.md` has a 02b section, and now records plan 02 as
  merged. The chart lists no CCD or linking variables, so none was added.


## Deliberately not in this plan

- A scheduler of any kind; linking from `POST /caseworker/cases`; linking web's online submissions, which is the
  payment plan's.
- A manual bind, unbind or replacement of `ccdCaseReference`.
- The admin screen; publishing `ccdCaseReference` (the payment plan's Task 8).
- Dataverse ingestion: [ccd-migration](../ccd-migration.md). Its imported records are linked by this plan's sweep.
- Documents and CDAM: [plan 03](../deed-poll-api-03-cdam-documents.md).

---

## Flags for Steve

1. ~~**The defaults are mine.**~~ **Settled 2026-09-27: keep them.** That is 2 records and 5 reconciliations per
   sweep, PT5M × tries between retries, and a 10 s ceiling on CCD timeouts. A higher cap for an import window is
   ccd-migration's decision.
2. ~~**Repairs write no case note.**~~ **Settled 2026-09-27: no note, but every repair is logged**, without the
   admin's identity (decision 10).
3. ~~**The sweep is synchronous on the list request.**~~ **Settled 2026-09-27: synchronous**, matching the drain.
   Task 5 measures the cost. If that figure is uncomfortable, stop and record it rather than switching to `@Async`:
   the switch is a decision for Steve.
