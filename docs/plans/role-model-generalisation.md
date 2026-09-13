# Plan: generalise the derived admin boolean into a role/capability model

## Overview

**A behaviour-preserving refactor, and nothing else.** Replace `boolean isAdmin`, threaded through three
context records, with named capabilities resolved from roles. Every response byte-identical before and after,
and the suite is what proves it.

> **Do not start this plan yet, and it may not be needed at all.** It is gated on two things:
>
> 1. **Scope question Q0.1** in [`scope-gap-questions.md`](scope-gap-questions.md) — whether the service needs
>    Power App parity. That answer decides whether the capability table will hold two rows or eight, and
>    therefore whether the indirection earns its keep. At two rows, `if (isAdmin)` is defensible and most of
>    this plan is unnecessary.
> 2. **Preferably a first successful deployment.** Nothing has ever been deployed. When the first deploy
>    fails it should be unambiguously a deployment problem, not "did that 33-site refactor break something".
>
> **The one business rule we are currently getting wrong has been split out** into
> [`gate-note-deletion.md`](completed/gate-note-deletion.md), which depends on none of the above and can go
> independently. That is the thing to do first.

The business has now supplied the permission matrix as
[`Deed Poll Power App Permissions Summary.docx`](../Deed%20Poll%20Power%20App%20Permissions%20Summary.docx),
describing the Power App this service is a port of. Two roles: **Basic User** and **Administrator**.
Read [`role-based-access-findings.md`](role-based-access-findings.md) for the survey this plan acts on.

### The headline, before any code

Of the eleven capability rows in the business document, **exactly one is both implementable today and
wrong**: deleting a note. Everything else is either already correct or has no operation to attach to.

| Business rule | Our position |
|---|---|
| Create and update records — both roles | Already open to both. **No change** |
| Add and edit notes — both roles | Already open to both. **No change** |
| Add and edit documents — both roles | Already open to both. **No change** |
| Add and edit witnesses — both roles | Already open to both, via the case patch. **No change** |
| Progress through the business process — both roles | Already open to both. **No change** |
| **Delete notes — Administrator only** | **Open to everybody.** Fixed by [`gate-note-deletion.md`](completed/gate-note-deletion.md), not here |
| Privacy read-only for Basic User | Resolved below — no change needed |
| Delete documents — Administrator only | **No document delete operation exists** |
| Delete witnesses — Administrator only | **No witness write operations exist** |
| Manage Supporting Document Types — Administrator only | **Read-only endpoint only** |
| Deactivate / reactivate records — Administrator / both | **No deactivation concept exists at all** |

The last four are **missing features, not missing permissions**, and are out of scope here — see the closing
section. `package-info.java` records that the surface deliberately carries "no deletes, no witness writes,
no document-type writes… because they have no consumer".

## Decisions taken — implement these, do not revisit

1. **Privacy needs no change.** The business document says the Privacy field is read-only for Basic User,
   unconditionally. The decision taken is narrower and matches the code: *only an admin may change the
   setting after an application has been created.* `privacyEditable` already returns `false` for a non-admin
   on an existing record, and `true` for an admin always. **Do not touch the rule, and do not "fix" it
   towards the document's wording** — the document is a summary and the code is the port.
   - One inference, stated so it can be corrected: the creation-time carve-out for a **new Deed of
     Relinquishment** survives, because the decision speaks to what happens *after* creation. It is
     deliberately tested (`FormPresentationTest`, `DeedPollServiceTest:437`). If it should also go, that is
     a one-line change plus two test updates — but it is not assumed here.
2. **Superuser is dropped.** Not deferred, not designed for. The business document has two roles and no
   third tier. Remove any allowance for it.
3. **Capabilities, not role checks, at the point of use.** See the challenge section — the justification has
   changed and is weaker than when this plan was first drafted.
4. **`isAdmin` is deleted, not deprecated.** Two sources of truth for one question is how a claim goes stale
   in one of them.
5. **The role→capability map is a separate table.** It mirrors the business document's own summary table, so
   a reviewer can check one against the other. That is its main justification now.
6. **The wire is untouched.** No controller signature, DTO, OpenAPI path or response body changes at all.
   If `openapi.json` moves, something has gone wrong.

## The decision most open to challenge

**Whether capability indirection is still worth it, now that superuser is gone.**

