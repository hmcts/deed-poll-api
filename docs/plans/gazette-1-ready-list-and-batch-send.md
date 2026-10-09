# Plan: London Gazette publication — ready list, batch send and audit trail

## Overview

A caseworker-admin needs to notice when applications are ready to be sent to the London Gazette, check them,
and send one email with adult/minor spreadsheet download links for the batch. This plan is the api half: a
`readyForPublication` case-list filter, a batch send operation that marks every included case as sent and
writes an audit trail, and a history read for that trail. The admin companion,
`deed-poll-admin/docs/plans/16-gazette-1-ready-list-review-and-send.md`, consumes all three.

**This is new surface, not a port.** There is no reference implementation for a Gazette batch send — the
Dynamics original is silent on it — so unlike `domain/deedpoll`, nothing here is checked against
`src/test/resources/reference/`. Treat every decision below as this repo's own, not an inherited one.

## Current handoff — 9 October 2026

The ready list/history, production spreadsheet generator and persistent recipient
settings are merged (PRs #80, #87, #88 and #89). The internal
[request ledger and guards](gazette-1c-request-ledger-and-guards.md) are now implemented
on the task 4a branch. Batch sending remains unavailable.
Use [Gazette batch send and recovery contract](gazette-1b-send-and-recovery-contract.md)
for the next slices. It replaces the former single-transaction email design:
acceptance by Notify cannot be undone by rolling back a local transaction.

The contract specifies durable request states, one delivery attempt, reservations,
original-recipient snapshots, status reads and complete frozen-reference pagination.
An uncertain outcome stops further sends for those cases pending investigation.
The old foundation validation files retain their historical evidence. Task 0 still
records the outstanding predicate reconfirmation and environment provisioning;
no further spreadsheet layout decision is needed to implement the next ledger slice.

## Spreadsheet implementation slice — 8 October 2026

Following the supplied templates and Steve's instruction to proceed with the mapping
and generator recommendation, `GazetteSpreadsheet` implements production workbook
generation independently of Task 4's send protocol. The 27 adult / 21 minor visible
columns and blank-value rule are implemented using cleaned tracked resources.
Full display references, existing English labels and UTC calendar dates displayed
`dd/mm/yyyy` are the implementation choices. Adult/Adult Transgender and
Minor/Minor Transgender form the two files. Relinquishment and Unknown/missing
classification refuse the whole selection. Ready-list handling of Unknown/null is
unchanged. Input order is retained within each file; absent groups produce no file.

See [template provenance and mapping](../../src/main/resources/gazette-templates/README.md).
This supersedes the undecided/placeholder spreadsheet paragraphs below for this
slice only. It supplies generation to the new durable send protocol; it provides no
transactional email guarantee. Current cross-repo decisions also require an admin-editable API
recipient setting and three Notify variants (adult, minor, mixed), superseding the
original fixed chart recipient and single-template proposal below.

- [x] Implement the production generator and cleaned runtime templates.
- [x] Validate the generator and full API build; see [validation evidence](gazette-spreadsheet-validation.md).
- [ ] Complete Task 4's independent send/recovery design and implementation.

## Recipient settings slice — 8 October 2026

`GET` and `PUT /caseworker/gazette-settings` now provide the admin-editable
recipient contract, independently of batch sending. V33 seeds an unset singleton
at revision 0 and adds its change audit. The domain requires `DEEDPOLL_ADMIN` for
both operations and takes change attribution from the authenticated caller.
Updates validate one address, lock the singleton and require the original revision;
stale edits return 409, including concurrent initial edits. A current unchanged
address is a no-op. Both the new settings and their audit row commit atomically.
OpenAPI publishes the exact keyed request and response, without caller subjects.

- [x] Implement persistent settings, validation, role gate and attribution.
- [x] Publish the read/update contract for the admin editor.
- [ ] Integrate the settings revision into send confirmation and snapshot the
  accepted recipient for retries and batch audit. Settings writes send no email.

See [recipient settings validation](gazette-recipient-settings-validation.md).
This supersedes the original fixed chart recipient below; Notify provisioning,
review/selection and the send/recovery contract remain outstanding.

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

Confirmed with the business as a working assumption, not a ported rule. Add `readyForPublication` as a seventh
member of `ListFilters`, translating to that predicate with the exclusion below, default sort
`dateEnroled:asc` (oldest-enrolled-first — the same "longest-waiting first" instinct `awaitingConfirmation`
already applies, oldest cases get published first). Like every other filter, an unrecognised or repeated
`filter` value falls back to `all`; this one is additive and does not change that fallback.

Decision amendment (2026-10-08, following Steve's 7 October instruction): Deed of
Relinquishment stays outside this process. Exclude `DeedOfRelinquishment` from the
ready list and its total, and from future spreadsheet generation and batch sending.
Other types, including Unknown and unset types, keep their current read behaviour;
their send eligibility remains a separate decision. A selected case reclassified as
relinquishment must cause a whole-request refusal before any email is sent, never
silent removal from the batch. The current change implements the read exclusion;
the generator now enforces this rule; the send endpoint remains outstanding.

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
- **Reuse `NotifyProperties` and `NotificationClient`** for the provider boundary,
  with distinct adult-only, minor-only and mixed Gazette template IDs. Recipient
  settings are API-owned through the merged admin editor; a fixed chart recipient
  is superseded. Freeze the confirmed recipient/revision and relevant Notify
  configuration on admission, as specified in the send/recovery contract.

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
    deed_poll_id text not null references deedpoll_deedpoll (id),
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

Schema correction, 6 October 2026: `deed_poll_id` uses text to match the existing
V2 case primary key; PostgreSQL cannot create the originally proposed UUID-to-text
foreign key. The migration is V31, since V26–V30 already exist on this branch.

**The spreadsheet itself is not retained.** Keep frozen membership, file names,
counts, hashes and delivery evidence. References alone cannot recreate the original
spreadsheet after case values change; the former regeneration claim was incorrect.
No archive/download endpoint is included in this slice. The request ledger and its
case reservations are separate from V31's successful-send evidence.

### 4. Durable admission, one delivery attempt and explicit uncertainty

Follow [the send/recovery contract](gazette-1b-send-and-recovery-contract.md).
The originating request commits admission before preparation and commits send-start
before contacting Notify. It commits acceptance, sent audit, case dates and notes
atomically afterwards. Failure to persist an accepted send means uncertainty.
No local rollback, timeout or expired claim authorises another provider call.

An identical idempotency key/payload/subject returns recorded status; changed content
or subject conflicts. A known `NotSent` outcome permits a fresh confirmation and
new request. An `Uncertain` outcome retains its reservation and needs investigation.
There is no automatic delivery retry or operator resend endpoint in the first version.

### 5. Validate the selection is still ready, immediately before sending

Preview validates distinct case IDs and returns current recipient/revision,
counts and a fingerprint of mapped data/readiness. Send admission requires that
fingerprint and recipient revision and rechecks both under its locking protocol.
Any changed, excluded, unclassified, missing or reserved member refuses the whole
selection; no silent removal. Case writes are guarded during preparation/sending.
See the contract for expiry, key identity, typed refusals and concurrency tests.

### 6. `GET /caseworker/gazette-notices` is a plain, paged history read

Returns `sentAt`, `sentBy` (display name only — the subject id is never published, same discipline as
`claimedBy`/`decidedBy`), `caseCount`, and a preview of at most 100 frozen `referenceNumbers`
per batch, with `referencesTruncated` indicating omitted members. Batches are newest first;
references use lexical display order, not send order. No custom filtering or sorting.

The next contract adds `batchId` to each history summary and a separate paginated
full-membership read for new and legacy batches, preserving the bounded list preview.
Pending/not-sent/uncertain requests use a separate request list; they never appear
as successful history.

### 7. Notes belong to committed local transitions

Admission writes one submitted marker per member. Success settlement writes one
sent marker per member alongside case dates and V31 evidence. A terminal failure
writes one fixed not-sent or uncertain marker per member alongside its outcome.
Repeat POSTs, concurrent settlement and repeated recovery add no duplicate notes.
A note failure rolls back that local transition, never the remote email; a durable
send-start marker prevents recovery from sending again. See the new contract.

### 8. Every Gazette-specific operation is gated on `Role.DEEDPOLL_ADMIN`, refusing everyone else with 403

Not a presentational check. `readyForPublication` as a filter value is visible to anyone who can call the case
list at all (the same as every other filter), so the **list-level gate belongs to admin's own `/judicial`-style
route mounting, not to api** — but all Gazette-specific operations (preview, send, settings, status and history) are gated here,
because a non-admin caller must not be able to send the Gazette email or read who has, regardless of what
admin's own UI shows them. Refuse with a plain 403 and a logged reason, the same shape `requireRole` uses on
the admin side.

## The spreadsheet and implementation layers

The merged `GazetteSpreadsheet` and tracked clean templates implement the agreed
27 adult / 21 minor visible columns, blank missing values and case-type grouping.
Use frozen detached inputs from admission and prepare every required file before
emailing. No placeholder generator or all-column source workbook is permitted.

The new [send/recovery contract](gazette-1b-send-and-recovery-contract.md) defines
ledger persistence, transaction boundaries, HTTP DTOs, Notify configuration,
recovery and the ordered implementation slices. Keep these outside the existing
per-case notification ledger. Add the next unused migration; never edit V31–V33.

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

- [x] Spreadsheet column layout, blank behavior and type grouping implemented;
  generator merged in PR #88.
- [x] Initial Notify wording drafted for adult-only, minor-only and mixed batches,
  following Steve's instruction; subject/body remain editable in Notify.
- [ ] Provision templates, reply-to and file-download settings; record their IDs.
- [ ] Ready-list predicate re-confirmed beyond the agreed relinquishment exclusion.

These release/business dependencies do not block the local ledger and contract
implementation with synthetic data and a stub sender. Keep production sends disabled
until provisioning, support ownership and controlled acceptance checks are complete.

### Task 1: `readyForPublication` filter

Add the filter, its default sort, and its description text. Test: the predicate matches exactly the three
conditions of decision 1, the default sort, and that every other filter's behaviour is unchanged.

- [x] Filter added, tests green, OpenAPI delta is exactly the filter's description text
  and readyForPublication added to CreateSavedQueryRequest and UpdateSavedQueryRequest
  filter enums (both are generated from ListFilters, matching saved-query validation).

Execution correction, 6 October 2026: the original description-only expectation
overlooked the two generated saved-query enums. These additive entries preserve the
existing shared filter catalogue; omitting them would publish an inaccurate contract.
Verified against retained snapshots and the successful full build (3,619 test /
14 integration, zero failures or skips); see `gazette-1a-task1-validation.md`.

### Task 2: Audit schema

`V31__gazette_notice_batch.sql`, entities, repositories. Test the unique index on `request_id` refuses a
repeat at the database level, not only in application code.

- [x] Migration, entities and repositories added; constraint-level test green

Verified on 6 October 2026: V31, including the text case-ID foreign key correction
above. Full build: 3,629 test / 14 integration, no failures, errors or skips;
OpenAPI unchanged from Task 1. See `gazette-1a-task2-validation.md`.

### Task 3: Role gate and history read

`GET /caseworker/gazette-notices`, gated, paged, newest first. Test: 403 for a non-admin token, correct
shape and ordering for an admin one, no subject id anywhere in the response.

- [x] History endpoint implemented and gated; no owner id on the wire

Verified on 6 October 2026: the full-context PostgreSQL tests cover role refusal,
admin results, frozen references, newest-first ordering, pagination and privacy.
Full build: 3,641 test / 15 integration, no failures, errors or skips.
See `gazette-1a-task3-validation.md`.

### Task 4: Batch send and recovery

Implement the ordered slices in [the new contract](gazette-1b-send-and-recovery-contract.md):

- [x] 4a — Request ledger, state transitions, legacy compatibility and case guards
  ([implementation and validation](gazette-1c-request-ledger-and-guards.md)).
- [ ] 4b — Preview/admission, status and full-membership APIs; regenerate OpenAPI.
- [ ] 4c — One-shot Notify delivery, all-file preparation and bounded recovery.
- [ ] 4d — Fault-injection, races and recovery evidence; no automatic uncertain resend.

The next executable slice is preview/admission and status APIs, including locked
fingerprint verification and detached export inputs. It must not expose an operational send
control before the complete contract and sender tests are ready. Successful local
settlement updates every case/date/note atomically; external email acceptance is
never covered by that transaction.

### Task 5: Full validation and OpenAPI regeneration

- [ ] Full build and contract validation after the send implementation.
- [ ] Measure the complete generated delta: five new operations in the design and
  `batchId` on sent-history summaries, with all new request/status/error schemas.
  Existing settings, case/email, judicial and deferred CCD contracts stay intact.
- [ ] Record final test counts, local fault-injection and review evidence.
- [ ] Provision/activate only after the contract's release prerequisites are met.

Documentation-only contract planning does not count as any of these completed
implementation tasks. No live send or runtime contract was added by that planning PR.
