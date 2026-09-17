# Plan: referrals to the judiciary (2 of 3) — the referral state machine: claim, direction, close

## Overview

**Second of three api plans** for the in-app judiciary-referral feature (the whole feature is seven plans;
see `referrals-api-1-create-and-withdraw.md` for the shape and the shared decision record, held in the
workspace memory `deed-poll-referrals-feature`). api-1 built the `Referral` entity and the caseworker
operations that exist before a judge is involved — create, list, get, withdraw — and defined the full
lifecycle enum `Open → Claimed → Decided → Closed`, with `Withdrawn` as the caseworker's escape hatch. It
transitioned only into `Open` and `Withdrawn`.

**This plan builds the rest of the state machine** — the three transitions a judge's involvement drives:

- **Claim** — a judge picks a referral out of the shared pool; it becomes theirs and drops off everyone
  else's, with a **2-hour inactivity expiry** that returns it to the pool and leaves a system-authored note
  on the application timeline.
- **Decide** — the judge records the direction (approved/rejected, the directions text, and whether to
  publish to the London Gazette).
- **Close** — the *caseworker*, having actioned the returned direction, closes the referral off the
  "action needed" queue.

Per the boundary agreed with Steve (cut 1, 2026-09-17): **api-2 owns the referral state machine end to end —
the columns each transition mutates, the transition logic, and the one caseworker-facing HTTP endpoint
(close).** api-3 is a thin judicial *exposure* layer — the shared-pool list, the read-through to application
details/notes, and the judicial HTTP endpoints that **call into this plan's `claim` and `decide`
transitions**. Keeping each transition's logic with the columns it mutates is the point of the cut: one plan
owns "how a referral changes state," and it is fully testable here with mock tokens, because a transition
needs a *role claim*, not a real judicial identity.

## What this plan adds to the table, and why it is additive

api-1's `deedpoll_referral` already has `state` (the five-member `ReferralState` enum), `reasons`, `notes`,
`urgency`, `urgencyReason`, `migrated`, and audit columns. This plan **ALTERs that table** (migration `V8`,
additive — it adds columns, drops and edits nothing) with two groups:

