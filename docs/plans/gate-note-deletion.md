# Plan: restrict note deletion to Administrators

## Overview

**One rule, one route, deliberately standalone.** Today
`DELETE /caseworker/cases/{id}/notes/{noteId}` calls `notes.removeNote(id, noteId)` with **no role check and
no authorship check** — any authenticated caller can delete any author's note, and notes are the audit record
of what happened on a case.

The business is explicit
([`Deed Poll Power App Permissions Summary.docx`](../Deed%20Poll%20Power%20App%20Permissions%20Summary.docx)):
Basic User "cannot delete notes"; Administrator can "delete notes". Of the eleven capability rows in that
document, **this is the only one that is both implementable today and currently wrong.**

### Why this is its own plan

It was originally phase 2 of [`role-model-generalisation.md`](role-model-generalisation.md) and has been
split out because **it depends on nothing**. It does not need the capability refactor, it does not need any
answer in [`scope-gap-questions.md`](scope-gap-questions.md), and it can land while both of those are still
open. The refactor, by contrast, is waiting on a scope decision that determines whether capability
indirection earns its keep.

### Severity, stated honestly

Not urgent, and not a vulnerability. The route requires a valid IDAM access token, so this is an
authenticated member of staff doing something the business says they should not be able to do — not anything
externally reachable. **Nothing is deployed in any environment**, so it currently cannot be exercised at all.
It is a correctness gap with a known right answer.

## Decisions taken — implement these, do not revisit

1. **Note *editing* stays open to both roles.** The business splits edit from delete: Basic Users "create,
   view and edit notes" and only deletion is restricted. **Do not gate `PATCH`.** Getting this wrong in the
   tidy-minded direction would remove a capability caseworkers are meant to have.
2. **Enforce in the domain, not the controller.** `NoteService.removeNote` refuses; the controller just calls
   it. Consistent with `privacyEditable` living in `FormPresentation` rather than in a controller, and it
   means the rule holds for any future caller — a citizen surface, a batch job — rather than only for the one
   HTTP route that exists today.
3. **Replace the ungated signature; do not add an overload.** `createNote` and `updateNote` each have a
   2-arg and a 3-arg form, and for *authorship* that is harmless — the short form just means "no author". For
   *authorisation* an ungated overload is a bypass: any caller who reaches `removeNote(id, noteId)` skips the
   check entirely. There must be exactly one way to delete a note.
4. **Use the existing `requireAnyRole`.** It exists in `CurrentUser`, is tested, and is called from nowhere in
   production — this becomes its first production caller, which is what it was ported for. Passing an explicit
   role avoids its open-on-empty behaviour, which only bites with zero arguments.
5. **No new capability enum.** That belongs to the refactor. When the refactor lands, this one check swaps
   from `requireAnyRole(DEEDPOLL_ADMIN)` to `require(DELETE_NOTE)` and nothing else about this change moves.
6. **No authorship rule.** "Only the author may delete" is a third possibility the business did not choose —
   they said Administrator. Do not add an author check as well.

## Constraints

- **Baseline: `./gradlew test` = 1,234 tests, 0 failures, 0 errors, 0 skipped** across 147 suites, verified
  2026-09-11. A task that starts red means the working tree is not what this plan assumed.
- **Test count must rise.** This adds tests and removes none.
- **`TESTCONTAINERS_RYUK_DISABLED=true` must be set**, or container-backed tests fail in a static initialiser
  on an unreachable Ryuk.
- **Verify counts from the result XML, not Gradle's summary** — `./gradlew test` reporting "up-to-date" means
  it did not run.
- **The not-found behaviour must not change.** Deleting an already-deleted note currently 404s rather than
  answering 204 again, because the domain reports absence by throwing. That is documented as deliberate and
  consistent with the reads. It must still 404 for an Administrator afterwards.
- **`403`, not `404`.** `ForbiddenError` already maps to 403. Do not disguise a refusal as a not-found; a
  caseworker is entitled to know the note exists and that they may not remove it.

## Tasks

### Task 1: Refuse the deletion in the domain

