# Plan: referrals to the judiciary (API follow-up 4) — caseworker completion rules

## Overview

Business testing of the completed referral feature found that a caseworker can still withdraw a referral
after a judge has recorded a direction. That is what the current implementation was designed to permit:
`withdrawReferral` accepts every non-terminal state, including `Decided`. The tested workflow now says
otherwise:

- `Open` or `Claimed` means the office may still pull the referral back;
- `Decided` means a judicial direction exists and must be actioned, not withdrawn;
- the existing `Decided → Closed` transition records that the office has actioned it.

This is a narrow behavioural correction. It changes no endpoint, request body, response property, enum,
database column or role. The existing closure operation is already the correct API for the admin journey:

`POST /caseworker/cases/{id}/referrals/{referralId}/closure`

The companion admin plan is
`deed-poll-admin/docs/plans/referrals-admin-4-caseworker-completion.md`.

## Preconditions and reconciliation gate

Before implementation:

1. Work from `master` after the currently planned Notify work has been resolved; do not fold unrelated
   Notify changes into this referral branch.
2. Verify `ReferralService.close` still permits only `Decided`, preserves the direction fields and refreshes
   `currentReferralState` to `NULL`.
3. Verify the published OpenAPI document still contains the existing withdrawal and closure operations.
   This plan expects **no OpenAPI delta**. If the paths or DTOs have changed, stop and reconcile the admin
   companion plan before coding.
4. Measure the current `./gradlew build` test totals rather than copying totals from the completed referral
   plans.

## Decisions — implement these, do not revisit

### 1. Withdrawal is allowed only before a direction exists

`withdrawReferral` accepts `Open` and `Claimed`. It refuses `Decided`, `Closed` and `Withdrawn` with 409.

`Claimed` remains withdrawable. A caseworker may need to correct or cancel a referral while a judge holds it,
and the existing transition safely invalidates a late judicial submission. The new business rule starts when
the judge records a direction, not when the judge claims the work.

Do not implement this as `isTerminal()`: `Decided` is deliberately non-terminal because it remains in the
caseworker action queue. Use an explicit allowed-state predicate or an enum capability whose name states the
business rule, such as `canBeWithdrawn()`. Do not scatter `Open || Claimed` checks through controllers.

### 2. A direction does not automatically close the referral

Keep `Claimed → Decided → Closed`. The distinction drives the returned-direction count:

- `Decided`: direction received and awaiting caseworker action;
- `Closed`: direction actioned by a caseworker.

Changing `decide` to produce `Closed` would make the attention banner drop the case before the office had
acted. The admin companion plan therefore exposes the existing closure transition rather than collapsing
the states.

### 3. No automatic application-state mutation

Neither `decide` nor `close` updates the case's legacy flat referral fields or date-derived `DeedPollState`.
A judicial `Approved` or `Rejected` decision does not uniquely identify the next case state: the direction
may require forms, correspondence, more evidence, Gazette action or another case update.

**Business confirmation, 2026-09-20:** the caseworker updates the case as the direction requires and then
marks the direction actioned. Referral state must not automatically drive the overall application status.

The admin closure confirmation will state that the direction and any necessary case updates have been
completed. An automatic mapping from a referral decision to a case date must not be invented in this plan.
If the business later defines deterministic mappings, they require their own rules and tests.

### 4. Closure remains the existing no-body transition

Do not add a replacement endpoint or a request DTO. Closure records one fact: the caseworker has actioned the
direction. Identity and audit time continue to come from the authenticated caller and `AuditClock`.

Repeated closure, closure of `Open`/`Claimed`/`Withdrawn`, cross-case access and unauthenticated access retain
their existing conflict/not-found/unauthorized behaviour.

### 5. Lifecycle case notes are not part of this plan

The feedback also asks for a note at each lifecycle transition. That is recommendation 2 and will be planned
separately. Do not add only a closure note here: doing one transition opportunistically would leave the audit
policy inconsistent and pre-empt the wording/authorship decisions needed for the whole lifecycle.

### 6. No migration or contract-version work

This is a service-rule change over existing states and operations:

- no Flyway migration;
- no enum change;
- no OpenAPI operation-count change;
- no DTO or schema change;
- no compatibility shim.

The admin mock must adopt the same changed withdrawal behaviour in its companion plan, but the published
wire contract remains identical.

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

`build` is compile + checkstyle across the source sets + `test` + `integration`, so `OpenAPIPublisherTest` and
`NamespaceSeparationSecurityTest` — the two that would catch an accidental contract or namespace change — both
run here. That matters to this plan more than to most, because decision 6 is that there is **no** contract
delta: a passing `OpenAPIPublisherTest` is the evidence for it.