When this plan was drafted, the case rested partly on making a third role additive. That reason is gone. With
two roles and no third coming, `if (isAdmin)` is defensible and YAGNI and KISS both point at it.

What survives, and why I still recommend capabilities:

- **The matrix is checkable.** A `RoleCapabilities` table that mirrors the business document row-for-row can
  be reviewed against their artefact. Six `if (isAdmin)` branches across six files cannot.
- **The rules are not all "admin or not".** Privacy is `admin OR (new AND DeedOfRelinquishment)`. Naming it
  `OVERRIDE_PRIVACY` makes the call site state the question rather than the answer.
- **The business splits edit from delete per entity.** Those are distinct permissions that presently happen
  to align with admin; a named capability each costs nothing and stops the alignment being assumed.

**If a reviewer prefers a boolean, this plan is unnecessary** — the note-delete gate already stands alone in
[`gate-note-deletion.md`](completed/gate-note-deletion.md), and nothing else needs a capability.** That is a legitimate outcome and cheaper than what follows. Decide before starting, not
midway.

The second, smaller open decision: **`CurrentUser.requireAnyRole(Role...)` is replaced with a fail-closed
capability check.** It exists, is tested, is called from nowhere in production, and its docblock is explicit
that "requiring nothing is open rather than closed" — an empty list permits everybody, faithfully ported from
the `requireRoles` middleware. A table-driven design cannot distinguish "needs no capability" from "we forgot
to configure this", so this plan replaces it with `require(Capability)` taking exactly one. If a reviewer
prefers keeping the ported semantics, keep them and add the open-on-empty test instead.

## Constraints

- **The baseline is green and must stay green.** `./gradlew test` = **1,234 tests, 0 failures, 0 errors,
  0 skipped** across 147 suites, verified before this plan was written. A task that *starts* red means the
  working tree is not what this plan assumed.
- **Test count must not fall.** It rises — tasks 1 and 6 add tests. A drop means a test was deleted rather than
  migrated, which is how a behavioural change hides inside a refactor.
- **`TESTCONTAINERS_RYUK_DISABLED=true` must be set**, or container-backed tests fail in a static
  initialiser on an unreachable Ryuk. Confirm rather than assume.
- **Do not change `FormPresentation`'s rules while moving them.** Move the *condition* from `ctx.isAdmin()`
  to `ctx.can(OVERRIDE_PRIVACY)`; change nothing inside the per-type switch, the `isNew` ladder or the
  `Unknown`/null fallbacks. Its existing assertions must pass unchanged.
- **Verify test counts from the result XML, not Gradle's summary.** `./gradlew test` reporting
  "5 actionable tasks: 5 up-to-date" means it did not run — that happened while establishing this baseline
  and briefly suggested the suite was 17 tests rather than 1,234.

## Call sites, measured

| Symbol | `src/main/java` | tests |
|---|---|---|
| `WriteContext` | 20 | 33 |
| `isAdmin` | 15 | 50 |
| `PresentationContext` | 8 | 16 |
| `EditContext` | 3 | 5 |
| `Role.` | 3 | 22 |
| `requireAnyRole` | 2 | 4 |

Seven files in `src/main/java` mention `isAdmin`: `domain/auth/CurrentUser.java`,
`domain/auth/IdamClaims.java`, `domain/deedpoll/DeedPollService.java`, `domain/deedpoll/EditView.java`,
`domain/deedpoll/FormPresentation.java`, `domain/deedpoll/PresentationContext.java`,
`domain/deedpoll/WriteContext.java`. The test numbers are the bulk of the work and most of it is mechanical.

---

# The refactor (no behaviour change)

Each task ends green. Run its validation before starting the next.

### 1. Add the capability vocabulary and the matrix

- `domain/auth/Capability.java` — enum, single member `OVERRIDE_PRIVACY`. **One member, because one rule
  exists.** `DELETE_NOTE` arrives with the operation it gates — see
  [`gate-note-deletion.md`](completed/gate-note-deletion.md), which if it has already landed will have a
  `requireAnyRole` check for this refactor to convert. Do not add members for the four capabilities that
  have no operation.
