# notify-3 — *Request confirmation to proceed*, the second email

## Overview

Adds the second of the original four templates: the email a caseworker sends when the judge **refused a
non-publication request but enrolment may still go ahead**. The applicant is asked to confirm they still want
the deed enrolled, or to say they do not and be refunded.

Everything this email needs already exists. notify-1 built the client, the error taxonomy, the outbox, the
drain and the three operations, and built them to take a second template: `EmailTemplate` is an enum whose
members carry their own resolved keys and slots, `CaseEmailService.cause()` is a `switch` with no `default`
so a new member **fails to compile** until somebody says what makes it applicable, and `EmailSlots.parse`
already refuses a request that tries to set a resolved value. This plan is mostly a new enum member, a new
personalisation record, and the rules for when the email is offered.

Read first: [`NOTIFY_TEMPLATE_TAXONOMY.md`](../../../deed-poll-admin/.business_context/NOTIFY_TEMPLATE_TAXONOMY.md)
§3, §4 and §6, and this repo's `notify-1-email-plumbing.md` — its decisions are this plan's decisions except
where said otherwise below.

**The template's actual words, read from the supplied `.docx` rather than inferred.** Worth having in front of
you, because three separate design points fall out of it:

> Your application was referred to a judge for a decision as you provided us with a statement requesting for
> your **[child's] [delete if an adult application]** details not to be published in The Gazette.
>
> On **XX/YY/ZZZZ** we received instructions from the judge on this matter. They have rejected your request.
> They have stated the following:
>
> **"…" [Enter judge's comments]**
>
> If you still wish to enrol the deed poll then let us know **by replying to this email** so we can pass on
> your **[child's]** change of name for enrolment.
>
> **[If the applicant applied online …]** … we will issue your refund to the same account you used to pay.
> **[If the applicant applied on paper …]** … please provide the old and new name, how payment was made, …

**This plan depends on notify-1 being finished, including its task 7.** Task 7 is what lets a permanently
refused send be sent again; without it a misconfigured environment permanently poisons a referral, and this
plan doubles the number of ways to reach that. It also depends on **notify-0** for the template UUID, exactly
as notify-1 does — the placeholder convention is already in place and nothing here changes it.

**Scope is the api.** The Email tab is notify-2's, and notify-2 was written to need no change when a template
is added — its task 4 asserts that. If this plan forces a change in `deed-poll-admin`, that is a finding worth
reporting rather than a change worth making quietly.

---

## Decisions taken — implement these, do not revisit

1. **The caseworker chooses which email to send; the service does not derive it.** This is taxonomy §4's
   recommendation and it is settled here. `MasterDecision` is only `Approved | Rejected`, and a rejection
   means "enrolment refused" for template ③ and "your non-publication request is refused, enrolment may still
   proceed" for ④ — opposite outcomes. The discriminator would have to be `ReferralReason`, and referrals are
   deliberately multi-select: one raised for both `NonPublication` *and* `DeclarantRequirementsNotMet` gets one
   `Rejected` and one free-text direction covering both, which cannot be decomposed. A caseworker is already
   reading the direction in order to action it, so choosing is work they are doing anyway.

2. **`NonPublication` is necessary for ④ to be offered, but not sufficient to send it.** Availability is
   derivable in the safe direction only: a `Rejected` referral whose `reasons` do **not** include
   `NonPublication` cannot be about a non-publication request, so ④ is not applicable and the `GET` says so.
   A referral that does include it offers **both** ③ and ④, and the caseworker picks. `Referral.reasons` is
   `List<String>` holding `ReferralReason` member names, so the check is against
   `ReferralReason.NonPublication.name()` and not a free-text match.

3. **③ and ④ are mutually exclusive for one referral, and the database enforces it (Steve, 2026-09-19).**
   The partial unique index is on `("referralId", "template")`, so it stops a second ③ and a second ④ but
   permits one of each — and those two emails contradict each other: one says enrolment is refused, the other
   asks whether to proceed with enrolment.

   **Enforced by widening the existing index, not by adding a second mechanism.** `EmailTemplate` gains an
   **exclusion group**; the notification row stores the group alongside the template; and the unique index
   becomes `("referralId", "exclusionGroup")`. ③ and ④ share the group `REFERRAL_OUTCOME`, so one referral
   gets one of them, decided atomically by Postgres rather than by a check with a race in it. A template
   alone in its group behaves exactly as today, so this is a generalisation of the existing rule rather than
   a new one beside it.

   **The group is declared on the enum rather than in a pair table, and that is the load-bearing part.**
   A table of incompatible pairs is data, and data cannot force a decision: somebody adds a fifth template,
   forgets to add its rows, and it silently excludes nothing. This codebase deliberately does the opposite —
   `CaseEmailService.cause()` is "a `switch` with no `default`, so a template added to the enum does not
   silently inherit another one's rule: it fails to compile until somebody says what makes it applicable."
   A group as a required constructor argument on the enum member has that same property, and a pair table
   does not. So the enum decides the grouping and the database enforces it.

   **The stored group is deliberately a copy, not a lookup.** The index needs a column, and a row keeps the
   group it was written under — so re-grouping templates later cannot retrospectively make two already-sent
   emails conflict. That is the right direction: history stays true to what was sent.

4. **The referral is a parameter to this personalisation, unlike template ③'s — and the guard stays.**
   `ApplicationRejectedPersonalisation` deliberately refuses the referral so that a second, un-edited copy of
   the judge's words cannot reach the same email. ④ needs `Referral.decisionDate` for "On XX/YY/ZZZZ we
   received instructions from the judge", which is not the judge's words and has no slot. So the referral is
   passed, and the rule it was protecting is restated as a narrower one: **`directionsText` is never read
   here.** The javadoc says so and a test asserts the resolved map does not contain it.

5. **`XX/YY/ZZZZ` becomes `decision_date`, formatted for an applicant.** It is a literal in the document
   today — a date nobody turned into a merge field. `Referral.decisionDate` is a `LocalDateTime`; the
   applicant sees a date, so it is formatted as one (`d MMMM yyyy`, the format the rest of this service shows
   a member of the public) and not as an ISO timestamp. Formatted in the resolver rather than in the template,
   because Notify has no date formatting.

6. **"Minor" is four enum members' worth of question, not two.** `DeedPollType` is
   `Adult | AdultTransgender | Minor | MinorTransgender`, so the `[child's]` branch is
   `Minor || MinorTransgender` and not `type == Minor`. A `switch` with no `default` over `DeedPollType`, so a
   fifth member added later fails to compile rather than silently addressing a child's application as an
   adult's. Sent as a JSON boolean `is_minor`, for the reason `applied_online` is a boolean.

7. **The refund branch is template ③'s rule, reused unchanged.** Same discriminator — `PostCounter.Online` is
   the only value that gets the "we will refund the account you paid from" paragraph, everything else
   including `Unknown` and unset gets the paper paragraph that asks the applicant how they paid. The asymmetry
   is deliberate and `ApplicationRejectedPersonalisation.appliedOnline` documents why. **Extract it rather than
   copy it**, so the two emails cannot drift into disagreeing about who gets an automatic refund.

8. **Both slots are unchanged.** `JUDGES_REASON` required, `ADDITIONAL_NOTES` optional, same caps.
   `EmailSlot`'s javadoc already says requiredness holds for the whole proposed template set, and this is the
   second template proving it. No new slot member.

9. **This email invites a reply, and the reply-to is a provisioning dependency Steve owns.** Two of its
   paragraphs say "let us know by replying to this email", so the default Notify no-reply sender would lose
   the answer this email exists to collect. Steve owns registering and verifying the monitored inbox
   (2026-09-19) and it is assumed to be in place, so this plan does not design around its absence — but it
   **does** make the assumption fail loudly rather than silently. `emailReplyToId` is required configuration
   for a template that invites a reply, and `isProvisioned` for such a template is false without it, exactly
   as it is false without a template UUID. Both are notify-0's to supply and neither is a secret.

10. **Whether a template invites a reply is a property of the template, not of the service.** ③ does not
    invite one and must not start requiring a reply-to; ④ does. So the flag lives on `EmailTemplate` beside
    `key()` and `slots()`, and `isProvisioned` takes the template rather than answering for the whole
    service. That keeps the Email tab able to say "this email cannot be sent yet" about one template without
    disabling the other.

---

## Constraints

notify-1's constraints apply in full — Java 21, `-Werror`, checkstyle on five source sets,
`TESTCONTAINERS_RYUK_DISABLED=true`, DTOs never entities, a controller test boots the whole context, never
edit `src/test/resources/reference/`, no live Notify call in any test. Additional:

- **One migration, and it supersedes notify-1's task 7 index.** Decision 3 needs an `"exclusionGroup"` column
  and a rewritten unique index — and task 7 rewrites that *same* index to stop a permanently failed send
  blocking its own replacement. **They must not be written independently.** notify-1 ships first, so this
  plan's migration takes task 7's predicate as its starting point and widens the key from `"template"` to
  `"exclusionGroup"`, keeping the permanent-failure exclusion intact. Read task 7's migration before writing
  this one, and re-state its predicate rather than reconstructing it from memory.
- **Take the next free migration number and say so**, as notify-1's task 2 had to. `V11` is committed and
  task 7 takes `V12`; do not assume `V13` is free without looking.
- **No other schema change.** Every value this email needs is already a column. If this plan reaches for one
  that is not, stop — that is a taxonomy error worth reporting, since §2 asserts all four emails resolve
  against the existing model.
- **Do not change `deed-poll-admin`.** notify-2's design intent is that a new template appears with no change
  there. If something forces one, report it rather than making it.
- **Container-free where CI runs it.** Same rule and same reason as notify-1: availability, the applicability
  rules, the resolved-values-are-not-input rule and the minor branch need no container and must be stated
  container-free.
- **notify-1 must be merged first**, including its task 7. Do not start against a branch where tasks 5–7 are
  open.

---

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

**Measure the baseline on this branch before Task 1** by summing `tests="…"` across
`build/test-results/{test,integration}/*.xml`, and record it next to the final count in Task 3. Confirm rather
than assume — notify-1 moved these figures and its task 7 moved them again.

### Baseline and reconciliation, performed in the parent session on 2026-09-21

This plan has no Task 0, so this stands in for one. Checked against `origin/master` at `227b714`.

**Baseline:** `test` **2,379** and `integration` **10** — **2,389** in total, zero failures, `./gradlew build`
green from cold. Compare against this, not against a figure quoted in another notify plan.

**The migration is `V15`.** `V14__referral_owner_subjects.sql` is the highest on `master`, so the correction
below resolves to `V15` — confirmed by looking, as it asks. Nothing between has taken it.

**`exclusionGroup` does not exist anywhere in `src/main/java`**, so this plan introduces it rather than
extending it.

**V12's predicate, verbatim, as both places on `master` spell it today** — this is the thing the plan says must
be carried through unchanged, and getting it wrong silently changes which rows the partial index covers:

```sql
-- V12__notification_failure_verdict.sql, the index being replaced
CREATE UNIQUE INDEX "deedpoll_notification_referral_template_key"
    ON "deedpoll_notification" ("referralId", "template")
 WHERE "referralId" IS NOT NULL
   AND ("state" <> 'Failed' OR "failureVerdict" IS DISTINCT FROM 'PERMANENT');
```

`NotificationRepository.enqueue` repeats that target and predicate in its `ON CONFLICT` clause, and must go on
matching the new `("referralId", "exclusionGroup")` index exactly — the two are one mechanism, and a mismatch
makes the statement silently stop deduplicating rather than fail.

**A worker or container startup timeout is an environment failure, not a code failure** — re-run before
investigating.

---

## Corrections from the pre-implementation review — 2026-09-20

These corrections were checked against notify-1 and notify-5 as merged on `master`. They supersede any
less-specific wording in the tasks below; the original wording remains so the reasoning that led here is not
lost.

1. **Choose the newest rejected referral first, then test whether it includes `NonPublication`.** Do not search
   for the newest rejected *non-publication* referral: that can skip a newer rejected referral and revive an
   older outcome the existing `APPLICATION_REJECTED` rule treats as superseded. Both templates must start from
   the same newest rejected referral. ④ is applicable only if that referral's reasons include
   `ReferralReason.NonPublication.name()`, which also guarantees that ③ and ④ use the same `referralId` when
   both are offered and can therefore be made mutually exclusive by one database key.

2. **The migration is ~~V14~~ the next free number, and must be safe during a rolling deployment.** V13 now
   exists on `master`.

   > **Corrected 2026-09-21:** `referrals-api-6-judicial-my-referrals.md` ran first and **takes `V14`** for its
   > nullable `claimedById`/`decidedById` columns, so this plan takes the next free number after it — `V15`
   > unless something else lands between. Check before writing it; do not assume.
   >
   > **Do not instead leave `V14` reserved and jump ahead.** Flyway runs with out-of-order disabled, so a `V14`
   > appearing after `V15` has already been applied fails every environment that applied `V15` — a reserved gap
   > is worse than a renumber. Nothing else in this plan changes: the column, its database default, the
   > replacement index and V12's predicate are all unaffected by which number carries them.

   `exclusionGroup` is a non-null text column copied onto every row and mapped on `Notification`; the migration
   must give it a database default of `REFERRAL_OUTCOME` as it is added. `APPLICATION_REJECTED` is the only
   template an old application instance can enqueue, so that default both backfills every existing row correctly
   and keeps old instances able to insert while old and new pods overlap. New code still binds
   `template.exclusionGroup().name()` explicitly. The replacement index is
   `("referralId", "exclusionGroup")`, retaining V12's predicate verbatim:
   `WHERE "referralId" IS NOT NULL AND ("state" <> 'Failed' OR "failureVerdict" IS DISTINCT FROM 'PERMANENT')`.
   `NotificationRepository.enqueue` must repeat that exact conflict target and predicate.

3. **A zero-row enqueue has two different meanings after re-keying and must not use the current template
   finder.** The existing
   `findByDeedPollIdAndReferralIdAndTemplate` cannot find a row for the counterpart template. Add a case-scoped
   lookup by referral and exclusion group which applies the same blocking predicate as the index. If the
   blocking row has the requested template, preserve notify-1's idempotent second-click response (`200`,
   `enqueued: false`, original row id). If it has the counterpart template, throw `ConflictError` (`409`)
   naming that template and its `createdOn`. Describe it as already requested/recorded, not necessarily
   delivered: a blocking row may be `Pending`, `Sent`, or an uncertain `Failed` row.

4. **Availability must use the index's definition of a blocker.** A historical row with
   `state = Failed` and `failureVerdict = PERMANENT` does not occupy the unique index and must not hide either
   template; Notify definitely sent nothing and notify-1 deliberately permits a replacement. A pending, sent,
   technical, exhausted-temporary, or verdict-less failed row does block the counterpart. State this in
   container-free service tests and prove the predicate against PostgreSQL in the repository tests.

5. **The judge's direction still reaches Notify once, through the editable `judges_reason` slot.** Decision 4
   means `RequestConfirmationToProceedPersonalisation` must not read `directionsText` or add a second resolved
   key for it. It does not mean the final personalisation map lacks the direction's text: `EmailSlots.parse`
   pre-populates `judges_reason` from it. Test that there is no extra `directions_text`/judge-direction key and
   that the record's resolved values contain only `RESOLVED_KEYS`; separately retain the existing slot-default
   test for `judges_reason`.

6. **Reply-to wiring uses the verified five-argument client overload.** Version `6.2.0-RELEASE` exposes
   `sendEmail(templateId, recipient, personalisation, reference, emailReplyToId)`. Add
   `notify.email-reply-to-id: ${NOTIFY_EMAIL_REPLY_TO_ID:}` to `application.yaml`, trim it in
   `NotifyProperties`, and make per-template provisioning require a non-blank value only when
   `invitesReply` is true. `NotifyEmailSender` must continue using the four-argument overload for ③ and use the
   five-argument overload for ④, so adding a global reply-to does not silently change an email that does not
   invite a reply.

7. **OpenAPI validation is the deliverable here, not an untracked file refresh.** `OpenAPIPublisherTest`
   writes `/tmp/openapi-specs.json`; this repository does not publish or commit that output. Task 3 must prove
   the existing Email-tab schema exposes both template entries without changing the 33-operation count.
   Importing that generated document and regenerating the TypeScript client remains notify-2 Task 0 in
   `deed-poll-admin`.

---

## Tasks

### Task 1: The template, its resolved values and the two new conditionals

Read first: `EmailTemplate`, `ApplicationRejectedPersonalisation` and `EmailSlots.parse`.

- `REQUEST_CONFIRMATION_TO_PROCEED("request-confirmation-to-proceed", …)` added to `EmailTemplate`, with
  `JUDGES_REASON` and `ADDITIONAL_NOTES`. Adding the member breaks `CaseEmailService.cause()` and
  `reasonNotApplicable()` at compile time by design — that is the prompt for Task 2, not a problem to route
  around.
- `RequestConfirmationToProceedPersonalisation`, a record beside the existing one, resolving:
  `reference_number`, `online_application_reference`, `old_forename`, `old_surname`, `applied_online`,
  **`is_minor`**, **`decision_date`**, plus the two slots. `RESOLVED_KEYS` declared on the record and read by
  the enum, as ③ does it.
- **Extract the refund branch** so both records call one `appliedOnline(PostCounter)` rather than two copies
  (decision 7). Moving it is a refactor of ③'s code — keep its javadoc, which is the argument for the
  asymmetry, with the code.
- `isMinor(DeedPollType)` as a `switch` with no `default` (decision 6).
- `decision_date` formatted `d MMMM yyyy` from `Referral.decisionDate`, empty string when null — for the
  reason every other key is present even when blank: Notify refuses a send whose personalisation is missing a
  placeholder the template uses.
- Tests, container-free: every resolved key present and in `toMap()` order; `is_minor` true for `Minor` and
  `MinorTransgender` and false for both adult members; `applied_online` true only for `Online`; a null
  `decisionDate` renders empty rather than absent; and **`directionsText` appears nowhere in the resolved map**
  even though the referral is in scope (decision 4).

- [x] The template resolves its own personalisation, the refund branch is shared with ③ rather than copied,
      `is_minor` covers all four `DeedPollType` members, `decision_date` is a formatted date, the judge's
      direction never leaks into a resolved value, and `./gradlew build` green

#### Findings from Task 1 — 2026-09-21

1. **`DeedPollType` has six members, not the four decision 6 names.** `DeedOfRelinquishment` and `Unknown` sit
   beside the four, so the `switch` with no `default` had to answer for all six. Both take the **adult** wording,
   with the reason stated on `ResolvedValues.isMinor`: addressing an applicant about their own deed is merely
   impersonal if wrong, where the child wording refers an adult applicant to a child who does not exist. A null
   `type` — the column is nullable — is handled before the `switch` and takes the same branch. This strengthens
   flag 4: the taxonomy's "adult vs minor is chosen by `type`" gloss is two members further from the truth than
   the flag says, and in notify-4 it picks *which template* rather than which sentence.

2. **The extraction landed as `ResolvedValues`**, a package-private helper holding `appliedOnline` (moved from
   ③ with its javadoc, which is the argument for the asymmetry), the new `isMinor` and `date`, and `text` —
   which was a private copy in ③ and would have become a second one here. `ApplicationRejectedPersonalisationTest`
   now calls `ResolvedValues.appliedOnline`.

3. **Task 2's `cause()` and `reasonNotApplicable()` branches were written here, because the build could not be
   green without them.** Adding the enum member breaks three `switch`es, and the validation commands run after
   every task. They are implemented as decision 2 and correction 1 specify — newest `Rejected` referral first,
   then `ReferralReason.NonPublication.name()` — with container-free tests for both directions, the
   newest-refusal ordering, a reasons-less migrated referral and free-text non-matching. **Still open for Task 2:**
   the `exclusionGroup` column and migration, the re-keyed unique index and `enqueue`'s conflict target, the
   counterpart-already-sent availability check, the 409 on a counterpart row, and the container-backed
   repository tests. `reasonNotApplicable` currently gives ④ one sentence (`NO_NON_PUBLICATION_REFUSAL`) covering
   both halves of its condition; Task 2 splits it into the two the plan asks for.

### Task 2: When the email is offered, and when it is refused

Read first: `CaseEmailService.cause()`, `availability()` and `EmailAvailability`.

- `cause()` gains its branch: the newest `Rejected` referral whose `reasons` contain
  `ReferralReason.NonPublication.name()` (decision 2). Newest first, for the reason ③ is — an older refusal
  has been superseded.
- `reasonNotApplicable()` gains its sentence, naming the actual reason: no rejected referral, or one that was
  not about non-publication. Those are two different sentences and a caseworker can act on the difference.
- **The mutual-exclusion rule (decision 3)** ships as its own migration, read together with notify-1's task 7:
  an `"exclusionGroup"` column on `deedpoll_notification`, backfilled for existing rows from their template,
  and the unique index re-keyed from `("referralId", "template")` to `("referralId", "exclusionGroup")` while
  keeping task 7's permanent-failure predicate exactly as task 7 left it. `EmailTemplate` gains the group as a
  required constructor argument — ③ and ④ both `REFERRAL_OUTCOME` — and `enqueue` binds it alongside the
  template.
- **The `GET` still reports it, because a 409 is a bad way to find out.** `availability()` says ④ is
  unavailable once ③ has a row in its group for that referral and vice versa. That is a courtesy, not the
  enforcement: the index is the enforcement, and the check is there so the Email tab can grey the option out
  rather than let a caseworker compose an email that will be refused on submit.
- The refusal, when the index does fire, is a **409** naming which email was already sent and when — not a
  `DataIntegrityViolationException` reaching the caseworker. `enqueue` already answers `0` on conflict rather
  than throwing, and that path is reused, so the caseworker's transaction stays usable exactly as it does for
  a double-click.
- Tests, container-free: offered for a rejected non-publication referral; not offered when the referral was
  rejected for other reasons only; not offered when there is no rejected referral; offered for a multi-reason
  referral that includes `NonPublication`, **alongside ③**, because the caseworker chooses; and the
  availability check hides the counterpart once one is sent, in both directions. Container-backed, in
  `NotificationRepositoryTest` beside the three this review already added: enqueueing ④ for a referral that
  already has ③ answers `0` and leaves one row, the backfill gives every existing row the right group, and a
  template alone in its group still behaves exactly as it did before the re-keying.

- [x] ④ is offered exactly when a rejected non-publication referral exists and its counterpart has not been
      sent, the index refuses the second email of a group rather than a service check doing it, existing rows
      are backfilled, a single-template group behaves as before, and `./gradlew build` green

#### Findings from Task 2 — 2026-09-21

1. **The migration is `V15__notification_exclusion_group.sql`**, confirmed by looking rather than assumed:
   `V14__referral_owner_subjects.sql` is the highest committed, and nothing has taken `V15`. One column
   (`"exclusionGroup" text NOT NULL DEFAULT 'REFERRAL_OUTCOME'`), one index dropped, one created. V12's predicate
   is carried through verbatim and `NotificationRepository.enqueue` repeats the new target and that predicate.

2. **The index is renamed, not replaced under the old name, and that reverses V12's choice on purpose.** V12 kept
   `deedpoll_notification_referral_template_key` because only the predicate moved; here the *key* moves, so the
   name becomes `deedpoll_notification_referral_exclusion_group_key` — an index named after a column it is no
   longer keyed on is a name a reader has to check the definition to distrust. Nothing but
   `NotificationRepositoryTest` names it: `ON CONFLICT` infers a partial index from its target and predicate, not
   from its name.

3. **`findByDeedPollIdAndReferralIdAndTemplate` is gone, replaced by `findBlocking`.** Correction 3 asked for the
   group-scoped lookup and the template finder then had no caller — a repository method nothing uses is worse than
   the diff. `findBlocking` is native so the blocking predicate is the same text as the index's and `enqueue`'s,
   where all three can be held to each other, and the caller reads the template off the returned row to tell a
   second click (200, `enqueued: false`) from the counterpart (409 naming it and its `createdOn`).

4. **`reasonNotApplicable` now takes the newest refusal, which is what let ④'s sentence split in two.**
   `NO_REFUSAL_TO_CONFIRM` (nothing refused at all) and `REFUSAL_NOT_ABOUT_NON_PUBLICATION` (something was, but
   not this) replace notify-3 Task 1's single `NO_NON_PUBLICATION_REFUSAL`. `availability()` and `compose()` each
   read the newest refusal once and pass it to both `cause` and the reason, so the two cannot disagree about which
   referral they are describing.

5. **The backfill is stated as the column default doing two jobs, because a test cannot create a pre-migration
   row.** The container is migrated before any test runs, so "existing rows are backfilled" is proved as the thing
   that backfilled them: an `INSERT` that does not name `"exclusionGroup"` — exactly what an application instance
   predating V15 issues during a rolling deployment — gets `REFERRAL_OUTCOME`, and such a row then blocks the
   counterpart.

6. **A container-free test cannot state the `PERMANENT` exception on its own, and does not pretend to.** The
   service keeps no second definition of "already sent": it asks `findBlocking`, and which rows that returns is
   proved against PostgreSQL in `NotificationRepositoryTest`. What the container-free test states is the seam —
   the question is asked by exclusion group for the referral `cause()` named, an own-template row does not hide a
   template, and a counterpart row hides it with a sentence naming the email and its date.

7. **Totals after this task:** `test` **2,436** and `integration` **10** — 2,446 against the 2,389 baseline, zero
   failures, `./gradlew build` green. Still open for Task 3: the security assertions for ④, `invitesReply` and
   `emailReplyToId`, the ArchUnit re-check and the OpenAPI refresh.

### Task 3: Security, the reply-to, and full validation

- `NotificationSecurityTest` extended rather than duplicated: ④'s personalisation contains **only** its own
  keys, asserted against the record's components; its recipient is `applicantEmail` and never
  `representativeEmail`; and a caseworker cannot reach another case's ④ operations.
- **The reply-to (decisions 9 and 10):** `EmailTemplate` gains an `invitesReply` flag — false for ③, true for
  ④ — and `NotifyProperties` gains `emailReplyToId`. `isProvisioned` becomes per-template: a template that
  invites a reply is unprovisioned without a reply-to, exactly as any template is without a UUID. The send
  passes `emailReplyToId`; ③'s call is unchanged. Tested container-free: ④ reads unprovisioned with no
  reply-to configured while ③ still reads provisioned, and the id reaches the send when it is set.
- **Re-check the ArchUnit rule still holds** — nothing outside the notify package reaches `NotificationClient`.
  It should pass untouched; if it does not, something in this plan took a shortcut.
- Full validation: cold `./gradlew build` with `TESTCONTAINERS_RYUK_DISABLED=true`, checkstyle clean on five
  source sets, `test` and `integration` totals recorded next to this plan's baseline, and the count with
  `INTEGRATION_TESTS_ENABLED=false`. Re-measure `build.gradle`'s exclusion comment if this plan adds a
  container-backed class, as that comment instructs.
- Refresh the OpenAPI output so notify-2 can see the new template without a change of its own, exactly as
  notify-1's contract reached it.

- [x] ④'s keys, recipient and case-scoping stated container-free, ④ reads unprovisioned without a reply-to
      while ③ still reads provisioned, the ArchUnit rule passes untouched, the OpenAPI output is refreshed,
      and `./gradlew build` green from cold with both totals recorded

#### Findings from Task 3 — 2026-09-21

1. **A blank reply-to is dropped by the Notify client rather than refused, so the sender refuses it instead.**
   Read off the 6.2.0 jar: the four-argument `sendEmail` passes `""` for `emailReplyToId`, and the overload they
   both delegate to adds `email_reply_to_id` only when the value is non-null and non-empty. So an environment that
   provisioned ④'s template UUID and forgot the inbox would have sent an email asking the applicant to reply from
   the no-reply sender, successfully, with nothing anywhere saying so — an applicant who appears never to have
   answered. `NotifyEmailSender` now fails such a row `PERMANENT` before any HTTP, which is what makes decision 9's
   "fail loudly" true of the drain and not only of the Email tab.

2. **`isProvisioned` takes the template and answers two questions.** `NotifyProperties.isProvisioned(String)` is
   gone; the one caller passes the template, and the method asks for a real UUID always and for
   `emailReplyToId` only when `invitesReply` is true. That is what keeps a missing inbox from disabling ③, which is
   the property decision 10 asks for and the one a service-wide flag would have lost.

3. **`application.yaml` gained ④'s template key as well as the reply-to.** `templateId` answers the placeholder for
   an absent key, so the map entry is not needed to make the code work — but without it
   `NOTIFY_TEMPLATE_REQUEST_CONFIRMATION_TO_PROCEED` would be an environment variable nothing reads, and notify-0
   has no way to point ④ at a real template. `NotifyConfigurationTest` now asserts the committed map over
   `EmailTemplate.values()` rather than naming one key, so a third template listed nowhere fails there.

4. **The OpenAPI deliverable needed a customiser after all, and correction 7 is why it is a description.** The
   published document named no template at all: `{template}` is a `String` path variable, so springdoc reflected it
   undescribed, and neither key appeared anywhere in `/tmp/openapi-specs.json`. `OpenAPIEmailTemplates` describes
   the token from `EmailTemplate` itself — the convention `OpenAPIQueryParameters` sets for the operator table — so
   a third template appears in the document with no edit. **Deliberately prose and not an `enum`:** an enumerated
   path token becomes a closed union in `deed-poll-admin`'s generated client, which would make a later template
   break the clients that already had the route, and the `GET` is where a tab is meant to learn what it may send.
   The caseworker count is asserted to be unchanged at 33 in the same test.

5. **Nothing in `deed-poll-admin` is touched, and nothing needed to be.** `EmailTypeResponse` carries the template
   as a key on a row rather than a field per template, so the second entry arrives in the existing `availability`
   array; the OpenAPI test now states that shape as well as the count, which is the assertion that would fail if a
   later template tried to widen the response instead.

6. **The ArchUnit rule passed untouched.** `OpenAPIEmailTemplates` is outside the notify package and depends on
   `EmailTemplate`, not on `NotificationClient`, so the one boundary still holds.

7. **Totals after this task, from a cold `./gradlew clean build`:** `test` **2,444** and `integration` **11** —
   2,455 against the 2,389 baseline, zero failures, checkstyle clean on five source sets. With
   `INTEGRATION_TESTS_ENABLED=false`: **1,544**. `build.gradle`'s exclusion comment is re-measured to 900 of 2,444
   across 31 classes; notify-3 added no container-backed class of its own — the 31st is
   `ReferralLifecycleTimelineTest`, which arrived on `master` in between.

---

## Deliberately not in this plan

- **notify-0, the provisioning.** The template UUID and the reply-to address. Without them this email cannot
  send, exactly as notify-1 cannot — that is expected, not a defect.
- **The two *forms for completion* emails (notify-4).** Blocked on document generation, which Notify cannot
  do, and on the attachment-wording decision in taxonomy §5.4.
- **The deferred sixth email**, the Gazette notice PDF. No template was supplied; taxonomy §5.2. notify-6
  added the fifth template by assuming a missing-documents chase.
- **Any change to `deed-poll-admin`.** The Email tab is notify-2's and is designed to need none.
- **Modelling per-reason referral outcomes.** Decision 1 settles this by letting the caseworker choose. If
  that is ever revisited, it is a change to the direction model and a plan of its own.

---

## Flags for Steve — pick these up on return

1. **The reply-to inbox is yours and is assumed in place (Steve, 2026-09-19).** Decisions 9 and 10 build on
   that assumption rather than working around it, and make it visible: ④ reports itself unprovisioned until
   `emailReplyToId` is configured, so a deployment missing it says so in the Email tab instead of quietly
   sending from no-reply. Nothing here blocks on it; this flag is now only a reminder that the inbox must be
   **monitored**, not merely registered — a verified address nobody reads fails in exactly the same way as no
   address at all, and does it invisibly.

2. **Mutual exclusion is decided: the database enforces it (Steve, 2026-09-19).** Decision 3 has the shape.
   You asked for the exclusion-table option, and I have implemented its *intent* — enforcement in data rather
   than a service check — but as an **exclusion group on the enum plus a re-keyed unique index**, not as a
   table of incompatible pairs. The reason is the convention `CaseEmailService.cause()` already relies on: a
   table is data, and data cannot make anyone notice it. Add a fifth template, forget its rows, and it
   silently excludes nothing. A group as a required constructor argument cannot be forgotten, and the index
   still does the enforcing. **If you specifically wanted pairs to be editable without a deploy, say so and I
   will swap it** — but note that adding a template needs a deploy anyway, so runtime-editable pairings buy
   nothing this feature can use.

   One limitation worth knowing: a group is a strict partition, so it cannot express "A excludes B, B
   excludes C, A and C are fine". Nothing in the four-template set needs that — ③④ are one group and ①②
   would be another — but a fifth template that half-overlaps two others would need the pair table after all.

3. **`XX/YY/ZZZZ` is evidence these documents were never proofread as templates.** Decision 5 turns it into
   `decision_date`, but it suggests the supplied `.docx` files should be re-read by whoever authors the Notify
   versions rather than transcribed. Taxonomy §5 lists five more content gaps of the same kind.

4. **The taxonomy says "adult vs minor is chosen by `type`", which is not quite true.** `DeedPollType` has
   four members, and `AdultTransgender` / `MinorTransgender` have to fall on the right side of the `[child's]`
   branch. Decision 6 handles it, but the same gloss appears in the notify-4 rows of §6 — where it picks
   *which template* rather than which sentence, so getting it wrong sends an applicant the wrong email
   entirely. Worth correcting in the taxonomy before notify-4.

5. **Tracking the reply is now its own plan — `notify-3b-confirmation-tracking.md`.** ④ leaves a case waiting
   on an answer, and without somewhere to record that answer the email is a message rather than a process.
   That plan is written and **notify-3 should not go live without it**; the dependency is stated in both.
   What it cannot settle on its own is in its own flags — chiefly that the case state is a derived cascade
   ported from Dynamics, so "awaiting confirmation" cannot become a ninth `DeedPollState`.

6. **①② are mutually exclusive too, and this mechanism does not cover them.** The adult and minor
   forms-for-completion emails should never both go to one applicant, but they are not referral-driven, so
   the partial index — `WHERE "referralId" IS NOT NULL` — does not apply to either. Their exclusivity is per
   *case*, not per referral, and nothing enforces it today. Out of scope here because neither template
   exists yet, but notify-4 inherits the problem and the group column will be sitting there looking like it
   already solved it. It has not.
