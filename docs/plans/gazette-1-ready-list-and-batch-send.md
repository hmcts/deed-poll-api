# Plan: London Gazette publication — ready list, batch send and audit trail

## Overview

A caseworker-admin needs to notice when applications are ready to be sent to the London Gazette, check them,
and send one email carrying a spreadsheet of every application in the batch. This plan is the api half: a
`readyForPublication` case-list filter, a batch send operation that marks every included case as sent and
writes an audit trail, and a history read for that trail. The admin companion,
`deed-poll-admin/docs/plans/16-gazette-1-ready-list-review-and-send.md`, consumes all three.

**This is new surface, not a port.** There is no reference implementation for a Gazette batch send — the
Dynamics original is silent on it — so unlike `domain/deedpoll`, nothing here is checked against
`src/test/resources/reference/`. Treat every decision below as this repo's own, not an inherited one.

## What already exists and needs no change

`publishToLondonGazette` (boolean) and `dateNoticeSentToLondonGazette` (date) are already columns on
`deedpoll_deedpoll`, already readable and queryable through `DeedPollFields`/`FormFields`, and `state` already
queryable. **Do not add a bespoke endpoint for the ready list.** The generic `where` mechanism
(`CaseQuery`/`QueryOperator`) already expresses `publishToLondonGazette:eq:true`,
`dateNoticeSentToLondonGazette:isEmpty` and `state:eq:Enroled` — a caller could compose the ready list today
with three `where` conditions and no api change at all.

What is missing is a **named filter key** rather than three `where` conditions, for the same reason
`awaitingConfirmation` is a filter and not something the case list's caller assembles by hand: it comes with
its own default sort, it is one bookmarkable/testable token instead of three, and it is what makes the ready
count cheap for the admin worklist banner (`total` on an unfiltered-by-`where` request, same shape as the
`awaitingConfirmation` count admin already reads).

## Decisions taken — implement these, do not revisit

### 1. Ready is `publishToLondonGazette:true AND dateNoticeSentToLondonGazette IS NULL AND state:Enroled`

Confirmed with the business as a working assumption, not a ported rule. Add `readyForPublication` as a sixth
member of `ListFilters`, translating to exactly that predicate, default sort
`dateEnroled:asc` (oldest-enrolled-first — the same "longest-waiting first" instinct `awaitingConfirmation`
already applies, oldest cases get published first). Like every other filter, an unrecognised or repeated
`filter` value falls back to `all`; this one is additive and does not change that fallback.

### 2. The batch send is a new, case-driven Notify path — outside `CaseEmailService`/`NotificationOutbox` entirely

`CaseEmailService`, `EmailCause`, `NotificationOutbox` and `DocumentUploads` are built around **one case per
outbox row**: `deedpoll_notification` has a `deedPollId` column that is never null in practice (`EmailCause`
gives either a referral or "the case itself", but always exactly one case), and `DocumentUploads` resolves a
document by reading it back through `DocumentService.downloadDocument(deedPollId, id)` — case-scoped by
design. A Gazette batch email is about many cases and belongs to none of them individually, and forcing it
through that shape would mean either duplicating the row per case (an email that is sent 40 times to the
Gazette for one batch, and 40 competing "which row is the audit trail" answers) or loosening the case-scoping
those classes exist to enforce. Neither is acceptable, so:

