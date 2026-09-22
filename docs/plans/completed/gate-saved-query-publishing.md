# Plan: only an Administrator may publish a saved query

## Overview

**One missing predicate, and a test that currently pins its absence.** A saved query's `shared` flag is what
puts it in the library where every caseworker can find it. Today **any** caseworker can set that flag — on
create and on patch — because nothing checks the role. The business rule is that publishing to the library is
an Administrator's act; saving a query for yourself is everybody's.

Scope is this repo. The staff service's half — hiding the control and grouping the two kinds of query on
screen — is a separate plan in `deed-poll-admin`, and the two are **independent** (see "Sequencing" below).

## Where this rule came from, and why it is not in the business permission matrix

The matrix in `Deed Poll Power App Permissions Summary.docx` has eleven capability rows and does not mention
saved queries, because **saved queries are new behaviour rather than a port** — `SavedQuery` is the one entity
with no model in `reference/deed-poll.prisma`, and `SavedQueryService`'s docblock records that the Dynamics
original had fixed system views (`ListFilters`) rather than user-created ones.

So this rule is the product owner's, given directly on 2026-09-22, and it is **not** derivable from the
vendored specification. Recorded here so that a later reader reconciling against the docx does not "correct"
it away as an invention. Two consequences the owner confirmed at the same time:

- **No data migration.** The service has never been deployed and holds no rows, so there is no existing
  published query to grandfather or retract.
- **The staff service will group the two kinds on screen** — "Your queries" versus "Query library", split on
  whether a query is published rather than on who owns it.

## The gap, measured

| Where | What it does today | File |
|---|---|---|
| Create | `query.setShared(dto.shared())`, no role check | `SavedQueryService.java:118` |
| Patch | `dto.shared().ifPresent(query::setShared)`, no role check | `SavedQueryService.java:157` |
| Default | `shared.orElse(false)` — correct, and pinned | `SavedQuerySchemas.java:121` |
| The only role check in the whole domain | `editableBy` — an Administrator may edit somebody else's **already shared** query | `SavedQueryPermissions.java:61` |

**A passing test pins the defect.** `CaseworkerSavedQueryControllerTest.savesTheWholeView` (line ~320) posts
`"shared":true` as `OWNER`, whose roles are `List.of("caseworker-civil")` — no admin role — and asserts `201`
with `$.shared == true`. This is the create happy path, not incidental coverage, so Task 2 must change it
rather than add beside it. An `ADMIN` fixture already exists at line ~84.

**What is NOT broken, and must not be "fixed".** `visibleTo` is correct: an unpublished query is visible to
its owner alone, an Administrator gets no override, absence and inaccessibility are one indistinguishable
404, and the list query is `findBySharedTrueOrOwnerIdOrderByNameAsc`. All of it is tested. The docblock
already warns against adding an admin override to `visibleTo` "for consistency" — that warning stands, and
this plan does not touch visibility.

## Decisions — implement these, do not revisit

### 1. The predicate lives in `SavedQueryPermissions`, beside the other two

That file exists precisely so the rules most expensive to get wrong need no database to state, and so CI
actually runs them — the container-backed service tests are excluded on the Jenkins agent
(`INTEGRATION_TESTS_ENABLED=false`, no Docker). A publish gate belongs in that category.

### 2. Publishing is refused with `403`, not silently downgraded

A caller who is told `201 {"shared": false}` after asking for `true` has been lied to in a way no client can
detect. `403` rather than `400` because this is a permission, not a malformed field, and it is raised where
`requireEditableBy` raises its own — the service, never the controller, because
`SavedQueryService`'s docblock requires every rule about who may do what to live there so a future non-HTTP
caller is held to it too.

### 3. The patch rule is "may not set `shared` **true**", not "may not mention `shared`"

Refusing any patch that names the field would 403 a non-admin owner sending `shared: false` on their own
already-unpublished query — a request that changes nothing. Worse, it would make **unpublishing** a privileged
act, and the one case where that matters argues the other way: an Administrator who creates and publishes a
query and later loses the role still owns it, and should be able to withdraw it from the library. De-escalation
is not the thing being gated.

So: refuse when the patch would result in `shared == true` and the caller is not an Administrator. Setting it
false stays open to anyone `editableBy` already admits.

### 4. `editableBy` and `visibleTo` are unchanged

The new predicate is a third, orthogonal question. Publishing is not editing: an Administrator may already
edit a shared query they do not own, and that is a different rule with a different reason (an owner on leave
should not freeze a query the office relies on). Do not fold the two together.

### 5. No migration, no entity change, no contract change

