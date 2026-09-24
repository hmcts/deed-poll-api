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

- [x] Sending ④ starts the clock in the same transaction as the outbox row, both outcomes record with their
      author and note, recording twice and recording unasked are each refused by name, and `./gradlew build`
      green

**Task 2 notes.**

- **The stamp lives in `CaseEmailService.send`**, not in a listener and not on the drain. One `AuditClock.now()`
  is read for the row and the stamp, so the outbox row's `createdOn` and `confirmationRequestedAt` are the same
  instant rather than two readings that could straddle a tick — asserted both ways round
  (`CaseEmailServiceTest`, and over HTTP against the committed row).
- **`startConfirmationClock` is an exhaustive switch over `EmailTemplate`, not an `if`.** A third template has to
  say whether it starts a clock instead of silently inheriting ④'s answer. ③ starts none, and that is asserted:
  it tells the applicant a decision and asks nothing, so a case recorded as waiting for a reply to it would wait
  for ever.
- **Only a fresh enqueue stamps.** A second send writes no row (the unique index refuses it) and must not move a
  clock already running, or a double-click would reset the wait a caseworker is being reminded of. A replacement
  after a terminally failed send *does* re-stamp, which is right: the applicant has been asked again.
- **The note is a case note, not an append to `Referral.notes`.** That column is the caseworker's text from when
  the referral was raised, and appending would rewrite what went to the judiciary and break the cap
  `MAX_NOTES_LENGTH` exists for. `ReferralNotes.confirmationRecorded` composes it, `MAX_CONFIRMATION_NOTE_LENGTH`
  (1,000, the same number and for the same reason as the referral's) keeps the worst case inside
  `NoteForm.MAX_NOTE_LENGTH`, and `ReferralNotesTest` pins that arithmetic for both outcomes.
- **The two 409s are checked already-recorded first.** Only their order needed deciding: an outcome cannot exist
  without a request date through any path this service has, so the order matters only for a row repaired by hand,
  and the more specific fact wins. Both refusals compose without throwing on a null date, because a refusal that
  failed while explaining itself would answer 500 for the state it exists to explain.
- **`Withdrawn` is spelled out in the note.** It is also a `ReferralState` — the office pulling a referral back —
  and here it is the *applicant* withdrawing with a refund behind it, so a grid entry reading only "Withdrawn"
  would be read as the other event.
- **Recording is ungated on `ReferralState`**, and that is deliberate rather than an omission: a caseworker may
  close or withdraw the referral before the reply arrives, and the applicant still answered a question the office
  asked. Parameterized over every state.
- **Two files this plan did not name had to move for the build to stay green**, both because the route is new:
  `OpenAPIWriteBodies` gains `RecordConfirmationRequest` (a keyed body springdoc otherwise publishes as `{}`, which
  `OpenAPIPublisherTest.documentsEveryKeyedWriteBody` scans the classpath for), and `OpenAPIPublisherTest`'s
  caseworker path list and operation count go from 33 to 34. Task 4 re-verifies the published output.
- **Totals after this task:** `test` **2,510** across 319 classes (`+49` tests on Task 1's 2,461/314; the 5 new
  "classes" are `@Nested` groups), `integration` unchanged at 11 across 3, `./gradlew build` green. **No new
  container-backed top-level class** — the 12 new container-backed tests are nested inside
  `CaseworkerReferralControllerTest` and `CaseworkerEmailControllerTest`, which already carry `requires-docker` by
  inheritance — so `build.gradle`'s excluded-count comment is left for Task 4 to re-measure.
- `CaseworkerEmailControllerTest` now registers ④'s template id **and** `notify.email-reply-to-id`: without a
  reply-to the sender refuses ④ permanently, so the clock could only have been asserted through a send that never
  reached the stub. The terminal-failure case is asserted too — the stamp survives it, which is decision 4's named
  consequence and notify-1's flag 8.

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

- [x] The list filters and sorts by how long a case has waited, the row says how many days, the case read
      surfaces the wait beside the state without changing it, and `./gradlew build` green

**Task 3 notes.**

- **This task needed a migration the plan did not name: `V17__case_awaiting_confirmation.sql`**, one nullable
  `"awaitingConfirmationSince"` column on `deedpoll_deedpoll` plus a partial index on the rows that are waiting.
  V16 was the highest committed when this started, confirmed by listing `src/main/resources/db/migration`.
  **Why a column at all, when decision 7 says the wait is a `WHERE` clause:** it still is — the clause just has
  to be askable of the *case* table. `V9__current_referral_state.sql` settled this exact question for
  `currentReferralState` and its argument holds unchanged: the case list filters through `root.get(name)` on one
  entity, orders through a Spring Data `Sort` over a root property, and maps each row from the case the page
  returned. A correlated subquery cannot carry the `ORDER BY` a paged query needs, a join multiplies a case by
  its referrals and makes the page disagree with its own count, and reading each row's referrals lazily is one
  query per row. So the referral's answer is copied onto the case.
- **Kept in step by a recompute from truth, never a push.** `ConfirmationWait.earliestOutstanding` (container-free,
  in `domain/referrals`) is the rule; `ReferralService.refreshAwaitingConfirmation` pairs it with
  `ReferralRepository.findOutstandingConfirmationRequestsByDeedPollId` and
  `DeedPollRepository.writeAwaitingConfirmationSince`. Both writes call it: `CaseEmailService.send` when ④ starts
  the clock, `recordConfirmation` when the answer ends it. That method is **public** — the only reason — because
  the two sites are in two packages and the alternative was the same three lines copied into `notify`, which is
  how a read-model and its truth drift. **Earliest, not latest:** a referral can be asked and then closed and a
  second asked (recording is ungated on referral state), and the case has waited since the first — asserted over
  HTTP in `CaseworkerReferralControllerTest`, where answering the second leaves the column at the first date.
- **The filter is a sixth `ListFilter`, appended.** `StateWhere` is now `FilterWhere` and permits a third shape,
  `AwaitingConfirmation` — the one tab whose criterion is not about `state` at all — so `CaseSpecifications.inState`
  became `criterion`. The ported five keys stay in front of it, and `ListFiltersTest`'s ported key assertion says
  so rather than being loosened.
- **The sort is the tab's default, not an override.** `ListFilter` gains `Optional<CaseSort> defaultSort` (with a
  three-arg constructor so the ported five are untouched) and `CaseSort.parse(String, CaseSort)` takes the
  fallback. A caller who names `sort` still wins: a tab that forced its order would make its own column headers
  unclickable. `ListFilters.LONGEST_WAIT_FIRST` is ascending on the stamp, which is descending in days waited.