- **Do not add a new `EmailTemplate` member.** That enum's four `switch` expressions
  (`cause`, `compose`, `reasonNotApplicable`, `startsConfirmationClock`) and `FormsForCompletion.deedOf`'s
  fifth are all case-shaped questions ("which case is this about", "does it invite a reply", "which deed does
  it carry") that a batch email has no answer to.
- **Do not write to `deedpoll_notification`.** Add a new table (decision 3) instead.
- **Do reuse `NotifyProperties` and `NotificationClient` directly** — the configuration surface
  (`notify.templates.*`, api key, base URL) and the client library, not the outbox/drain machinery. A new
  `notify.templates.gazette-notice` key and a `notify.gazette.recipient` key (the Gazette's own fixed address,
  chart-managed, never caseworker-entered) are provisioned the same way every other template is, and
  `NotifyProperties`/`EmailTemplateTest.Configuration`-style pinning should be extended to include it so a
  missing chart value fails a test rather than failing silently in a deployed pod.

### 3. A new audit table, not a projection of anything else

```sql
create table deedpoll_gazette_notice_batch (
    id uuid primary key,
    sent_at timestamp not null,
    sent_by_id varchar(255) not null,
    sent_by_name varchar(255) not null,
    request_id uuid not null,
    case_count integer not null
);

create unique index deedpoll_gazette_notice_batch_request_id_key
    on deedpoll_gazette_notice_batch (request_id);

create table deedpoll_gazette_notice_batch_case (
    batch_id uuid not null references deedpoll_gazette_notice_batch (id),
    deed_poll_id uuid not null references deedpoll_deedpoll (id),
    reference_number varchar(255) not null,
    primary key (batch_id, deed_poll_id)
);
```

`sent_by_id` is the IDAM subject (never a display name alone, following the same ownership discipline as
`claimedById`/`decidedById`) and `sent_by_name` is attribution only, the same split. `reference_number` is
denormalised onto the join row deliberately: the history read (decision 6) must still show what was sent even
if a case is later amended, and reading it live off `deedpoll_deedpoll` would show today's reference for a
record whose reference could theoretically change, which is not what "what did we send" means. The unique
index on `request_id` is this operation's idempotency guard — see decision 4.

**The spreadsheet itself is not retained.** Confirmed with the business: the audit trail is the batch, its
sender, its timestamp and its case list: enough to answer "who sent what, when", and enough to regenerate the
spreadsheet from the same case refs if it is ever needed again. Do not add a document/blob column here.

### 4. The send is transactional and all-or-nothing, with a client-minted idempotency key

`POST /caseworker/gazette-notices` requires `Idempotency-Key`, following `notify-6`'s admin-idempotent-send
discipline exactly: the caller mints one UUID when the review/confirmation page renders and resubmits the same
key on retry. On a repeat of a key already recorded, answer the existing batch's summary (200, not a new send)
rather than raising `DataIntegrityViolationException` — the same "read before assuming a conflict is a
conflict" shape `CaseEmailService.resolveRefusedInsert` uses, simplified because there is exactly one unique
index to consider here rather than three.

**Everything happens in one transaction, and Notify is called synchronously inside it, not drained
afterwards.** This is a deliberate departure from the outbox pattern, flagged for review rather than asserted
as obviously right:

- The outbox/drain split exists because a per-case email must survive Notify being briefly unreachable
  without blocking the caseworker's request, and because `DocumentUploads` needs to resolve a document per
  row at drain time. Neither reason applies here: there is no per-row document resolution (the spreadsheet is
  built once, for the whole batch, before the call), and this is a rare, deliberate admin action rather than a
  request-path email — the caseworker-admin is already on a "confirm and send" screen and can be told
  synchronously that it failed and to retry, exactly as the existing email check pages do at 409.
- **On any failure — spreadsheet generation, the Notify call, or the database write — the whole batch rolls
  back**: no case gets `dateNoticeSentToLondonGazette` set, no audit row is written, no case note is added.
  A failed send is "nothing happened, try again", never a partially-sent batch.
- **This is the one design choice in this plan most worth revisiting if requirements change** — if the
  Gazette send is ever expected to run unattended (a scheduled batch rather than an admin clicking a button),
  the outbox's retry-on-failure story becomes relevant again and this decision should be reopened.

### 5. Validate the selection is still ready, immediately before sending

The request body is a list of case ids. Before doing anything else, re-read each and confirm it still
satisfies decision 1's predicate. Any case that does not (already sent by a racing admin, or its state
changed) refuses the whole request with 409, naming which ids failed and why — the same "a race is a 409, not
a silent partial success" shape the caseworker referral endpoints already use. **Do not send the batch minus
the stale ones**: the caseworker-admin reviewed a specific set on the confirmation screen, and silently
dropping members of it is a different action from the one they confirmed.

### 6. `GET /caseworker/gazette-notices` is a plain, paged history read

Returns `sentAt`, `sentBy` (display name only — the subject id is never published, same discipline as
`claimedBy`/`decidedBy`), `caseCount`, and the list of `referenceNumber`s per batch, newest first. No filtering
or sorting beyond that; this is an audit list, not a second case-list surface.

### 7. Every batch-sent case gets exactly one case note, in the same transaction as the send

