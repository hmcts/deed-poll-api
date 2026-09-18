# Plan: referrals to the judiciary (3 of 3) — the judicial HTTP surface

## Overview

**Third and last of the api plans** for the in-app judiciary-referral feature (seven plans total; see
`referrals-api-1-create-and-withdraw.md` for the shape and the full decision record, held in the workspace
memory `deed-poll-referrals-feature`). api-1 built the `Referral` entity and the caseworker operations
(create/list/get/withdraw). api-2 built the referral **state machine** — the `claim`, `decide`, `close`
transitions and the release-on-read expiry sweep — as **domain methods**, exposing over HTTP only the
caseworker's `close`, and added `Role.JUDICIAL` so the transitions' gate is expressible and testable.

**This plan is the thin judicial exposure layer that api-2 was cut to leave.** It adds a new `/judicial/**`
namespace and the four things a judicial user does, and nothing else — every state change it performs is a
call into an api-2 transition:

- **See the shared pool** — the `Open` referrals awaiting a judge, urgent-first then oldest, paged. This is
  the query that runs api-2's `reclaimStaleClaims()` sweep.
- **Open a referral** — read the referral, the application details needed to direct it, and the case's
  notes — read-only, and only for a referral the judge may see.
- **Claim** — call api-2's `claim` transition.
- **Direct** — call api-2's `decide` transition.

This is the security core of the whole feature. A judicial user is **not** a caseworker: the boundary
(decision 13) is the tightest that still lets them work, and getting it wrong is a data-exposure bug, not a
UX one. The three rules below are the plan.

## The judicial boundary — decision 13, restated because it is the whole plan

- **List:** only the shared pool of `Open` referrals, plus the judge's own `Claimed`/`Decided` referrals. No
  full case list, no case search, no dashboard of all applications. A judge cannot enumerate deed poll
  applications.
- **Read:** for a referral the judge may see, the referral itself, a **narrow** projection of its
  application's details, and that application's notes — all **read-only**. A judge reaches an application
  *only* through a referral they may see, never by case id.
- **Write:** exactly two — `claim` a pool referral, and record a `direction` on one they hold. No editing
  case data, no adding notes, no creating or withdrawing referrals, no closing.

## Decisions taken — implement these, do not revisit

1. **A new `/judicial/**` namespace, with its own controllers and its own DTOs.** `api/judicial/controller`
   and `api/judicial/dto`, mirroring how `api/citizen` is structured and separated. **The judicial DTOs must
   not reuse the caseworker DTOs**, and `NamespaceSeparationSecurityTest` must be extended to assert it (see
   decision 4). This is not tidiness: `CaseDetailResponse` carries every field a caseworker sees, including
   the admin-only privacy fields, and a judge reusing it is one field away from seeing what they must not.
   The judicial application view is a deliberately narrow, separate projection.

2. **The judicial surface is keyed on the *referral*, not the case.** Paths are `/judicial/referrals` and
   `/judicial/referrals/{referralId}` — there is no `{caseId}` in a judicial URL, because a judge does not
   browse cases and must not be able to name one. The service resolves the case *from* the referral. This is
   a deliberate departure from the caseworker surface's case-scoped nesting, and it means the judicial read
   path legitimately needs a **finder by referral id** — which api-1/api-2 forbade for the caseworker
   surface. The safety boundary is not gone, it has **moved**: where a caseworker read is scoped by case, a
   judicial read is scoped by **visibility** (decision 3). Every judicial read runs through the visibility
   check before returning anything.

3. **Visibility, and "resolve then refuse", exactly as the saved-queries plan does it.** A referral is
   visible to a judge if it is `Open` (in the pool) **or** it is `Claimed`/`Decided` **and** `claimedBy`/
   `decidedBy` is this judge. For a referral that is not visible — `Withdrawn`, `Closed`, or `Claimed`/
   `Decided` by *another* judge, or an id that never existed — every judicial read and write answers **404**,
   indistinguishably, so no referral id is discoverable and one judge cannot learn another holds a case.
   There is no 403 on the judicial surface for an invisible referral: a judge either may see it or is told it
   is not there. (The 403 that exists is the *role* gate — a non-`JUDICIAL` token — which api-2's
   `requireAnyRole` already throws before any of this.)

