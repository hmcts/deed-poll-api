# Plan: referrals to the judiciary (API follow-up 5) — lifecycle case notes

## Overview

Business testing found that the case Notes grid does not record the complete referral lifecycle. It currently
records:

- referral raised;
- referral withdrawn;
- an inactive judicial claim returned to the pool.

It deliberately does not record claim, direction or closure because the original plans treated the referral
row as sufficient. The business requirement now is that each meaningful lifecycle transition also leaves a
chronological entry in the application's case notes.

This plan adds those missing entries without adding a new audit resource or changing any HTTP contract. The
companion admin plan is
`deed-poll-admin/docs/plans/referrals-admin-5-lifecycle-case-notes.md`; production admin pages already render
case notes, but the in-memory API must reproduce the new side effects.

## Dependency and reconciliation gate

Implement after `referrals-api-4-caseworker-completion.md`, so the final transition set is fixed:

`Open → Claimed → Decided → Closed`, with withdrawal permitted from `Open`/`Claimed`, and
`Claimed → Open` when a claim lapses.

Before coding:

1. Verify `ReferralNotes` still owns the raised, withdrawn and claim-lapsed prose.
2. Verify `ReferralService` still writes those three notes and explicitly writes none for claim, decide and
   close.
3. Verify `NoteForm.MAX_NOTE_LENGTH` remains 2,000 and
   `ReferralSchemas.MAX_DIRECTIONS_LENGTH` remains 4,000.
4. Verify caseworker case details and `JudicialReferralDetail.caseNotes` already expose the same stored notes.
5. Measure the current build/test baseline and stop on state-machine or note-contract drift.

## Lifecycle event matrix

| Transition/event | Note | Author | Existing/new |
|---|---|---|---|
| Referral raised (`→ Open`) | Reasons, urgency and referral note | Acting caseworker | Existing |
| Referral claimed (`Open → Claimed`) | Identifies the referral and records that it was claimed | Claiming judge | **New** |
| Claim touched/read | None | — | Deliberately none |
| Claim lapsed (`Claimed → Open`) | Names the former holder and says it returned to the pool | Service account | Existing |
| Direction recorded (`Claimed → Decided`) | Decision and Gazette answer; points to referral for full directions | Deciding judge | **New** |
| Referral withdrawn (`Open/Claimed → Withdrawn`) | Raised date and reasons | Acting caseworker | Existing |
| Direction actioned (`Decided → Closed`) | Records that the returned direction was actioned | Acting caseworker | **New** |
| Refused/failed transition | None | — | Required |

There is no separate “reclaimed” transition after a lapse: the next successful claim writes another claimed
note, producing an honest sequence of claimed → lapsed → claimed.

## Decisions — implement these, do not revisit

### 1. These are case notes, not a new event store

Continue using `DeedPollNote` and `NoteService.createNote`. That is the timeline caseworkers already read and
the same timeline the judicial referral detail exposes. Do not add a referral-event table, endpoint, DTO,
enum, migration or UI component.

This is an operational history, not a tamper-proof statutory audit log. Existing case notes can be amended,
and administrators can delete them; lifecycle notes inherit that behaviour because the note contract has no
system/generated discriminator. If immutable audit records are required later, that is a separate schema,
authorization and migration decision and must not be implied by this plan.

### 2. Write one note for each successful state transition

The note is part of the same transaction as the referral state change and `currentReferralState` refresh.
There must be no successful transition without its note, and no note for a transition that is refused,
conflicts, or rolls back.

Do not write notes for:

- judicial reads or `touch`, because they do not change lifecycle state;
- rejected duplicate claims/directions/closures/withdrawals;
- historic rows during startup or read;
- the one-time referral backfill retrospectively.

### 3. Authorship comes from the actor that caused the transition

- Raise, withdraw and close use `caller.noteAuthor()` for the caseworker.
- Claim and decide use `caller.noteAuthor()` for the judge.
- Claim lapse continues using `NoteAuthor.NONE`, because the service—not the person whose page happened to
  trigger the sweep—performed the release.

Do not embed a caller-supplied author in any request. The note's `createdBy`/`createdOn` columns already show
who and when; the prose describes what happened.

### 4. Identify the referral in human terms, never by UUID

A case may have multiple referrals over its lifetime. Claim, direction, withdrawal and closure notes must be
pairable without exposing an internal UUID that staff cannot recognise. Reuse one helper that identifies a
referral by:

- its raised date; and
- its reasons, or “no reasons recorded” for a migrated referral.

Do not copy the applicant's personal data into the note. The note is already nested under the case.

### 5. Direction notes are summaries, not copies

The direction can contain 4,000 characters while a case note is limited to 2,000. Copying or truncating
`directionsText` would either make a valid direction fail its transaction or create an incomplete second
version that appears authoritative.

The direction note records:

- that a judicial direction was recorded;
- `Approved` or `Rejected`;
- the explicit Gazette answer;
- the human referral identity;
- “See the referral for the full direction.”

It does **not** copy `directionsText`. The caseworker completion plan makes the full text visible on the
referral page. Null direction text therefore needs no special truncation or placeholder in the case note.

### 6. Wording is centralised and length-proven

Extend `ReferralNotes`; do not compose prose in `ReferralService`. Suggested stable wording:

```text
Referral raised on 1 September 2026, reasons: Minor, Other, claimed by the judiciary.

Judicial direction recorded for referral raised on 1 September 2026, reasons: Minor, Other.
Decision: Approved. Publish to the London Gazette: No. See the referral for the full direction.

Judicial direction for referral raised on 1 September 2026, reasons: Minor, Other, marked as actioned.
```

The note author supplies the actor's name. Preserve the existing raised, withdrawn and lapse wording unless
a shared helper removes duplication without changing their tested output.

Every composer must be tested through `NoteForm.validateNoteText`, including all referral reasons and a
migrated referral with none. Retain the existing worst-case test for the token-derived judge name in the
claim-lapse note. No path may make an otherwise valid transition fail because its generated note exceeds the
note limit.

### 7. Existing read surfaces require no contract change

Caseworker case details already include `NoteResponse`; the judicial detail already includes
`JudicialCaseNote`. The new rows therefore appear on the next read with no response-shape change.

Do not add notes to `ReferralResponse`, add a new endpoint, or regenerate a changed OpenAPI document.
Regeneration is still used as a proof that the operation/schema sets remain unchanged.

### 8. Judicial visibility is accepted and explicit

A judge who can view a referral can read the application's case notes. New claim and direction notes may
therefore be visible to another judge after a claim lapses and the referral returns to the pool. That is
consistent with the already-approved claim-lapse note, which names the former holder so the next judge can
coordinate with them.

The new note text must contain no data beyond the referral and direction fields already available on the
judicial referral detail.

## Tasks

### Task 0: Reconcile note and transition behaviour

Inspect `ReferralService`, `ReferralNotes`, `NoteService`, `NoteAuthor`, referral service/controller/security
tests and both case-note read projections. Confirm the event matrix above, the two length limits and the
transaction boundaries. Regenerate/compare OpenAPI and record the current build/test totals.

Stop if API follow-up 4 is not merged or if case notes have gained a generated/immutable discriminator this
plan does not account for.

- [ ] Current transitions, note behaviour, visibility and limits reconciled; baseline recorded; no files
      changed

### Task 1: Define and test lifecycle note text

Extend `ReferralNotes` with composers for:

- claimed;
- direction recorded;
- direction actioned.

Extract a single human referral-description helper used wherever raised date/reasons are needed. Keep
existing user-authored text verbatim and avoid UUIDs and applicant data.

Extend `ReferralNotesTest` to pin exact prose, migrated/no-reason handling, both decisions, both Gazette
answers, and the 2,000-character invariant. Prove the 4,000-character `directionsText` never enters the note.

- [ ] Three new composers and shared referral identity implemented; exact wording and length invariants
      covered by container-free tests

### Task 2: Write claimed and direction notes

In the successful `claim` and `decide` transitions, create one note after the referral has its final saved
fields and before returning:

- claim note authored by the claiming judge;
- direction note authored by the deciding judge.

Update `ReferralServiceTest` to replace the existing “writes no case note” assertions. Prove the note is
written once, against the correct case, with the actor's display name, and only after an allowed transition.
Keep `touch` note-free and prove repeated reads/touches add nothing.

Cover lapsed then re-claimed sequencing: the service-account lapse note remains, and the new successful claim
adds a second human-authored claim note.

- [ ] Claim and decide each write exactly one correctly attributed note; touch and refused transitions write
      none; lapse/reclaim sequence covered; focused tests green

### Task 3: Write the direction-actioned note

In `close`, create the actioned note using the closing caseworker's `noteAuthor()`. Preserve the stored
judicial direction and existing `currentReferralState → NULL` behaviour.

Update service and controller tests to prove:

- `Decided → Closed` writes one caseworker-authored note;
- the closure response and stored referral retain decision, directions, Gazette answer and deciding judge;
- repeated or invalid closure writes no note;
- cross-case and unauthenticated attempts write nothing.

- [ ] Closure writes one correctly attributed note without changing direction data; failure paths remain
      side-effect free; service/controller tests green

### Task 4: Prove the full timeline and contract stability

Add one integration/controller journey that raises, claims, decides and closes a referral. Assert each note
is created in lifecycle order with the correct author, then assert the case-note read surfaces return the
completed timeline in their established newest-first order. Include a lapse/reclaim branch in the existing
service coverage rather than forcing time-based expiry into the HTTP journey.

Verify both read projections:

- caseworker `CaseDetailResponse.notes`;
- judicial `JudicialReferralDetail.caseNotes` while the referral remains visible to that judge.

Regenerate OpenAPI and verify no path/schema/property/enum delta.

- [ ] Full lifecycle timeline and both read projections proven; OpenAPI unchanged

### Task 5: Documentation and full validation

Update `ReferralService`/`ReferralNotes` Javadocs and completed-plan cross-links that deliberately excluded
claim, direction and closure notes. Keep the earlier reasoning visible but mark it superseded by this plan.

Run:

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

Record final unit/integration totals against Task 0.

- [ ] Documentation current; full build green; final test totals recorded

## Out of scope

- Making generated notes immutable or visually distinct from human-authored notes.
- Adding a referral event table or audit API.
- Copying or truncating the full judicial directions into a note.
- Changing referral states, withdrawal/closure rules or application-state derivation.
- Admin page changes; only the companion mock/test plan is required.
- Retrospective notes for migrated or already-completed referrals.
- A judge's personal worklist, planned in `referrals-api-6-judicial-my-referrals.md`.