Following the "every email event puts exactly one marker on the Notes grid" rule: a fixed-prose note
("Notice of publication sent to the London Gazette") on each of the batch's cases, written in `send`'s own
transaction (not the drain's `REQUIRES_NEW`, because there is no drain here) so a note the validator refuses
would roll back the whole batch rather than silently succeeding on 39 of 40 cases.

### 8. Both new endpoints are gated on `Role.DEEDPOLL_ADMIN`, refusing everyone else with 403

Not a presentational check. `readyForPublication` as a filter value is visible to anyone who can call the case
list at all (the same as every other filter), so the **list-level gate belongs to admin's own `/judicial`-style
route mounting, not to api** — but the two new operations (batch send, and the history read) are gated here,
because a non-admin caller must not be able to send the Gazette email or read who has, regardless of what
admin's own UI shows them. Refuse with a plain 403 and a logged reason, the same shape `requireRole` uses on
the admin side.

## The spreadsheet

**Column layout is not decided in this plan.** Confirmed with the business as an open item: what the Gazette
actually requires (which fields, in which order, any header format) needs their sign-off before
`GazetteSpreadsheet` is written. Build the generation as an isolated, independently testable component
(`domain/gazette/GazetteSpreadsheet`, given a list of cases and answering the workbook bytes) precisely so the
column mapping can change without touching the transactional send path around it. Do not guess a layout and
ship it; stub it behind a clearly-marked placeholder mapping and flag Task 4 as blocked on the answer if it has
not arrived by then.

## Layers

1. **Filter** — `ListFilters` gains `readyForPublication`; `CaseworkerReadController`'s filter parameter
   description is updated (it is generated into the OpenAPI document, so this alone changes the published
   spec — expected and must be reconciled by the admin companion plan's Task 0).
2. **Schema** — `V26__gazette_notice_batch.sql`; JPA entities `GazetteNoticeBatch`,
   `GazetteNoticeBatchCase`; repositories.
3. **Domain** — `domain/gazette`: `GazetteNoticeService` (validate-then-send), `GazetteSpreadsheet`
   (generation), reusing `NotifyProperties`/`NotificationClient` directly rather than the outbox.
4. **API** — `api/caseworker/dto` (`SendGazetteNoticeRequest`, `GazetteNoticeBatchResponse`,
   `GazetteNoticeHistoryResponse`), `CaseworkerGazetteController` (or a new controller class, kept apart
   from `CaseworkerCaseController`/`CaseworkerEmailController` the way judicial's is kept apart from
   caseworker's), gated on `Role.DEEDPOLL_ADMIN`.
5. **Notify config** — `notify.templates.gazette-notice`, `notify.gazette.recipient` in `application.yaml`
   and the chart, pinned the way `EmailTemplateTest.Configuration` pins the existing templates.
6. **Tests** — unit tests per class, an integration test for the unique-index idempotency behaviour against
   real Postgres (following `NotificationRepositoryTest`'s precedent for why that one needs a real database),
   and the OpenAPI delta reconciled the way every prior admin-consuming plan reconciles it.

## Validation Commands

**These run after every task, and everything here must be green after every task, not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

Measure and record the baseline `test`/`integration` test counts on this branch before Task 1, the same way
`notify-1` and every plan since records theirs, and compare after each task rather than only at the end.

## Tasks

### Task 0: Confirm business inputs before writing any send-path code

Gather sign-off on the three things this plan explicitly does not decide:

- the Gazette spreadsheet's required column layout;
- the Gazette notice email's Notify template wording (a new entry alongside the existing
  `.business_context/notify-templates/` docs and `NOTIFY_PROVISIONING_REQUEST.md`, since this is a new
  template Notify has to provision);
- the ready-list predicate in decision 1, already tentatively agreed but worth restating as a checkpoint
  before it is built against.

This task produces no code. Do not start Task 4 (the send path) until the first two are answered; Tasks 1–3
and 6 do not depend on them and may proceed.

- [ ] Spreadsheet column layout confirmed
- [ ] Notify template content confirmed and provisioning requested
- [ ] Ready-list predicate re-confirmed

### Task 1: `readyForPublication` filter

Add the filter, its default sort, and its description text. Test: the predicate matches exactly the three
conditions of decision 1, the default sort, and that every other filter's behaviour is unchanged.

- [ ] Filter added, tests green, OpenAPI delta is exactly the filter's description text

### Task 2: Audit schema

`V26__gazette_notice_batch.sql`, entities, repositories. Test the unique index on `request_id` refuses a
repeat at the database level, not only in application code.

- [ ] Migration, entities and repositories added; constraint-level test green

### Task 3: Role gate and history read

`GET /caseworker/gazette-notices`, gated, paged, newest first. Test: 403 for a non-admin token, correct
shape and ordering for an admin one, no subject id anywhere in the response.

- [ ] History endpoint implemented and gated; no owner id on the wire

### Task 4: Batch send

**Blocked on Task 0's first two items.** `GazetteSpreadsheet`, `GazetteNoticeService`, the controller
operation, the case note, the idempotency behaviour (decision 4), the re-validation (decision 5), the
all-or-nothing transaction (decision 4). Test: a successful send sets the date and note on every case and
writes one audit row; a stale selection 409s naming the stale ids and changes nothing; a repeated
`Idempotency-Key` returns the original batch's summary rather than sending twice; a Notify failure rolls back
every write.

- [ ] Batch send implemented; all four test scenarios above green

### Task 5: Full validation and OpenAPI regeneration

Regenerate the OpenAPI document. Confirm the delta is exactly: the filter description text, two new
operations, and their new schemas — nothing pre-existing altered. Record the final test counts next to the
Task-0 baseline.

- [ ] `./gradlew build` green; OpenAPI delta matches exactly; final counts recorded
