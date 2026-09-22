# notify-3b — tracking the confirmation a caseworker asked for

## Overview

notify-3 sends *Request confirmation to proceed*: the applicant is asked to reply saying whether they still
want the deed enrolled now the judge has refused their non-publication request. **Nothing currently records
that a case is waiting for that reply, and nothing records the reply when it arrives.** This plan adds both,
plus the list that stops a waiting case being forgotten.

Without it, ④ is a message rather than a process — a caseworker sends it, the applicant answers into a shared
inbox, and the only trace is an email thread. **notify-3 should not go live without this plan**, and its flag
5 says so.

Read first: notify-3's decisions, `CaseState`, `ReferralService.reclaimStaleClaims` (the sweep precedent), and
`Referral`'s direction columns from `V8`.

**The one thing that shapes everything here:** case state is *derived*, not stored.
`CaseState.deriveState` is a verbatim port of `SetStatesbasedonDatescompleted.xaml` and picks a
`DeedPollState` from which date columns are populated. `DeedPollState`'s member order is not cosmetic either —
each position is the Dynamics 365 option-set integer, and those integers are what the Dataverse exports hold
and what a re-import would expect. So "awaiting confirmation" **cannot** become a ninth state, and the
cascade **cannot** grow a branch. This plan therefore models the wait where it actually lives — on the
referral — and surfaces it alongside the state rather than inside it.

---

## Decisions taken — implement these, do not revisit

1. **No new `DeedPollState` member and no new branch in the cascade.** For the reasons above: the enum's
   order encodes Dynamics option-set integers and `CaseState` is a verbatim port. A ninth member would invent
   a Dataverse value this service is not entitled to mint, which is the same rule that keeps the CCD case type
   a placeholder. Anyone who finds themselves editing `deriveState` has taken a wrong turn.

2. **The wait is recorded on the referral, not on the case.** The non-publication request, the judge's
   refusal and the direction all live on `Referral`; the question "did they confirm?" is about that referral's
   outcome. Putting it on the case would also make a second referral on the same case ambiguous. Four new
   columns on `deedpoll_referral`: `confirmationRequestedAt`, `confirmationOutcome`, `confirmationReceivedAt`,
   `confirmationRecordedBy`.

3. **A new Postgres enum `"ConfirmationOutcome"` — `Confirmed | Withdrawn`.** New rather than reused, and
   minting it is legitimate where minting a `DeedPollState` member is not: `NotificationState` in `V11` is the
   precedent — an internal enum this service owns end to end, not a ported Dynamics option set. `Confirmed`
   means the applicant still wants enrolment; `Withdrawn` means they do not and want a refund.
   `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`, as every enum column here has.

4. **The clock starts when the caseworker sends ④, in the same transaction as the outbox row.** Not when
   Notify accepts it. The outbox's whole design is that the row is written inside the caseworker's
   transaction, and hanging this off delivery would make a case's process state depend on an asynchronous
   outcome that may still be retrying. **The consequence is real and worth naming:** if the send then fails
   terminally, the case is waiting for a reply to an email nobody received. That is not fixed here — it is
   exactly what notify-1's flag 8 is about, and it is another reason to resolve that flag.

5. **Recording the outcome is its own operation, not a case edit.**
   `POST /caseworker/cases/{caseId}/referrals/{referralId}/confirmation` taking `{ outcome, note }`. Scoped by
   case as every referral operation here is. It is idempotent-by-refusal: recording an outcome twice answers
   409 naming what was already recorded and when, because the second caseworker needs to know the first
   already acted rather than silently overwriting them.

6. **Recording an outcome does not move the case on by itself.** `Confirmed` records that the applicant
   wants to proceed; it does not set `dateEnroled`, clear `dateReturned` or otherwise reach into the date
   cascade. The caseworker then does whatever the process requires through the existing case edit, which is
   audited as their action rather than as a side effect of this one. **See flag 1 — what those dates should
   become is a business question this plan deliberately does not answer.**

7. **Overdue is a query, not a sweep.** `ReferralService.reclaimStaleClaims` sweeps because a stale *claim*
   has to be mutated to release it. Nothing here needs mutating: a case is overdue if
   `confirmationRequestedAt` is old and `confirmationOutcome` is null, which is a `WHERE` clause. So there is
   no sweep, no scheduler and no new mutating read — this plan adds strictly less machinery than the outbox
   did, and the absence is deliberate rather than an omission.

8. **The waiting list is a filter on the existing caseworker case list, not a new screen's endpoint.** The
   repo already resolves filter keys server-side and `deed-poll-admin`'s convention is to ask api to filter
   rather than fetch-and-narrow. So this is a filter key plus a sort, and the days-waiting figure comes back
   on the row. A bespoke endpoint would be a second way to ask the same question.

9. **No chasing email.** Taxonomy §5.1 records that no template was supplied for chasing an applicant, and
   this plan does not invent wording. The list makes a forgotten case visible to a caseworker, who chases by
   whatever means they use today. When a chase template exists it is a notify plan, not this one.

