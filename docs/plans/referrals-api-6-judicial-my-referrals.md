# Plan: referrals to the judiciary (API follow-up 6) — My referrals

## Overview

Business testing found that a judge who claims a referral cannot find it again after leaving the detail page.
The only judicial collection,

```http
GET /judicial/referrals
```

is intentionally the shared `Open` pool. Claiming changes the referral to `Claimed`, so the successful
transition removes it from the only list that linked to it. The existing detail endpoint still permits the
claiming judge to read the referral, but only if they retained or remembered its UUID.

The original judicial-boundary decision intended list access to include the shared pool **and the judge's own
`Claimed`/`Decided` referrals**. The implementation delivered the pool and individual visibility but
deliberately left the personal list out of scope. This follow-up completes that boundary with:

```http
GET /judicial/referrals/mine
```

The companion admin plan is
`deed-poll-admin/docs/plans/referrals-admin-6-judicial-my-referrals.md`.

## Security finding — stable ownership is part of this plan

Referral ownership currently compares `CurrentUser.name()` with the display names stored in `claimedBy` and
`decidedBy`. `CurrentUser` already documents this as a known authorization defect and says not to add a third
name-based authorization site:

- two judges with the same display name can read and direct each other's referrals;
- a judge whose display name changes loses access to their own referral.

A personal list keyed by display name would materially amplify the first defect: it would enumerate another
judge's referrals instead of requiring their UUID to be guessed or obtained. This plan therefore migrates
claim and direction ownership to the token's stable IDAM subject before adding the list. Display-name columns
remain for human-readable attribution; they cease to authorize.

## Dependencies and reconciliation gate

Implement after referral follow-ups 4 and 5 so withdrawal, closure and lifecycle-note behaviour are settled.
The personal list has no note-specific contract, but its transition tests must reflect the final lifecycle.

Before coding:

1. Verify `CurrentUser.requireUserId()` is still the established stable-owner guard.
2. Verify `claim`, `requireHeldBy`, `JudicialReferralService.visibleTo` and its `heldBy` helper still authorize
   using display names.
3. Count `Claimed` and `Decided` rows with no stable owner in each deployment target.
4. Stop rollout while any active `Claimed` row exists. Let it lapse and trigger the existing sweep, or agree a
   controlled operational release; do not guess a subject from a display name.
5. Verify the current judicial surface has four operations and the admin client is contract-equal to it.
6. Record focused and full build baselines.

Existing `Decided` rows cannot be safely backfilled either. They remain visible to caseworkers and actioned
normally, but are not assigned to a judge's new personal list. That is a fail-closed rollout consequence, not
a reason to invent identity data.

## Contract

| Method | Path | Result |
|---|---|---|
| `GET` | `/judicial/referrals/mine?page={page}&size={size}` | This judge's `Claimed` and `Decided` referrals, paged |

The response is a new `JudicialMyReferralsResponse` envelope:

```text
referrals: JudicialReferralSummary[]
page: integer
size: integer
total: integer
totalPages: integer
```

`JudicialReferralSummary` is intentionally reused. Its documentation already says `state` exists so the same
type can carry a judge's own claimed referral later, and it contains only referral fields—no application
identity or case id. The new envelope names different collection semantics without changing the existing
pool contract.

## Decisions — implement these, do not revisit

### 1. “Mine” means currently judicially visible ownership

The list contains exactly:

- `Claimed` where `claimedById == caller.requireUserId()`;
- `Decided` where `decidedById == caller.requireUserId()`.

It excludes:

- `Open`, because that belongs to the shared pool;
- `Withdrawn`, because the office pulled it back;
- `Closed`, because the direction has been actioned and judicial involvement is over;
- every referral owned by another subject;
- pre-migration rows whose owner id is null.

This is not permanent judicial history or case search. The same predicate must drive personal-list membership
and individual detail visibility, so the list cannot expose a referral its link then answers 404 for.

### 2. Ownership uses IDAM subject; names remain attribution

Add nullable `claimedById` and `decidedById` columns to `deedpoll_referral`.

- Claim requires `caller.requireUserId()` and stores it in `claimedById`; `claimedBy` still stores the display
  name shown to staff.
- Direction requires the same subject that owns the active claim and stores it in `decidedById`;
  `decidedBy` remains the display name.
- Claim release clears `claimedById` together with the other active-claim fields.
- `requireHeldBy`, `heldBy` and `visibleTo` compare the caller's subject, never their display name.

Do not publish either id in any DTO, note or log. Do not fall back to name when an id is absent: fallback
would preserve the collision vulnerability precisely on the rows where identity is uncertain.

### 3. The personal list runs the stale-claim sweep first

Like the pool, `mine` is a read-write transaction that calls `reclaimStaleClaims()` before querying.
Otherwise a stale claim remains in “My referrals”; opening it would call `touch` and revive ownership after
the expiry window had elapsed merely because nobody loaded the shared pool first.