- **Four files this plan did not name had to change, all because a new case column has to be declared:**
  `DeedPollFields` gains a reader (readable, never writable — `CaseSort.sortableFields()` admits only names that
  are readable, and `CaseDetailResponseFieldsTest` requires the detail DTO to publish exactly the readable set);
  `CaseDetailResponse` and `SavedCaseResponse` gain `awaitingConfirmationSince`; and the shared test fixture
  `CaseResponseFields` gains it to its unwritable set, which is now nine rather than eight. It is deliberately
  **not** queryable — not a `FormFields` field — because the tab is how it is asked about.
- **The row publishes `daysAwaitingConfirmation`, not the date**, and the case read publishes the date, not the
  count: the row answers "how long", the case answers "since when". The count is **not** in `ComputedFields`,
  which is a port of the reference's own derived pair; it is computed in `CaseSummaryResponse` from one reading of
  the clock per page, so two rows cannot straddle a midnight and disagree.
- **Totals after this task:** `test` **2,537** across 322 classes (`+27` on Task 2's 2,510/319), `integration`
  unchanged at 11 across 3, `./gradlew build` green. The one new top-level test class, `ConfirmationWaitTest`, is
  **container-free**, so `build.gradle`'s excluded-count comment is unchanged; every new container-backed test is
  nested in a class that already carries `requires-docker`.
- `OpenAPIQueryParameters` now says in the `sort` description that the default depends on the filter. Nothing
  else about the published contract moved: no route, no operation count.

### Task 4: Security, scoping and full validation

- Case-scoping asserted rather than assumed, as api-1's rule requires: a caseworker cannot record an outcome
  against another case's referral, and the filter never returns a case the caller could not otherwise read.
- The new columns appear in no DTO that is not a caseworker DTO, and `confirmationRecordedBy` is never
  client-supplied — it comes from the token, as every author here does.
- Full validation: cold `./gradlew build`, checkstyle clean on five source sets, `test` and `integration`
  totals recorded next to this plan's baseline, the `INTEGRATION_TESTS_ENABLED=false` figure, and
  `build.gradle`'s exclusion comment re-measured if this plan adds a container-backed class.
- Refresh the OpenAPI output so `deed-poll-admin` can build its screen against a published contract.

- [x] Case-scoping and authorship proven rather than assumed, the OpenAPI output refreshed, and
      `./gradlew build` green from cold with both totals recorded

**Task 4 notes.**

- **The whole statement is one new container-free class, `ConfirmationSecurityTest`** (17 tests, four `@Nested`
  groups), beside `ReferralSecurityTest` and `SavedQuerySecurityTest` and for their reason: the HTTP versions of
  these rules live in `CaseworkerReferralControllerTest`, which CI excludes for want of Docker, so the rules that
  must not disappear with it are restated where CI runs them. Nothing here duplicates a rule for its own sake —
  each is either new or is the CI-runnable half of one asserted over HTTP.
- **The cross-case half that is genuinely new is the read model.** A recording now writes a column on the *case*
  as well as the referral, so a cross-case attempt has a second row it must not reach: the refusal is asserted to
  call neither `findOutstandingConfirmationRequestsByDeedPollId` nor `writeAwaitingConfirmationSince` on either
  case, and a successful recording is asserted to name only the case in the path. A refused recording that had
  recomputed the case it was *addressed at* would move a waiting list entry on the strength of a referral that
  case does not own.
- **A cross-case attempt on an already-answered referral is 404 and not 409**, and the message is asserted not to
  carry the stored outcome or its author: the scope answers before the conflict, so a caller pointing another case
  at the id learns the referral is not there rather than that somebody already answered it and when.
- **Disclosure is asserted in three directions, and the third is what keeps the first two honest.** No judicial or
  citizen class depends on any `Confirmation*` type (ArchUnit); no field declared in either package has a name
  matching `(?i).*confirmation.*` — which is the rule a dependency check cannot make, because a `String` called
  `confirmationRecordedBy` names no type this service owns; and the caseworker DTOs that *do* publish the columns
  are named, so a pair of rules reading "nobody publishes this" cannot stay green for a service that publishes it
  nowhere. The regex is asserted to match the three real field names before the rule that uses it, because a
  pattern matching nothing would pass whatever a judicial DTO grew.
- **The waiting list is proven to narrow rather than widen, four ways**: its criterion is one `isNotNull` on one
  column of the case root with *no other interaction* with the root or the query — asserted through a mocked
  criteria API, so a join (which would multiply a case by its referrals) or a correlated subquery (which would
  read referrals the page's case does not own) fails here; the unpaged named finder takes no argument at all, so
  nothing a caller sends chooses which cases come back; the recompute's read carries the parent case id and
  projects `LocalDateTime`, never a `Referral`; and the column is readable but **not** writable and **not**
  queryable, since a caller who could patch it could leave the waiting list without anybody having answered.
- **One main-source change, and it is the OpenAPI one the plan asked for.** `filter` was the one list parameter
  springdoc reflected correctly and therefore the one `OpenAPIQueryParameters` left alone — correct as a type and
  silent as a contract, because the tab keys are a list in the domain rather than an enum. So the published
  document named `awaitingConfirmation` only in the `sort` description, and `deed-poll-admin` could not have
  discovered the tab it is meant to render. `describeFilter` now sets that parameter's description from
  `ListFilters.filterTabs()` — described in place rather than added, because a second parameter of the same name
  would publish it twice — and as prose rather than an `enum`, because an unrecognised key falls back to `all`
  rather than being refused and a validating client that rejected one would refuse a request the service answers.
- **`OpenAPIPublisherTest` gains `documentsTheConfirmationContract`** (the 12th integration test): the route
  answers `ReferralResponse`; the body publishes the outcome vocabulary from `ConfirmationOutcome`, the note's cap
  from `ReferralSchemas`, `outcome` as its only requirement, and `additionalProperties: true`; the body publishes
  **no** `confirmationRecordedBy` or `confirmationReceivedAt`, because a document that offered them would have
  clients sending an attribution the service strips; the answer publishes all four columns; the row publishes
  `daysAwaitingConfirmation` as an integer and the case read publishes `awaitingConfirmationSince` as a
  `date-time`, neither carrying the other's; and no judicial schema publishes anything matching "confirmation".
  The filter assertion is in `documentsTheCaseListQueryParameters` beside the operator, sort and search ones, and
  reads keys *and* labels out of `ListFilters` so a sixth tab is published without either file being edited.
- **No route, operation count or schema name moved**, so the caseworker surface is still 34 operations across 21
  paths and the judicial surface still 5 — both asserted, unchanged, on this task's run. The published spec is
  regenerated by `./gradlew integration` writing `/tmp/openapi-specs.json`, which is the pipeline's own contract.
- **Full validation, from cold** (`./gradlew clean build`, then `./gradlew build`; `TESTCONTAINERS_RYUK_DISABLED=true`):
  green, checkstyle clean on all five source sets. `test` **2,554** tests across 326 classes — `+17` on Task 3's
  2,537/322, the 4 new "classes" being this class's `@Nested` groups — and `integration` **12** across 3 (`+1`).
  Against this plan's baseline of 2,458/11: **+96 tests** and **+1 integration test** across the four tasks.
- **`INTEGRATION_TESTS_ENABLED=false`: 1,619 tests run**, so 935 of the 2,554 are container-backed. `build.gradle`'s
  exclusion comment is re-measured to that pair even though this plan **added no container-backed class** — 31
  classes need Docker, exactly as before (30 by inheritance from `AbstractPostgresContainerTest`, plus
  `SchemaConformanceTest`) — because the comment's figures are a measurement and a stale one invites the next
  reader to trust it rather than re-measure.

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

   **Correction, 2026-09-24 (`email-case-notes`, appended not rewritten):** the *visibility* half is closed. A
   terminally failed email now writes a case note, so the timeline says why no reply is coming. What remains is
   the business half — the clock is not stopped and the case stays on the `awaitingConfirmation` list — which is
   a decision about what should then happen rather than something the timeline can fix. See
   [`email-case-notes.md`](email-case-notes.md), flag 2.
