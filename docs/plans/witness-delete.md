# Plan: allow Administrators to delete a witness

## Overview

Adds `DELETE /caseworker/cases/{id}/witnesses/{witnessId}`, restricted to Administrators, and **closes a latent
defect while doing it**.

The business document
([`../Deed Poll Power App Permissions Summary.docx`](../Deed%20Poll%20Power%20App%20Permissions%20Summary.docx))
puts "Delete witnesses" at **✗ for Basic User, ✓ for Administrator** — the same split as deleting notes.

### The defect this closes

`WitnessService.removeWitness` already exists, and it takes **no caller**:

```java
// domain/witnesses/WitnessService.java:130
@Transactional
public void removeWitness(String deedPollId, String id) {
    witnesses.delete(findWitness(deedPollId, id));
}
```

That is character-for-character the shape `NoteService.removeNote` had before
[`completed/gate-note-deletion.md`](completed/gate-note-deletion.md) fixed it — an ungated delete reachable by
any authenticated caller. **It is currently harmless only because no controller calls it.** Adding a route
without also adding the gate would reintroduce the exact bug `#7` closed, and it would look like a deliberate
choice because the route would be new.

**So the gate is the substance of this plan and the route is the easy part.**

### Sequencing

**This gate is released.** [`completed/witness-create-and-edit.md`](completed/witness-create-and-edit.md) ran on
2026-09-12 (branch `witness-create-and-edit`, `95649b2`) and created `CaseworkerWitnessController` with its four
routes. This plan adds one method to that class, which is why the two could not run in parallel.

**Two things it left in place for this plan**, both deliberate and both confirmed in the merged code:

- `WitnessService.removeWitness` is **still the ungated two-argument form** — plan 1's decision 4 kept the domain
  untouched, so the defect described below is exactly as stated.
- `WitnessService`'s docblock now says *"`removeWitness` is still uncalled, and is the one witness operation the
  business restricts"*, and `WitnessResponse`'s says *"A witness delete is still absent"*. **Both become untrue
  when this plan lands — update them.**

### Severity, stated honestly

Not a vulnerability today: no controller reaches `removeWitness`, and nothing is deployed in any environment.
It is a correctness gap with a known right answer, and the right moment to fix it is the moment a route would
otherwise expose it.

## Decisions taken — implement these, do not revisit

1. **Hard delete — remove the row.** Decided 2026-09-12. `NoteService.removeNote` does exactly this
   (`notes.delete(...)`), the business signed that off for notes, and notes are the audit record — so a witness
   is not a stronger case for preservation. Needs no migration and no list filtering.
   **Dataverse's `statecode` / `statuscode` on `deeppoll_witness` are not the counter-argument they look like:**
   `conformance/DataverseSchemaConformanceTest.java:52-75` treats both as platform system columns present on
   every table and deliberately not ported. See the open question below all the same.
2. **Gate in the domain, not the controller.** Change the signature to
   `removeWitness(String deedPollId, String id, CurrentUser caller)` and call
   `caller.requireAnyRole(Role.DEEDPOLL_ADMIN)`. The rule then holds for every future caller — a citizen
   surface, a batch job — not just the one route that exists.
3. **Replace the signature; do not add an overload.** There must be exactly one way to delete a witness. An
   ungated two-argument form left in place is a hole someone reaches by accident — this is the reasoning
   `gate-note-deletion.md` decision 3 recorded, and it applies unchanged.
4. **Refuse first, then resolve.** `requireAnyRole` runs **before** `findWitness`, so a caller without the role
   gets the same 403 whether or not the witness exists. Resolving first lets them distinguish an existing
   witness from a missing one by status code.
5. **Do not consult authorship.** "Only the person who added it may delete it" is a third rule the business did
   not choose.
6. **Creating and editing stay open to both roles.** Do not touch what
   [`witness-create-and-edit.md`](witness-create-and-edit.md) built.

## Open question — flagged, NOT answered here

- **Is there a minimum number of witnesses a case must retain?** **No artefact states one** — checked
  `DeedPollValidation.java`, `reference/ts/deed-poll/deed-poll-validation.ts`, and
  `reference/dataverse_tables/deedpoll/business_rules.md` (zero mentions of "witness"). This plan therefore
  enforces **no minimum**: an Administrator may delete a case's last witness.
  **This is the one decision here a reviewer might want changed**, because a deed poll with no witness may not be
  a valid record. If the business says there is a minimum it is a service-level rule plus two tests, and it
  belongs in this file rather than a new one.
- **Should a deleted witness be recoverable?** Answered "no" by decision 1, on the notes precedent. Recorded
  because it is business-visible and the precedent is an argument rather than a ruling.

## Constraints

- **Java 21, `-Werror`, checkstyle on all five source sets.**
- **`export TESTCONTAINERS_RYUK_DISABLED=true`**, or container-backed tests fail in a static initialiser.
- **Controller tests boot the whole context** — a `@WebMvcTest` slice does not get `SecurityConfiguration`, so
  it cannot tell you anything about a role gate. This constraint is load-bearing for this plan specifically.
- **`CurrentUsers.required()`** is how the controller gets the caller, as in `CaseworkerNoteController:142`.
- **The operation count changes again: 17 → 18.** Plan 1 left it at seventeen. **Grep for it rather than trusting
  a list** — plan 1's own enumeration was incomplete and only the grep instruction saved it. Known sites, still
  not guaranteed complete: `CLAUDE.md`, `README.md`, `docs/RESUME.md`,
  `api/caseworker/controller/package-info.java`, and **`OpenAPIPublisherTest`, which asserts the number
  outright** (`isEqualTo(17)`) so a missed update fails the integration stage rather than going quiet.
  `OpenAPIWriteBodies` will also need the delete described if it takes a body — it does not, so check rather
  than assume.

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

