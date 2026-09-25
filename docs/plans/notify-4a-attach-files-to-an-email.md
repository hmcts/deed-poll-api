# notify-4a — attach files to an email

## Overview

The first half of the old `notify-4-forms-for-completion.md`, split on 2026-09-25 because **five of its seven
documents need no generating at all** and were carrying the one genuinely unresolved technical risk in the
programme along with them.

This plan builds the machinery to **send a file with an email**, and stores the five blank forms that need
nothing done to them. [`notify-4b`](notify-4b-generate-the-deed.md) generates the deed and is what finally makes
① and ② send.

**Read the inherited evidence before starting** — it is in notify-4b's "What the evidence actually says", and it
is the reason this split exists. The load-bearing claim for *this* plan is evidence 2:

> *Only LOC020 and LOC022 carry case data… The other five are blank forms for a declarant and a commissioner of
> oaths to complete. They are **static files to be stored and served, not documents to be generated**.*

## What this plan does NOT deliver, stated plainly

**No email is sent at the end of it.** Every one of ① and ② attaches a deed — the adult email says *"we have
attached three forms"* and one of the three is LOC020 — so neither template can go out until notify-4b produces
it. This plan's deliverable is a **tested seam**: a stored document becomes a Notify download link at drain time,
with retention and download-confirmation set, size and failure behaviour pinned. It is infrastructure proved by
tests, not by an inbox.

That is deliberate rather than unfortunate. It means the risky half can fail without stranding the ordinary half,
and it means the two halves are blocked on different things — see below.

**The dependency is the other way round from what you might expect.** notify-4b needs **nothing external**: the
two deed templates are already in the workspace. *This* plan is the one waiting on people — the five forms do not
exist here, and *send a file by email* is a per-service Notify setting somebody has to switch on. So if only one
can start, it is notify-4b.

---

## Decisions taken — implement these, do not revisit

Carried from the original plan, which argued them; the numbering is this plan's.

1. **The five blank forms are stored files, not generated ones.** LOC021, LOC023, LOC024, LOC027 and LOC028 are
   the same bytes for every applicant. They are stored once and referenced, **never copied per case** — a per-case
   copy would multiply identical blobs by the caseload and make a corrected form impossible to replace in one
   place. (Original decision 4.)

2. **A document this service sends is a case document, stored as one.** `DeedPollSupportingDocument` already has
   the shape, so the existing `DocumentService` / `DocumentStore` seam is the route and no parallel store appears.
   (Original decision 8.)

3. **The personalisation stores a document *reference*, and the drain resolves it to an upload.** The frozen row
   holds an id; immediately before `sendEmail` the drain swaps each id for
   `NotificationClient.prepareUpload(...)`. **The resolved map is never written back** — it is built, passed and
   discarded. A row whose personalisation held a Notify upload token would be a row that could not be re-sent and
   a token at rest in the database. (Original decision 9.)

4. **`confirm_email_before_download` is `true` for every document this service sends.** A deed carries the
   applicant's name, address and email; a link that anyone holding the URL can fetch is the wrong default for
   that, and Notify's confirmation step costs the applicant one field. (Original decision 10.)

