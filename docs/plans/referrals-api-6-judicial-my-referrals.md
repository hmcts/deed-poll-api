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

- [x] Stable subject owns claim/direction; display name is attribution only; unsafe legacy rows fail closed;
      migration and focused tests green

#### What Task 1 did, 2026-09-21

`V14__referral_owner_subjects.sql` adds nullable `claimedById` and `decidedById` and nothing else; no index, for
the reason V6 gives for the pool. Nothing is backfilled. `Referral` maps both, and the three name-based ownership
sites now compare the subject: `ReferralService.requireHeldBy`, `JudicialReferralService.visibleTo` and its
`heldBy` helper. `claim` requires `caller.requireUserId()` **before the sweep** — the first thing either claim path
writes — and stores it; `decide` stores `decidedById`; `releaseStaleClaim` clears `claimedById` with the rest of the
active-claim fields and never touches `decidedById`. The subject is resolved before `visibleTo`'s switch rather
than inside its arms, as `SavedQueryPermissions.visibleTo` does, so an unidentified caller is refused even for the
shared pool. Display names and every response shape are unchanged.

Tests: namesake and renamed-judge cases in `ReferralServiceTest` (new `ClaimOwnership` nest), in
`JudicialReferralServiceTest.VisibleTo`, in `JudicialSecurityTest.CrossJudgeIsolation`, and over real HTTP in
`JudicialReferralControllerTest` — which now mints a token per judge, because ownership is keyed on the token's
`sub` and `IdamUserInfoClient` requires the token and userinfo to agree on it. Null-owner rows are proved
invisible and undirectable; a subject-less caller is proved to be refused before the sweep runs.

`./gradlew build` green: `test` **2,343** + `integration` **8** = **2,351**, zero failures (baseline 2,336).

`CurrentUser`'s "two places authorise on name" warning is deliberately **not** touched here — Task 4 owns it.

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

- [x] Owner-scoped query and service implemented; stale rows cannot be revived; ordering/paging/isolation
      proven; focused tests green

#### What Task 2 did, 2026-09-21

`ReferralRepository.findOwnedBy(owner, claimed, decided, pageable)` is one JPQL query with two state arms —
`Claimed` against `claimedById`, `Decided` against `decidedById` — so the predicate is the same rule
`visibleTo` applies to an owner, and a null owner column matches nothing because SQL `=` never does. No index,
for the reason V6 and V14 both give. The count is Spring Data's over the same predicate, so the rows and the
total cannot describe different result sets. It is the **fourth** unscoped finder, admitted in
`ReferralSecurityTest` with a promise of its own (an owner, two states and a page; never a referral id) and an
ArchUnit rule that nothing but `JudicialReferralService` calls it — because a second caller would be a second
place that could pass an owner a *request* had named.