- [x] Change `NoteService.removeNote(String deedPollId, String id)` to take the caller, and have it call `requireAnyRole(Role.DEEDPOLL_ADMIN)` **before** it looks the note up
- [x] **Delete the old two-argument signature** rather than keeping it alongside. `createNote` and `updateNote` each have a short and a long form, and for *authorship* that is harmless; for *authorisation* an ungated overload is a bypass. There must be exactly one way to delete a note
- [x] Order matters: refuse first, then resolve. Looking the note up first and refusing second would let a non-admin distinguish an existing note from a non-existent one by the status code
- [x] Expect the existing `removeNote` tests to fail to compile on the changed signature. That is this task's boundary; they are fixed in Task 3 — `./gradlew compileJava` currently reports exactly one error, the controller call site, which is Task 2
- [x] Mark completed

### Task 2: Update the controller

- [x] `CaseworkerNoteController.delete` passes `CurrentUsers.required()` through, exactly as `createNote` and `updateNote` pass `CurrentUsers.required().noteAuthor()`
- [x] Update the method's docblock: it explains the double-delete 404 and says nothing about who may delete
- [x] **Leave the route, verb and success status alone** — still `DELETE`, still 204
- [x] Mark completed

### Task 3: Tests — the four that matter

- [ ] **An Administrator deletes successfully** — 204, and the note is gone
- [ ] **A caller without the role is refused** — `ForbiddenError` to 403, **and the note still exists afterwards**. Asserting the refusal alone would pass against an implementation that threw *after* deleting; the surviving note is the assertion that matters
- [ ] **A caller with no roles at all is refused** — the fail-closed case. `currentUser([])` is the state a bug in authentication would produce, and it must not delete anything
- [ ] **An Administrator deleting a non-existent note still gets 404** — proving the new check did not swallow or reorder the existing not-found behaviour
- [ ] Add a controller-level test, not only a service one. Per this repo's convention a controller test must boot the whole context, because a `@WebMvcTest` slice does not get `SecurityConfiguration`
- [ ] Fix the `removeNote` call sites broken by Task 1
- [ ] Validate: `./gradlew test` green, count strictly greater than 1,234
- [ ] Mark completed

### Task 4: Reconcile the stale package documentation

`api/caseworker/controller/package-info.java` predates the note delete — comment dated 2026-09-07, the delete
added 2026-09-08 in `#4` — and is now wrong three ways.

- [ ] It says the surface has "no deletes". It has one
- [ ] It lists `CaseworkerNoteController` as "list, add, read and amend a case's notes", omitting delete
- [ ] It asserts "that is twelve routes" where there are thirteen; `#4`'s own title calls it "the thirteenth caseworker operation"
- [ ] **State the route count as a property of the package rather than a total** — the current wording goes stale every time a route is added, which is how it got wrong in the first place
- [ ] Check `docs/RESUME.md` and this repo's `CLAUDE.md` agree with whatever route count they quote
- [ ] Mark completed

### Task 5: Full validation

- [ ] `./gradlew clean build` green
- [ ] `./gradlew test` green. **Confirm the totals from the result files, not Gradle's summary** — a run reporting "N actionable tasks: N up-to-date" did not execute anything, and briefly suggested this suite was 17 tests rather than 1,234. Count with:
      `grep -ho 'tests="[0-9]*"' build/test-results/test/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END{print s}'`
      (`bc` is not installed in this devcontainer — do not reach for it)
      and check failures are zero with:
      `grep -l 'failures="[1-9]' build/test-results/test/*.xml` (no output expected)
- [ ] `git status --short -- src/main/resources` clean: the OpenAPI spec must not move. This changes who may call an existing route, not its shape
- [ ] Mark completed

## Deliberately not in this plan

- **The capability model.** See [`role-model-generalisation.md`](role-model-generalisation.md). This change is
  written so that plan can later swap one line.
- **Gating anything else.** Create, update, upload, download and the reads are all open to both roles per the
  business document, and are already correct.
- **The four capabilities with no operation** — delete a document, delete a witness, manage Supporting
  Document Types, deactivate a record. All Administrator-only in the business document, none of them built.
  See [`scope-gap-questions.md`](scope-gap-questions.md).
- **`deed-poll-admin`'s side.** The staff app offers note deletion as a second submit button on the note edit
  form. Once this lands, a non-admin pressing it will get a 403 rather than a disabled control. **That is a
  worse experience than hiding the button, and it is the same class of problem the privacy field already
  solved** — the lock was UI-only until 2026-08-10 and had to be enforced server-side too. Here the server is
  right and the UI needs to catch up: a follow-on in that repo should hide or disable the control for a
  caseworker. Server-side enforcement first is the correct order; the UI gap is cosmetic and must not delay it.