**This plan is not stacked** — it branches from `master`, where the referral state machine and the closure
transition already exist and notify-1/notify-5 are merged. **Measure the baseline before Task 1** by summing
`tests="…"` across `build/test-results/{test,integration}/*.xml`, and record it next to the final count in
Task 3 rather than copying a total from an earlier plan.

**A worker or container startup timeout is an environment failure, not a code failure** — re-run before
investigating.

## Tasks

### Task 0: Reconcile the current implementation

Read the current `ReferralService`, `ReferralState`, `CaseworkerReferralController`, referral security tests
and OpenAPI publisher assertions. Confirm:

- withdrawal currently accepts `Open`, `Claimed` and `Decided`;
- closure is already published and accepts only `Decided`;
- `currentReferralState` becomes `NULL` after withdrawal or closure;
- no newer work has changed the referral state machine or operation count.

Record the build/test baseline. Stop on any contract or state-machine drift.

- [x] Current referral implementation and OpenAPI reconciled; baseline recorded; no files changed

#### Reconciliation performed 2026-09-20, before the run — read this rather than repeating it

Checked against source on `master` at `135bb1a`. Every premise in this plan held; nothing had drifted.

| Premise | Verified |
|---|---|
| Withdrawal accepts `Open`, `Claimed`, `Decided` | `ReferralService.withdrawReferral` guards on `referral.getState().isTerminal()` |
| Closure accepts only `Decided` | `ReferralService.close` guards on `!= ReferralState.Decided` |
| Both refresh the denormalised column | both call `refreshCurrentReferralState(deedPollId)` |
| No newer work moved the state machine | five members unchanged in `ReferralState`; 33 caseworker + 4 judicial operations |

**Baseline, measured not copied:** `test` 2,292 and `integration` 8 — **2,300**, zero failures, across 300 +
3 result files, with `./gradlew build` green from `TESTCONTAINERS_RYUK_DISABLED=true`.

**The trap in decision 1, stated with its location.** `ReferralState.isTerminal()` has **two** callers, and
only one of them is withdrawal: `ReferralService.LIVE_STATES` derives the one-live-referral-per-case guard
from it too. Narrowing `isTerminal()` — the obvious-looking way to make `Decided` unwithdrawable — would
therefore also let a case hold a second referral alongside a `Decided` one. Add `canBeWithdrawn()` and leave
`isTerminal()` exactly as it is; the enum's own Javadoc ("a referral may only be withdrawn while it is
non-terminal") is then the wording to correct, not the method.

**Re-verified on `referrals-api-4` at `65da02a`, 2026-09-20.** Every row of the table above still held, and
the baseline re-measured to the same totals: `test` 2,292 over 300 result files and `integration` 8 over 3 —
**2,300**, zero failures, zero errors, `./gradlew build` green.

The trap above is in fact **wider than two callers**, and the count is the thing to correct rather than the
conclusion. `ReferralState.isTerminal()` is asked by `ReferralService.LIVE_STATES`, by
`CurrentReferralState.of` (which is what makes the denormalised column NULL for a terminal state), and
through Javadoc-linked contracts by `ReferralRepository` and `DeedPollRepository`, whose live-state sets the
callers pass in are derived from it — `DeedPollRepositoryTest`, `ReferralRepositoryTest`,
`CurrentReferralStateTest`, `ReferralStateTest` and `CaseworkerReferralControllerTest` all derive their
parameter sets from it as well. So narrowing it would move the one-referral-per-case guard, the read-model
column *and* several test parameter sets at once. `canBeWithdrawn()` as a second, independent predicate
remains the right shape; `CaseworkerReferralControllerTest:364-368` is the one existing parameter set that
derives withdrawable states from `isTerminal()` and therefore the one Task 2 must repoint.

### Task 1: Tighten the domain withdrawal rule

Change the single domain authority used by `ReferralService.withdrawReferral` so only `Open` and `Claimed`
can be withdrawn. The 409 must name the actual state and say that a direction already recorded must be
actioned when the state is `Decided`; do not reuse “already terminal” wording for a non-terminal state.

Update `ReferralServiceTest` to prove:

- `Open → Withdrawn`;
- `Claimed → Withdrawn`, invalidating a later direction;
- `Decided` is refused and remains `Decided`, with no save, note or case-column refresh;
- `Closed` and `Withdrawn` remain refused;
- existing cross-case and caller rules are unchanged.

Update service documentation that currently says every non-terminal referral is withdrawable.

- [x] Domain withdrawal rule accepts only `Open`/`Claimed`; every state covered; refused writes have no side
      effects; focused unit tests green

#### What Task 1 did, 2026-09-20

