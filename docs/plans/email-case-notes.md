# Plan: a case note when an email is sent, and when one terminally fails

## Overview

**A caseworker reading a case's timeline cannot see that an email went out.** notify-5 built a per-case
correspondence history and notify-2 renders it as an Email tab, so the *detail* exists — but the Notes grid,
which is the only timeline this domain has, says nothing. This plan writes one note per email event.

The split, agreed with the product owner 2026-09-23: **the note is a timeline marker, the Email tab is the
record.** The note says which email and points at the tab. It does not repeat content, recipient,
personalisation or the provider's reason.

**Scope is this repo only.** No `deed-poll-admin` change is needed — see decision 3.

## Why the Notes grid at all

`ReferralNotes`' class docblock already settled this, and the argument transfers unchanged:

> *"There is no timeline concept in this domain — only the human-authored Notes grid — and the referral has to
> be visible where caseworkers already look, so MVP reuses the grid rather than inventing a second history
> nobody reads."*

And **business testing rejected writing fewer notes**: caseworkers *"saw a referral leave and come back with
nothing in between"*, because a row states only where something is *now*. A sent email is exactly that shape —
the outbox row is current state, the grid is history.

## Decisions — implement these, do not revisit

### 1. Compose from `EmailTemplate`, never per template

Two functions, no branching on which email it is:

```java
EmailNotes.sent(template, storedName)          → "<Name> email sent. See the Emails tab for its delivery status."
EmailNotes.couldNotBeSent(template, storedName) → "<Name> email could not be sent. See the Emails tab for the reason."
```

`EmailTemplate.displayName()` is documented as *"the name a caseworker sees for this email type"*, which is
exactly the varying part and the only one. **There are two templates today and five planned** — ⑤ arrives with
notify-6, ①② with notify-4 — so a design that switched on the template would need editing three more times and
would be a case somebody forgets. This one gets them for free.

### 2. "Sent", with the pointer disclaiming delivery

The note is written at **enqueue** (decision 4), so at the instant it is written the email has been accepted by
this service and not yet by Notify. `"sent"` is nonetheless the right word, and the second sentence is what makes
it honest:

- **No hook could claim delivery.** `state = Sent` means *Notify accepted it*, not that the applicant received
  it. Delivery status needs Notify's callbacks, which are not built — notify-1 stores `providerNotificationId`
  precisely so they are additive later.
- **The codebase has already ruled on the adjacent question.** `CaseEmailService`'s blocking message carries
  *"**\"Requested\", not \"delivered\", deliberately**"*. That forbids claiming delivery; it does not forbid
  "sent".
- *"See the Emails tab for its delivery status"* tells the reader **this note is not the record of delivery**,
  which inoculates the verb better than swapping it — and keeps the note correct when callbacks land.

### 3. The pointer is prose, not a hyperlink

`deed-poll-admin`'s `detail.njk` passes the note body to `govukTable` as **`{ text: n.note }`**, so it is
escaped: markup in a note renders as literal characters. Three ways to change that and two are wrong:

- Render note text as `html` — **no.** Notes are caseworker-authored free text; that is an XSS hole.
- Have admin detect email notes and decorate them — requires parsing the prose, which `ReferralNotes`
  explicitly forbids: *"Nothing parses it… so it may be reworded freely."*
- Add a structured field to the note row so admin can link without parsing — possible, but a new column plus a
  contract change, and it breaks the "a note is prose" model six existing note types rely on.

So the note names the Emails tab in words. **This is what makes the plan api-only.**

### 4. The sent note is written at enqueue, in `CaseEmailService.send`'s transaction

Not in the drain, and the reason is a duplicate-email hazard rather than taste. `NotificationOutbox.drain(limit)`
runs **the whole batch** — up to `DEFAULT_LIMIT` 5 rows — inside one `@Transactional(REQUIRES_NEW)`. A note
write that failed there would roll back state changes for rows **Notify had already accepted**, and the next
drain would send them again.

`send` already runs in a request transaction with the caseworker's `CurrentUser`, and the controller drains
*after* it commits. So `ReferralService`'s invariant transfers exactly: *"each note is created after the
referral is saved and inside the same transaction, so there is no successful transition without its note and no
note for one that was refused or rolled back."*

### 5. The failure note is written in the drain, and composed so it cannot be refused

