# Findings: role-based access control

**This is findings, not yet a plan.** It exists because the intended model — three access levels
(caseworker, admin, and superuser wired for future adaptability) — cannot be designed until somebody
decides what each level may do, and no such decision exists. What follows is the complete inventory of
behaviours in this service that could be role-gated, measured from source rather than assumed, so the
business question is "which of these, for whom" against a concrete list rather than a blank sheet.

Everything below was measured on `master` at the time of writing. Where a claim is about code, it names
the file so it can be re-checked rather than believed.

## The model this is heading towards, and what is already agreed

IDAM owns identity; AM/RAS owns access; **enforcement is this service's**, not `deed-poll-admin`'s. That
last point is not a preference — `deed-poll-admin`'s rules already state that `Caller` is
`{ accessToken }` and nothing else, "because api derives the audit author and the admin status from the
token", so a caller asserting its own privileges would be asserting an identity this service never
verified. Admin's `isAdmin` decides which control to grey out. This service decides what actually happens.

Two consequences worth stating before the inventory:

- **RAS is not a decision point.** It stores and validates assignments and answers "what does this actor
  hold right now". It does not answer "may they do this". CCD queries RAS and then enforces; Work
  Allocation queries RAS and enforces against its own `task_roles` table, "entirely independent of CCD
  case-level access grants". Whichever source we use, the rules below are ours to write.
- **So the permission matrix is required whether or not we ever adopt RAS.** Adopting RAS without it buys
  three role names and nothing to enforce with them.

## What exists today

### One privileged role, and it is an IDAM role

`Role.java:22` defines a single member, `DEEDPOLL_ADMIN("caseworker-deedpoll-admin")`. Admin's
`roles.ts:8` mirrors it and records its origin: it "ports the Dynamics security role Deed Poll Admin
User". Roles are read from IDAM's `/o/userinfo` — not from the ID token, which carried none.

Worth naming for the record: a `caseworker-*` role minted in IDAM is the pre-AM access model, not the
identity half of the AM model. It is the thing AM was built to replace. That does not make it urgent —
CCD still synthesises `idam:`-prefixed pseudo-assignments from IDAM roles on every access check, the
behaviour defaults on, and no deployed environment disables it — but it does mean this role is a
migration candidate rather than a foundation to build on.

### The role list already exists; the boolean is derived

`CurrentUser` is `record CurrentUser(List<Role> roles, boolean isAdmin, String name)`, and
`currentUser(...)` derives `isAdmin` from the list — deliberately: "a caller that could assert it
independently of its roles is a caller that can be wrong about it." Admin does the same.

**So the seam a richer model needs is already there.** What is boolean is everything downstream:
`PresentationContext(boolean isAdmin, boolean isNew)`, `WriteContext(boolean isAdmin, String userName)`,
`EditContext(boolean isAdmin)`, and the branches inside `FormPresentation`. Roughly 33 non-test call
sites across the two repositories, 56 including tests. Contained — a retrofit, not a rewrite — but
threaded through view models, which is the reason it gets more expensive the longer it waits.

### There is an enforcement primitive, and nothing uses it

`CurrentUser.requireAnyRole(Role...)` exists, throws `ForbiddenError`, and is ported from a
`requireRoles` Express middleware. It has four test call sites and **zero production call sites.**

Two things follow. First, the mechanism for gating an operation is already built and tested, so gating
operations is cheap once we know which. Second, it carries a trap that its own docblock is explicit
about: **"requiring nothing is open rather than closed"** — `requireAnyRole()` with no arguments permits
everyone. That is a deliberate port of the original behaviour, so it should not be quietly changed, but
it means a permission table that resolves to an empty role list for some operation would silently grant
it to everybody rather than refusing. Any table-driven design needs a test for that case.

### Exactly two behaviours are role-dependent today

Both are in `FormPresentation`, both concern the Privacy field, and both were ported from Dynamics:

| Rule | Admin | Non-admin |
|---|---|---|
| `privacyEditable(type, ctx)` | always editable | editable only on a **new** Deed of Relinquishment; otherwise locked |
| `defaultPrivacy(type, current, ctx)` on create | `MinorTransgender` → `Private`, else unchanged | fixed per type: Adult/AdultTG/Minor/DeedOfRelinquishment → `Normal`, `MinorTransgender` → `Private` |

Note the shape: the non-admin path is the *more* deterministic one. Admin does not get "more access" so
much as discretion over a field the process otherwise sets for you. That is a useful precedent for how
the business may think about admin generally, and worth testing with them rather than assuming.

## The inventory: everything that could be role-gated

### A. Operations — 13 caseworker endpoints, none of them gated

Every one of these is reachable by any authenticated caller holding any role, or none.