`ReferralState.canBeWithdrawn()` is the new domain authority — `Open || Claimed` — added **beside**
`isTerminal()` rather than narrowing it, for the reason Task 0 recorded. `isTerminal()` is byte-for-byte
unchanged, so `LIVE_STATES`, `CurrentReferralState.of` and every test parameter set derived from it are
untouched; only `ReferralService.withdrawReferral`'s guard moved.

The 409 wording splits in two, in `ReferralService.refusedWithdrawal`:

- `Decided` → `Referral {id} is Decided -- a direction has been recorded on it and must be actioned and the
  referral closed, rather than withdrawn`;
- `Closed`/`Withdrawn` → the existing `Referral {id} is already {state} and cannot be withdrawn`, left
  verbatim because `CaseworkerReferralControllerTest` asserts it as an exact string.

Tests: `ReferralStateTest` gains three (the withdrawable set, `Decided` non-terminal-yet-unwithdrawable, and
withdrawable ⊂ non-terminal so the two predicates cannot contradict). `ReferralServiceTest.Withdrawing` gains
the `Decided` refusal — no save, no note, **no `writeCurrentReferralState`**, direction intact — and a
`@EnumSource(ReferralState.class)` case that asks the service about all five states and expects whatever
`canBeWithdrawn()` says, so a sixth state cannot slip through untested. The claimed-withdrawal test now uses a
real `storedClaim(JUDGE, …)` and proves the judge's later `decide` is refused.

`./gradlew build` green: `test` 2,301 over 300 result files, `integration` 8 over 3 — **2,309**, zero failures,
zero errors. Baseline was 2,300, so +9, which is exactly the tests added (3 + 1 + 5 parameterised).

### Task 2: Prove the HTTP and read-model behaviour

Extend `CaseworkerReferralControllerTest` so a genuinely claimed-and-decided referral is refused by
`POST …/withdrawal` with 409 and remains:

- `Decided`;
- present in `currentReferralState`;
- eligible for the existing closure operation.

Then close it through `POST …/closure` and prove the direction survives and `currentReferralState` becomes
`NULL`. Retain the existing authentication and cross-case assertions. Update any security tests whose
parameter sets currently treat `Decided` as withdrawable.

This task changes behaviour only. Regenerate/verify OpenAPI and assert that the path, operation and schema
sets are unchanged.

- [x] Controller and security tests prove `Decided` cannot be withdrawn but can be closed; case read model
      follows both outcomes; OpenAPI has no shape delta

#### What Task 2 did, 2026-09-20

Behaviour-only, as decision 6 requires: no main-source file changed at all, so the whole task is tests.

`CaseworkerReferralControllerTest.WithdrawReferral` gains three, each reaching `Decided` through the real claim
and direction rather than by writing the state into the row:

- **`withdrawsAReferralAJudgeIsHolding`** — the permitting half, over the route. `Claimed → Withdrawn` is 200,
  the claim survives on the row (`claimedBy` is still published), `currentReferralState` goes NULL, and the
  holding judge's later `decide` is then refused, which is what makes withdrawing a held referral safe rather
  than a race.
- **`refusesWithdrawingADecidedReferral`** — 409 asserted as the exact new message, then the row still `Decided`
  with `decision` and `directionsText` intact, `currentReferralState` **still `Decided`** so the case stays in the
  "returned — action needed" queue, and still one note.
- **`leavesADecidedReferralCloseable`** — the refusal is a redirection, not a dead end: the refused withdrawal is
  followed by a 200 closure on the same referral, the direction survives into the closure response, and
  `currentReferralState` becomes NULL only once the direction has been actioned.

`ReferralSecurityTest` repoints its withdrawal-refusal parameter set from `{Closed, Withdrawn}` to
`{Decided, Closed, Withdrawn}`, so `Decided` is refused for an administrator exactly as for a caller with no
roles — an escape hatch only admins had would be the version of this rule that got used. `@EnumSource` takes
names and not a predicate, so a second test asserts that the named set *is* the complement of `canBeWithdrawn()`,
which is what stops the two drifting.

**Task 0's instruction to repoint `CaseworkerReferralControllerTest:364-368` was based on a misreading, and the
line was left on `isTerminal()` deliberately.** That parameter set belongs to
`refusesWhileClaimedOrDecided` — the **one-live-referral** guard on the *create* route, not the withdrawal guard —
and `isTerminal()` is the correct authority for it. `Decided` is precisely where the two predicates part: it may
not be withdrawn and nonetheless still blocks a second referral. Repointing it to `canBeWithdrawn()` would have
asserted that a case holding a decided referral may raise another, which is the bug Task 0's own trap note warned
about, arriving from the test side instead of the enum side. The comment there now says which guard it is and why
it stays.