A terminal failure has no request behind it, so this one has to be in `settle()` — inside the batch transaction
the hazard above is about. **The hazard is eliminated rather than worked around**, by making the note write
unable to fail for any reason that would not already fail the batch:

- **The provider's `failureReason` does not go in the note.** It is unbounded external text and would put the
  length bound beyond proof. The tab holds `failureReason` and `failureVerdict` — that is what the pointer is
  for.
- Composed from `displayName()` plus fixed prose: short, bounded, known at compile time.

Then the only remaining failure mode is the database, which fails the batch regardless, and the strong
invariant holds: **no terminal failure without its note, and no note without a terminal failure.**

**Rejected:** writing the failure note in its own `REQUIRES_NEW`. If the batch then rolled back, a
"could not be sent" note would survive for a row that returned to `Pending` and might later succeed — and notes
are never revised.

### 6. Terminal, not `PERMANENT`

The trigger is **the transition into `NotificationState.Failed`**, not `failureVerdict == PERMANENT`. The outbox
reaches `Failed` by two routes:

```java
if (failed.failure().retryable() && !exhausted) { …retry… }
else { row.setState(NotificationState.Failed); … }
```

so terminal means *not retryable* **or** *retryable and we gave up after N attempts*. `failureVerdict` is set on
every failure including retryable ones, so keying on `PERMANENT` would **miss the exhausted case** — which is the
more insidious one: it looked recoverable, then wasn't, and nothing is coming back to it.

### 7. No note for a transient failure

A note per retry would bury the timeline. The outbox's own comment says the three ways to reach `Failed` are
*"deliberately not collapsed"*; this plan collapses none of them, it just declines to narrate the retries.

### 8. An unresolvable template name degrades exactly as the Email tab does

`Notification.template` stores the enum **member name as a string**, and `EmailTemplate.byName` answers
`Optional.empty()` for one that no longer exists. Its docblock notes this is **reachable rather than
theoretical**: *"the one way this happens is a member renamed after rows were written — which the sender reports
as a permanent failure naming the row"*. So the unresolvable case and the failure-note case coincide.

`correspondence()` already degrades by using the raw stored name as the display name. Do the same: no gap in the
timeline, no invented prose, and the note reads consistently with the tab beside it.

### 9. Same two sentences for every template, including the ones carrying documents

①② attach forms; ③④⑤ do not. Tempting to mention documents — **do not.** Notify does not attach files, it
inserts a time-limited link, and a permanent case note saying "attached" would be wrong in a way nobody would
revisit. `documentKeys()` stays out of the note; the tab holds the document detail.

## Validation Commands

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

Green before any task runs — **measured 2026-09-23 on this branch at `93fcd79`: `BUILD SUCCESSFUL`, 2,580 unit
tests across 327 classes and 12 integration across 3, 0 failures and 0 skipped**, checkstyle clean on all
source sets. Task 4 compares against these numbers.

Notes so no task rediscovers them:

- **Measure, do not read the exit code.** A run with zero tests selected also exits 0, and a second
  `gradle test` wipes `build/test-results/`. Count from the XML immediately after each run, and use
  `--rerun-tasks` when the count is the point. `python3` is **not** installed; `node` is.
- `checkstyleTest` **fails the build on `severity="warning"`**, and the rule that bites is the method-name
  pattern `^[a-z][a-z0-9][a-zA-Z0-9_]*$` — a single-letter first word followed by a capital is rejected, so
  `aCaseworkerSees…` fails where `anAdministratorSees…` passes.

## Tasks

### Task 0: Reconcile and measure the baseline

- [x] Record the baseline counts from `./gradlew build` on this branch — done, see Validation Commands.
      **Re-measured 2026-09-23 at `90784d0`: `BUILD SUCCESSFUL`, 2,580 unit tests across 327 classes, 12
      integration across 3, 0 failures, 0 errors, 0 skipped** — identical to the `93fcd79` figures, so the
      numbers Task 4 compares against are the ones above and nothing has drifted.
