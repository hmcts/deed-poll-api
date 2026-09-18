# Plan: referrals to the judiciary (2b of 3) — the case's current referral state, as a queryable summary field

## Why this plan exists, and why it is a plan of its own

The dashboard's **"Returned — action needed"** count (admin-2) is "how many cases have a referral the judge
has decided but the caseworker has not yet closed" — a count of cases whose active referral is in state
`Decided`. Steve's decision (2026-09-17): admin-2 gets that count by asking api the question it already
answers for every other list facet — a filtered `listCases` call, `?where=currentReferralState:is:Decided`,
reading `total` off the response — and **the same filter, clicked, is the worklist**. No bespoke count
endpoint, no second shape to keep in step; the count and the list it drills into are one query. That matches
decision 9 (the count is a live read, and it doubles as the queue).

**That question has no answer today, and cannot be given one by admin-2 alone.** A referral's state lives in
the `deedpoll_referral` table; the case list filters on columns of `deedpoll_deedpoll`. `CaseSpecifications`
has no join in it — every filterable field resolves through `root.get(name)` against the one entity
(`CaseSpecifications.java:240`, the `predicate` switch), and `CaseQuery.queryableField` admits only a name in
`FormFields.FIELD_BY_NAME` that `DeedPollFields.readableFields()` also holds (`CaseQuery.java:185`). There is
no field, no column, and no join by which a caseworker can ask "which cases have a `Decided` referral." api-2
exposes the `Decided` state **per case** (on that case's referral list); it exposes no cross-case aggregate,
and api-2's line 296 ("the existing list already carries the Decided state") is about the per-case list, not
the case-list landing page. This is the gap.

**This is the producer half of admin-2, cut out as its own api plan.** It is deliberately not folded into
api-2: api-2 owns *the referral state machine* (how a referral changes state), and this owns *the case's
read-model* (how the current state is projected onto the case row and made queryable). They touch different
tables for different reasons, and merging them would put a list-page projection concern inside a plan whose
job is transitions. This is the "field reaches a caller by being asked for" direction the
`CaseSummaryResponse` docblock already names (`CaseSummaryResponse.java:28`).

**This grows the feature from seven plans to eight.** Running order is unchanged except for the insertion:
api-1 → api-2 → **api-2b (this)** → api-3 → idam-1 → admin-1 → admin-2 → admin-3. It **must run after api-2**,
because the column it maintains is written at api-2's transition sites (claim/decide/close/reclaim), which do
not exist until api-2 lands. It **must run before admin-2**, which is its only consumer.

## The decision taken, and the one it forecloses

Steve chose (2026-09-17, from two options) a **denormalised column** on the case, kept in step by the
referral state machine — over a computed correlated-subquery join evaluated at query time. The consequences,
owned here:

- **Filterable for free.** A real `"currentReferralState"` enum column on `deedpoll_deedpoll` is queryable
  through the exact path `state` already uses — the `ENUM` branch of `CaseSpecifications.enumeration`
  (`CaseSpecifications.java:341`) resolves it via `root.get("currentReferralState")` and binds the constant
  off the column's own Java type. **This plan adds no predicate code and no join.** That is the whole reason
  the column was chosen over the subquery: the subquery would have needed a new branch in
  `CaseSpecifications` that does not fit the "a field is a column" model the class is built on.

- **It is derived state, and derived state can drift.** The mitigation is structural, not vigilance: the
  column is **recomputed from truth after every transition**, never set to a per-site guess. See decision 2.

- **A migrated/legacy case has no active referral, so its column is NULL** — and NULL is the honest answer,
  distinct from any live state. Decision 3 covers what the backfill writes.

## Decisions — implement these, do not revisit

1. **One nullable enum column, `"currentReferralState"`, on `deedpoll_deedpoll`, typed `"ReferralState"`**
   (the DB enum api-1 created — reuse it, mint nothing). NULL means "this case has no active referral right
   now": either it never had one, or its referrals are all terminal (`Closed`/`Withdrawn`). A non-NULL value
   is exactly one of `Open`/`Claimed`/`Decided` — the non-terminal states — because **a case has at most one
   non-terminal referral at a time** (api-1 decision 11: create 409s if the case already has a referral in a
   non-terminal state). This is what makes a single column correct rather than a lie: there is never a second
   active referral whose state it would have to also represent.

2. **The column is recomputed from the referral table after every state transition — it is never set to a
   per-site value.** After any transition changes a referral's state (create → `Open`, withdraw →
   `Withdrawn`, claim → `Claimed`, decide → `Decided`, close → `Closed`, and the bulk reclaim → `Open`), the
   case's column is set to *the state of that case's current non-terminal referral, or NULL if it has none*.
   A single helper — `refreshCurrentReferralState(deedPollId)` — runs one query (the case's non-terminal
   referral, of which there is at most one) and writes the result onto the case.

   - **Why recompute, not push.** A push ("on close, set the case to `Closed`") is a second place the rule
     lives and the place it drifts: close moves the referral to a *terminal* state, so the case column must
     become NULL, not `Closed` — a naive push would leave the case reading `Closed` forever and every
     dashboard count would be wrong. Recompute-from-truth cannot make that class of mistake: it asks the
     table what the current state *is* rather than assuming what a transition *did*.
   - **`reclaimStaleClaims` is the one bulk site.** api-2's sweep (`referrals-api-2` decision 1) is a single
     conditional `UPDATE … WHERE state='Claimed' AND lastTouchedAt < :cutoff` that may move *many* referrals
     `Claimed → Open` at once. Its case-column counterpart is therefore **also a set-based statement**, not a
     per-row helper call in a loop — recompute the column for exactly the cases whose referral the sweep
     reclaimed (`Claimed → Open` means those cases go from `currentReferralState = 'Claimed'` to `'Open'`).
     One `UPDATE` mirroring the sweep's `WHERE`, in the same transaction as the sweep, so the two never
     disagree even across replicas. This is the one transition where "call the helper per row" is wrong.
   - **Transactionality.** The column write is in the same transaction as the transition that triggers it, so
     a case never persists with a referral in one state and a case column naming another. A test asserts this
     for each transition.