---

## Constraints

This repo's standing constraints apply — Java 21, `-Werror`, checkstyle on five source sets,
`TESTCONTAINERS_RYUK_DISABLED=true`, mixed-case quoted columns, `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` on any
Postgres enum, DTOs never entities, a controller test boots the whole context, never edit
`src/test/resources/reference/`. Additional:

- **Do not edit a committed Flyway migration, and do not assume a number.** `V11__notifications.sql` is
  committed and notify-1's task 7 takes `V12`. Take the next free number when you start and say so in the
  task notes, exactly as notify-1's task 2 had to.
- **Do not touch `CaseState` or `DeedPollState`.** Decision 1. If something appears to require it, stop and
  report it.
- **Container-free where CI runs it.** The outcome rules, the refusal on a second recording and the
  days-waiting arithmetic need no container. The new columns, the enum round trip and the filter's SQL get
  container-backed repository tests, CI-excluded and accepted — the same split notify-1 used.
- **notify-3 must be merged first.** This plan records the consequence of an email notify-3 introduces.

---

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

**Measure the baseline on this branch before Task 1** and record it against the final count in Task 4.

**A worker or container startup timeout is an environment failure, not a code failure** — re-run before
investigating.

---

## Tasks

### Task 1: The columns and the enum

- The migration: `"ConfirmationOutcome"` as a Postgres enum, and the four columns of decision 2 on
  `deedpoll_referral`, all nullable — every existing referral predates this feature and none of them was
  waiting for anything.
- A partial index on `("confirmationRequestedAt")` where `confirmationOutcome IS NULL`, because the only
  question asked of these columns is "what is still outstanding" and that is the half of the table worth
  indexing.
- `Referral` gains the four fields; `EntityMappingTest` and `SchemaConformanceTest` cover them as they cover
  every other column.
- Tests, container-backed: the enum round trips, the columns persist and read back, and an existing referral
  with all four null is still readable.

- [x] The migration applies cleanly on the next free number with the number recorded, Hibernate validates
      against it, the enum round trips, and `./gradlew build` green

**Task 1 notes.**

- **The number is `V16`** — `V16__referral_confirmation.sql`. `V15__notification_exclusion_group.sql` was
  the highest committed when this task started, confirmed by listing `src/main/resources/db/migration`
  rather than assumed. Nothing is left reserved: Flyway runs with out-of-order disabled.
- **Baseline, measured on this branch before any change here:** `test` 2,458 tests across 314 classes,
  `integration` 11 tests across 3 classes, `./gradlew build` green. After this task: `test` **2,461** across
  314 classes (`+3`), `integration` unchanged at 11.
- **No new container-backed class**, so `build.gradle`'s exclusion comment is not re-measured here — the
  three new tests extend `EntityMappingTest`, which already carries the `requires-docker` tag by
  inheritance. Task 4 re-measures the totals.
- `confirmationRecordedBy` is `text` with **no matching subject column**, unlike V14's
  `claimedById`/`decidedById`. Nothing compares it: recording an outcome is a caseworker act on a case the
  caller can already read, so no ownership check turns on who did it and a subject would be an identifier
  stored for nobody to use. Decision 2's four columns, exactly four.
- `SchemaConformanceTest` needed no edit and that is not an omission: it is forward-only against
  `reference/deed-poll.prisma`, and `deedpoll_referral` has no reference model at all (V6 says so). Its
  coverage of these columns is that it runs Flyway end to end against a throwaway Postgres, so V16 applying
  cleanly in sequence is asserted there. `ddl-auto: validate` in `EntityMappingTest` is what holds the
  entity to the table.
- The partial index is asserted directly (`awaitingConfirmationIndexIsPartial`) because `ddl-auto: validate`
  does not look at indexes at all — an index created on the wrong column, or without the
  `WHERE "confirmationOutcome" IS NULL` predicate, would be silent in every other test.
- The enum round trip is parameterized over `ConfirmationOutcome.values()` rather than written as a
  `@TestFactory` like the reference-enum factory beside it: a dynamic test's executable runs after the
  factory method returns, which is outside the class's `@Transactional` boundary, so it would have no
  transaction to persist in.

### Task 2: Starting the clock, and recording the answer

Read first: notify-3's Task 2, and `ReferralService`'s existing write operations for the audit idiom.

- Sending ④ stamps `confirmationRequestedAt` on the referral **in the same transaction as the outbox row**
  (decision 4). Nothing else about the send changes.
- `POST /caseworker/cases/{caseId}/referrals/{referralId}/confirmation` with `{ outcome, note }` records
  `confirmationOutcome`, `confirmationReceivedAt` and `confirmationRecordedBy`, and appends the note to the
  referral's notes the way every other caseworker action here does.