4. **Every judicial route requires `Role.JUDICIAL`, enforced in the domain, and asserted by a security
   test.** The transitions already call `requireAnyRole(Role.JUDICIAL)` (api-2). The two read endpoints must
   too — a caseworker token must not read the pool or a judicial application view, because that view and the
   pool are the judicial persona's, and a caseworker has their own richer surface. So the judicial *service*
   methods each `requireAnyRole(Role.JUDICIAL)`, and `NamespaceSeparationSecurityTest` (or a new
   `JudicialSecurityTest`) asserts (a) the judicial namespace shares no DTO with caseworker or citizen, and
   (b) a caseworker token is refused every judicial route.

5. **The pool is ordered urgent-first, then oldest within each urgency band, and paged** (decisions 15, 16).
   `Urgent` referrals sort above `Normal`; within a band, oldest `createdOn` first (the longest-waiting is
   worked first). Default page size ~10 (decision 15 — a page size, **not** a cap; the whole pool is
   reachable by paging, and the total count is returned so "work waiting" is visible). Ordering and paging
   are done in the query — api asks the database to sort and page, never fetches-and-reorders — reusing the
   `CasePaging`/sort approach the case list already uses where it fits.

6. **Reading the pool runs the expiry sweep first** (decision 22 / api-2). The pool endpoint calls
   `reclaimStaleClaims()` before it queries, so a stale claim is released (and its lapse note written) and
   the referral is back in the pool the same request it is observed. This is the mutating-read api-2
   documented; the pool endpoint is its caller.

7. **Opening a referral refreshes the claim's `lastTouchedAt` when the judge holds it** (api-2's `touch`).
   A judge who is actively reading a referral they claimed is working it, so the inactivity clock resets —
   this is what stops decision 6's "lapsed while researching law" from firing on a judge who is plainly still
   engaged. Reading a referral the judge does *not* hold (a pool referral they have not claimed) touches
   nothing.