The `shared` column already exists and its meaning is unchanged. And **nothing needs publishing to the
OpenAPI document**: this repo carries no `@ApiResponse` annotations on any caseworker controller — behaviour
is documented in Javadoc, and `OpenAPIWriteBodies` hand-describes only request bodies. Verified 2026-09-22:
no `403` is documented for any existing refusal either, including `requireEditableBy`'s. Adding one here
would be a new convention, and is out of scope.

### Sequencing — the two repos are independent

Stated explicitly because the opposite was assumed first. `deed-poll-admin`'s `contract.test.ts` is
set-equality over `components.schemas` and `paths`; it does not compare response codes. Since this plan
changes neither schemas nor paths, **no spec refresh is triggered and admin's suite is unaffected**. Either
repo may land first. The admin plan is still worth landing promptly — until it does, a caseworker sees a
Publish checkbox that now produces a 403 — but it is a UX gap, not a broken build.

## Validation Commands

All green **before any task runs** — verified 2026-09-22: **134 tests, 0 failures, 0 skipped**, the
container-backed controller tests included.

```bash
export TESTCONTAINERS_RYUK_DISABLED=true    # Ryuk listens on IPv6; this Docker config does not forward it
./gradlew test --tests "*SavedQuery*"
./gradlew test --tests "*Security*"
./gradlew build                              # compile + checkstyle + unit + integration; -Werror is on
```

Notes so no task rediscovers them:

- **Measure, do not read the exit code.** A run with zero tests selected also exits 0. Count from
  `build/test-results/test/*.xml`. `python3` is **not** installed here; `node` is.
- Gradle prints `Couldn't poll for events, error = 4` and a JVM class-sharing warning in this devcontainer.
  Both are noise and appear on a fully green run.

## Tasks

### Task 0: Reconcile before changing anything

- [x] Confirm the four rows of "The gap, measured" still read as stated, by file and line
- [x] Confirm `CaseworkerSavedQueryControllerTest`'s `OWNER`/`OTHER`/`ADMIN` fixtures and which the create
      happy path uses
- [x] Confirm no test asserts a non-admin *may* publish anywhere other than that one
- [x] Record anything that has moved, in this file, before writing code
- [x] Task 0 complete

#### What Task 0 found, 2026-09-22 — read this before Task 2

Reconciled on `master`, clean. **All four rows of "The gap, measured" are exactly as written**, at the lines
stated: `SavedQueryService.java:118` and `:157`, `SavedQuerySchemas.java:121`, `SavedQueryPermissions.java:61`.
The fixtures are as described — `OWNER` and `OTHER` hold `List.of("caseworker-civil")` and no admin role,
`ADMIN` holds `Role.DEEDPOLL_ADMIN.claimValue()` — and `savesTheWholeView` (line 322) posts as `OWNER`.

**One finding, and it changes Task 2's scope from "one test" to "one test and a fixture".**

The plan said a single test pins the defect. Over HTTP that is true — `"shared":true` appears in exactly one
request body in the whole suite. But `CaseworkerSavedQueryControllerTest` also has a fixture helper:

```java
private String save(Caller owner, String name, boolean shared) {          // line 914
    CreateSavedQueryInput input = new CreateSavedQueryInput(name, Optional.empty(), shared, SavedQueryDefinition.NONE);
    return savedQueryService.createSavedQuery(input, owner.currentUser()).query().getId().toString();
}
```

It goes **through the service**, and there are **35 `save(…, true)` calls — 34 as `OWNER`, one as `OTHER`**,
neither of whom is an Administrator. Gating `createSavedQuery` makes every one of them throw, which would take
most of the file's visibility, selection and permission coverage with it.

**The fixture must publish through the repository instead, and that is not a workaround.** After the gate, the
only way to reach "published" *through the service* is as an Administrator — and an Administrator who creates
a query owns it, so the service can no longer construct the state these tests need: **a published query owned
by somebody who is not currently an Administrator.** That state is legitimate and reachable in production —
an Administrator publishes a query and later loses the role, keeping ownership; it is the exact case
`deed-poll-admin`'s Task 1 had to handle. So a fixture that sets the column directly is modelling a real state
the service is not the only route to, which is what a fixture is for.

**`SavedQuerySecurityTest` is unaffected, and this was checked rather than assumed.** Its three service calls
are all outside the gate: `:171` creates with `shared=false`, and `:183` and `:299` both pass
`UpdateSavedQueryInput.EMPTY`, which presents no `shared` at all. That is decision 3 earning its keep — a gate
written as "refuse any patch naming `shared`" would have been fine here too, but one written as "refuse any
patch from a non-Administrator" would have broken both.

**`EntityMappingTest.java:249`** calls `setShared(true)` on the entity directly. That is a persistence
round-trip, not a permission path. Unaffected.

### Task 1: Add the predicate and its refusal

- [x] `SavedQueryPermissions.publishableBy(CurrentUser)` → `caller.isAdmin()`, resolving the caller through
      `requireUserId()` first for the reason the other two do — an unidentified caller must be refused, not
      compared