`build` is compile plus checkstyle across all five source sets, plus `test`, plus `integration`
(`build.gradle:113`). **Baseline verified green from a cold `clean build` on 2026-09-12, after
[`completed/witness-create-and-edit.md`](completed/witness-create-and-edit.md) landed: `test` 1,275 across 153
suites, `integration` 5 across 3, 0 failures and 0 errors in both.** That is the number to beat — it already
includes the 33 tests plan 1 added. Re-take it from your branch point anyway if anything else has merged since.

Deliberately **not** here: test counts, baseline comparisons and the `git grep -n 'removeWitness'` assertion.
Those only hold once the plan is finished — the grep in particular is guaranteed to fail until task 1 is done —
so they live in the final task.

## Tasks

### Task 1: Gate the deletion in the domain

Change `WitnessService.removeWitness` to take `CurrentUser` and refuse first, per decisions 2–4. Replace the
signature — no overload. Update the docblock to say why the check is here rather than on the route, matching
`NoteService.removeNote`'s.

- [x] `removeWitness` takes `CurrentUser`, refuses before resolving, and no two-argument overload remains

### Task 2: The route

Add `@DeleteMapping("/{witnessId}")` to `CaseworkerWitnessController`, passing `CurrentUsers.required()`.
Return the same status the note delete returns.

- [x] `@DeleteMapping("/{witnessId}")` on `CaseworkerWitnessController`, passing `CurrentUsers.required()` —
      204 no body, as the note delete answers. `OpenAPIPublisherTest`'s count went 17 → 18 in the same change
      because leaving it would have failed the integration stage; the prose counts are still task 4's.
      `OpenAPIWriteBodies` needed nothing — the delete carries no body. `WitnessResponse`'s "a witness delete is
      still absent" and the controller's "no delete here yet" / "no caller parameter at all" docblocks updated.

### Task 3: Tests — the four that matter

1. **An Administrator deletes a witness** and it is gone from the case's list afterwards.
2. **A caseworker without the role gets 403** and the witness is still there.
3. **A caller without the role gets 403 for a witness that does not exist** — the refuse-before-resolve
   guarantee from decision 4. This is the test that actually pins the ordering; without it a later refactor can
   swap the two lines and nothing complains.
4. **A witness id belonging to another case answers 404** for an Administrator — scoping still applies to
   someone who is allowed to delete.

Plus: assert there is **no** ungated `removeWitness` overload. A compile-time fact, so a test that references
the two-argument form would fail to compile — say so in a comment rather than writing a test that cannot exist.

- [x] All four tests present, including the 403-for-a-nonexistent-witness ordering guard — as a
      `DeleteWitness` group in `CaseworkerWitnessControllerTest` whose `@BeforeEach` restubs the token as an
      Administrator, mirroring the note test's delete group, plus an unauthenticated-delete test so that group
      still covers every route. The gate is also tested where it lives: `WitnessServiceTest` gained the four
      refusals `NoteServiceTest` has (no role, no roles at all, 404 for an Administrator, refuse-before-resolve)
      and one asserting the last witness on a case can go, since the open question's answer is "no minimum".
      The no-ungated-overload assertion is recorded as a comment on the `DeleteWitness` docblock, not a test:
      the two-argument form would not compile. **1,286 tests, up from the 1,275 baseline; 0 failures, 0 errors.**

### Task 4: Reconcile the documented operation count

Per the constraint. Grep, then update.

- [ ] Every stated operation count updated — grepped, not assumed

### Task 5: Full validation

- [ ] `./gradlew clean build` green — checkstyle across all five source sets, `test` and `integration`
- [ ] **`./gradlew test --rerun`**, never bare `./gradlew test` — a bare run reports `:test UP-TO-DATE`,
      **executes nothing**, and still prints `BUILD SUCCESSFUL`
- [ ] Count from the XML, not Gradle's summary:
      `grep -ho 'tests="[0-9]*"' build/test-results/test/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END{print s}'`
      (`bc` is not installed here)
- [ ] `grep -l 'failures="[1-9]\|errors="[1-9]' build/test-results/test/*.xml` → no output
- [ ] **Baseline: 1,242 as at 2026-09-12**, plus whatever
      [`witness-create-and-edit.md`](witness-create-and-edit.md) added. Take the count from master immediately
      before starting rather than from this line
- [ ] `git grep -n 'removeWitness'` shows **no** two-argument call site anywhere
- [ ] Mark completed

> The suite is container-backed and takes about eight minutes. **One red run is not proof of a break** — a
> `--rerun` failed on 2026-09-12 and the immediate re-run was fully green. Re-run once, and read the XML.

## Deliberately not in this plan

- **Soft deletion.** Decision 1. If overruled, this plan needs a Flyway migration, a filter on every read, and a
  decision about who can see deactivated witnesses — a different plan, not an edit to this one.
- **A minimum-witness rule.** See the open question. Deliberately absent, not overlooked.
- **The UI.** `deed-poll-admin/docs/plans/06-witness-delete.md`, which depends on this merging.
- **Gating create or edit.** Both roles have them.
- **Deleting a document, or managing Supporting Document Types.** Same shape of gap, different plans — see
  [`scope-gap-questions.md`](scope-gap-questions.md).