- **Refusals, each naming what it is about:** 409 if an outcome is already recorded, naming it and when; 409
  if this referral was never asked to confirm (`confirmationRequestedAt` null), because recording an answer
  nobody asked for is a mis-click on the wrong case rather than a valid state; 404 if the referral is not on
  that case, through the existing case-scoped lookup.
- Tests, container-free: both outcomes recorded; the double-recording refusal; the never-asked refusal; the
  note reaching the referral; and that the recorded author is the caseworker rather than the service account.
  Container-backed: the send and the stamp commit together, and neither survives the other's rollback.

- [ ] Sending ④ starts the clock in the same transaction as the outbox row, both outcomes record with their
      author and note, recording twice and recording unasked are each refused by name, and `./gradlew build`
      green

### Task 3: Making a waiting case impossible to forget

- A filter key on the caseworker case list for "awaiting confirmation", resolved server-side (decision 8),
  with `confirmationRequestedAt` ascending so the longest wait is first.
- The row carries how long it has been waiting, computed from `confirmationRequestedAt` — a count of days, so
  a caseworker can see "43 days" without doing arithmetic.
- The case read exposes `awaitingConfirmationSince` alongside the derived state, **not inside it**
  (decision 1), so a client can show "Returned — awaiting confirmation since 3 May" without the state itself
  meaning two things.
- Tests, container-free: the days-waiting arithmetic, including the day it was sent reading as 0 rather than
  1. Container-backed: the filter returns only outstanding referrals' cases, ordered longest-waiting first,
  and a case whose outcome has been recorded drops out.

- [ ] The list filters and sorts by how long a case has waited, the row says how many days, the case read
      surfaces the wait beside the state without changing it, and `./gradlew build` green

### Task 4: Security, scoping and full validation

- Case-scoping asserted rather than assumed, as api-1's rule requires: a caseworker cannot record an outcome
  against another case's referral, and the filter never returns a case the caller could not otherwise read.
- The new columns appear in no DTO that is not a caseworker DTO, and `confirmationRecordedBy` is never
  client-supplied — it comes from the token, as every author here does.
- Full validation: cold `./gradlew build`, checkstyle clean on five source sets, `test` and `integration`
  totals recorded next to this plan's baseline, the `INTEGRATION_TESTS_ENABLED=false` figure, and
  `build.gradle`'s exclusion comment re-measured if this plan adds a container-backed class.
- Refresh the OpenAPI output so `deed-poll-admin` can build its screen against a published contract.

- [ ] Case-scoping and authorship proven rather than assumed, the OpenAPI output refreshed, and
      `./gradlew build` green from cold with both totals recorded

---

## Deliberately not in this plan

- **The admin screen.** `deed-poll-admin` needs a way to show the waiting list and record an outcome. That is
  a `deed-poll-admin` plan — a notify-2 addendum or its own — and it should consume this plan's published
  OpenAPI rather than being designed against this file.
- **Chasing the applicant.** Decision 9: no template exists and this plan does not invent wording.
- **Reading the reply automatically.** The applicant replies to a human-monitored inbox. Parsing that inbox
  is a different feature with a different risk profile, and nothing here assumes it.
- **Anything about the date cascade.** Decision 1 and decision 6; see flag 1.

---

## Flags for Steve — pick these up on return

1. **What should happen to the dates when an applicant confirms or withdraws?** This is the one business
   question the plan cannot answer for itself. A case that got ④ is in **Returned**, which the cascade derives
   from `dateReturned`. If the applicant confirms, the case has to leave Returned and head for enrolment —
   which means clearing `dateReturned`, and the cascade has a hard rule that `dateReturned` and `dateEnroled`
   are contradictory terminal outcomes. If they withdraw, Returned is presumably right and a refund follows.
   Decision 6 deliberately leaves the dates to the caseworker so this plan does not encode a guess, but
   **somebody has to decide what a caseworker is supposed to do**, or the feature records an answer and then
   leaves them where they started.

2. **There is no terminal state for "withdrawn and refunded".** `DeedPollState` has no `Withdrawn` or
   `Cancelled`, and decision 1 forbids adding one. So a withdrawn case looks exactly like any other Returned
   case, and `confirmationOutcome` is the only thing distinguishing them. That is workable — it is why the
   column exists — but if the business expects to report on withdrawals, that reporting reads a referral
   column rather than a case state, and it is worth knowing that before somebody asks for the number.

3. **A case can wait forever and this plan only makes that visible.** There is no deadline, no automatic
   closure and no escalation — a caseworker has to look at the list. That is the right first step and
   deliberately less machinery than a rules engine, but if the business expects "after 8 weeks we close it",
   that is a rule nobody has stated and it is not built.

4. **The clock can start on an email that never arrived.** Decision 4 explains the trade. Until notify-1's
   flag 8 is resolved, a terminally failed ④ leaves a case sitting in the waiting list with nobody able to see
   why no reply is coming. The two flags should be closed together.