The sweep may therefore remove an expired `Claimed` row from this response and return it to the shared pool
in the same request. Its existing lapse note remains authoritative.

### 4. The database filters, orders and pages

Do not load all referrals and filter by subject in Java. Add one repository query with a fixed predicate and
order:

1. `Claimed` before `Decided`, because unfinished judicial work comes first;
2. urgent before normal within each state;
3. oldest referral first within each urgency band;
4. UUID as the deterministic final tie-break.

The page size defaults to the existing judicial value of 10 and uses `CasePaging`'s validation/cap. The
response total comes from the same query, not from the page row count.

This ordering deliberately differs from permanent history: `Decided` rows remain here only while the office
actions them, and the primary purpose is to resume outstanding claimed work.

### 5. The route is a literal collection beneath the existing namespace

Use `/judicial/referrals/mine`, not a caller-selected `owner` or `claimedBy` query parameter. Ownership comes
only from the authenticated token and cannot be used to enumerate another judge.

Prove the literal route wins over `/{referralId}` so `mine` is never parsed as a referral UUID. The endpoint
requires `Role.JUDICIAL`, just like all existing judicial operations.

### 6. Lifecycle membership follows existing transitions

| Event | Personal-list result |
|---|---|
| Claim succeeds | Appears as `Claimed` |
| Claim is touched/read | Remains; no reordering or duplicate |
| Claim lapses | Disappears and returns to shared pool |
| Direction recorded | Remains as `Decided` |
| Caseworker withdraws an `Open`/`Claimed` referral | Disappears |
| Caseworker closes a `Decided` referral | Disappears |
| Refused transition | No membership change |

Do not add a list-specific status, flag or denormalized case column.

### 7. No application data is added to list rows

The list reuses `JudicialReferralSummary`: referral id, state, reasons, referral note, urgency and raised
timestamp. It does not expose applicant names, display reference, case id, payment data, notes timeline or
direction text. Those remain behind the existing visible referral detail read.

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

`build` is compile + checkstyle across the source sets + `test` + `integration`, so `OpenAPIPublisherTest` and
`NamespaceSeparationSecurityTest` both run. This plan is the one referral plan that **does** move the contract:
the judicial count goes **4 → 5** and one response schema is added, so that test's judicial assertion has to be
updated deliberately in the task that publishes the operation — and the **caseworker count must stay 33**.
`ddl-auto: validate` means the new columns must match the migration or the context fails to start.

**Baseline measured on this branch before Task 1, 2026-09-21** — `test` **2,328** and `integration` **8**,
**2,336** in total, zero failures, `./gradlew build` green from cold at `7226fc9` (api-5 merged).

**This plan is not stacked.** It branches from `master` after follow-ups 4 and 5, so withdrawal, closure and the
lifecycle notes are all settled, as the gate requires.

**A worker or container startup timeout is an environment failure, not a code failure** — re-run before
investigating.

### The migration number is **V14**, decided 2026-09-21

`V13` is the highest on `master`. `notify-3` had also planned `V14`; it is unrun, so this plan takes `V14` and
**notify-3 has been corrected in the same commit** to take the next free number instead. Reserving `V14` for
notify-3 and jumping to `V15` here was rejected: Flyway runs with out-of-order disabled, so a `V14` arriving
after `V15` had been applied would fail every environment that applied `V15`.

## Tasks

### Task 0: Reconcile identity, data and contract

Inspect the ownership checks, transition tests, migrations, repository security rules, judicial controller
tests and OpenAPI assertions. Measure legacy active rows in each target environment and stop rollout if any
`Claimed` row cannot be safely allowed to lapse before deployment.

Regenerate the current OpenAPI baseline and confirm the only planned additions are one judicial GET operation
and one response schema.

- [x] Identity and rollout state reconciled; active legacy claims resolved safely; baselines recorded; no
      files changed

#### Reconciliation already performed, 2026-09-21 — read this rather than repeating it

Done in the parent session against `origin/master` at `7226fc9`.

| Gate item | Found |
|---|---|
| 1. `requireUserId()` is the stable-owner guard | `CurrentUser:114` — throws `UnauthorizedError` when the token carries no subject, so an unidentified caller cannot own a resource |
| 2. Ownership still authorises on display names | **Exactly three sites**, as `CurrentUser`'s own warning says: `JudicialReferralService:145-146` (`visibleTo`'s `Claimed`/`Decided` arms), `:359` (`heldBy`), `ReferralService:586` (`requireHeldBy`). `claimedBy` at `:326` and `decidedBy` at `:425` are attribution and stay |
| 5. Judicial surface is four operations | Four mappings in `JudicialReferralController`; `OpenAPIPublisherTest` asserts 4 and 33 separately |
| 6. Baseline | `test` 2,328 + `integration` 8 = **2,336**, zero failures, build green |

