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

3. **The backfill leaves the column NULL for every migrated case** (migration `V{n}`, additive). api-1's
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

6. **No new HTTP operation. The caseworker operation count stays 30.** This plan adds a *field* to an
   *existing* response (`CaseSummaryResponse`, the list row) and a *queryable field*, both served by the
   already-published `GET /caseworker/cases`. No path is added, so `CASEWORKER_PATHS` and the `30` count do
   **not** change. What *does* move is `OpenAPIPublisherTest`'s schema surface: `CaseSummaryResponse` gains a
   property and the queryable-field set gains a member, so any assertion in that test (or `DeedPollSchemas`
   /the published `openapi.json`) over the summary schema's properties or the field list must be updated in
   the same task, or `build` goes red. **Grep before assuming which:** `git grep -n currentReferralState` will
   be empty at the start; grep `CaseSummaryResponse` and the schema/field-count assertions to find every
   place the new property is counted.

## What this plan adds, and why every piece is additive

- **A nullable enum column** on `deedpoll_deedpoll` (migration `V{n}`, `ALTER TABLE … ADD COLUMN`, additive —
  adds one nullable column, edits nothing). Defaults NULL.
- **One field on `CaseSummaryResponse`** — `currentReferralState` (`ReferralState`, nullable). The list row
  already carries `state`; this sits beside it. Both `from(...)` factories
  (`CaseSummaryResponse.java:58,71`) map it.
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

- [ ] All three confirmed against code, not the plan text; if any fails, STOP and report which and how the
      reality differs — do not build on a wrong assumption

### Task 1: The column, the entity mapping, and the summary field

`V{n}__current_referral_state.sql` (verify the highest `V*` first): `ALTER TABLE "deedpoll_deedpoll" ADD
COLUMN "currentReferralState" "ReferralState" NULL`. Additive; edits no committed migration and no
`deedpoll_referral` column.

Add `currentReferralState` to the `DeedPoll` entity with `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` (an ordinal
otherwise) and a nullable mapping; it validates against `V{n}` under `ddl-auto: validate`. Extend the entity
round-trip test.

Add `currentReferralState` (`ReferralState`, nullable) to `CaseSummaryResponse` and map it in **both**
factories (`from(DeedPoll)` and `from(List<DeedPollWithComputed>)`). Update `CaseSummaryResponse`'s test and
any summary-schema assertion in `OpenAPIPublisherTest`/`DeedPollSchemas` for the new property.

- [ ] `V{n}` added (number verified against the branch), additive, column nullable, `NAMED_ENUM`-mapped, and
      validates under `ddl-auto: validate`; entity round-trip test covers it; `CaseSummaryResponse` exposes it
      via both factories; summary-schema assertions updated; op count unchanged at 30; `./gradlew build` green

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

- [ ] `refreshCurrentReferralState` recomputes NULL-when-all-terminal / the-one-non-terminal-state, proven
      container-free; called by create/withdraw/claim/decide/close; `reclaimStaleClaims` updates the column
      set-based in its own transaction; a test per transition asserts the case column matches the referral's
      new state (and NULL after withdraw/close); `./gradlew build` green

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

- [ ] Field registered read-only + queryable, `FormFieldsTest` green (labels set resolves);
      `where=currentReferralState:is:Decided` and `:isEmpty` proven to select the right rows in a
      `CaseSpecifications`/`CaseQuery` test; backfilled cases assert NULL current state; the queryable-field
      set assertion in `OpenAPIPublisherTest` updated; `./gradlew build` green

### Task 4: Full validation

- [ ] `./gradlew build` green from cold with `TESTCONTAINERS_RYUK_DISABLED=true`; checkstyle clean on all five
      source sets; op count still 30 (no path added); test and integration totals recorded next to this
      branch's baseline, and the count with `INTEGRATION_TESTS_ENABLED=false` recorded so it is visible how
      much of this plan's coverage CI actually runs

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