| # | Operation | Endpoint | Notes for the business |
|---|---|---|---|
| 1 | List cases | `GET /caseworker/cases` | Five saved filters: all, open, received, enroled, returned. No per-user scoping — see finding 3 |
| 2 | Read a case | `GET /caseworker/cases/{id}` | Witnesses and documents arrive nested inside this response |
| 3 | Read field rules | `GET /caseworker/cases/field-rules` | Reference data; low sensitivity |
| 4 | Read document types | `GET /caseworker/document-types` | Reference data; low sensitivity |
| 5 | **Create a case** | `POST /caseworker/cases` | |
| 6 | **Update a case** | `PATCH /caseworker/cases/{id}` | 67 writable fields — see section B, this is the coarse-grained one |
| 7 | List notes | `GET /caseworker/cases/{id}/notes` | |
| 8 | **Add a note** | `POST /caseworker/cases/{id}/notes` | Author comes from the token, not the request body |
| 9 | Read a note | `GET .../notes/{noteId}` | |
| 10 | **Edit a note** | `PATCH .../notes/{noteId}` | Any caller may edit any author's note — see finding 2 |
| 11 | **Delete a note** | `DELETE .../notes/{noteId}` | Any caller may delete any author's note — see finding 2 |
| 12 | **Upload a document** | `POST .../documents` | |
| 13 | Download a document | `GET .../documents/{documentId}/file` | Serves bytes that may have been uploaded by a member of the public |

There is no document delete and no case delete. Absence of a destructive operation is worth confirming
as intended rather than recording as a gap.

### B. Fields — 67 writable case fields behind one endpoint

`DeedPollFields.java` registers 67 writers. Operation 6 is therefore not one permission but potentially
many, and the four that most plausibly warrant their own answer are:

| Field | Why it may need its own rule |
|---|---|
| `privacy` | Already the one admin-gated field. Also see finding 1 — the protection it implies does not exist |
| `state` | **Directly writable** (`DeedPollFields.java:347`) *and* derived from the two dates below |
| `dateEnroled` | Setting it moves the case to Enroled via the `CaseState` cascade |
| `dateReturned` | Setting it moves the case to Returned; setting both is a validation error |

**This is the finding most likely to shape the design.** Because state is derived from dates rather than
changed by a transition operation, "who may enrol a case" is not a permission on an action — it is a
permission on writing a field. A matrix written purely in terms of operations cannot express it, and a
matrix written purely in terms of fields cannot express note deletion. The model needs both, and that is
a design consequence of how the domain was ported, not a choice we get to make.

### C. What is not in the inventory, and why

- **Case-level segregation.** No requirement stated, and nothing in the code supports it today (finding
  3). Deliberately excluded — including it would pull in RAS case roles, a `jurisdiction`, a
  CCD-shaped `caseId`, new Drools rules and a case-allocation feature that does not exist in the product.
- **Citizen-facing access.** `api/citizen/controller/` holds only a `package-info.java`. Out of scope
  until there is something in it.
- **S2S / machine callers.** Inbound S2S is deliberately switched off:
  `idam.s2s-authorised.services` is unset, per `ServiceAuthConfiguration`. Every caller today is a human
  via `deed-poll-admin`. If that changes, machine callers are a fourth category and not a role.

## Findings that change the design

### 1. `Privacy.Private` grants no protection

The enum has two members, `Normal` and `Private`. Across the whole service the value is read once for
behaviour — `ListView.java:56`, `record.getPrivacy() == Privacy.Private` — where it sets a display flag.
It does not filter list results, restrict a read, or gate a field.

So there is a field called Privacy, which non-admins are deliberately prevented from editing, which
implies confidentiality and delivers none. That is either correct (the marker is for handling, and the
Dynamics original did no more) or it is the case-level access requirement arriving under another name.
**This is the single most important question in this document**, because if `Private` is supposed to
restrict who can see a case, the answer to "do we need case-level access control" changes from no to yes,
and with it the whole cost of the work.

### 2. Note edit and delete are ungated, and notes are the audit trail

`CaseworkerNoteController.delete` calls `notes.removeNote(id, noteId)` with no role check and no
authorship check. Same for `PATCH`. Authorship *is* recorded — `NoteAuthor` takes the name from the
request context and `createdBy` is stripped from any request that tries to supply it — so the data exists
to enforce "only your own", it is simply not enforced.

Three plausible answers, and they are genuinely different rules: anyone may delete any note; only the
author may; only an admin may. Deleting a record of what happened on a case is the kind of operation that
usually attracts the strictest of the three.

### 3. Every caseworker can see every case, and nothing suggests otherwise