3. **The backfill leaves the column NULL for every migrated case** (migration `V{n}`, additive).
   **CORRECTED IN TASK 3 — read that note before this decision.** The premise below is false: `V7` creates a
   `Closed` referral only for a case whose Master decided, and a live **`Open`** one for a case referred and
   still awaiting a decision. NULL is right for the first and wrong for the second, so `V10` recomputes the
   column from the referral table instead of leaving it defaulted. What survives is the prohibition: never
   write `Closed` onto a case. api-1's
   `V7__backfill_referrals.sql` creates one `Closed` referral per legacy case that had referral data
   (api-1 decision, "state = 'Closed' where …"). `Closed` is terminal, so **the correct current state for
   every backfilled case is NULL** — there is no active referral. The new column defaults to NULL and the
   backfill sets nothing, which is the same thing said two ways; assert it rather than write it. A case with
   no referral data at all is likewise NULL. **Do not** backfill the column to the legacy referral's `Closed`
   state — that would make migrated cases show up as having a live-but-closed referral, which is precisely
   the single-referral-per-case snapshot model this feature replaced (api-2 decision 10).

4. **The column is registered as a read-only, queryable form field — the same shape as `state`.** Queryable
   in this service means "a `FormFields` field whose name is in `DeedPollFields.readableFields()`"
   (`CaseQuery.java:185`). The precedent is exact: `state` is
   `enumField("state", "State", "state", RIGHT).asReadOnly()` (`FormFields.java:73`) — a read-only enum that
   is not editable but *is* one of the most useful things to ask about (`CaseQuery.java:180`). Register
   `currentReferralState` the same way: an `.asReadOnly()` `enumField` on a form section, a reader in
   `DeedPollFields`, and a `DynamicsValues.LABELS` set it names (decision 5).

   - **Consequence, flagged not blocked (api-2 decision-10 style):** because queryability is coupled to being
     a form field, the field **renders on the case form** as a read-only value, like `state` and the two day
     counts do. That is a visible addition to the case detail form, not only to the list filter. It is the
     honest cost of the chosen mechanism and it is small — a read-only "Current referral state" alongside
     "State". If Steve wants it queryable-but-not-rendered, that is a **change to the query model** (decouple
     `queryableField` from `FORM_TABS`), which is a larger, separate piece of work; this plan takes the
     existing precedent rather than reshaping the query layer. *(Steve: say if the rendered field is
     unwanted; the alternative is not free.)*

5. **The column needs a `DynamicsValues.LABELS` set, because `enumField` requires one and `FormFieldsTest`
   pins it.** `FieldDef.enumOf` takes an `enumSet` naming a `LABELS` map (`FieldDef.java:33`), and
   `FormFieldsTest` asserts every `enumSet` used in `FORM_TABS` resolves (`FieldDef.java` docblock; the
   labels map is built in `DynamicsValues.labels()`, `enums/DynamicsValues.java:245`). `ReferralState` is a
   DB-native string enum with **no Dynamics integer mapping**, unlike `state`'s `DEED_POLL_STATE_LABEL`. Add a
   labels set keyed by the enum's own member names (`Open` → "Open", `Claimed` → "Claimed", `Decided` →
   "Decided" — human labels for the three non-terminal states; the terminal two need no entry because a case
   is never non-NULL in them, but include all five if `byName` over the enum is the simplest honest source,
   and let `FormFieldsTest` be the check). Follow whatever the existing string-keyed label sets do; do not
   invent a Dynamics integer for a state that never had one.