8. **The judicial application view is a narrow projection, and its field set is now CONFIRMED (Steve,
   2026-09-17).** It carries what a judge needs to direct — the reference, application type, old and new
   names, and the referral's own reasons/notes/urgency — and it **excludes** the caseworker-only and
   administrative fields (payment/fee references, internal audit trails, the admin-only privacy editability).
   This was written as a defensible *minimum* pending a legal/policy call ("what must a judge see to make a
   lawful direction, and what must they not?"); **Steve has confirmed this exact set is correct.** Implement
   it as stated — it is no longer provisional. Notes are returned via the existing `NoteService.findNotes`,
   read-only. Any *addition* to this set is a fresh legal/policy decision, not an engineering one.

## The endpoints

Four new operations in a **new namespace**, so they do **not** change the caseworker count (api-2 left it at
30). The judicial surface is published and documented alongside the caseworker one.

| Method | Path | Who | Calls |
|---|---|---|---|
| `GET` | `/judicial/referrals` | `JUDICIAL` | the pool (runs the sweep), urgent-first then oldest, paged |
| `GET` | `/judicial/referrals/{referralId}` | `JUDICIAL` | the referral + narrow application view + notes; 404 if not visible |
| `POST` | `/judicial/referrals/{referralId}/claim` | `JUDICIAL` | api-2 `claim` |
| `POST` | `/judicial/referrals/{referralId}/direction` | `JUDICIAL` | api-2 `decide` |

`claim` and `direction` add **no new transition logic** — they are HTTP adapters over api-2's domain
methods, translating the path/body/`CurrentUser` into a call and the result (or its `ConflictError`/
`NotFoundError`) into a response. The 409s api-2 defined (claim a non-`Open` referral; direct one you do not
hold) surface here unchanged.

## Constraints

Same as api-1/api-2 (Java 21, `-Werror`, checkstyle on five source sets; `TESTCONTAINERS_RYUK_DISABLED=true`;
mixed-case columns; DTOs not entities; a controller test boots the whole context; do not edit `reference/`).
Additional to this plan:

- **This plan adds no migration** — no schema change. It is controllers, DTOs, judicial service/query
  methods, and tests over api-1's table and api-2's transitions.
- **`SecurityConfiguration` needs no change.** The service is authenticated-by-default with a fixed public
  allowlist; `/judicial/**` is protected by omission the moment it exists, and the `JUDICIAL` role gate is a
  domain check (api-2's `requireAnyRole`), not a config matcher. Do **not** add a per-namespace matcher — the
  allowlist-not-per-route decision is deliberate (see `SecurityConfiguration`'s javadoc). An unknown or
  unauthorised judicial path answers 401 before routing, which is correct.
- **`NamespaceSeparationSecurityTest` is an ArchUnit bytecode rule and must be extended, not sidestepped.**
  It today asserts caseworker and citizen share no DTO. Add `judicial` as a third namespace and assert it
  shares no DTO with either — the rule catches a fully-qualified reference or a generic parameter, not just
  an import, so the judicial DTOs must be genuinely separate types, not aliases of caseworker ones.
- **`OpenAPIPublisherTest` asserts the *caseworker* count over `CASEWORKER_PATHS`, and this plan must not
  change that number** (it stays 30 — judicial paths are not caseworker paths). But the judicial routes
  should be **published and asserted too**: add a `JUDICIAL_PATHS` list and assert the spec `containsAll` of
  it, mirroring the existing `containsAll(CASEWORKER_PATHS)`, so a judicial route that silently disappears
  from the spec fails the build the same way a caseworker one would. Do not fold judicial paths into
  `CASEWORKER_PATHS` or the caseworker count — that would misreport the caseworker surface.
- **Grep before touching any count.** `git grep -inE '\b(30|thirty)\b'` for the caseworker count (leave it
  at 30), and state the judicial operation count (4) wherever the caseworker one is stated —
  `package-info.java` for the new namespace, `README.md`, `docs/RESUME.md`, `CLAUDE.md`. The judicial surface
  is new, so those files gain a judicial line rather than having a number changed.
- **Container-free tests where CI runs them.** The visibility rule (decision 3 — which states are visible to
  whom, and that an invisible referral is a 404) and the ordering rule (decision 5) are the logic most worth
  pinning and need no container. The pool query's SQL ordering/paging and the sweep's atomic UPDATE get
  repository tests that boot a container (CI-excluded, accepted).

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

`build` is compile + checkstyle across five source sets + `test` + `integration` (`build.gradle:113`) — so
`OpenAPIPublisherTest` and `NamespaceSeparationSecurityTest` both run here.

**This plan is stacked on api-2's branch** (`referrals-api-plans`), so the table, transitions and
`Role.JUDICIAL` already exist. **Measure the baseline on this branch before Task 1** by summing `tests="…"`
across `build/test-results/{test,integration}/*.xml`, and record it next to the final count in Task 4.

**A worker or container startup timeout is an environment failure, not a code failure** — re-run before
investigating, and consider a `git archive` copy under `/tmp`.

## Tasks

### Task 1: The judicial namespace, visibility, and the pool query

Create `api/judicial/controller` and `api/judicial/dto` with `package-info.java` on each, mirroring
`api/citizen`'s — stating that the namespace is judicial, authenticated by default, role-gated in the
domain, and separate from caseworker DTOs on purpose.

`domain/referrals/`: a judicial query/service surface (`JudicialReferralService` or methods on the existing
referral service — keep it one clearly-judicial unit):

- `visibleTo(referral, caller)` — the decision-3 predicate: `Open`, or (`Claimed`/`Decided` and this judge).
  A plain, container-free-tested function; every judicial read uses it and returns 404 when it is false.
- `pool(caller, paging)` — `requireAnyRole(JUDICIAL)`; call `reclaimStaleClaims()`; return the `Open`
  referrals ordered `Urgent` first then oldest `createdOn`, paged (decision 5), with the total count.

Extend `NamespaceSeparationSecurityTest` to add `judicial` as a third namespace sharing no DTO with the
other two (decision 4).

- [x] `api/judicial/{controller,dto}` exist with documented `package-info`; `visibleTo` implements decision
      3 and is unit-tested container-free for every state and both judges; `pool` requires `JUDICIAL`, runs
      the sweep, orders urgent-then-oldest and pages with a total, proven by a container-free ordering test
      and a repository test for the SQL; `NamespaceSeparationSecurityTest` covers judicial;
      `./gradlew build` green

**Task 1 as built.** `JudicialReferralService` (domain/referrals) holds both halves — `visibleTo` as a static
predicate and `pool(caller, paging)` gating on `JUDICIAL`, sweeping, then querying. Ordering is
`POOL_ORDER = urgency desc, createdOn asc` handed to the query: descending `Urgency` is most-urgent-first
because `V1__enums.sql` declares the type `('Normal', 'Urgent')` and Postgres orders an enum by its declared
order — `ReferralRepositoryTest` proves that against a real database, `JudicialReferralServiceTest` proves
container-free that the service asks for it rather than fetching and reordering. `ReferralPool` carries the
page and the pool's total (decision 15: a page size, not a cap; `POOL_PAGE_SIZE = 10`). The pool needed
`ReferralRepository.findByState(state, pageable)`, a second documented exception to that interface's
case-scoping rule; `ReferralSecurityTest` admits it deliberately, asserts it can only be asked for "a page of
the referrals in this state" and never for one referral, and adds an ArchUnit rule that nothing but
`JudicialReferralService` calls it.

**Branch baseline, measured before Task 1** (for Task 4): `test` 1975, `integration` 7 — 1982 total. After
Task 1: `test` 2005, `integration` 7 — 2012 total.

### Task 2: The pool and referral read endpoints

`JudicialReferralController`:

- `GET /judicial/referrals` → `pool(...)`, returning a `JudicialReferralSummary` list (id, reasons, urgency,
  age/`createdOn`, state) plus the total — a **judicial** DTO, not `CaseSummaryResponse`.
- `GET /judicial/referrals/{referralId}` → resolve by id, `visibleTo` else 404; return a
  `JudicialReferralDetail` = the referral's fields + a **narrow** `JudicialCaseView` (decision 8: reference,
  type, old/new names, the referral's reasons/notes/urgency — and none of the caseworker-only/admin fields)
  + the case's notes (read-only, via `NoteService.findNotes`). If the judge holds the referral, refresh
  `lastTouchedAt` (decision 7); if they do not, touch nothing.

DTOs are judicial-namespace types; the controller returns them, never an entity and never a caseworker DTO.

- [x] Pool endpoint returns judicial summaries + a total and 401s an unauthenticated caller; detail endpoint
      returns the referral + narrow application view + read-only notes for a visible referral and 404s an
      invisible one (withdrawn, closed, or another judge's) indistinguishably from a non-existent id;
      reading a held referral refreshes `lastTouchedAt`, reading a pool referral does not; controller test
      boots the context; no judicial DTO references a caseworker DTO; `./gradlew build` green

**Task 2 as built.** `JudicialReferralController` at `/judicial/referrals`, two handlers, both thin: `pool` reads
`page`/`size` through `CasePaging.parse(page, size, POOL_PAGE_SIZE)` — a new three-argument overload, because the
default screenful belongs to the page being rendered while the cap and every refusal stay `CasePaging`'s — and
`referral` maps one `JudicialReferralView`. Five judicial DTOs, no type shared with caseworker:
`JudicialPoolResponse`/`JudicialReferralSummary` (a queue of referrals, carrying **no** case field at all — not
even the reference, since reaching the application is the detail read), `JudicialReferralDetail`,
`JudicialCaseView` (decision 8's eight fields, with the exclusions enumerated on the record) and
`JudicialCaseNote` (note, author, when — narrower than `NoteResponse`: no id a judge can address, no amendment
trail).

`JudicialReferralService.referral(referralId, caller)` gates `JUDICIAL`, resolves, `visibleTo`-or-404, then calls
api-2's `touch` **only when the caller holds it** — `Claimed` by them, which is narrower than visible, because
`touch` 409s on the `Decided` referral visibility admits. The notes come from `NoteService.findNotes`, unchanged.

**The one new declaration, and the plan's flag 3 made concrete.** `ReferralRepository.findWithCaseById` is the
finder by referral id alone that api-1 forbids, admitted as the third documented exception: the judicial surface
has no case id to scope with, so `visibleTo` stands where the parent id stands. It join-fetches the case, which is
a correctness requirement rather than a tuning choice (`Referral.deedPoll` is `LAZY` and `open-in-view` is false,
so mapping `JudicialCaseView` after the transaction would otherwise be a `LazyInitializationException`);
`ReferralRepositoryTest` asserts the fetch with `Hibernate.isInitialized`, and `ReferralSecurityTest` adds an
ArchUnit rule that nothing but `JudicialReferralService` calls it, plus rules that no judicial handler takes a
case id and that the judicial controller neither returns nor binds an entity.

`JudicialReferralControllerTest` (24 tests, container-backed, CI-excluded) is the first test in the suite where
the **judge holds a real token** — `StubIdam` serves a userinfo document carrying the judicial claim — so the
role gate, the 404s and the mutating reads are asserted end to end: the sweep releases a lapsed claim and offers
it in the same response, a held referral's clock is refreshed and a pool referral's is not, another judge's
referral answers a body byte-for-byte identical to a non-existent id's, and the excluded fields are asserted
absent by *value* over the whole document as well as by name. After Task 2: `test` 2050, `integration` 7 — 2057
total.

### Task 3: The claim and direction endpoints

`JudicialReferralController`, both thin adapters over api-2 transitions:

- `POST /judicial/referrals/{referralId}/claim` → resolve, `visibleTo` else 404, then api-2 `claim` (which
  re-checks `Open` and 409s otherwise, and runs the sweep). Returns the updated referral as a judicial DTO.
- `POST /judicial/referrals/{referralId}/direction` → body `JudicialDirectionRequest` (decision + directions
  text + `publishToGazette`), resolve, `visibleTo` else 404, then api-2 `decide` (which re-checks the judge
  holds it and 409s otherwise, validates the direction as a 400). Returns the updated referral.

Then the spec, **this task**: add `JUDICIAL_PATHS` (`/judicial/referrals`,
`/judicial/referrals/{referralId}`, `/judicial/referrals/{referralId}/claim`,
`/judicial/referrals/{referralId}/direction`) to `OpenAPIPublisherTest`, assert the spec `containsAll` of
it, **leave the caseworker count at 30**, and state the judicial operation count (4) in the prose files a
grep finds. Add a judicial `package-info` operation note.

- [ ] Claim and direction are live, gated `JUDICIAL`, 404 on an invisible referral, and surface api-2's 409s
      (claim non-`Open`; direct one not held) and 400 (bad direction) unchanged; spec publishes all four
      judicial paths and `OpenAPIPublisherTest` asserts them; caseworker count still 30; judicial count
      stated in prose by grepping; controller test boots the context; `./gradlew build` green

### Task 4: Security and full validation

- Security tests: a **caseworker** token is refused every judicial route (403 from the role gate), an
  unauthenticated caller gets 401, and one judge cannot read, claim or direct a referral another judge holds
  (404, the visibility rule). Keep the role and visibility assertions container-free.
- [ ] Caseworker-refused-on-judicial, unauthenticated-401, and cross-judge-isolation tests pass; namespace
      separation green; `./gradlew build` green from cold with `TESTCONTAINERS_RYUK_DISABLED=true`;
      checkstyle clean on all five source sets; test and integration totals recorded next to this branch's
      baseline; the count with `INTEGRATION_TESTS_ENABLED=false` recorded

## Deliberately not in this plan

- **The judicial IDAM role's provisioning and the judicial login surface.** idam-1. This plan consumes
  `Role.JUDICIAL` (added in api-2) and gates on it; it configures no IDAM and builds no login.
- **Any UI** — the simplified judicial screen (10-referral dashboard, application view, direction
  check-your-answers). `deed-poll-admin` admin-3.
- **New transition logic.** All of it is api-2's; this plan only exposes `claim`/`decide` and reads.
- **A judicial "my decided referrals" history view** beyond what visibility already permits. Visibility
  includes a judge's own `Decided` referrals, so the data is reachable; a dedicated history/list endpoint,
  if wanted, is additive and not asked for.
- **A scheduled expiry sweep** and **sync-back to the legacy flat columns** — api-2's flagged follow-ups.

---

## Flags for Steve — pick these up on return

These are the points where I made a defensible call to keep the plan whole, but that you should confirm or
redirect. None blocks the plan being *written*; some should be settled before it is *run*.

1. **RESOLVED (Steve, 2026-09-17): the judicial application view's field set (plan decision 8) is
   confirmed.** This was the one field in the feature where "too much" is a disclosure and "too little"
   blocks a lawful decision, so it was implemented as a defensible minimum — reference, application type,
   old/new names, plus the referral's reasons/notes/urgency, excluding payment/fee references, internal
   audit trails and the admin-only privacy fields — pending a legal/policy call. **Steve has confirmed that
   exact set is correct.** Decision 8 is no longer provisional; build it as stated. Any later *addition* to
   the set reopens the legal/policy question.

2. **`/judicial/**` is a new third namespace (plan decision 1).** It sits alongside `caseworker` and the
   reserved-empty `citizen`. I judged this cleaner and safer than gating a subset of `/caseworker/**` by
   role, because it keeps the judicial DTOs physically separate (the ArchUnit rule enforces no field
   leakage). If you'd rather the judiciary live under a role-gated slice of the caseworker surface, that is a
   different shape and I should redraw api-3 — but I don't recommend it.

3. **The judicial surface is keyed on the referral, not the case (plan decision 2)** — no case id in a
   judicial URL. This is what stops a judge naming an arbitrary case, and it required a referral-id finder
   that the caseworker surface deliberately forbids. The safety moved from case-scoping to the visibility
   check. I'm confident in this, flagging it because it's the one place api-3 inverts an api-1 rule and a
   reviewer should understand why.

4. **Carried forward from api-2, still open:** the exact `JUDICIAL` IDAM **claim-value string**
   (`caseworker-deedpoll-judicial` is the working value — idam-1 confirms it), and **decision 10's
   sync-back** to the legacy flat columns on close (I resolved it leave-as-is; the alternative is a
   "latest decision" mirror, additive).

5. **Running order and provisional state, for when you pick the admin plans up.** All three api plans are
   written and committed on `referrals-api-plans`, **local, not pushed, and not yet run through ralphex.**
   They are runnable in order (api-1 → api-2 → api-3) with the `./gradlew build` gate green from task 1 —
   they have no upstream contract dependency, unlike the admin plans. The **admin** plans (admin-1/2/3) will
   be provisional-ahead-of-contract in exactly the way plans 11/12 are: each needs this api work run, its
   spec published, and `deed-poll-admin`'s `openapi.json` refreshed, plus a Task 0 reconciliation gate. idam-1
   (the judicial role) is small and may not warrant a ralphex run, but is written up so the running order is
   complete.