Re-confirmed on this branch at `73aff11` before Task 1: the three name-based sites are unchanged
(`JudicialReferralService.visibleTo` lines 145-146, `heldBy` line 359, `ReferralService.requireHeldBy` line 586),
`JudicialReferralController` still declares four mappings, `OpenAPIPublisherTest` still asserts 4 judicial and 33
caseworker operations separately, `V13` is still the highest migration so `V14` is free, and `./gradlew build` is
green at `test` 2,328 + `integration` 8 = 2,336 with zero failures.

**Gate items 3 and 4 are vacuous today, and that is a finding rather than a skip.** There are no legacy
`Claimed` or `Decided` rows to count in any persistent environment, because **api has never been deployed to
one**: AAT has neither the `.internal` record (raised as `azure-platform-terraform#3161`) nor a
`cnp-flux-config` HelmRelease, and the service's first successful deploy of any kind was an **ephemeral
preview** on 2026-09-21. So no row anywhere has a display-name owner that cannot be migrated.

**Keep the fail-closed rollout design anyway.** It stops being vacuous the first time api runs in AAT with a
judge holding a claim, and by then this plan will be history. Do not simplify the null-owner handling on the
grounds that no such row exists today — pre-migration rows with a null owner must still fail closed.

**`CurrentUser` already anticipates this plan** and is worth reading before Task 1: it documents the
display-name comparison as a known authorization defect, says the fix "is a migration, not a refactor", and
instructs against adding a third name-based site. This plan is that migration.

### Task 1: Migrate referral ownership to stable subjects

Add a Flyway migration for nullable `claimedById` and `decidedById`, entity mappings, and any narrow indexes
the measured query plan justifies. Do not backfill ids from names.

Update:

- `claim` to require/store the caller subject;
- `requireHeldBy` to authorize by subject;
- `decide` to store `decidedById`;
- stale-claim release to clear `claimedById`;
- `JudicialReferralService.visibleTo` and `heldBy` to use subject ids.

Keep display names and response shapes unchanged. Extend entity mapping, migration, transition, visibility,
same-display-name and changed-display-name tests. Prove a caller without a subject fails closed before a
state change.

- [ ] Stable subject owns claim/direction; display name is attribution only; unsafe legacy rows fail closed;
      migration and focused tests green

### Task 2: Add the personal-list query

Add a repository query for this subject's `Claimed`/`Decided` referrals with the fixed state/urgency/age/id
order and database paging. Add a small domain page type if reusing `ReferralPool` would misname the semantics.

Add `JudicialReferralService.mine(caller, paging)`:

- require `JUDICIAL`;
- require a stable subject;
- run the stale-claim sweep;
- execute the owner-scoped query;
- return rows and the total.

Repository tests prove predicate, ordering, paging, tie-break, null-owner exclusion and isolation between two
different subjects sharing the same display name. Service tests prove role/subject gates, sweep-before-query
and no in-memory filtering.

- [ ] Owner-scoped query and service implemented; stale rows cannot be revived; ordering/paging/isolation
      proven; focused tests green

### Task 3: Publish `GET /judicial/referrals/mine`

Add the literal controller mapping and `JudicialMyReferralsResponse`. Reuse `JudicialReferralSummary`; add no
case fields or owner ids.

Controller and security tests prove:

- `Claimed` and `Decided` rows for the caller are returned;
- `Open`, terminal, null-owner and other-subject rows are absent;
- claim → list, direction → list, lapse → absent and close/withdraw → absent;
- malformed paging is 400;
- unauthenticated is 401 and non-judicial is 403;
- `/mine` is not dispatched through `/{referralId}`;
- no additional data is disclosed.

- [ ] Fifth judicial operation implemented with exact membership and security; HTTP tests green

### Task 4: Publish the contract and update documentation

Update `OpenAPIPublisherTest`:

- add `/judicial/referrals/mine` to `JUDICIAL_PATHS`;
- increase the separately asserted judicial operation count from 4 to 5;
- assert `JudicialMyReferralsResponse` and unchanged `JudicialReferralSummary`;
- keep the caseworker operation count unchanged.

Update README/API inventory, package/Javadocs, `CurrentUser`'s resolved warning and the completed api-3 plan.
Regenerate OpenAPI and verify the delta is exactly the new GET/schema plus the expected operation-count text.

- [ ] Contract and documentation describe five judicial operations; no caseworker or existing schema drift

### Task 5: Full validation

Run:

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

Record final unit/integration totals against Task 0.

- [ ] Full build green; final test totals recorded

## Out of scope

- Permanent judicial history after `Closed`/`Withdrawn`.
- Judicial case search or applicant data in list rows.
- Filtering by another judge, display name or caller-supplied owner.
- Changing claim expiry or adding a scheduled sweep.
- Changing referral/application states or caseworker worklists.
- Automatically backfilling a stable subject from a display name.