The list is filtered only by saved-query keys over case state (`ListFilters.java:37-42`). There is no
owner, no assignee, no team and no location on the read path. Combined with the absence of any stated
requirement for segregation, the working assumption is that all deed poll cases are visible to all deed
poll caseworkers — but it should be confirmed rather than inferred from an absence, because it is the
assumption that makes the cheap version of this work viable.

### 4. Superuser has no defined powers, so it should not yet exist as a role

Adding a third enum member now produces a role that silently does nothing, which is worse than not having
it: someone will later grant it to a person and assume it took effect. The adaptability being asked for
comes from generalising the model in section "the role list already exists" — once permissions are
expressed as data rather than as `if (isAdmin)`, adding superuser is an enum member plus rows.

## Questions for the business

Ordered by how much the answer changes the work.

1. **Is `Private` supposed to restrict who can see a case, or is it a handling marker?** If it restricts,
   we need case-level access control and this becomes a much larger, multi-team piece of work.
2. **Who may delete or amend a note** — anyone, the author only, or an admin only?
3. **Who may move a case's state**, i.e. write `dateEnroled` and `dateReturned`?
4. **Who may create a case?** Both levels, or admin only?
5. **Who may upload and download documents?** Download in particular serves material a member of the
   public supplied.
6. **Does admin mean "can do more" or "can override"?** The only existing rule is the second kind —
   discretion over a field the process otherwise decides. Worth knowing which they mean before the
   pattern is set.
7. **What is superuser actually for** — a break-glass account, a service-management role, or a third tier
   of business permission? It has been asked for as future-proofing, which is a reason to design for it,
   not a definition of it.

For each of 2–5, the useful form of the answer is one of: both levels, admin only, or author only.

## What can be done without waiting for answers

Sequencing, with the dependency stated rather than implied.

1. **Nothing that hard-codes a rule.** With one role and two rules, any structure invented now will be
   wrong in a way that is expensive to unpick later.
2. **Generalising the derived boolean is safe and independent.** Replacing `isAdmin` with the role model
   through `PresentationContext`, `WriteContext` and `EditContext` does not depend on any answer above,
   preserves current behaviour exactly, and is the change that gets more expensive with time. It is also
   what makes the source of roles swappable: `IdamClaims.java` here and `idam-claims.ts` in admin are the
   only two places that map claims to roles, so moving from IDAM roles to RAS later is a change in one
   file per repository.
3. **The RAS S2S allowlist entry is worth raising early if we are going there at all.**
   `ROLE_ASSIGNMENT_S2S_AUTHORISED_SERVICES` in `am-role-assignment-service`'s `application.yaml:127`
   lists 21 services and `deed_poll_api` is not among them. Per AM's own documentation that list governs
   whether a caller may reach RAS's endpoints **at all**, queries included. It is a one-line PR to a repo
   another team owns, with a review lead time we do not control, and it commits us to nothing.

Note that 3 gets us as far as *reading* assignments. Creating them additionally needs a stage-1 Drools
rule naming our service — the rule files are per-jurisdiction (`ccd/`, `civil/`, `iac/`, …) and none names
us — and role definitions in a `roleconfig` JSON file, of which there are twelve and none is ours. Reading
is a config change; writing is an onboarding.

## Sources

Measured from these, on `master`:

| Claim | Where |
|---|---|
| Single role, its IDAM name and Dynamics origin | `domain/auth/Role.java:22`, `../deed-poll-admin/libs/deed-poll/src/auth/roles.ts:8` |
| Role list exists, boolean derived | `domain/auth/CurrentUser.java:22,49-56` |
| Enforcement primitive, unused, open-on-empty | `domain/auth/CurrentUser.java:67-74`, `domain/ForbiddenError.java:14` |
| The two Privacy rules | `domain/deedpoll/FormPresentation.java:62-101` |
| `Private` is display-only | `domain/deedpoll/ListView.java:56` |
| 13 endpoints | the four `api/caseworker/controller/Caseworker*Controller.java` |
| Note delete ungated | `api/caseworker/controller/CaseworkerNoteController.java:134-138` |
| Authorship from context | `domain/notes/NoteAuthor.java`, `domain/notes/CreateNoteInput.java:7` |
| 67 writable fields; `state` directly writable | `domain/deedpoll/DeedPollFields.java:347` and the `writers.put` registrations |
| State derived from dates | `domain/deedpoll/CaseState.java:62-66` |
| List filters are state-only | `domain/deedpoll/ListFilters.java:37-42` |
| Inbound S2S off | `config/ServiceAuthConfiguration.java:25` |
| RAS allowlist, Drools rules, roleconfig files | `../apps/am/am-role-assignment-service/src/main/resources/` and `../apps/am/docs/explanation/overview.md` |
| WA enforces locally against RAS assignments | `../apps/wa/docs/explanation/access-control.md:65-66` |
