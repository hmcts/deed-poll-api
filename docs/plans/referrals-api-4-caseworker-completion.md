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

## Tasks

### Task 0: Reconcile the current implementation

Read the current `ReferralService`, `ReferralState`, `CaseworkerReferralController`, referral security tests
and OpenAPI publisher assertions. Confirm:

- withdrawal currently accepts `Open`, `Claimed` and `Decided`;
- closure is already published and accepts only `Decided`;
- `currentReferralState` becomes `NULL` after withdrawal or closure;
- no newer work has changed the referral state machine or operation count.

Record the build/test baseline. Stop on any contract or state-machine drift.

- [ ] Current referral implementation and OpenAPI reconciled; baseline recorded; no files changed

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

- [ ] Domain withdrawal rule accepts only `Open`/`Claimed`; every state covered; refused writes have no side
      effects; focused unit tests green

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

- [ ] Controller and security tests prove `Decided` cannot be withdrawn but can be closed; case read model
      follows both outcomes; OpenAPI has no shape delta

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

- [ ] Documentation reflects the revised rule; full build green; final test totals recorded

## Out of scope

- The admin tab consolidation, direction display and closure journey — the companion admin plan.
- Case notes for claim, direction or closure — referral audit plan, recommendation 2.
- A judge's “My referrals” list — recommendation 3, planned in
  `referrals-api-6-judicial-my-referrals.md`.
- Mapping a judicial decision to an application state or case date without an approved business rule.
- Changing the judicial pool or visibility rules.