**No contract delta, as evidence rather than as assertion:** `OpenAPIPublisherTest` passed untouched — 33
caseworker operations across 20 paths, 4 judicial, every body schema unchanged.

`./gradlew build` green: `test` 2,306 over 300 result files, `integration` 8 over 3 — **2,314**, zero failures,
zero errors. Task 1 ended at 2,309, so +5: the three controller tests, the `Decided` parameter case, and the
enum-agreement test.

### Task 3: Documentation and full validation

Update Javadocs and completed-plan cross-references that still state a decided referral is withdrawable.
Keep the historical decision visible but mark it superseded by this plan so future work does not restore the
old rule from an archived plan.

Run:

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

Record final unit/integration totals and compare them with Task 0's baseline.

- [x] Documentation reflects the revised rule; full build green; final test totals recorded

#### What Task 3 did, 2026-09-20

**One main-source file still stated the old rule, and the search for it was exhaustive rather than assumed.**
Every mention of withdrawal across `src/main`, `src/test` and `docs/` (excluding the vendored
`src/test/resources/reference/`) was read. Tasks 1 and 2 had already corrected `ReferralState`,
`ReferralService` and the tests; what remained was `CaseworkerReferralController`:

- its withdrawal Javadoc claimed "409 for a referral that is already terminal" and then said the guard "is
  written against *non-terminal* rather than against `Open`, so the claimed and decided states the directions
  plan adds need no change here" — the exact sentence that would talk a future change back into the old rule.
  It now names `canBeWithdrawn()`, says why `Claimed` is still withdrawable and `Decided` is not, and records
  that writing the guard against "non-terminal" is deliberately *not* what happens, because that question has
  its own callers.
- the class Javadoc's "withdraw-only-while-**live** guard" became "withdraw-only-**before-a-direction**", since
  `Decided` is live and no longer withdrawable, so the old name had quietly become a wrong description.

Everything else checked and deliberately left alone: the `Closed`/`Withdrawn` terminal-pair prose in
`ReferralResponse`, `CurrentReferralState`, `ReferralRepository`, `DeedPollRepository`, `JudicialReferralService`
and `V6`/`V9`/`V10` is about the *terminal* question, which this plan did not change, and a migration may not be
edited in any case.

**Superseding banners were already in place and were verified, not added twice.**
`completed/referrals-api-1-create-and-withdraw.md` and `completed/referrals-api-2-claim-direction-close.md` each
carry a follow-up banner naming this plan; `api-2b` and `api-3` say nothing about withdrawability, so neither
needed one. The live plans `referrals-api-5` and `referrals-api-6` already describe withdrawal as
`Open`/`Claimed`, so no future plan restores the old rule from an archived one.

`docs/RESUME.md` now describes today rather than the plan: this plan is complete on `referrals-api-4` and
unmerged, with the `canBeWithdrawn()`-beside-`isTerminal()` reason and the no-contract-delta evidence stated
where a next session reads it first, and the live-plans table row moved off **Planned**.

**Final totals, measured:** `test` 2,306 over 300 result files and `integration` 8 over 3 — **2,314**, zero
failures, zero errors, `./gradlew build` successful in 4m 1s. Identical to Task 2, which is the expected result
for a documentation task: no test was added or removed. Against Task 0's baseline of **2,300** the plan is
**+14** in total — Task 1's 9 and Task 2's 5 — with no change to the 33 caseworker and 4 judicial operations.

## Flags for the business — raised 2026-09-20, not blocking this plan

1. **A `Decided` referral that should never be actioned has no honest exit.** `ReferralService.LIVE_STATES`
   counts `Decided` as live, so after decision 1 a case holding one can neither withdraw it nor raise a
   replacement: the only remaining transition is `close`, which records that the direction *was actioned*.
   For the normal path that is exactly right. For a referral raised in error, or a direction made moot
   because the application itself moved on, the only action available records something untrue.

   Steve's decision, 2026-09-20: **implement decision 1 as written and flag this**, rather than inventing an
   escape hatch. A withdrawal-with-reason for `Decided`, or a distinct "no longer required" terminal state,
   would each need a business rule for its wording and audit, and neither has one. Do not add either in this
   plan.

## Out of scope

- The admin tab consolidation, direction display and closure journey — the companion admin plan.
- Case notes for claim, direction or closure — referral audit plan, recommendation 2.
- A judge's “My referrals” list — recommendation 3, planned in
  `referrals-api-6-judicial-my-referrals.md`.
- Mapping a judicial decision to an application state or case date without an approved business rule.
- Changing the judicial pool or visibility rules.
