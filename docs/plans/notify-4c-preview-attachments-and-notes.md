# notify-4c — preview a template with attachments, and add notes to the forms emails

## Overview

Two code changes that [`notify-4a`](notify-4a-attach-files-to-an-email.md) found on 2026-09-26 while checking ①
`forms_for_completion_adult` against the real Notify API. They were split out so that ralphex can run them: the
rest of 4a needs a person (authoring ② in the Notify portal, and opening a guest-listed inbox).

**Read notify-4a's Task 3 before starting.** Its five findings are the evidence both tasks here rest on. They were
measured against Notify, not read from documentation, because this devcontainer cannot reach Notify's docs.

1. **Task 1: ① cannot be sent through admin at all.** A file attached to a template in the Notify portal is a
   placeholder named after that file (`((LOC021_0226.pdf))`). A real send fills it in as a download link, but
   Notify's *preview* endpoint answers 400 `Missing personalisation` without it. api's check page previews, and
   the check page is mandatory (notify-2 decision 2).
2. **Task 2: ① and ② gain the optional `additional_notes` slot**, by Steve's decision on 2026-09-26. That also
   closes notify-4b flag 6, the empty compose page for a slotless template.

**Nothing here touches deed-poll-admin.** The content drafts were already updated on 2026-09-26, and admin
renders slots generically.

## Decisions taken — implement these, do not revisit

1. **The send path does not change.** A real send fills template attachments in, so the drain keeps sending the
   frozen personalisation exactly as it does now. Only the preview needs the stand-ins.
2. **Attachment keys are never written into api.** They are the portal's file names and change when a file is
   replaced. They are discovered from Notify at preview time.
3. **A key api owns is never stood in for.** Resolved keys, slots and document keys fail the preview exactly as
   today, so a misspelt `deed_document` in a template is caught at the check page and not at the drain.
4. **Reuse `EmailSlot.ADDITIONAL_NOTES`** rather than adding a second slot that means the same thing.

## Validation Commands

**These run after every task, so everything here must be green after every task, not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

**Measure the baseline before Task 1 and record it.** As at api master after notify-4b (`d21db37`, 2026-09-26)
it was **2,922 unit tests across 361 classes + 13 integration**, measured at the branch's head commit on
2026-09-25. Re-measure rather than quoting that.

**Re-measured 2026-09-26 at `12c57da`, before Task 1:** 2,922 unit tests across 361 classes + 13 integration
(3 classes), 0 skipped, 0 failed (`--rerun-tasks`, counted from the XML). **After Task 1:** 2,945 across 363 + 13. **After Task 2:** 2,950 across 363 + 13.

- **Measure, do not read the exit code.** A run selecting zero tests also exits 0, and a second `gradle test`
  wipes `build/test-results/`. Count from the XML straight after each run, using `--rerun-tasks` when the count is
  the point.
- A `--tests` filter matches **method** names as well as classes.
- **`checkstyleTest` fails on `severity="warning"`.** The method-name pattern is `^[a-z][a-z0-9][a-zA-Z0-9_]*$`.
- **Integration tests do run here** with `TESTCONTAINERS_RYUK_DISABLED=true`. Do not report them as unsupported.
  CI excludes them (`INTEGRATION_TESTS_ENABLED=false`), so any rule CI must enforce needs a container-free test.

---

## Tasks

### Task 1: Let a template with attachments be previewed

Read first: `CaseEmailService.preview`, `EmailPreview`, `NotifyEmailSender`, and notify-4a Task 3's findings 1–3 and 5.

**The defect:** `preview` passes `composed.personalisation()` to `generateTemplatePreview`, and for ① Notify
answers 400, which `preview` turns into an `UpstreamFailureError` (502). The check page is mandatory (notify-2
decision 2), so a caseworker cannot get past it and **① cannot be sent through admin at all**, however it is
provisioned. No test fails today, because `StubNotify` has no notion of an attachment placeholder.