- [x] `requirePublishableBy(CurrentUser)` throwing `ForbiddenError` with a message beside `NOT_YOURS`, naming
      the rule rather than the caller: *"Only an Administrator may publish a saved query to the library"*
- [x] `SavedQueryPermissionsTest`: an Administrator may publish; a caseworker may not; a caller with no
      subject is refused rather than compared; **holding the judicial role is not holding the admin one**
- [x] Task 1 complete

#### What Task 1 did, 2026-09-22

`publishableBy(CurrentUser)` and `requirePublishableBy` are in `SavedQueryPermissions`, with `NOT_PUBLISHABLE`
beside `NOT_YOURS`. **The signature takes no query**, which is the structural half of decision 4: there is no
row whose `shared` or `ownerId` could make a non-Administrator publishable, so the obvious "improvement" of
passing a query in for symmetry with the other two is not available. A test states that.

`requireUserId()` is called before `isAdmin()` for the reason the other two predicates do it — an
**admin-shaped token with no `sub` must be refused rather than asked about its privileges**. Tested with a
`CurrentUser` holding `DEEDPOLL_ADMIN` and a null subject, which is a stronger case than the file's existing
`ANONYMOUS`-based test (that caller holds no roles, so it would pass either way).

The class docblock changed from "Two predicates and nothing else" to three, and now says why the three are
orthogonal — visibility and editability are properties of a caller *and a row*, publishing of the caller alone.

**Publishing block: 7 tests. `SavedQueryPermissionsTest` totals 19, 0 failures, 0 skipped.**

### Task 2: Gate create and patch

- [x] `createSavedQuery`: refuse before writing anything when `dto.shared()` and the caller is not an
      Administrator
- [x] `updateSavedQuery`: refuse when the patch presents `shared` as `true` and the caller is not an
      Administrator. A patch presenting it as `false` is untouched (decision 3)
- [x] **Change** `savesTheWholeView` to post as `ADMIN`, since its body publishes. Do not add a second
      happy path beside it
- [x] Add over HTTP: a caseworker creating with `shared:true` → 403 **and nothing persisted**; a caseworker
      creating without it → 201 unpublished; an Administrator creating with it → 201 published; a caseworker
      patching their own to `shared:true` → 403 and the stored row unchanged; a caseworker patching their own
      to `shared:false` → 200; an Administrator patching to `shared:true` → 200
- [x] Confirm the refusal is the service's: a `403` must be reachable without going through the controller
- [x] **Added, not in the original list:** the fixture helper publishes through the repository — Task 0's finding
- [x] Task 2 complete

#### What Task 2 did, 2026-09-22

Both gates are in the service, never the controller, as decision 2 requires. The create asks only when the
body requests publication, so the common case is never refused to guard the rare one. The patch reads
`Boolean.TRUE.equals(dto.shared().value())` — absent, cleared and explicitly-false all fall through, which is
decision 3 expressed in one line.

**The patch gate is placed *after* `editable`**, and the order is load-bearing: a caller naming somebody else's
unpublished query gets the 404 it already earned rather than a 403 that would confirm the id names something
publishable. There is a test for that ordering.

**Task 0's fixture finding, resolved.** `save(Caller, String, boolean)` now creates unpublished **through the
service** — preserving the original docblock's point that a fixture cannot have an owner no token would
supply — and then publishes **through the repository** when asked. All 35 `save(…, true)` fixtures pass. The
docblock explains that this is not a way around the gate: after it, the service cannot construct "a published
query owned by somebody who is not currently an Administrator", and that state is legitimate and reachable.

**Two tests beyond the list**, each pinning a decision somebody could reasonably undo:

- *"is refused rather than quietly given an unpublished query"* — decision 2's rejected alternative, stated as
  a test because a later reader could think the lenient version kinder.
- *"a caller who cannot see the query gets 404 rather than the publish refusal"* — the ordering above.

An existing test already proved de-escalation is open — `theOwnerAmendsIt` patches `shared:false` as a
non-Administrator — but it does so incidentally inside a broader assertion, so the rule now has a test of its
own too.

**148 tests across `*SavedQuery*`, 0 failures, 0 skipped** — up 14 from the 134 baseline: 7 in the predicate,
3 in create, 4 in patch.

### Task 3: Say so where the rules are already written down

- [x] `SavedQueryService`'s class docblock lists the permission outcomes as prose — add the publish rule to
      that list, since it is the file a reader is sent to
- [x] `SavedQueryPermissions`' class docblock says "Two predicates and nothing else". It is now three
- [x] `SavedQuery.shared`'s field comment ("Published to the library, which makes it findable") should say
      who may set it