6. **No new HTTP operation. The caseworker operation count stays 30.** This plan adds a *field* to two
   *existing* responses (`CaseSummaryResponse` the list row, and `SavedCaseResponse`) and a *queryable
   field*, all served by already-published operations. No path is added, so `CASEWORKER_PATHS` and the `30`
   count do **not** change. What *does* move is `OpenAPIPublisherTest`'s schema surface: both DTOs gain a
   property and the queryable-field set gains a member, so any assertion in that test (or `DeedPollSchemas` /
   the published `openapi.json`) over those schemas' properties or the field list must be updated in the same
   task, or `build` goes red. **Grep before assuming which:** `git grep -n currentReferralState` will be empty
   at the start; grep `CaseSummaryResponse`, `SavedCaseResponse` and the schema/field-count assertions to find
   every place the new property is counted.

7. **`currentReferralState` MUST be exposed on `SavedCaseResponse`, not only `CaseSummaryResponse` — this is
   the cross-repo fact that keeps admin-2 a clean consumer.** admin-2 develops and unit-tests against
   deed-poll-admin's in-memory mock (`libs/deed-poll-api-client/src/mock/server.ts`), which is also what
   `yarn dev` runs. That mock derives its set of *queryable* fields from the wire `SavedCaseResponse` schema's
   keys, **not** `CaseSummaryResponse`'s (it is the fuller row), and it filters by reading the property off
   each stored case by name. So a `where=currentReferralState:is:Decided` filter — the exact call admin-2
   makes — **400s in the admin mock ("names no case field") unless `currentReferralState` is a property of
   `SavedCaseResponse`**. On the real api this does not bite (queryability comes from
   `DeedPollFields.readableFields()`, independent of any DTO), but the mock is the contract admin builds
   against, and the honest fix is here, not a mock special-case in admin. `state` is on both DTOs for exactly
   this reason; `currentReferralState` follows it. **Do not** try to make it queryable while omitting it from
   `SavedCaseResponse` — that leaves admin unable to test its own count without hacking the mock.

## What this plan adds, and why every piece is additive

- **A nullable enum column** on `deedpoll_deedpoll` (migration `V{n}`, `ALTER TABLE … ADD COLUMN`, additive —
  adds one nullable column, edits nothing). Defaults NULL.
- **One field on `CaseSummaryResponse`** — `currentReferralState` (`ReferralState`, nullable). The list row
  already carries `state`; this sits beside it. Both `from(...)` factories
  (`CaseSummaryResponse.java:58,71`) map it.
- **The same field on `SavedCaseResponse`** — `currentReferralState` (`ReferralState`, nullable), mapped
  `record.getCurrentReferralState()` in its `from(DeedPollWithComputed)` factory beside `record.getState()`
  (`SavedCaseResponse.java:58,169`). **This is not optional and not gold-plating** — it mirrors exactly how
  `state` is carried on both DTOs, and the reason is a downstream contract fact, decision 7.
- **One `FormFields` field**, read-only, queryable, plus its `DeedPollFields` reader and its
  `DynamicsValues` labels set.
- **One recompute helper** on the referral service, called at every transition, plus the set-based mirror of
  the reclaim sweep.

No column is dropped, no committed migration edited, no `deedpoll_referral` column touched, no HTTP operation
added, no existing field's type changed.

## Open questions — flagged, NOT answered here

- **The rendered read-only field** (decision 4) — the chosen mechanism renders it on the case form; flagged
  for Steve to override, at the cost of reshaping the query model.
- **The exact label strings** for the `ReferralState` labels set (decision 5) — "Open"/"Claimed"/"Decided"
  are the working values; a caseworker-facing wording pass can revise them, they are display-only.
- **Highest migration number** — this stacks after api-2's `V8`; **verify the highest `V*` on the branch
  before numbering** (`git ls-tree --name-only <branch> src/main/resources/db/migration/`), do not assume
  `V9`.

## Constraints

Same as api-1/api-2 (Java 21, `-Werror`, checkstyle on five source sets; `TESTCONTAINERS_RYUK_DISABLED=true`;
mixed-case columns + `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` on the enum column, or Hibernate sends an ordinal;
Flyway owns the schema, Hibernate validates under `ddl-auto: validate`; DTOs not entities from controllers; a
controller test boots the whole context; do not edit `reference/`). Additional to this plan:

- **This plan is stacked on api-2's branch** (`referrals-api-2` → whatever it merges to). It needs api-2's
  transition sites to hook the recompute into, so it cannot be developed against master. **Verify api-2 has
  landed the six transition sites before Task 2** — grep the referral service for `claim`, `decide`, `close`,
  `reclaimStaleClaims`; if any is absent, STOP: api-2 is not merged and this plan has nothing to hook.
- **The recompute logic must be plain unit-testable without a container.** "Given a case's referrals, what is
  the current state" is pure and is the important rule (NULL when all terminal; the one non-terminal state
  otherwise) — put it where CI runs it (`INTEGRATION_TESTS_ENABLED=false` excludes container tests). The
  set-based reclaim mirror needs a repository/container test, which CI excludes — accepted; keep the decision
  logic separable from the SQL, exactly as api-2 does for its sweep.
- **`OpenAPIPublisherTest` runs in `build`** (compile + checkstyle ×5 + `test` + `integration`,
  `build.gradle:113`). The summary-schema property and the queryable-field set change here; update the test's
  assertions in the **same task** that adds the field. `git grep -n currentReferralState` starts empty; grep
  `CaseSummaryResponse` and any field-count/schema-property assertion to find what moves.
- **Measure the baseline on this branch before Task 1** by summing `tests="…"` across
  `build/test-results/{test,integration}/*.xml`; record it next to the final count in the last task. Do not
  quote api-2's numbers.

  **Baseline measured on `referrals-api-2b` at Task 0 (2026-09-17), `./gradlew build` green: `test` = 1920
  tests / 245 classes, `integration` = 7 tests / 3 classes, total 1927, 0 failures, 0 errors.** Compare the
  final count in Task 4 against these numbers.
- **A worker or container startup timeout is an environment failure, not a code failure** — re-run before
  investigating; consider a `git archive` copy under `/tmp`.

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

## Tasks

### Task 0: Reconciliation gate — STOP unless api-2 has landed

This plan was written against api-2's *plan*, ahead of api-2's *code*. Before building, confirm the code
matches the assumptions, and STOP if it does not:

- api-2's six state transitions exist on this branch: `claim`, `decide`, `close` and `reclaimStaleClaims` on
  the referral service, plus api-1's `create` and `withdraw`. (`git grep -n "reclaimStaleClaims" src/main`.)
- `deedpoll_referral.state` is the `"ReferralState"` DB enum with members `Open`, `Claimed`, `Decided`,
  `Closed`, `Withdrawn`, and `ReferralState.isTerminal()` returns true for exactly `Closed`/`Withdrawn`
  (confirmed present on `referrals-api-2`).
- api-1 decision 11 holds in code: create refuses (409) when the case has a non-terminal referral, so **at
  most one non-terminal referral per case**. If this guard is gone, decision 1 is unsafe and the whole plan
  must be reconsidered — STOP and report.

- [x] All three confirmed against code, not the plan text; if any fails, STOP and report which and how the
      reality differs — do not build on a wrong assumption

**Gate result (2026-09-17, verified on `referrals-api-2b`) — all three hold, this plan may proceed:**