5. **The retention period is generous, and the number is notify-0's to choose.** `RetentionPeriodDuration(int,
   ChronoUnit)` is configuration, not a constant: an applicant who must print, gather two witnesses and find a
   commissioner of oaths is not doing it in a week. Read it from configuration with no working default rather than
   guessing a number here. (Original decision 11.)

6. **The email says "download", not "attached".** Notify's *send a file by email* produces a **link**, not a MIME
   attachment. The templates must be authored to say so, because an email promising an attachment and delivering a
   link is a support call. (Original decision 12.)

7. **A missing or unreadable document is a `TECHNICAL` failure, never `PERMANENT`.** The store being briefly
   unavailable must not poison the case — and after notify-1 task 7, `PERMANENT` is the one verdict that can be
   replaced, which is the opposite of what is wanted. Classify deliberately and say why in the javadoc.

8. **A document over 2 MB must fail before `sendEmail`, with a message naming the document and its size.** The
   Notify Java client throws rather than truncating. `NotificationFailure.classify` would treat that as `TECHNICAL`
   by default — correct, but arrived at by accident, so assert it.

---

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

**Measure the baseline before Task 1 and record it.** As at api master `b01f8f4` (2026-09-25) it was **2,716 unit
tests across 341 classes + 13 integration**, and **1,739 unit tests** with `INTEGRATION_TESTS_ENABLED=false` —
but **re-measure rather than quoting that.**

Notes so no task rediscovers them:

- **Measure, do not read the exit code.** A run selecting zero tests also exits 0, and a second `gradle test`
  **wipes `build/test-results/`** — count from the XML immediately after each run, and use `--rerun-tasks` when the
  count is the point. `BUILD SUCCESSFUL` with no result file means the task was `UP-TO-DATE` and ran nothing.
  **Stale result files are not marked stale**, so delete the directory or compare the task list rather than
  trusting the XML's presence.
- A `--tests` filter matches **method** names as well as classes; `@Nested` classes each get their own result file.
- **`checkstyleTest` fails on `severity="warning"`.** The rule that bites is the method-name pattern
  `^[a-z][a-z0-9][a-zA-Z0-9_]*$` — a single-letter first word followed by a capital is rejected.
- **A container or worker startup timeout is an environment failure, not a code failure** — re-run before
  investigating. Wall-clock variance here is wide: the same Docker-free build has taken 1m 7s and 35m 32s on
  identical code. **If a Docker-free run fails, capture the whole log before re-running** — a previous one was lost
  to a `tail -8` and its cause is still unknown.
- The `integration` source set needs Docker and is **excluded in CI** (`INTEGRATION_TESTS_ENABLED=false`), so a
  rule that only has a container-backed test does not protect the pipeline. Give anything CI must enforce a
  container-free companion.

---

## Tasks

### Task 1: Settle where the five blank forms live, and put them there

Read first: flag 1 below, `DocumentService`, `DocumentStore`, `DeedPollSupportingDocumentType`.

- **The five forms do not exist in this workspace.** Record what was supplied, from whom and when, before writing
  code that assumes a shape. If they are still missing, **stop and report** rather than inventing placeholders —
  a plan that ships fake statutory forms is worse than one that waits.
- Decide and record *where* they live: classpath resources in this repo, or rows in the document store seeded
  once. Argue it rather than picking — the test is which one lets a corrected LOC024 replace the old one in one
  place, for every case, without a migration.
- **They are referenced, not copied per case** (decision 1). Whatever the mechanism, adding a case must not add
  five blobs.
- Check whether a suitable `DeedPollSupportingDocumentType` already exists before adding one, and if one is added,
  make `applicableTypes` reflect that adult and minor take different sets.
- [ ] the five forms are stored once and referenced per case, their provenance is recorded, no per-case copies
      exist, and `./gradlew build` green

### Task 2: Resolve a stored document to a Notify upload at drain time

Read first: `NotifyEmailSender.send`, `NotificationOutbox`, and decisions 3, 4, 5, 7 and 8.

- Immediately before `sendEmail`, replace each document id in the personalisation with
  `NotificationClient.prepareUpload(bytes, filename, true, retention)` — `true` being decision 4, the retention
  from configuration per decision 5, and the filename one an applicant will recognise on their own computer rather
  than a UUID.
- **The resolved map is never written back.** Assert the row's `personalisation` is unchanged after a successful
  send: this is the invariant the design rests on and it would break silently.
- A missing or unreadable document is `TECHNICAL` (decision 7). A document over 2 MB fails **before** `sendEmail`
  with a message naming it and its size (decision 8).
- Tests, **container-free** with a stubbed store and `StubNotify`: a send resolves a multi-document set; the stored
  row survives unchanged; a missing document yields `TECHNICAL`; an oversized one fails with a useful message; and
  `confirm_email_before_download` and the retention period reach the client as configured.
- [ ] documents resolve to uploads at drain time and are never persisted, the frozen row survives a send
      unchanged, missing and oversized documents fail as `TECHNICAL` with useful messages, and `./gradlew build`
      green

### Task 3: Prove it end to end against a real Notify, and record what is left

- With *send a file by email* enabled (flag 2), send **one** document through the non-prod service to a
  guest-listed address and confirm the link arrives, the confirmation step appears, and the retention is what was
  configured. Use an existing template rather than authoring one — this is a proof of the seam, not of ①.
  **If the setting is not on, stop and record that**; do not simulate a success.
- Re-measure the test counts against Task 1's baseline and confirm `skipped` is 0.
- Record what notify-4b still needs from this plan, so the handover is a fact rather than an assumption.
- [ ] a real document has arrived as a Notify link with confirmation and the configured retention, the counts are
      recorded against the baseline, and the handover to notify-4b is written down

---

## Deliberately not in this plan

- **Generating the deed.** notify-4b, including its spike. Nothing here depends on the answer.
- **Authoring ① and ②, their personalisation, availability or exclusivity.** They cannot send without a deed, so
  they belong with the half that produces one.
- **`DOCUMENT_STORE_ROOT`.** See flag 3: this plan makes it urgent but does not fix it.

## Flags

1. **The five blank forms do not exist in this workspace and somebody has to supply them.** LOC021, LOC023,
   LOC024, LOC027 and LOC028. `doc_templates/` holds the two deeds and the four email sources and none of these.
   Task 1 cannot start without them.
2. **Notify's *send a file by email* is a per-service setting and may not be on.** It is not in notify-0's
   checklist as a distinct item beyond the template work, so it needs checking on the non-prod service — and
   again on prod when that exists.
3. **This plan makes `DOCUMENT_STORE_ROOT` urgent.** `LocalFilesystemDocumentStore` writes to local disk, which
   `RESUME.md` already flags must not reach a deployed environment: pod disk is ephemeral, replicas share no
   volume and the chart declares none. Today that loses uploads; after this plan it also loses the blob a deed
   link points at. **It is a deployment blocker for ① and ②, not a tidy-up**, and it is nobody's plan yet.
4. **The retention number is unchosen** (decision 5) and is notify-0's to pick.
