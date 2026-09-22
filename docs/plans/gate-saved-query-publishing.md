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

- [ ] Confirm the four rows of "The gap, measured" still read as stated, by file and line
- [ ] Confirm `CaseworkerSavedQueryControllerTest`'s `OWNER`/`OTHER`/`ADMIN` fixtures and which the create
      happy path uses
- [ ] Confirm no test asserts a non-admin *may* publish anywhere other than that one
- [ ] Record anything that has moved, in this file, before writing code

### Task 1: Add the predicate and its refusal

- [ ] `SavedQueryPermissions.publishableBy(CurrentUser)` → `caller.isAdmin()`, resolving the caller through
      `requireUserId()` first for the reason the other two do — an unidentified caller must be refused, not
      compared
- [ ] `requirePublishableBy(CurrentUser)` throwing `ForbiddenError` with a message beside `NOT_YOURS`, naming
      the rule rather than the caller: *"Only an Administrator may publish a saved query to the library"*
- [ ] `SavedQueryPermissionsTest`: an Administrator may publish; a caseworker may not; a caller with no
      subject is refused rather than compared; **holding the judicial role is not holding the admin one**
- [ ] Mark completed

### Task 2: Gate create and patch

- [ ] `createSavedQuery`: refuse before writing anything when `dto.shared()` and the caller is not an
      Administrator
- [ ] `updateSavedQuery`: refuse when the patch presents `shared` as `true` and the caller is not an
      Administrator. A patch presenting it as `false` is untouched (decision 3)
- [ ] **Change** `savesTheWholeView` to post as `ADMIN`, since its body publishes. Do not add a second
      happy path beside it
- [ ] Add over HTTP: a caseworker creating with `shared:true` → 403 **and nothing persisted**; a caseworker
      creating without it → 201 unpublished; an Administrator creating with it → 201 published; a caseworker
      patching their own to `shared:true` → 403 and the stored row unchanged; a caseworker patching their own
      to `shared:false` → 200; an Administrator patching to `shared:true` → 200
- [ ] Confirm the refusal is the service's: a `403` must be reachable without going through the controller
- [ ] Mark completed

### Task 3: Say so where the rules are already written down

- [ ] `SavedQueryService`'s class docblock lists the permission outcomes as prose — add the publish rule to
      that list, since it is the file a reader is sent to
- [ ] `SavedQueryPermissions`' class docblock says "Two predicates and nothing else". It is now three
- [ ] `SavedQuery.shared`'s field comment ("Published to the library, which makes it findable") should say
      who may set it
- [ ] Check whether `docs/` or the mock-facing notes in `deed-poll-admin` state the old rule anywhere
- [ ] Mark completed

### Task 4: Full validation

- [ ] Run every command above, and record the counts in this file rather than asserting green
- [ ] Confirm the total rose by the number of tests added and that `skipped` is still 0
- [ ] Mark completed

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