- `domain/auth/RoleCapabilities.java` — `Map<Role, Set<Capability>>`, single entry
  `DEEDPOLL_ADMIN → EnumSet.of(OVERRIDE_PRIVACY)`, plus a resolver over `List<Role>` returning the union.
  An empty or unknown role list resolves to the empty set. Comment it as the port of the business
  document's summary table, naming the file.
- Tests: the mapping resolves; an empty role list yields nothing; the set is immutable; two roles union.

Nothing consumes these yet, so behaviour cannot change.

**Validation:** `./gradlew test` — green, count ≥ 1,234.

### 2. Resolve capabilities on `CurrentUser`, and remove the boolean

- `record CurrentUser(List<Role> roles, Set<Capability> capabilities, String name)`. Capabilities derived in
  the factory from `RoleCapabilities`, exactly as `isAdmin` was — **keep the existing docblock reasoning
  verbatim**: a caller that could assert it independently of its roles is a caller that can be wrong about it.
- Add `boolean can(Capability)`.
- Replace `requireAnyRole(Role...)` with `require(Capability)` throwing `ForbiddenError` when absent —
  subject to the open decision above. Update `ForbiddenError`'s docblock, which names `requireAnyRole`.
- `writeContext()` and the feeds for the other two contexts pass capabilities through.

**Validation:** `./gradlew test` — green. Expect compilation failures in the three context records; that is
the task boundary, so finish the wiring before running.

### 3. Migrate `PresentationContext` and `FormPresentation`

- `PresentationContext(Set<Capability> capabilities, boolean isNew)`, with `can(Capability)` delegating.
- `defaultPrivacy` and `privacyEditable`: `ctx.isAdmin()` → `ctx.can(OVERRIDE_PRIVACY)`. Nothing else.
- Update the 16 test occurrences to construct capability sets.

**Validation:** `./gradlew test` — green, and the `FormPresentation` assertions unchanged. If an assertion had
to change, the rules moved when they should not have.

### 4. Migrate `WriteContext` and `DeedPollService`

The largest surface: 20 main, 33 test.

- `WriteContext(Set<Capability> capabilities, String userName)`.
- `NoteAuthor` is untouched — a note write has no capability-dependent field, as its docblock says.

**Validation:** `./gradlew test` — green.

### 5. Migrate `EditContext` and `EditView`

- `EditContext(Set<Capability> capabilities)`, passed into `PresentationContext` as today.

**Validation:** `./gradlew test` — green.

### 6. Prove the boolean is gone

- `git grep -n 'isAdmin' -- src/` returns **nothing** — not in main, not in tests, not in a comment
  describing something that no longer exists.
- Add one test: a caller holding no roles has no capabilities and is refused `OVERRIDE_PRIVACY`. The
  fail-closed case as a test, not as a property of a constructor.

**Validation:** `./gradlew test` and the grep above.

### 7. Full validation

```bash
./gradlew clean build
./gradlew test
git grep -n 'isAdmin' -- src/                 # no output
git status --short -- src/main/resources      # spec artefacts unchanged
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=e=s=0
for p in glob.glob('build/test-results/test/*.xml'):
    r=ET.parse(p).getroot()
    t+=int(r.get('tests',0)); f+=int(r.get('failures',0)); e+=int(r.get('errors',0)); s+=int(r.get('skipped',0))
print(t,f,e,s)"
```

---

---

## Deliberately not in this plan

- **The four missing features.** Deactivate/reactivate records, delete a document, delete a witness, and
  manage Supporting Document Types are all Administrator capabilities in the business document with **no
  operation in this service to gate**. Deactivation is the largest: it is a Dynamics `statecode` lifecycle,
  entirely absent, and distinct from `Received`/`Enroled`/`Returned`. Each needs building before it needs
  permissioning, and "Edit deactivated Enrolled or Returned records" depends on deactivation existing first.
- **Case-level access control.** Not a requirement. The business document has no row about who may *see* a
  case, and Privacy is a handling marker — read once, for a display flag.
- **RAS / AM.** Where roles come from is a separate question. Afterwards it is a change in `IdamClaims`
  alone.
- **`deed-poll-admin`'s mirror of this refactor.** Its own derived `isAdmin`, 16 non-test sites. Genuinely
  independent — `Caller` is `{ accessToken }`, so neither repo sends the other roles or capabilities. Do this
  one first: this is where authorisation is decided, admin's copy only greys out controls.