`JudicialReferralService.mine(caller, paging)` requires `JUDICIAL`, then `requireUserId()` **before the sweep**
(as `claim` does — an unidentified caller must not release somebody else's lapsed claim on their way to a 401),
then `reclaimStaleClaims()`, then the query. `MINE_ORDER` is `state` asc, `urgency` desc, `createdOn` asc, `id`
asc: ascending state is `Claimed` before `Decided` because `V6` declares `"ReferralState"` in lifecycle order,
and the id tie-break is what makes paging stable rather than nearly stable. The page size is the pool's
`POOL_PAGE_SIZE` — same dashboard, same screenful, one number to keep in step.

A new domain page type `ReferralWorkload`, because reusing `ReferralPool` would put "the pool" in the name of a
response that is the opposite collection. Its page count and the pool's now come from one place,
`CasePaging.pagesOf` — `CaseResults` still derives its own and should come here the next time the case list is
opened.

Tests: five container-backed ones in `ReferralRepositoryTest` (membership including `Open`/`Closed`/`Withdrawn`
rows that still name the judge, namesake and null-owner exclusion in both directions, the four-key order with a
tie-break compared the way **Postgres** orders `uuid` — sixteen unsigned bytes, not `UUID.compareTo`'s two
signed longs — database paging with another judge's row present, and a direction listed for its author rather
than its claimant); a `Mine` nest in `JudicialReferralServiceTest` (role and subject gates refusing before the
sweep, sweep-before-query in order, the exact question asked, subject-not-name, a rename keeping its list, no
in-memory filtering, totals and immutability); and `mine` added to `JudicialSecurityTest.operations()`, so the
whole surface's role-gate and near-miss rules cover it today. That class's controller assertion is now
`ROUTED_OPERATIONS = 4` — the service is deliberately one ahead of the controller until Task 3 publishes the
route, which Task 3 takes to 5.

`./gradlew build` green: `test` **2,363** + `integration` **8** = **2,371**, zero failures (Task 1: 2,351).

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

- [x] Fifth judicial operation implemented with exact membership and security; HTTP tests green

#### What Task 3 did, 2026-09-21

`JudicialReferralController.mine` is `@GetMapping("/mine")` — a literal segment, so Spring's pattern comparator
prefers it over `/{referralId}` and `mine` is never parsed as a referral UUID. It binds `page`/`size` as strings and
reads them through `CasePaging.parse` with `POOL_PAGE_SIZE`, exactly as `pool` does, so an unreadable value is this
service's 400 naming the parameter rather than Spring's type-mismatch answer.

`JudicialMyReferralsResponse` is a new envelope with the pool's shape and `JudicialReferralSummary` rows, reused
unchanged — that type already publishes `state` for this list, and a row here says which of a judge's referrals they
are still holding and which they have directed. A separate envelope from `JudicialPoolResponse` for the reason
`ReferralWorkload` is separate from `ReferralPool`: in the generated client the type is the documentation, and one
type would put "pool" in the name of the pool's complement.

Sixteen tests in a new `JudicialReferralControllerTest.MyReferrals` nest, over real HTTP with a real judicial token:
membership (both owned states present, `Open`/`Closed`/`Withdrawn`/other-judge/null-owner absent, and a namesake of
the holder getting their own empty list); the four-key order with the oldest of five referrals coming last because it
is `Decided`; database paging with another judge's row present; the whole lifecycle in one test — claim → appears,
read → no duplicate or reorder, direction → remains as `Decided`, close → gone — plus withdrawal separately; the
sweep taking a lapsed claim off this page and returning it to the pool in the same request; 400 on both malformed
paging parameters; 401 with the claim proved unswept; 403 for a caseworker and for an administrator; and the
dispatch, asserted against `GET /judicial/referrals/theirs` still being the detail read's 404. No case data, no
direction text and no owner subject on a row, asserted over the whole document as well as field by field.

`JudicialSecurityTest.ROUTED_OPERATIONS` is now **5** and `/judicial/referrals/mine` is in its `JUDICIAL_PATHS`, so
the service and controller surfaces agree again and the allowlist assertion covers the new path. The controller's
class Javadoc and `api/judicial/controller/package-info.java` now describe five operations — they stated four as
decision 13's boundary, so leaving them would have been a documented falsehood in the package being changed.
`OpenAPIPublisherTest` is untouched and still green: its judicial count sums only over its own `JUDICIAL_PATHS`, and
adding the path and the 4 → 5 assertion is Task 4's.

`./gradlew build` green: `test` **2,379** + `integration` **8** = **2,387**, zero failures (Task 2: 2,371).

### Task 4: Publish the contract and update documentation

Update `OpenAPIPublisherTest`:

- add `/judicial/referrals/mine` to `JUDICIAL_PATHS`;
- increase the separately asserted judicial operation count from 4 to 5;
- assert `JudicialMyReferralsResponse` and unchanged `JudicialReferralSummary`;
- keep the caseworker operation count unchanged.

Update README/API inventory, package/Javadocs, `CurrentUser`'s resolved warning and the completed api-3 plan.
Regenerate OpenAPI and verify the delta is exactly the new GET/schema plus the expected operation-count text.

- [x] Contract and documentation describe five judicial operations; no caseworker or existing schema drift

#### What Task 4 did, 2026-09-21

`OpenAPIPublisherTest` now lists `/judicial/referrals/mine` in `JUDICIAL_PATHS` and asserts **5** judicial
operations, separately from the caseworker **33**, which is untouched. Two assertions were added beside the count,
because a count alone would not have caught either mistake: `/judicial/referrals` still carries exactly one
operation, so `mine` cannot have been published by widening the pool's entry, and `/judicial/referrals/mine`
carries exactly a `get`.

A new test, `documentsThePersonalReferralList`, pins the contract the admin client is generated from: the
operation answers with `JudicialMyReferralsResponse` by name — a client generated against `JudicialPoolResponse`
would compile and then call the pool's complement the pool — its five properties, `referrals` items pointing at
`JudicialReferralSummary`, the two optional paging parameters, and the pool still answering with its own envelope.
The row type is asserted to carry **exactly** the seven referral fields it carried as the pool's row and nothing
else, and the two owner columns are searched for as text over the whole document, so a schema this test does not
know about cannot publish a judge's subject either. Response media types are asserted as `*/*`, which is what
springdoc publishes for a controller declaring no `produces` — the document as it is, not as one might prefer it.

**The delta was measured, not asserted.** The spec was regenerated from `master` at `7226fc9` in a throwaway
worktree and compared with this branch's: **one** new path (`/judicial/referrals/mine`, one GET), **one** new
schema (`JudicialMyReferralsResponse`), and with those two deleted the two documents are identical — so
`JudicialReferralSummary`, every caseworker path and every other schema are unchanged. 56,771 → 57,495 bytes.

Documentation: `README.md` (five operations, the new table row, the personal list's membership rule and why it is
not an `?owner=` parameter, ownership as the IDAM subject, and "two reads that write" corrected to three reads all
of which do); `CLAUDE.md` (five operations, and a new rule that ownership is the subject and a null owner belongs
to nobody); `docs/RESUME.md` (V14 and notify-3's V15, judicial 4 on `master` and 5 here, api-6 complete on branch
with its contract delta, the IDAM judicial-role item, test totals 2,388, and the spec-size baseline);
`CurrentUser`'s javadoc, whose "two places authorise on name" warning is now the record of a **fixed** defect and
a plain instruction to authorise on the subject and never fall back to the name; and the completed
`referrals-api-3` plan, where a dated note says the four it states throughout is now five and flag 7's migration
is done — appended rather than rewritten, as this repo's plans require.

`./gradlew build` green: `test` **2,379** + `integration` **9** = **2,388**, zero failures (Task 3: 2,387 — the
new OpenAPI test is the one added test, and it is an integration one).

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