- **Claim columns:** `claimedBy` text null (the judge's display name from the token), `claimedAt`
  timestamp null, `lastTouchedAt` timestamp null (refreshed on every judicial interaction — the input to the
  inactivity sweep).
- **Direction columns:** `decision` `"MasterDecision"` null (the existing Approved/Rejected enum, reused —
  decision below), `directionsText` text null, `publishToGazette` boolean null, `decisionDate` timestamp
  null, `decidedBy` text null (the judge's display name at decision time).

All nullable, because an `Open` referral has none of them and a migrated/backfilled one never will. No
column on `deedpoll_deedpoll` is touched.

## Decisions taken — implement these, do not revisit

1. **Claim expiry is release-on-read, not a scheduler** (decision 22). **There is no scheduling
   infrastructure in this repo** — no `@EnableScheduling`, no `@Scheduled`, no injectable clock, only
   `AuditClock.now()` (static UTC). Do not add scheduling. Instead:

   - A claim is `claimedBy` + `claimedAt` + `lastTouchedAt`. `lastTouchedAt` is refreshed on every judicial
     interaction with that referral (claiming it, reading it through the judicial surface, saving a
     direction).
   - Staleness is **computed and reclaimed as a side effect of reading the pool**, via a single method on
     the referral service — `reclaimStaleClaims()` — which runs one conditional statement:

     ```sql
     UPDATE deedpoll_referral
        SET state = 'Open', claimedBy = null, claimedAt = null, lastTouchedAt = null
      WHERE state = 'Claimed' AND lastTouchedAt < :now - :window
     ```

     and, for **each row the statement actually changed** (rows-affected > 0), writes a system-authored
     timeline note ("Referral claim by {judge} lapsed after inactivity"). It is called at the top of api-3's
     pool query, and defensively at the top of `claim` (so a stale claim never blocks a new one).
   - **Race-safe with no lock:** the conditional `UPDATE … WHERE state='Claimed' AND lastTouchedAt < …` is
     atomic, so two concurrent pool loads across two replicas reclaim a given referral once — exactly one
     note is written. The database solves the multi-replica problem; do not add a distributed lock or a
     scheduled sweep to "fix" a race that does not exist.
   - **A GET with a side effect is deliberate here.** Contain it in `reclaimStaleClaims()` with a class-level
     javadoc explaining why (this section), the same "implement, do not revisit" treatment the saved-queries
     plan gave its deliberate 404s. A reviewer who has not read this will read the mutating read as a bug.
   - **The note may lag the 2-hour mark by up to one pool load**, which in practice is minutes — the pool is
     the judicial dashboard, the most-polled judicial page. The only unnoted case is a lapse on a referral
     nobody ever looks at again, when the note has no audience anyway. A `@Scheduled` sweep at exactly-2h is
     a clean additive follow-up (same conditional UPDATE, fired by a timer) — explicitly out of scope.

2. **The inactivity window is configurable, default 2 hours** (decision 19). A `@ConfigurationProperties`
   record (`ReferralProperties`, `prefix = "deedpoll.referrals"`) with a `Duration claimExpiry` defaulting to
   `PT2H`, wired the way `IdamProperties` is. Add it under the existing `deedpoll:` block in
   `application.yaml` with a comment that it is tunable once real judicial behaviour is observed. Do not
   hard-code the window in the service; the sweep reads it.

3. **The `JUDICIAL` role is added to the `Role` enum in this plan; IDAM provisions it later** (idam-1).
   `Role.JUDICIAL("caseworker-deedpoll-judicial")` — the claim value follows the existing
   `caseworker-{product}-{subrole}` convention (confirm the exact subrole string with idam-1; `judicial` is
   the working value). Gating a transition to a role IDAM does not yet issue is **safe and testable**:
   `Role.fromClaimValue` already returns empty for a claim this service has no rule for, so a token never
   carrying `JUDICIAL` simply never passes the gate, and unit tests supply a `CurrentUser` with the role
   directly. This plan adds no IDAM configuration and no login surface.

4. **Claim and decide require the `JUDICIAL` role; close requires an authenticated caseworker (no finer
   gate).** The transitions call `caller.requireAnyRole(Role.JUDICIAL)` — the `requireAnyRole` /
   `ForbiddenError` → 403 pattern `NoteService`/`WitnessService` already use for `DEEDPOLL_ADMIN`. **Close is
   a caseworker action** (decision 10, 18) — the caseworker actions the direction and closes the referral —
   so it takes any authenticated caseworker and no role check, exactly as create/withdraw do in api-1. A
   judge does not close; a caseworker does not claim or decide.

5. **The direction reuses the existing `MasterDecision` enum (`Approved`/`Rejected`)** rather than minting a
   referral-specific one. It is the ported decision vocabulary and it is already exposed on the case DTOs;
   the referral's `decision` column is the same type. `directionsText` is free text (the form's "Directions,
   including any comment on Gazette publication"); `publishToGazette` is the boolean the judge sets.

6. **Gazette publication is the judge's decision, set on the direction — never derived from the caseworker's
   reasons** (decision 3). `publishToGazette` is a required part of a direction submission, captured from the
   judge. Do not default it from a `NonPublication` reason or any other referral field.

7. **Recording a direction is a single transition, not a draft-then-submit.** The admin-side
   check-your-answers page (the "Signed" attestation, decision 14) is `deed-poll-admin`'s concern; api
   receives one complete, confirmed direction and records it atomically: `decision`, `directionsText`,
   `publishToGazette`, stamping `decisionDate = AuditClock.now()` and `decidedBy = caller.name()` from the
   token. api does not model a draft. A direction is permitted **only on a referral the caller currently
   holds** (state `Claimed` and `claimedBy` is this judge) — deciding a referral you have not claimed, or
   that another judge holds, is a 409. This is what the withdraw-voids-a-claim rule relies on: a referral
   withdrawn or reclaimed out from under a judge is no longer `Claimed`-by-them, so their late decision
   submission 409s rather than landing on a stale referral (decision 12).

8. **Claim is permitted only on an `Open` referral, and is idempotent-free — a second claimant gets a 409.**
   After `reclaimStaleClaims()` runs, `claim` moves `Open → Claimed`, stamping `claimedBy`, `claimedAt` and
   `lastTouchedAt`. A referral already `Claimed` (by anyone, including the same judge) is a 409 — there is no
   silent re-claim, because two judges seeing "claimed by me" for different reasons is worse than an explicit
   conflict. A judge who wants to keep working simply interacts, which refreshes `lastTouchedAt`.

9. **Close is permitted only on a `Decided` referral.** `Decided → Closed` (decision 10) — closing an
   `Open`, `Claimed`, `Withdrawn` or already-`Closed` referral is a 409. Close clears nothing and reverses
   nothing; it is the caseworker's acknowledgement that the direction has been actioned, and it is what drops
   the referral off admin-2's "Returned — action needed" count (that count is "state = Decided").

10. **No sync-back to the legacy flat columns on close — MVP leaves them as the ported historical snapshot.**
    Decision 7 deferred this to api-2; this plan resolves it as **leave-as-is**. The flat `masterDecision`,
    `masterDirections`, `publishToLondonGazette` etc. on `deedpoll_deedpoll` remain the pre-migration
    snapshot; the live direction is on the referral. Writing a closed referral's direction back onto the case
    would make the case columns say two different things over time (the backfilled snapshot vs the latest
    referral) and re-introduce the single-referral-per-case model this feature replaced.
    *(Steve: this is my call, flagged — the alternative is api keeping the case's flat columns in sync as a
    "latest decision" convenience for any reader still reading them. Say if you want that; it is additive.)*

## The endpoints

**One new caseworker operation** — close. The judicial claim/decide *endpoints* are api-3 (this plan
provides the transitions they call). So the caseworker surface moves **29 → 30**, one new path:

| Method | Path | Who |
|---|---|---|
| `POST` | `/caseworker/cases/{id}/referrals/{referralId}/closure` | any caseworker — 409 unless the referral is `Decided` |

`claim` and `decide` are **domain transitions in this plan, exposed over HTTP by api-3.** They are exercised
here by unit tests on the referral service with a `CurrentUser` carrying `JUDICIAL`; they gain no route until
api-3. This keeps api-2's published-spec change to the single close operation.

## Open questions — flagged, NOT answered here

- **Sync-back to the flat columns** (decision 10) — resolved leave-as-is, flagged for Steve to override.
- **The exact `JUDICIAL` claim-value string** — `caseworker-deedpoll-judicial` is the working value;
  idam-1 confirms what IDAM will actually issue. If it changes, it changes in one place (`Role.JUDICIAL`).
- **A scheduled sweep at exactly-2h** — additive follow-up; decision 1 keeps it a one-method change.

## Constraints

Same as api-1 (Java 21, `-Werror`, checkstyle on five source sets; `TESTCONTAINERS_RYUK_DISABLED=true`;
mixed-case columns + `NAMED_ENUM`/`JSON` `@JdbcTypeCode`; Flyway owns the schema, Hibernate validates;
DTOs not entities from controllers; a controller test boots the whole context; do not edit
`reference/`). Additional to this plan:

- **The next migration is `V8__`.** `V1`–`V5` are committed; `V6__referrals.sql` and
  `V7__backfill_referrals.sql` are api-1's. This plan adds `V8__referral_claim_and_direction.sql`, an
  **additive `ALTER TABLE`** — it adds nullable columns and edits nothing. **Never edit a committed
  migration.** (If api-1 has not merged when this runs, V6/V7 are still the immediately-preceding numbers —
  check the highest `V*` on the branch before numbering, do not assume.)
- **`reclaimStaleClaims` must be plain unit-testable without a container.** The staleness rule (a claim older
  than the window is reclaimed; a fresh one is not; the note is written once per reclaimed row) is the most
  important logic in this plan and the multi-replica reviewer worry — put it where CI runs it. The atomic
  `UPDATE` itself needs a repository test that boots a container, which CI excludes
  (`INTEGRATION_TESTS_ENABLED=false`); that is accepted, so keep the *decision* logic separable from the SQL.
- **`OpenAPIPublisherTest` runs in `build`.** Add `/caseworker/cases/{id}/referrals/{referralId}/closure` to
  `CASEWORKER_PATHS` and change the count `29` → `30` (plus `@DisplayName` and the comment) in the **same
  task** that adds the endpoint, or `build` goes red. Grep every prose count:
  `git grep -inE '\b(29|twenty-nine|30|thirty)\b'` — `README.md`, `docs/RESUME.md`, `CLAUDE.md`,
  `package-info.java`. (These numbers assume api-1 landed 29; if this branch is stacked on api-1's, the
  starting count is 29 — verify with the grep before editing.)

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

`build` is compile + checkstyle across five source sets + `test` + `integration` (`build.gradle:113`), which
is why `OpenAPIPublisherTest` runs here.

**This plan is stacked on api-1's branch** (`referrals-api-plans`), so `deedpoll_referral` and the lifecycle
enum already exist. **Measure the baseline on this branch before Task 1** by summing `tests="…"` across
`build/test-results/{test,integration}/*.xml`, and record it next to the final count in Task 4 — do not quote
api-1's numbers.

**Baseline measured on this branch, 2026-09-17, before Task 1: `test` 1,813, `integration` 7.** After Task 1:
`test` 1,818 (+4 `ReferralPropertiesTest`, +1 `EntityMappingTest`), `integration` 7. After Task 2: `test` 1,854
(+29 `ReferralServiceTest` — claim, touch and the sweep — +3 `ReferralNotesTest`, +4 `ReferralRepositoryTest`),
`integration` 7.

**A worker or container startup timeout is an environment failure, not a code failure** — re-run before
investigating, and consider a `git archive` copy under `/tmp`.

## Tasks

### Task 1: The claim and direction columns, and the configurable window

`V8__referral_claim_and_direction.sql`: `ALTER TABLE "deedpoll_referral"` adding the five direction columns
and three claim columns from "What this plan adds" above — all nullable, mixed-case names, `decision` typed
`"MasterDecision"`. No new enum type (both `MasterDecision` and `ReferralState` already exist). Edit no
committed migration and no `deedpoll_deedpoll` column.

Add the new fields to the `Referral` entity with the right `@JdbcTypeCode` (`NAMED_ENUM` on `decision`);
they validate against V8 under `ddl-auto: validate`. Extend the entity's round-trip test to cover them.

`ReferralProperties` (`@ConfigurationProperties(prefix = "deedpoll.referrals")`, a `Duration claimExpiry`
defaulting to `PT2H`), registered like `IdamProperties`, with the `deedpoll.referrals.claim-expiry` key added
under the `deedpoll:` block in `application.yaml` and a comment that it is tunable.

- [x] `V8` added, additive, no committed migration or `deedpoll_deedpoll` column edited; the eight new
      columns validate under `ddl-auto: validate`; `decision` mapped `NAMED_ENUM`; entity round-trip test
      covers the new fields; `ReferralProperties` binds `deedpoll.referrals.claim-expiry` with a 2h default
      and a unit test proves the default and an override; `./gradlew build` green

### Task 2: Claim, and the release-on-read sweep

`domain/referrals/`: extend the referral service with `claim` and `reclaimStaleClaims`, and add
`Role.JUDICIAL`.

- `claim(deedPollId, referralId, caller)` — `caller.requireAnyRole(Role.JUDICIAL)`; call
  `reclaimStaleClaims()` first; then move an `Open` referral to `Claimed`, stamping `claimedBy = caller.name()`,
  `claimedAt` and `lastTouchedAt` from `AuditClock.now()`; a non-`Open` referral is a `ConflictError` (409)
  — decision 8. Case-scoped like every operation.
- `reclaimStaleClaims()` — the conditional `UPDATE` of decision 1, reading the window from
  `ReferralProperties`, writing one system-authored note (`NoteAuthor.NONE`) per reclaimed row. Its decision
  logic (which claims are stale given a window and a now) is a **container-free unit test**; the atomic SQL
  gets a repository test that boots a container (CI-excluded, accepted). Class-level javadoc explains the
  deliberate mutating-read.
- A `touch` helper that refreshes `lastTouchedAt`, called by `claim` and (later, from api-3) by every
  judicial read/write of a held referral.

- [x] `Role.JUDICIAL` added with the convention claim value; `claim` requires `JUDICIAL` (403 without),
      reclaims stale claims first, moves `Open`→`Claimed` stamping the three claim fields, and 409s on a
      non-`Open` referral; `reclaimStaleClaims` releases a claim older than the configured window and leaves
      a fresh one, writing exactly one service-account note per reclaimed row — staleness logic proven in a
      container-free test; a repository test covers the atomic UPDATE; `./gradlew build` green

### Task 3: Decide, and close

- `decide(deedPollId, referralId, direction, caller)` — `requireAnyRole(Role.JUDICIAL)`; permitted **only**
  when the referral is `Claimed` and `claimedBy` is this judge, else 409 (decision 7); records `decision`,
  `directionsText`, `publishToGazette`, stamps `decisionDate`/`decidedBy`, moves `Claimed → Decided`, and
  refreshes `lastTouchedAt`. Validate the direction (a decision is required; `directionsText` bounded;
  `publishToGazette` required) in the 400 shape, container-free.
- `close(deedPollId, referralId, caller)` — any authenticated caseworker, **no** role gate (decision 4);
  permitted only when `Decided`, else 409 (decision 9); moves `Decided → Closed`. Exposed as
  `POST /caseworker/cases/{id}/referrals/{referralId}/closure` on `CaseworkerReferralController`, with a
  `CloseReferralResponse`/updated `ReferralResponse` DTO (never the entity).
- **Spec, same task:** add the closure path to `CASEWORKER_PATHS`, count `29 → 30`, update `@DisplayName`,
  the comment, and every prose count found by the grep.

`decide` gains **no HTTP route here** — api-3 exposes it. Unit-test it on the service with a `JUDICIAL`
`CurrentUser`, including the "decide a referral you do not hold → 409" and "decide after being reclaimed →
409" cases, because those are the withdraw/lapse-voids-a-claim guarantees (decision 12) and they need no
container.

- [ ] `decide` requires `JUDICIAL`, is permitted only on a referral this judge holds (409 otherwise, incl.
      after reclaim/withdraw), records the direction and moves `Claimed`→`Decided`, validates the direction
      as a 400; `close` is caseworker-only-authenticated, permitted only on `Decided` (409 otherwise), moves
      `Decided`→`Closed`, and is live at `POST …/closure` returning a DTO; `CASEWORKER_PATHS` and the count
      updated to 30 and `OpenAPIPublisherTest` green; every prose count grepped and updated;
      controller test boots the context for close; `./gradlew build` green

### Task 4: Security and full validation

Security tests in the `*Security*` pattern: a caseworker token cannot `claim` or `decide` (403 — the
transitions demand `JUDICIAL`, tested on the service since the endpoints are api-3's), a `JUDICIAL` token
cannot `close` through the caseworker endpoint via the same-role reasoning, and cross-case isolation holds
for close (a referral of one case is unreachable through another's path). Keep the role assertions
container-free.

- [ ] Security tests: caseworker refused `claim`/`decide`, close cross-case-isolated, role gates fail
      closed; `./gradlew build` green from cold with `TESTCONTAINERS_RYUK_DISABLED=true`; checkstyle clean on
      all five source sets; test and integration totals recorded next to this branch's baseline; the count
      with `INTEGRATION_TESTS_ENABLED=false` recorded, so it is visible how much of this plan's coverage CI
      actually runs

## Deliberately not in this plan

- **The judicial HTTP surface** — the shared-pool list, the read-through to application details/notes, and
  the `claim`/`decide` *endpoints* that call this plan's transitions. api-3.
- **The judicial IDAM role's provisioning and login surface.** idam-1; this plan only adds the `Role` enum
  member so the gate is expressible and testable.
- **admin-side check-your-answers / the "Signed" attestation.** `deed-poll-admin` admin-3; api records one
  confirmed direction (decision 7).
- **The dashboard "Returned — action needed" count and its alert.** `deed-poll-admin` admin-2; api exposes
  the `Decided` state that the count keys off, which the existing list already carries.
- **Sync-back to the legacy flat columns** (decision 10, leave-as-is) and a **scheduled expiry sweep**
  (decision 1) — both additive follow-ups.