- [x] Check whether `docs/` or the mock-facing notes in `deed-poll-admin` state the old rule anywhere
- [x] Task 3 complete

#### What Task 3 did, 2026-09-22

Five places, not the three listed. The service docblock's outcome list gained **two** entries rather than one —
the publish refusal, and the fact that *"asking to publish somebody else's unshared query"* is the 404 case
because `editable` answers first, which is the cell a reader would otherwise have to derive. The predicate's
class docblock went from two predicates to three and now says why they are orthogonal. `SavedQuery.shared`'s
comment names who may set it and records that a fixture or a migration writing the column directly is *not*
going through the rule, deliberately.

**Two the checklist did not name, found by grepping rather than assumed:**

- **`README.md`'s route table**, which is the document a consumer reads. Both the `POST` and `PATCH` rows
  described the old rule; both now state the gate, and `PATCH`'s says that clearing the flag does not need an
  Administrator — the asymmetry is the part a client would otherwise get wrong.
- **Both controller javadocs.** They document each operation's status codes in prose, and a 403 that no route
  comment mentions is a 403 a client discovers at runtime.

`deed-poll-admin` needed nothing: its mock is deliberately role-blind, which its own plan 14 records and which
this rule does not change.

### Task 4: Full validation

- [x] Run every command above, and record the counts in this file rather than asserting green
- [x] Confirm the total rose by the number of tests added and that `skipped` is still 0
- [x] Task 4 complete

#### What Task 4 measured, 2026-09-22

| Command | Result |
|---|---|
| `./gradlew test --tests "*SavedQuery*"` | **EXIT 0** — **148 tests**, 0 failures, 0 errors, **0 skipped** |
| `./gradlew test --tests "*Security*"` | **EXIT 0** — **101 tests**, 0 failures, 0 skipped |
| `./gradlew build` | **EXIT 0** — unit **2,458 tests across 314 files**, integration **11**, 0 failures, 0 skipped |

**The `*SavedQuery*` total reconciles exactly: 134 → 148, a rise of 14** — 7 in the predicate, 3 in create, 4 in
patch. All 35 repository-published fixtures pass, which is what confirms Task 0's fix.

**Three measurement traps hit while doing this, each worth the next task's time:**

1. **A second `gradle test` run wipes `build/test-results/`.** Measuring `*SavedQuery*` and then `*Security*`
   and reading the directory once gave 13 SavedQuery tests — an artefact, not a regression. Measure immediately
   after each run.
2. **`BUILD SUCCESSFUL` with no result file at all.** The first targeted run was `UP-TO-DATE` and ran nothing,
   which looks identical to a pass. Use `--rerun-tasks` when the count is the point.
3. **`--tests "*SavedQuery*"` also runs one method in `DeedPollServiceTest`**, so a whole-directory count reads
   149 where the filename-filtered count reads 148. The 148 is the apples-to-apples figure against the
   baseline; the difference is method-name matching, not a new test.

**`./gradlew build` failed once, on `checkstyleTest`, and the cause is a naming rule worth knowing:** method
names must match `^[a-z][a-z0-9][a-zA-Z0-9_]*$`, so a **single-letter first word followed by a capital** is
rejected — `aCaseworkerMayNotPublish` fails, `anAdministratorMay` passes. Four of my test names broke it and
were renamed to the file's existing style (`caseworkerWithNoRolesMayNot`, matching `callerWithNoRolesMayNot`).
Checkstyle reports these as `severity="warning"` yet fails the task, so a "4 warnings" line in the output is a
build failure rather than advice.

## Out of scope

- **The staff service's UI** — hiding the Publish checkbox on `new`/`edit` and splitting the library screen
  and the filter-bar nav into "Your queries" / "Query library". Separate plan in `deed-poll-admin`, which
  already has `userFromRequest(req).isAdmin` as the established pattern for presentational gating.
- **Making the in-memory mock able to produce this 403.** The mock reads a name and a subject out of the
  forwarded token and deliberately **no roles** — which is fidelity, not laziness, because IDAM tokens carry
  no roles and api resolves them from `/o/userinfo`. Its docblock lists the saved-query permission rules and
  the `/judicial/**` role gate as deliberately unmodelled, with "if a page ever does depend on one, model it
  here and say so". Whether this rule crosses that line is the admin plan's question to answer, not this
  one's — the UI gate reads the app's own session and needs no help from the mock.
- **Generalising `isAdmin` into a capability model.** `role-model-generalisation.md` is explicitly gated on
  scope question Q0.1 and on a first successful deployment, and records that at two roles `if (isAdmin)` is
  defensible. This plan adds one more call site to that count and does not prejudge it.
- **Warning an owner that others have selected a query they are about to unpublish or delete.** Already a
  recorded open question in `SavedQueryService`, and it needs a count the service does not expose.
