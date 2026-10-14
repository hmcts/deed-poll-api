# Gazette batch send and recovery contract

> Superseded access decision (9 October 2026): [Team leader and Delivery Manager contract](completed/20261009-team-leader-delivery-manager.md)
> governs staff permissions. All Gazette entry points, including the named ready worklist/count and
> saved-query execution, require Delivery Manager alone. Team leader and temporary legacy admin retain
> non-Gazette casework capabilities only. Earlier admin-access wording below is historical; other
> delivery, idempotency and recovery decisions remain applicable.

Design revision: 1, 8 October 2026. **Implementation plan, not a published API.**
Based on API master `7ff8352` (recipient settings PR #89) and admin master
`5cd70ad` (PR #163). This replaces the transactional-email proposal in the
[parent plan](gazette-1-ready-list-and-batch-send.md), decisions 2–7 and Task 4.
The ledger and [preview/status slice](completed/gazette-1d-preview-admission-and-status.md) now
implement the persistence and HTTP contract. The
[delivery/recovery slice](completed/gazette-1e-delivery-and-recovery.md) connects confirmed
submissions behind the disabled-by-default flag and adds independent recovery.

The business rules already agreed are one Notify email per batch, adult/minor
spreadsheet download links with absent groups omitted, visible columns only,
blank missing values, an admin-configured recipient, and no automatic resend of
uncertain email outcomes (cross-repo ADR-0007). The engineering choices below make
those rules implementable. They do not approve the proposed review checklist,
Notify provisioning, operating ownership or production activation.

## Delivery boundary

A database transaction cannot include Notify's acceptance atomically. Keep short
local transactions before and after the remote call. An accepted email whose
local settlement fails is **uncertain**, never a safe-to-repeat failure.

The first version makes **at most one application-level Notify email invocation per
accepted request**. Even a known temporary refusal ends that request. There is no
automatic delivery retry or operator resend endpoint. This is narrower than the
existing per-case outbox's bounded retries and is compatible with ADR-0007, which
permits rather than requires retries of known refusals. An administrator can start
a new confirmation after a proved `NotSent` outcome. Unknown outcomes keep their
case reservations and require investigation. A Notify reference is for correlation;
do not depend on it deduplicating sends. Verify the SDK/HTTP/proxy retry behavior
before connecting the sender, and disable any transparent POST replay.

Reuse the Notify SDK's encoding, authentication and acceptance parser, existing
configuration conventions and the conservative classification in `NotificationFailure`.
The 6.2.0 SDK transport exposes no timeout/replay controls, so slice 3 deliberately
uses dedicated OkHttp HTTP with retry/redirect controls and a non-replayable body;
see the execution evidence. Do not route Gazette through the SDK's default send transport. Keep the batch ledger
and orchestration outside `EmailTemplate`, `CaseEmailService`, `NotificationOutbox`
and `deedpoll_notification`: those own correspondence for one case per row.

## Confirmation and proposed HTTP contract

All operations below require the verified `ACCESS_GAZETTE` capability in the domain
(Delivery Manager only, since PR #100; originally `DEEDPOLL_ADMIN`).
Unknown and inaccessible records have the existing not-found behavior. An opaque
batch UUID is public; IDAM subjects, request keys, lease owners, upload tokens and
raw provider exception messages are never published. Responses use UTC timestamps.

| Operation | Request | Result |
|---|---|---|
| `POST /caseworker/gazette-notice-previews` | Strict `{caseIds: string[]}` | 200 confirmation data; validates selection without reserving, sending or writing case notes |
| `POST /caseworker/gazette-notices` | `Idempotency-Key: UUID`; strict `{caseIds: string[], selectionFingerprint: string, recipientRevision: integer}` | Recorded batch status, or an admission refusal |
| `GET /caseworker/gazette-notice-requests` | Existing `page`/`size`; optional validated `state` enum | Paged request statuses, including pending, not-sent and uncertain requests |
| `GET /caseworker/gazette-notices/{batchId}` | Batch UUID | One status/summary, including legacy sent batches |
| `GET /caseworker/gazette-notices/{batchId}/cases` | Existing `page`/`size` | Every frozen membership reference, paged; no preview truncation |

The existing `GET /caseworker/gazette-notices` remains **sent history only**. Add
`batchId` to each summary so the UI can link to detail and full membership. Keep
its existing fields, ordering, count and bounded reference preview unchanged.
The new request list excludes legacy batches without a request-ledger row; those
remain available through history and detail. Existing settings operations remain
unchanged. Publish OpenAPI from code when implemented; never hand-edit admin's
pinned artifact to pretend these operations exist.

Preview returns `recipientEmail`, `recipientRevision`, `caseCount`, `adultCount`,
`minorCount`, `cases` (ID and display reference, bounded by the selection cap),
`selectionFingerprint` and `expiresAt`. The fingerprint is an opaque, versioned
SHA-256 comparison value over canonical case IDs, every mapped spreadsheet value,
and readiness inputs; it is **not a credential or proof that a checklist was done**.
API recomputes it under admission locks. An expiry is included in the fingerprint
input and echoed with the fingerprint as a single opaque token; reject expired or
malformed tokens before acceptance. The token is deterministic for its embedded
expiry and snapshot, needs no secret, and grants no authority by itself. Never trust
client-supplied recipient text or spreadsheet values.

Use a 30-minute confirmation lifetime and an initial engineering cap of 100 distinct
case IDs per request; these are implementation defaults, not Gazette requirements.
Reject duplicates, empty/oversized selections and unknown keys with 400. Sort IDs
canonically for fingerprint equality and lock ordering; display and generate cases
oldest-enrolled first, with case ID as tie-breaker. Reordering the same IDs is the
same payload. Group counts and file choice come from current case types.

Preview and admission validate the current named ready predicate, exclude
Relinquishment, and refuse Unknown/missing types, absent full references and case
reservations. Never silently drop a member. Preview additionally checks recipient
and the needed Notify configuration. No upload or email occurs at preview.

`recipientRevision` is the settings revision actually displayed at confirmation.
Admission locks and compares it, even if an address changed away and back. A change
requires a fresh preview and confirmation; no silent substitution. Once accepted,
the request uses its frozen address and revision even if settings subsequently change.

### Idempotency and responses

Authenticate first. Look up a recorded key **before** applying current recipient,
readiness, configuration or expiry checks. Compare its normalized request fields
and original caller subject. Same key/body/subject returns the stored status and
performs no delivery work, including when the previous HTTP response was lost.
Different contents or subject return `409 REQUEST_KEY_REUSED`. Never replace the
stored recipient, snapshot fingerprint or caller on a repeat. Enforce key uniqueness
in PostgreSQL; a race loser reads the winner after rollback of its attempted insert.

A new request is committed as `Preparing` before generation/uploads. The originating
POST may then do that request's one delivery attempt outside the transaction and
return 200 with its terminal status. Return 202, plus a status `Location`, if it is
still `Preparing` or `Sending`. Repeats use 200 for terminal states, 202 otherwise.
Both return the same summary shape. A 200 **does not mean sent**: inspect `state`.
No success response precedes the appropriate database commit.

A response lost at any point is resolved by repeating the identical POST with the
same key, or reading the known batch ID. A connection error/500 proves nothing about
acceptance. Admin must keep the original payload/key and offer “Check request status”,
never generate a new key or claim that no email was sent after a transport failure.

Before admission, use 400 `ApiError` for malformed fields/header and 403 for the role
gate. Use a Gazette-specific 409 response with `error`, stable `code` and optional
`conflicts: [{caseId, reason}]`. Member reasons are `NOT_FOUND`, `NOT_READY`,
`TYPE_UNSUPPORTED`, `REFERENCE_MISSING`, `DATA_CHANGED` and `RESERVED`; do not put
applicant fields or provider prose in this list. Codes: `RECIPIENT_NOT_CONFIGURED`,
`RECIPIENT_CHANGED`, `SELECTION_CHANGED`, `CASE_NOT_READY`, `CASE_TYPE_UNSUPPORTED`,
`CASE_RESERVED`, `REQUEST_KEY_REUSED`, `CONFIRMATION_EXPIRED`. Configuration disabled
or incomplete returns 503 with `GAZETTE_UNAVAILABLE`. These refusals create no ledger
row or notes. An unclassified network/5xx error must not be interpreted as this
structured no-admission response. Use typed DTOs/client parsers; do not classify prose.

Status summaries contain `batchId`, `state`, `submittedAt`, `submittedBy`,
`recipientEmail`, `recipientRevision`, `caseCount`, `adultCount`, `minorCount`,
`sentAt` and nullable fixed `failureCode`. Failure codes are `PREPARATION_FAILED`,
`CONFIGURATION_INVALID`, `PROVIDER_REFUSED`, `PRE_SEND_EXPIRED` and
`SEND_OUTCOME_UNKNOWN`; provider messages are never substituted. Request-list
paging follows the existing default 25 / maximum 100 convention, newest
`submittedAt` then descending batch UUID; unknown state filters are 400.
No rendered email body or download links.
For legacy sent batches, unavailable recipient/group/submission facts are null,
not reconstructed from current settings/cases. `Sent` means **accepted by Notify**,
not delivered to LG or published. Keep that distinction in UI wording.

## Durable states and atomic work

| State | Meaning | Allowed next states | Case reservation |
|---|---|---|---|
| `Preparing` | Admission committed; no send authority granted | `Sending`, `NotSent` | Held |
| `Sending` | Send-start marker committed; provider may have accepted | `Sent`, `NotSent`, `Uncertain` | Held |
| `Sent` | Acceptance and local case/audit settlement committed | None | Held permanently against another Gazette request |
| `NotSent` | Proved pre-send failure or explicit known refusal, durably settled | None | Released atomically with outcome/note |
| `Uncertain` | Send may have happened; investigate before resend | No automatic state change | Held |

The single delivery attempt has a random owner and five-minute lease (matching the
existing delivery ownership interval). No takeover is allowed. Every preparation
completion, send-start and settlement checks owner, state and unexpired lease in the
database. Expired pre-send ownership becomes `NotSent`; expired `Sending` becomes
`Uncertain`. The send-start marker is never cleared and a terminal request is never
made deliverable again. A late accepted receipt may be appended as evidence for its
original attempt while `Uncertain`, without releasing reservations, resending or
automatically calling that request `Sent`. Conflicting receipts trigger investigation.
If a receipt cannot be persisted, log only batch ID, provider notification ID and
returned template version to support correlation; do not log recipient/content or
report success. Send-start evidence remains the durable reason to stop.

Implement these local transaction boundaries:

1. **Admit:** reserve the request key, lock settings then all case rows in canonical
   ID order, validate fingerprint/readiness and capture detached immutable generation
   inputs. Atomically persist request metadata, frozen member references and unique
   per-case reservations, plus one submitted note on each case. `Preparing` starts
   its lease here. Use `FOR NO KEY UPDATE` for case rows to avoid conflicting with
   child-row foreign-key key-share locks; verify the existing referral/deed lock
   ordering before introducing another path. Never hold locks during remote work.
2. **Prepare outside transactions:** generate the agreed adult/minor files from the
   admitted detached inputs. Validate every required file and size before any email.
   Produce Notify upload personalisation in memory. Partial file preparation cannot
   send an incomplete batch. Store filename, case count, byte size and SHA-256 before
   send-start; do not store the upload token. No production spreadsheet bytes or full
   application snapshot are retained in this first version. A crash before send-start
   ends `NotSent`; recovery does not regenerate from changed case data.
3. **Grant send authority:** commit a conditional transition from this owner's
   `Preparing` to `Sending`, recording `sendStartedAt`. If it matches no row, do not
   invoke Notify. If commit acknowledgement is lost, do not invoke Notify either;
   recovery may conservatively mark the request uncertain. Call Notify only once,
   after known commit success, using the frozen recipient/template/reply-to settings,
   batch UUID as reference, and all prepared file values.
4. **Settle known acceptance:** in one short transaction, persist the provider ID,
   returned template version and acceptance time; insert the V31 sent batch/members;
   set every member's notice-sent date using narrow column updates and write one sent
   note per case. Use one timestamp. Then mark the request `Sent`. Failure of any
   local step leaves the durable send marker for uncertainty recovery. Never repeat
   Notify to repair failed local settlement. Do not overwrite unrelated concurrent
   edits through a detached entity save.
5. **Settle failure:** before send-start, failures are safe `NotSent`. After it,
   only a known explicit refusal (currently 400/403/429 under `NotificationFailure`)
   is `NotSent`. Timeout, connection loss, 5xx, unexpected exception/response or
   failed settlement is `Uncertain`. Outcome, fixed failure note on each member and
   reservation release (only for `NotSent`) commit together. If the database is down,
   leave the durable state intact; recovery settles later, without sending.

While `Preparing` or `Sending`, refuse ordinary case PATCH/delete with a clear
conflict; the short reservation keeps confirmed inputs and eligibility stable
through delivery. Read/note/document/referral operations keep their own rules, but
any writer of readiness or exported fields must participate in this guard. Admission
and patch/delete must share the case-row serialization point. Internal settlement
is the only bypass and changes only notice-sent/audit fields. `Uncertain` continues
to block new Gazette admission and deletion; normal corrections may resume, since
they cannot cancel an email that may already have gone. Sent membership retains the
existing deletion restriction. Inventory all such writers in Task 1 before coding.

Keep the current ready-list/count predicate unchanged. A case in an in-flight or
uncertain request can still satisfy it; preview/admission refuse it as reserved.
Admin explains this and links to the request list, rather than locally subtracting
rows or suggesting that every counted case can be selected now.

A bounded scheduled **recovery scan only** settles expired `Preparing`/`Sending`
rows; it never invokes Notify. Run every minute, at most 25 rows per pass/replica,
using row locks/compare-and-set so two replicas write only one terminal outcome and
one failure note per case. A failed recovery transaction keeps the row for the next
scan. Disable new sends independently of recovery so switching off delivery does
not strand existing requests. Test recovery with the sender replaced by a fail-on-call
spy, including rows left by an earlier process.

## Persistence and historical evidence

Add the next unused Flyway migration; do not edit V31–V33. Keep V31 as completed
sent evidence. Introduce a separate request ledger (batch UUID, unique request key,
caller subject/name, normalized request identity, settings revision/address,
counts, immutable Notify configuration IDs, timestamps, owner/lease, send-start,
state/failure code and nullable provider receipt), frozen request membership, and
a unique active case-reservation relation. Terminal `NotSent` releases only the
reservation, never its request/key/member evidence. Use restrictive member foreign
keys, so attempted-publication evidence also prevents deleting a case.

V31 batch ID equals the successful request's batch UUID and its request key remains
unique. Reconcile existing V31 request keys on admission as well, so a historical
sent key cannot be reused just because it predates the ledger; return a conflict
when its original payload cannot be proved. Backfill sent case reservations from
existing V31 membership, deduplicating cases that appear in multiple historical
batches without deleting evidence. Existing batches stay readable without invented
recipient/provider facts. Unique state/receipt/note keys make repeated settlement
or recovery idempotent. Recovery-owned notes use the original verified submitter's
stored display attribution; subjects remain private.

Full membership uses database pagination (default 25, maximum 100), sorted by
frozen reference then case ID, returning `cases: [{caseId, referenceNumber}]` and
`page`, `size`, `total`, `totalPages`. Both new and legacy batches work. Reference
membership is frozen even if a live case is edited. It does **not** recreate the
exact historical spreadsheet: without retained field values/bytes that is impossible.
Remove the parent's old regeneration claim; do not introduce an archive or export
endpoint without a separate retention/access decision.

## Investigation and release procedure

1. Open the admin request list/detail. Capture batch ID, state, time, recipient,
   frozen membership and displayed failure category. Do not use a new request key
   to work around `Uncertain`; reservations must refuse overlapping batches.
2. The designated support operator correlates the batch UUID with Notify reference
   and any retained provider ID/receipt, using their authorised provider tooling.
   A missing search result alone is not proof that nothing was accepted. Never put
   applicant data, tokens, API keys or full spreadsheets in operational logs/tickets.
3. Preserve findings and the investigation reference. No UI repair/resend facility,
   automatic provider lookup, direct SQL reset or release of an uncertain reservation
   is supplied by this plan. If acceptance is proven, a separately reviewed local
   reconciliation may complete evidence without sending. If non-acceptance is proven,
   a separately reviewed recovery may release the reservation for fresh confirmation.
   Quiesce/fence the original worker before any such recovery; an expired lease alone
   does not stop a process or prove a request was never sent.
4. Until a supported recovery is approved and performed, the request stays stopped.
   Name the operational team, escalation contact and evidence-retention/access policy
   before enabling production sends. These are release prerequisites, not inferred
   business agreements. The request list and notes make uncertainty visible; they do
   not establish a monitored alerting service by themselves.

Gazette sending ships disabled by default. Activation requires the recipient,
three template IDs, reply-to ID, file retention/confirmation configuration, verified
client request bounds, support ownership and end-to-end test-recipient evidence.
Freeze configuration identifiers and retention/confirmation values at admission;
provider-edited template copy is resolved at send time and the accepted version is
recorded. Require recipient email confirmation for downloads as in `DocumentUploads`;
provider retention duration still needs provisioning. Never retain expiring file tokens.

## Implementation slices and acceptance evidence

Each slice gets a separate execution plan and PR; completing this design does not
check off its code. Slices 1–3 are implemented; see the
[ledger evidence](completed/gazette-1c-request-ledger-and-guards.md) and
[preview/status evidence](completed/gazette-1d-preview-admission-and-status.md). Implement in this order:

1. **Request ledger and guards:** migration, states, unique key/case reservations,
   legacy backfill/key handling, deletion and relevant case-writer guards, atomic
   notes and narrow settlement writes. No sender endpoint. Prove real-Postgres races,
   interleavings with edits/deletes and rollback when a note fails.
2. **Preview, admission and status contract:** role gates, bound selection fingerprint,
   recipient revision, duplicate identity, typed refusals, request/status/full-member
   reads and generated OpenAPI. Keep public sending disabled until slice 3 is ready;
   tests may admit requests through the domain. Existing history adds only batch ID.
3. **One-shot delivery and recovery:** real generator, three provisioned template
   variants, all-file preparation, committed send authority, Notify adapter and
   bounded recovery scanner. Fault-inject crashes before/after every commit and
   remote boundary. Verify SDK retry behavior and freeze configuration per request.
4. **Admin contract, journey and status:** refresh generated OpenAPI; implement
   preview/confirmation, stable keys, typed outcomes, request list/detail and complete
   membership. Checklist gating waits on its business answer. Test cross-tab edits,
   lost HTTP responses, recipient changes and uncertain-outcome restrictions.
5. **Release verification:** environment configuration and support ownership,
   controlled test-recipient delivery and downloads, frozen audit/case dates/notes,
   human Welsh review and the separate external review requested for release.

Required scenarios include overlapping batches under different keys, same-key races
and different payload/subject reuse, edits after preview, recipient changes before
admission and after admission, all case-type groups, file oversize/preparation failure,
known refusal, timeout/5xx, accepted email followed by database failure, process death
with a committed send marker, expired owner and late receipt, two recovery replicas,
legacy history over 100 references, and non-admin access to every operation. For
all uncertain cases assert zero subsequent Notify calls and no reservation release.
For `NotSent`, assert that only a fresh explicitly confirmed request can send later.

## Source anchors checked for this design

- [Gazette settings service](../../src/main/java/uk/gov/hmcts/reform/deedpoll/domain/gazette/GazetteSettingsService.java): revision and verified role/attribution.
- [Generator](../../src/main/java/uk/gov/hmcts/reform/deedpoll/domain/gazette/GazetteSpreadsheet.java): type grouping and detached-input requirement.
- [V31](../../src/main/resources/db/migration/V31__gazette_notice_batch.sql): sent-only schema, restrictive membership and unique request key.
- [History](../../src/main/java/uk/gov/hmcts/reform/deedpoll/domain/gazette/GazetteNoticeHistory.java): current bounded frozen preview.
- [Case repository](../../src/main/java/uk/gov/hmcts/reform/deedpoll/repository/DeedPollRepository.java): existing row-lock strengths and narrow updates.
- [Delivery transactions](../../src/main/java/uk/gov/hmcts/reform/deedpoll/notify/NotificationDeliveryTransactions.java): separately committed ownership/send-start/outcomes.
- [Failure classification](../../src/main/java/uk/gov/hmcts/reform/deedpoll/notify/NotificationFailure.java): known refusal versus ambiguity.
- [Upload preparation](../../src/main/java/uk/gov/hmcts/reform/deedpoll/notify/DocumentUploads.java): transient file personalisation.