- **For the preview only**, supply a stand-in for each placeholder that is a Notify attachment. Use a sentence a
  caseworker can read on the check page, such as *"[A download link for this form is added when the email is
  sent]"*. That is honest, and it is what the preview already does for the deed (`DEED_NOT_YET_GENERATED`).
- **The keys cannot be hard-coded** (notify-4a Task 3, finding 5), so they come from Notify at preview time. Choose between two
  options and argue it in the javadoc, rather than just picking one:
  - read the template (`getTemplateById`) and fill each `required` key that api does not own;
  - or parse the 400's `Missing personalisation:` list and retry once.

  Either way, **a key api does own must never be stood in for**. If `deed_document` were misspelt in the
  template, a preview that papered over it would pass while every real send failed at the drain. So fill in
  only keys that are not in `EmailTemplate.resolvedKeys()`, `slots()` or `documentKeys()`, and let everything
  else fail as today.
- **The send path must not change.** notify-4a Task 3's finding 2 is that a real send fills the attachments in, so the drain sends
  the frozen personalisation exactly as now. Assert that no stand-in text can reach `sendEmail`.
- `StubNotify` has to learn the difference between the two endpoints, or the test proves nothing: a preview
  missing a required key is a 400 with Notify's message, and a send is not.
- Tests, container-free where possible: ①'s preview renders with a stand-in under each attachment; a template
  with no attachments (③, ⑤) makes exactly the calls it makes today; a placeholder that api owns but the
  personalisation lacks is still a 502; and a send carries no stand-in.
- [x] a template with Notify attachments previews, api-owned keys are never stood in for, the send path is
      unchanged, `StubNotify` distinguishes preview from send, and `./gradlew build` green

      **Done 2026-09-26.** `AttachmentStandIns` reads the 400's `Missing personalisation:` list and retries once,
      argued in its javadoc over `getTemplateById`: a template with no attachment keeps its single call. It also
      stands in only for a key shaped like a file name, so a misspelt `deed_documnet` still 502s.

### Task 2: Give ① and ② the optional additional-notes slot

Read first: `EmailSlot.ADDITIONAL_NOTES`, `EmailTemplate`, and how ③ `application-rejected` uses it.

- Add `EmailSlot.ADDITIONAL_NOTES` to both `FORMS_FOR_COMPLETION_ADULT` and `_MINOR`. **Reuse the existing slot**:
  it is already optional, capped at `MAX_NOTES_LENGTH` and rendered by admin with no change, so a second slot
  meaning the same thing would be two names for one box.
- **The idempotency comparison has to include it.** A second journey with *different* notes is a different
  email, so check `isTheSameSend` treats it as one, as it does for ③.
- **The content side is already done**, in deed-poll-admin's `forms-for-completion-static-forms-in-notify` branch
  (2026-09-26): the draft places `((additional_notes))` and `application-rejected.md`'s wrong sentence is
  corrected. **Do not edit deed-poll-admin from this task.** Steve adds the placeholder to ① in Notify when this
  lands (notify-4a Task 3, finding 4: without it, a note is dropped with nothing failing).
- Tests: both templates publish the slot as optional; a send with no notes and a send with notes both compose;
  the notes cap is enforced; two journeys differing only in their notes are a 409, not an idempotent 200.
- [x] ① and ② carry `additional_notes`, optional, compared by the idempotency check, and `./gradlew build` green

      **Done 2026-09-26.** 2,950 unit tests across 363 classes + 13 integration, 0 skipped. The notes are part of
      the frozen personalisation, so `isTheSameSend` compares them with no change to it: the same `Idempotency-Key`
      with different notes (or none) is a 409. A *new* key for a forms email already owed still answers 200 with
      the owed row through V20, exactly as ③ does through V15, so the second journey's notes are not sent.

### Task 3: Full validation and handoff

- The validation commands with `--rerun-tasks`, counts recorded against the baseline, `skipped` confirmed 0.
- Tick notify-4a's reference to this plan as done, and record in notify-4a's Task 4 that it is unblocked **once
  Steve has added `((additional_notes))` to ① in Notify**.
- [ ] counts recorded against the baseline, notify-4a updated, and the Notify edit handed to Steve in one sentence