- [x] Confirm `NoteService.createNote(String, CreateNoteInput, NoteAuthor)` still takes a plain `NoteAuthor` and
      not a `CurrentUser` — it is what lets the drain author a note at all. **Holds** (`NoteService:62`). It is
      `@Transactional` with the default propagation, so called from `settle` it joins the drain's
      `REQUIRES_NEW` rather than opening a second one — which is what decision 5 needs. `CurrentUser` appears
      in that class only on `removeNote`, so the create path has no request dependency. `NoteAuthor` is a
      one-field record over a name and maps a blank or null to the service account, so the drain can author
      from a stored string with no resolution step.
- [x] Confirm `Notification.createdBy` is still non-nullable, since it is the failure note's author. **Holds**
      — `@Column(name = "createdBy", nullable = false, length = 200)` (`Notification:192`), matching
      `V11__notifications.sql:102` (`varchar(200) NOT NULL`), and no later migration touching
      `deedpoll_notification` (V12, V13, V15) alters it. So the failure note always has a name to attribute to
      and never needs a fallback.
- [x] Confirm `drain(int)` still wraps the whole loop in one `REQUIRES_NEW`, which is what decisions 4 and 5
      rest on. **Holds** — the annotation is on the method (`NotificationOutbox:107`) and the
      `findClaimable`/`claimNextDue`/`settle` loop is entirely inside it, so every row in a batch of up to
      `DEFAULT_LIMIT` 5 commits or rolls back together. `settle` itself is package-private and un-annotated,
      so it has no transaction of its own to escape into: decision 5's "composed so it cannot be refused" is
      therefore the whole of the protection, and there is no second boundary quietly providing any.

### Task 1: `EmailNotes`, with the length arithmetic pinned across the whole enum

- [ ] A class beside `ReferralNotes` in spirit: prose only, no service, no branching on template
- [ ] **The length test iterates `EmailTemplate.values()`**, asserting both notes fit
      `NoteForm.MAX_NOTE_LENGTH` for *every* member. `ReferralNotesTest` pins its worst case against one shape;
      this must hold across a set that is still growing, so a future template with a verbose display name fails
      a test rather than having its note refused mid-drain
- [ ] A test that an unresolvable stored name still composes a note, using the raw name (decision 8)
- [ ] Mark completed

### Task 2: The sent note

- [ ] Written in `CaseEmailService.send` after the enqueue, inside the same transaction, authored by
      `caller.noteAuthor()`
- [ ] Over HTTP: a send writes exactly one note; the note names the template and the Emails tab; a **refused**
      send writes none
- [ ] An idempotent repeat send — which `send` answers with the original row — must **not** write a second note
- [ ] Mark completed

### Task 3: The terminal-failure note

- [ ] Written in `NotificationOutbox.settle` on the transition into `Failed`, authored from
      `Notification.createdBy`
- [ ] Tests: a non-retryable failure writes one; an exhausted retryable failure writes one; **a transient
      failure writes none**; a successful send writes none from the drain (Task 2 wrote it)
- [ ] A test that the note write cannot roll the batch back — drive a batch where one row fails terminally and
      another succeeds, and assert the successful row is still `Sent`
- [ ] Mark completed

### Task 4: Full validation

- [ ] Run the command above and **record the counts here** rather than asserting green
- [ ] Confirm the total rose by the number of tests added and that `skipped` is still 0
- [ ] Mark completed

## Deliberately not in this plan

- **Any `deed-poll-admin` change.** Decision 3 — the notes grid already renders notes, and the pointer is prose.
- **A hyperlink into the Emails tab.** Decision 3 names the only honest route and its cost; it deserves its own
  decision rather than being smuggled in here.
- **Notes for transient failures.** Decision 7.
- **Delivery-status notes.** There is no delivery signal to write one from until Notify callbacks exist.
- **Revising a note when a later attempt succeeds.** Notes are written once and never revised — that is the
  model, and a case with a failure note followed by a success note reads correctly as what happened.

## Flags for Steve

1. **A replacement send after a terminal failure will produce three notes** — sent, could-not-be-sent, sent —
   for what a caseworker may think of as one email. That is the timeline being honest, and it is what business
   testing asked for on referrals, but it is worth seeing on a real case before anybody is surprised by it.
2. **This closes half of notify-3b's flag 4**, which says *"a terminally failed ④ leaves a case sitting in the
   waiting list with nobody able to see why no reply is coming"*. The note makes it visible in the timeline; it
   does not stop the clock or take the case off the waiting list, which is the other half and is a business
   decision about what should then happen.