1. **All six transition sites exist** on `ReferralService.java`, each `@Transactional`, so the recompute of
   decision 2 has a tail to hook in the transition's own transaction: `createReferral` (:151),
   `withdrawReferral` (:225), `claim` (:268), `decide` (:359), `close` (:405), `reclaimStaleClaims` (:439).
   There is a **seventh** mutating method the plan does not name — `touch` (:309), api-2's claim-keepalive.
   It moves `lastTouchedAt`, **not `state`**, so it needs no recompute (Task 2's list is still exactly right);
   noted so Task 2 does not "helpfully" add a call there. `reclaimStaleClaims` is also invoked from inside
   `claim` (:272), so the set-based mirror must be correct when nested in another transition's transaction.
2. **`"ReferralState"` is the DB enum with the five expected members**, in lifecycle order, created at
   `V6__referrals.sql:48` (`Open`, `Claimed`, `Decided`, `Closed`, `Withdrawn`), and
   `ReferralState.isTerminal()` (`entity/enums/ReferralState.java:54`) returns true for **exactly**
   `Closed || Withdrawn`. So decision 1's "non-NULL is exactly one of Open/Claimed/Decided" is the enum's own
   terminal split, not a second hand-written set — reuse `isTerminal()` in the recompute rule.
3. **api-1 decision 11 holds in code**, which is what makes a single column correct rather than a lie:
   `createReferral` throws `ConflictError` when `referrals.existsByDeedPollIdAndStateIn(deedPollId,
   LIVE_STATES)` (`ReferralService.java:153`), `LIVE_STATES` being derived from `!isTerminal()` (:116) rather
   than listed, and `DomainErrorHandler:96` maps `ConflictError` → **409**. At most one non-terminal referral
   per case is therefore enforced, so the column never has two live states to represent.

**Also established for Task 1:** the highest committed migration on this branch is **`V8__referral_claim_and_direction.sql`**, so this plan's migration is **`V9__current_referral_state.sql`** (the open question about numbering is answered — it is `V9`, not assumed).

### Task 1: The column, the entity mapping, and the summary field

`V{n}__current_referral_state.sql` (verify the highest `V*` first): `ALTER TABLE "deedpoll_deedpoll" ADD
COLUMN "currentReferralState" "ReferralState" NULL`. Additive; edits no committed migration and no
`deedpoll_referral` column.

Add `currentReferralState` to the `DeedPoll` entity with `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` (an ordinal
otherwise) and a nullable mapping; it validates against `V{n}` under `ddl-auto: validate`. Extend the entity
round-trip test.

Add `currentReferralState` (`ReferralState`, nullable) to `CaseSummaryResponse` and map it in **both**
factories (`from(DeedPoll)` and `from(List<DeedPollWithComputed>)`). Add the **same field to
`SavedCaseResponse`** (decision 7), mapped `record.getCurrentReferralState()` beside `record.getState()` in
its `from(DeedPollWithComputed)` factory (`SavedCaseResponse.java:169`). Update both DTOs' tests and any
schema assertion in `OpenAPIPublisherTest`/`DeedPollSchemas` for the new property on each.

- [x] `V{n}` added (number verified against the branch), additive, column nullable, `NAMED_ENUM`-mapped, and
      validates under `ddl-auto: validate`; entity round-trip test covers it; **both** `CaseSummaryResponse`
      (both factories) and `SavedCaseResponse` expose it; schema assertions updated for both; op count
      unchanged at 30; `./gradlew build` green

**Done (2026-09-17). Two things the plan did not name, both forced by tests that already existed:**

1. **`CaseDetailResponse` had to gain the field too, and the `DeedPollFields` reader had to land here rather
   than in Task 3.** `SavedCaseResponseFieldsTest` pins the saved field set two ways — equal to
   `DeedPollFields.readAll(...)`'s keys plus the derived counts, *and* equal to `CaseDetailResponse`'s
   components minus the three children, in order. So decision 7's "expose it on `SavedCaseResponse`" is not
   satisfiable in isolation: without the reader the first assertion fails, and without the detail response the
   second does. Both were added in the referral block of all three (`readers()`, `CaseDetailResponse`,
   `SavedCaseResponse`), at the same position. This does **not** pre-empt Task 3: queryability needs
   `FormFields.FIELD_BY_NAME` as well (`CaseQuery.java:185`), and the field is on no form tab yet, so
   `where=currentReferralState:…` is still refused as "names no case field" until Task 3 registers it.
2. **`OpenAPIPublisherTest` needed no edit, and the op count is unchanged at 30 as predicted.** Decision 6
   anticipated a schema-surface change; in fact that test asserts over *write-body* schemas
   (`DeedPollSchemas.writeBodyFields()`, `CreateCaseRequest`/`UpdateCaseRequest`) and the response DTOs are
   not among them. `currentReferralState` is read-only, so `writeBodyFields()` is unchanged. There is no
   committed `openapi.json` and no `DeedPollSchemas` reference to either response DTO. The queryable-field
   set is not asserted in that test either — Task 3's mention of it should be read the same way.

**Also recorded:** the field is readable but **not** writable, which made
`CaseResponseFields.NOT_WRITABLE_BY_A_CALLER` eight rather than seven (a caller able to patch it could tell
the case list a case has a decided referral when it has none). `test` = 1923 / 246 classes, `integration` =
7 / 3 classes, total 1930 against the 1927 baseline, 0 failures.

### Task 2: Recompute-from-truth, wired into every transition

Add `refreshCurrentReferralState(deedPollId)` to the referral service: it reads the case's single
non-terminal referral (there is at most one) and writes its state — or NULL if none — onto
`deedpoll_deedpoll.currentReferralState`, in the caller's transaction. The pure rule ("given a set of
referrals, the current state is the one non-terminal state, else NULL") is a **container-free unit test**.

Call it at the tail of every per-referral transition: `create`, `withdraw` (api-1) and `claim`, `decide`,
`close` (api-2), so the case column tracks the referral's state through the full lifecycle including back to
NULL on withdraw/close.

For `reclaimStaleClaims` — the **bulk** sweep — do **not** call the helper per row. Add a **set-based**
statement in the same transaction that recomputes the column for exactly the cases the sweep moved
`Claimed → Open` (mirror the sweep's `WHERE`): those cases go `currentReferralState 'Claimed' → 'Open'`. One
`UPDATE`, so a multi-replica sweep and its column update stay atomic together (the reasoning api-2 decision 1
gives for the sweep itself).

Class-level javadoc on the helper: the column is a denormalised read-model of the referral state, recomputed
from truth after every transition and never set to a per-site value — a reviewer who has not read this plan
will otherwise "simplify" it into a drifting push.

- [x] `refreshCurrentReferralState` recomputes NULL-when-all-terminal / the-one-non-terminal-state, proven
      container-free; called by create/withdraw/claim/decide/close; `reclaimStaleClaims` updates the column
      set-based in its own transaction; a test per transition asserts the case column matches the referral's
      new state (and NULL after withdraw/close); `./gradlew build` green

**Done (2026-09-18). Four things the plan did not name, each a decision the code now carries:**

1. **The column is written by a one-column statement, never by saving the loaded case.** `DeedPollRepository`
   gained `writeCurrentReferralState(id, state)` (`update DeedPoll d set d.currentReferralState = :state`)
   because `DeedPoll` has no dirty-column tracking, so `save` writes every column, and a referral transition
   holds no lock on the case row — it would read the case, then write all of it, discarding whatever a
   concurrent patch committed in between (the lost update `findByIdForUpdate` exists to stop on the patch path).
   A single-column `UPDATE` cannot lose a write to another column, so the referral flows need no lock on the
   case at all. Consequence for the tests: `ReferralServiceTest`'s "does not write the case's flat master
   columns" could no longer assert `verifyNoInteractions(deedPolls)`; it now asserts the one permitted write and
   `verifyNoMoreInteractions`, which is the stronger claim.
2. **The bulk mirror is keyed on the cases the sweep actually released, and it still recomputes from truth.**
   Mirroring the sweep's `WHERE` *after* the releases would match nothing — a released row is already `'Open'`
   with `lastTouchedAt` NULL — and mirroring it *before* would include rows another replica takes. So
   `refreshCurrentReferralStates(deedPollIds, liveStates)` is one statement over exactly the ids whose release
   answered 1, and it sets each column from a correlated read of that case's non-terminal referral rather than
   to the `'Open'` the sweep happens to produce. Decision 2's "never a per-site value" therefore holds at the
   bulk site too, and a case whose referrals are by then all terminal gets NULL, which a hard-coded `'Open'`
   could not have given. `liveStates` is passed in from `LIVE_STATES` so `isTerminal()` stays the single
   definition. `reclaimStaleClaims` still returns releases (= notes written), not cases.
3. **`claim` refreshes the column twice in one transaction, and the order is load-bearing.** Its nested
   `reclaimStaleClaims()` may take this very case's lapsed claim back (column → `Open`) a moment before the
   claim's own recompute takes it to `Claimed`. Pinned by a unit test and by a container test, because the
   reverse order would commit a claimed referral whose case reads `Open`.
4. **`touch` has no call, as the Task 0 gate predicted**, and there is now a test that says so rather than a
   comment: a keepalive changes no state, so recomputing there would write to a case row on every judicial read.

**Where the rule lives:** `CurrentReferralState` (a class, so the reasoning has somewhere to sit) holds the pure
projection and `CurrentReferralStateTest` proves it with no container — including that a backfilled
`Closed`-only case is NULL, which is half of Task 3's assertion arriving early. `DeedPollRepositoryTest` proves
both statements against real Postgres (the null write is a value, not a no-op; the subquery gives NULL for an
all-terminal case), `ReferralRepositoryTest` proves the state projection is scoped to its case, and
`CaseworkerReferralControllerTest` walks a referral through raise → claim → decide → close over HTTP and a real
database, reading the **committed** column after each step — which is the transactionality claim, since every
read is of a row the transition's own transaction wrote.

**Counts:** `test` = 1964 / 249 classes, `integration` = 7 / 3, total 1971 against Task 1's 1930 and the 1927
branch baseline; 0 failures, 0 errors.

### Task 3: The queryable field, and the backfill assertion

Register `currentReferralState` as a read-only queryable enum field, mirroring `state`
(`FormFields.java:73`): an `.asReadOnly()` `enumField("currentReferralState", "Current referral state",
"currentReferralState", <column>)` on a form section, its reader in `DeedPollFields` (so
`readableFields()` contains it and `CaseQuery.queryableField` admits it), and a `DynamicsValues.LABELS` set
named `currentReferralState` (decision 5) so `enumOf`'s `enumSet` resolves and `FormFieldsTest` passes.

Prove queryability end to end with a `CaseSpecifications`/`CaseQuery` test: `where=currentReferralState:is:Decided`
parses, binds the enum constant, and selects only cases whose column is `Decided` — and
`where=currentReferralState:isEmpty` selects the NULL (no-active-referral) cases. This is the exact query
admin-2 will issue for its count and worklist; pin it here so admin-2 can rely on it.

Assert the backfill leaves the column NULL for every migrated case (decision 3): a container test over the
`V7`-backfilled data (or a unit test of the projection rule against a `Closed`-only referral set) showing the
current state is NULL, not `Closed`.

- [x] Field registered read-only + queryable, `FormFieldsTest` green (labels set resolves);
      `where=currentReferralState:is:Decided` and `:isEmpty` proven to select the right rows in a
      `CaseSpecifications`/`CaseQuery` test; backfilled cases assert NULL current state; the queryable-field
      set assertion in `OpenAPIPublisherTest` updated; `./gradlew build` green

**Done (2026-09-18). Decision 3 was wrong about `V7`, and correcting it is the one thing this task added
that the plan did not name:**

1. **`V7` does NOT create only `Closed` referrals, so "the column is NULL for every migrated case" was not
   the honest assertion.** `V7__backfill_referrals.sql` has a two-armed state rule, and only the first arm
   is terminal: `masterDecision` set → `'Closed'`, but **`dateReferred` set with no decision → `'Open'`** —
   deliberately, because a live backlog item must go into the pool rather than into a state nobody ever
   answers (`ReferralBackfillTest.anAwaitingCaseGetsAnOpenReferral` has pinned that since api-1). Decision 3
   and `V9`'s own comment both restate the plan's belief that every backfilled referral is `Closed`. So a
   case referred before cutover and still awaiting its decision comes out of `V7` with a **live `Open`
   referral and, under `V9` alone, a case column reading NULL** — the read model disagreeing with the
   referral table from the first minute of the first environment, and the case missing from every "which
   cases have a live referral" query until a judge happened to claim it. Asserting NULL there, as the task
   text asked, would have pinned that as intended behaviour.
2. **The fix is `V10__backfill_current_referral_state.sql`, not an edit to `V9`.** `V9` is committed and has
   run, so its comment stands as written and wrong; `V10` is additive data-only and states the correction in
   full. It writes the column from **a correlated read of the case's own non-terminal referral** — the same
   projection `DeedPollRepository.refreshCurrentReferralStates` runs after the sweep and
   `CurrentReferralState` holds for one case — rather than a third copy of `V7`'s `CASE`, so decision 2's
   "never a per-site value" holds at the migration site too. `'Closed'`/`'Withdrawn'` are excluded by the
   subquery, so decision 3's actual prohibition (**never write `Closed` onto a case**) is structural: a
   decided legacy case still reads NULL. Stated as *not terminal* rather than as the three live states, so a
   sixth non-terminal member added later is included by default. Idempotent, and no-op on a fresh database.
3. **Where the assertions live.** `ReferralBackfillTest.CurrentReferralStateColumn` runs both committed files
   in version order against real Postgres and asserts all three shapes `V7` distinguishes — decided → NULL
   (not `Closed`), never-referred → NULL, **awaiting → `Open`** — plus idempotence on a re-run, which is the
   likeliest way that file is ever executed again. The container-free half of decision 3 was already proven
   in Task 2 by `CurrentReferralStateTest` (a `Closed`-only referral set projects to NULL).

**Three more things the plan did not name:**

4. **The queryable-field count assertion is in `CaseQueryTest`, not `OpenAPIPublisherTest`** — exactly as
   Task 1's note predicted. `acceptsEveryStoredFormField` pins `allFields()` at 70 → **71** and the queryable
   subset at 68 → **69**; `OpenAPIPublisherTest` needed no edit, because it asserts over write-body schemas
   and this field is read-only.
5. **The rendered field landed in the General card beside `State`, not on the Referral tab**, and that is
   load-bearing rather than cosmetic: the Referral tab hides itself on a case not referred to the Master, so
   a field the dashboard filters on would have been invisible on exactly the cases it counts. `DetailViewTest`'s
   General-column assertion is updated for the third of "ours" that trails its related field. Decision 4's
   flagged consequence is therefore now real and visible — *Steve: say if the read-only field is unwanted.*
6. **`REFERRAL_STATE_LABEL` is the first `DynamicsValues` label set with no matching `_VALUE` map**, and it
   labels all five members. There is no Dynamics integer to state (Dynamics had no referral entity), and
   inventing one would put a number in `EnumValuesConformanceTest`'s way that no Dataverse export can
   confirm — that test's list is explicit, so it needed no change. All five are labelled because the terminal
   split is `ReferralState`'s business, not the label map's, and a missing member renders as nothing at all;
   `FormFieldsTest.labelsEveryReferralState` pins that. It also makes the query fail closed: an unknown state
   is refused through the labels set (`currentReferralState:is:Returned` → "has no value").

**Counts:** `test` = 1975 / 251 classes, `integration` = 7 / 3, total 1982 against Task 2's 1971, Task 1's
1930 and the 1927 branch baseline; 0 failures, 0 errors.

### Task 4: Full validation

- [x] `./gradlew build` green from cold with `TESTCONTAINERS_RYUK_DISABLED=true`; checkstyle clean on all five
      source sets; op count still 30 (no path added); test and integration totals recorded next to this
      branch's baseline, and the count with `INTEGRATION_TESTS_ENABLED=false` recorded so it is visible how
      much of this plan's coverage CI actually runs

**Done (2026-09-18). `./gradlew clean build` green from cold, twice** (once to validate the tree as Task 3 left
it, once after the one edit below), checkstyle **0 violations across all five** source sets (`main`, `test`,
`integrationTest`, `smokeTest`, `functionalTest`), **0 failures and 0 errors**, nothing skipped.

| run | tests | classes |
|---|---|---|
| branch baseline (Task 0) | 1927 (`test` 1920 + `integration` 7) | 245 + 3 |
| Task 1 | 1930 | 246 + 3 |
| Task 2 | 1971 | 249 + 3 |
| **final: `./gradlew clean build`** | **1982** (`test` **1975** + `integration` **7**) | **251 + 3** |
| **final: `INTEGRATION_TESTS_ENABLED=false ./gradlew test --rerun-tasks`** | **1202** | **168** |

**Op count still 30**, asserted rather than eyeballed: `OpenAPIPublisherTest` (5 tests, passing) sums the verbs
over `CASEWORKER_PATHS` and pins `isEqualTo(30)` — this plan added a field to existing responses and no path,
exactly as decision 6 said.

**What CI actually runs of this plan: 1202 of the 1982 — so 780 are not run there** (773 `requires-docker`
tests excluded from `test`, plus all 7 `integration`). The gate is verified two ways, not assumed: under
`INTEGRATION_TESTS_ENABLED=false` the `:integration` task is absent from the `check` graph, and with the
variable unset it is present. (`grep -c ':integration'` on the dry-run reads 1 either way — it matches
`:integrationTestClasses`, the *compile* task, which runs in both cases; grep the task name exactly, or the
gate looks broken when it is fine.)

**One edit, comment-only:** `build.gradle`'s exclusion comment carried measured figures and an instruction to
re-measure rather than trust them, so it now reads **773 of 1,975 / the other 1,202** in place of the stale
741 / 1,813 / 1,072. The **26 tagged classes are unchanged** (25 inheriting the tag from
`AbstractPostgresContainerTest` + `SchemaConformanceTest` carrying it directly): this plan's container-backed
cases landed as `@Nested` blocks on classes already tagged, which is also why the XML class count (251) is far
above the tagged-class count — a `@Nested` block reports as its own testsuite.

**Both open questions in this plan remain open and are Steve's, not blockers** — the read-only "Current
referral state" now renders in the General card beside "State" (decision 4's flagged cost, real and visible),
and the five label strings are display-only. **api-2b is complete; the consumer is admin-2, which can now
issue `?where=currentReferralState:is:Decided`.**

## Deliberately not in this plan

- **The dashboard count tile, the "Returned — action needed" alert, and the worklist page.** admin-2. This
  plan is only the producer — the field and its queryability. admin-2 issues
  `?where=currentReferralState:is:Decided`, reads `total` for the count, and renders the same filter as the
  drill-in list.
- **Decoupling queryability from the rendered form field** (decision 4) — a larger change to the query model,
  only if Steve does not want the read-only field on the case form.
- **Any change to the referral state machine.** api-2 owns the transitions; this only reads their result and
  projects it. If a transition is wrong, fix it in api-2, not here.
- **A `@Scheduled` exactly-2h sweep** (api-2 decision 1) — still an additive follow-up; when it lands, it
  fires the same set-based column recompute this plan attaches to `reclaimStaleClaims`.
