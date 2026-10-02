# CDAM callers: baseline and implementation contract

Task 1 of [plan 03b](plans/deed-poll-api-03b-cdam-callers-and-staging.md), recorded 2026-10-02.
This is a source audit and the contract for Tasks 2–8, not a claim that those tasks are implemented.
The plan's final decision record supersedes its historical proposed/open-status paragraphs.
CDAM remains off by default; staging and the privileged correspondence reader do not exist yet.

## Inspected revision

HEAD and the locally recorded `origin/master` are `f4eb7dbb1c7ba5673ed01fa57e9de1e6666b7823`
(PR #73). Local `master` is older, `b2d594e5d4e92aa7d3637370b745860d7584e638`; the audit uses
HEAD, not that stale local branch. No fetch, branch reset or remote write was performed. The current
branch is `docs/cdam-03b-callers-plan`. There are no source changes since the plan's inspected baseline.
The working tree already contained documentation changes to `RESUME.md`, plans 03/03a and the untracked
03b plan; those unrelated tracked edits are preserved outside this task's commit.

[V25](../src/main/resources/db/migration/V25__document_storage_state.sql) remains the highest migration.
It adds storage states, a two-upload database cap and the transient hash-token constraints. It does not
reserve one generated deed per case, persist payloads, or provide notification worker ownership.
Choose the next migration number afresh in Task 2; do not rewrite V25 or backfill generated provenance
from document type names. Fresh build evidence is recorded in the plan's Task 1 section.

## Current entry points and transactions

Source links below refer to this baseline. Java paths are under
`src/main/java/uk/gov/hmcts/reform/deedpoll/`.

| Entry / source | Current call and boundary | Consequence |
|---|---|---|
| [CaseworkerEmailController:105,121,166](../src/main/java/uk/gov/hmcts/reform/deedpoll/api/caseworker/controller/CaseworkerEmailController.java#L105) | GET email overview; POST preview; POST send with case-scoped UUID `Idempotency-Key`. Controller is non-transactional. Send obtains `CurrentUser`, calls service, maps receipt, then drains. | Enqueue commits before immediate drain; HTTP 200 acknowledges an owed email, not delivery. |
| [CaseEmailService:336,358](../src/main/java/uk/gov/hmcts/reform/deedpoll/notify/CaseEmailService.java#L336) | Overview/correspondence use read-only REQUIRED transactions. | No generation, storage reservation or enqueue from availability. |
| [CaseEmailService:440,1241](../src/main/java/uk/gov/hmcts/reform/deedpoll/notify/CaseEmailService.java#L440) | Preview runs read-only REQUIRED, composes with a deed description, then asks Notify to render; may retry rendering with attachment stand-ins. | Currently holds its transaction during rendering. Preview refactoring is outside 03b; it must remain free of deed side effects. |
| [CaseEmailService:538](../src/main/java/uk/gov/hmcts/reform/deedpoll/notify/CaseEmailService.java#L538) | REQUIRED transaction: entitlement → recipient → slots/composition → deed → native enqueue → confirmation clock and note. | Removing the deed method's annotation alone cannot move CDAM outside the caller's transaction. |
| [DeedDocumentService:125](../src/main/java/uk/gov/hmcts/reform/deedpoll/domain/deeds/DeedDocumentService.java#L125) | REQUIRED joins send. Reads the oldest row of either deed type in either direction; checks direction before template; otherwise generates and uploads. | No database uniqueness/reservation protects concurrent generation. Existing rows are not currently checked for storage readiness here; do not carry that omission into CDAM success. |
| [DocumentService:248,263,365](../src/main/java/uk/gov/hmcts/reform/deedpoll/domain/documents/DocumentService.java#L248) | Filesystem uses REQUIRED TransactionTemplate, with rollback blob cleanup. Remote branch calls `RemoteDocumentUploads`. | Preserve filesystem rollback semantics. Remote orchestration must begin outside any transaction. |
| [CdamDocumentUploads:76](../src/main/java/uk/gov/hmcts/reform/deedpoll/storage/CdamDocumentUploads.java#L76) | Explicit active-transaction rejection; claim via transactional collaborator, upload, then document event. | Forms send currently fails this guard. Every call currently claims a new row; retries of prepared deeds need a continuation seam. |
| [DocumentService:306](../src/main/java/uk/gov/hmcts/reform/deedpoll/domain/documents/DocumentService.java#L306) | Case-scoped read in read-only REQUIRED template; missing answer returned from callback; refusal and store open follow the callback. | Creates no transaction around the store call, but cannot end an outer drain transaction. Do not reintroduce throwing participating read-only methods that mark the drain rollback-only. |
| [CaseworkerReadController:165](../src/main/java/uk/gov/hmcts/reform/deedpoll/api/caseworker/controller/CaseworkerReadController.java#L165) | Computes case-list response, drains, then calls linking sweep. | Unrelated staff traffic can drain any due notification. Neither this entry nor email controller wraps the drain in a transaction. |
| [NotificationOutbox:112,132](../src/main/java/uk/gov/hmcts/reform/deedpoll/notify/NotificationOutbox.java#L112) | Both public drain overloads are REQUIRES_NEW. No-arg method self-calls the limit overload within the already-open transaction. Entire batch, documents, Notify, settlement and failure notes share it. | A later note/database failure can roll back earlier accepted sends. The new design requires real transactional collaborators, not self-invoked annotations. |
| [NotifyEmailSender:94](../src/main/java/uk/gov/hmcts/reform/deedpoll/notify/NotifyEmailSender.java#L94) | Resolves files before `sendEmail`, using row UUID as provider reference; records outcome taxonomy. | Reference is not a provider deduplication guarantee. Send-start evidence must be committed after file preparation and just before the network send. |

### Lazy reads and authentication

[DeedPollSupportingDocument:46,59](../src/main/java/uk/gov/hmcts/reform/deedpoll/entity/DeedPollSupportingDocument.java#L46)
has lazy parent and document-type associations. `storeDeed` reads the type name in its transaction;
`DocumentService.initialiseType` (line 424) touches the name inside the download read. Removal also uses
the parent for the CCD reference. The split flow must return scalar owner/document/type/filename/state
values and immutable correspondence snapshots from short transactions, not detached entities whose
associations are accessed during remote work. Current `CaseEmailService` composed/entitled records
(lines 1403/1410) retain entities; they are not suitable external-I/O snapshots. `Notification` stores
case/referral IDs and frozen JSON; resolve that JSON while building the claimed snapshot.

[SecurityConfiguration](../src/main/java/uk/gov/hmcts/reform/deedpoll/config/SecurityConfiguration.java)
requires authenticated IDAM access for these business routes; this plan grants no new role or broader document authority.
[DocumentService:306](../src/main/java/uk/gov/hmcts/reform/deedpoll/domain/documents/DocumentService.java#L306)
checks both local IDs before any store access. CDAM reads use the request's IDAM JWT plus S2S,
via [CdamDocumentStore:165,220](../src/main/java/uk/gov/hmcts/reform/deedpoll/storage/CdamDocumentStore.java#L165).
Missing staff context throws; CDAM 403 maps to local 403, 404 to 404, other failures to 502. An unmapped
staff identity can produce upstream 502. Never retry a staff denial using the system identity.
Uploads and soft deletes already use the system user plus S2S (`save:98`, `delete:201`).

Task 4's internal correspondence reader must take notification authority, re-read its case and declared
frozen references, check local ownership/readiness, then acquire fresh system/S2S tokens. An arbitrary
public document request cannot select this authority. Share local checks with the staff reader, not a
fallback credential chain. The narrowly scoped preparation verifier also needs system authority to
read back a reserved deed before payload release. Local fixtures prove neither deployed permissions
nor system-account ownership; onboarding still must confirm READ and the other required permissions.

### Document progress and current outbox claims

[DocumentStorageStates:85–255](../src/main/java/uk/gov/hmcts/reform/deedpoll/storage/DocumentStorageStates.java#L85)
commits the claim and each progress step through a separate proxied collaborator. `claim` verifies the
case, type and CCD binding before inserting `UploadPending`, with the first send already counted.
`UploadUncertain` permits one further binary send in that same live request, after committing its count.
`Uploaded` has a known CDAM ID and hash; `AttachUncertain` retains attachment evidence. Only `Attached`
is downloadable in CDAM mode and projected by CCD. `Failed` can still mean a binary exists remotely.
`Local` is served only by the filesystem path, so switching stores is not migration.

[CdamDocumentUploads:114](../src/main/java/uk/gov/hmcts/reform/deedpoll/storage/CdamDocumentUploads.java#L114)
reads state after every event failure before classifying it; the handler's committed `Attached` wins
over a lost answer. [SupportingDocumentsHandler:48](../src/main/java/uk/gov/hmcts/reform/deedpoll/ccd/SupportingDocumentsHandler.java#L48)
and `attachedOnTheCase:192` run MANDATORY in the event transaction, checking the document ID from the
payload URL against the stored CDAM ID. Data-store owns attachment. Do not replace this with a direct
CDAM attach call. Clear hash credentials on attachment/failure; never expose them in DTOs/logs/metrics.

[NotificationRepository:243,299](../src/main/java/uk/gov/hmcts/reform/deedpoll/repository/NotificationRepository.java#L299)
selects a bounded ordered candidate list, then conditionally updates Pending/due rows, increments attempts
and moves `nextAttemptAt`. It clears/reloads the persistence context. There is no worker ID/generation,
no durable send-start flag, and no attempt-cap predicate in that claim SQL. Today's transaction holds
the update lock through sending. A lease alone would not preserve that exclusion after splitting commits.
[NotificationBackoff:35](../src/main/java/uk/gov/hmcts/reform/deedpoll/notify/NotificationBackoff.java#L35)
uses a five-minute lease, eight attempts and 1/2/4/8/16/32/60-minute retry delays. Default drain limit is
five; no sender or nonpositive limit means no claims. Task 5 must preserve these behaviors while enforcing
the attempt cap even when workers repeatedly die before send.

[DocumentUploads:104,146](../src/main/java/uk/gov/hmcts/reform/deedpoll/notify/DocumentUploads.java#L104)
resolves only declared document keys into a copy; frozen JSON is never rewritten with upload tokens.
It reads at most 2,097,153 bytes, closes streams, and refuses above 2,097,152. Missing/malformed references,
oversize and unexplained client preparation refusals are TECHNICAL; unreadable files are TEMPORARY before
send; unusable retention is PERMANENT (valid range 1–78 weeks). Preserve this taxonomy and email confirmation
on Notify downloads. Its comment at lines 182–185 still describes the former `noRollbackFor` annotation;
the actual TransactionTemplate implementation is authoritative. Task 8 must correct stale comments.
[NotificationFailure:69](../src/main/java/uk/gov/hmcts/reform/deedpoll/notify/NotificationFailure.java#L69)
classifies 400/403 as PERMANENT, 429/500 as TEMPORARY and other/status-zero answers as TECHNICAL.

## Accepted design and sequence for implementation

All three flags are resolved by the final plan decision record, with no further design approval required:

1. Use bounded Postgres payload staging, not pod disk or a newly provisioned shared object store. Reserve
   one generated deed per case across LOC020/LOC022 under a case lock and database uniqueness; freeze the
   original bytes, checksum, template and local document identity together. Read both directions/types
   before reservation. Preserve legacy duplicates for investigation, not opportunistic deletion.
2. Commit notification claims and outcomes separately. A possibly-sent notification is terminal
   technical/uncertain for investigation, never automatically resent on lease expiry. Apply this to all
   templates. [ADR-0007](../../deed-poll-documentation/decisions/0007-uncertain-email-delivery-requires-investigation.md)
   accepts that an unsent email can remain stopped. No provider reconciliation or operator resend API is added.
3. Support existing-record and future citizen-draft staging owners, finite mandatory quotas, fixed expiry
   from first admission and opt-in bounded scheduled cleanup. These are accepted policies, not production
   values. [ADR-0008](../../deed-poll-documentation/decisions/0008-staged-upload-expiry-requires-release-agreement.md)
   and [ADR-0009](../../deed-poll-documentation/decisions/0009-staged-upload-cleanup-runs-on-a-bounded-schedule.md)
   record expiry and cleanup acceptance. Local tests may use explicit finite settings.

The forms-send coordinator is non-transactional. First validate current entitlement, recipient, slots,
provisioning and contradictory occupants; resolve a matching existing request without storage work.
Preserve the existing entitlement/address/slots refusal precedence rather than promising that a receipt
can be recovered after arbitrary case changes. Then reserve the deed in a short transaction, continue
its one document identity through remote upload/attachment, and revalidate/enqueue in one final transaction.
Enqueue, its one note and any confirmation-clock update commit atomically. Never acknowledge a reservation
as an email. Emails without documents keep one local enqueue transaction. Preview remains side-effect free.

The privileged reader verifies attachment with read-back/checksum before temporary payload deletion.
Task 3 retains payloads until Task 4 supplies that verifier. Failed or uncertain generated preparations
remain recoverable evidence; no expiry gives permission to generate another deed. Preserve filename,
MIME type, Outgoing direction and bytes after edits/restarts. The filesystem path retains rollback blob
cleanup; a confirmed CDAM attachment survives a failed final enqueue and is reused.

### Final-enqueue validation and response contract

The following is the implementation contract for Tasks 2–4, not current implemented CDAM behavior.
Use existing [DomainErrorHandler](../src/main/java/uk/gov/hmcts/reform/deedpoll/api/DomainErrorHandler.java)
and [SentEmailResponse](../src/main/java/uk/gov/hmcts/reform/deedpoll/api/caseworker/dto/SentEmailResponse.java)
shapes. No new email route, polling receipt, 202 or storage identifier is introduced.

| Condition, checked again after remote work | Result / retry rule |
|---|---|
| Case no longer exists | 404; no enqueue. |
| Entitlement lost or template routing changed | Existing applicability 409; keep the reserved deed. Never generate the other template as recovery. |
| Recipient missing/blank | Existing 422 with `applicantEmail` field error. |
| Slots invalid or required header missing/malformed | Existing 400 contract; no remote work on initial validation. |
| Same case/key, same template/referral/trimmed recipient/normalized personalisation | 200 with original `id`, `recipient`, `enqueued=false`; no new deed/note/upload. |
| Same key with different correspondence content | 409 using `REQUEST_ALREADY_USED` (CaseEmailService:237); no replacement. |
| V15/V20 group occupied by same template under another key | Existing 200/already-owed semantics; this does not establish new same-key content identity. |
| Group occupied by contradictory template | Existing 409 naming the owed email; initial check precedes deed generation and final check closes the race. |
| Existing deed has wrong direction or template | Existing DeedDocumentService 409, direction checked first; never regenerate. |
| CCD link missing | Existing 409: “This case is not ready for documents yet, because it has no CCD case. Try again once it has been linked.” Do not call linking. |
| Existing deed is unavailable, ambiguous, missing payload, or not attachable/usable | 409: “This case already has a deed preparation that is not ready to use. Its storage outcome must be resolved before this email can be sent.” No successful receipt and no blind upload. A concurrent preparation can be retried with the same key to observe committed progress, never to claim a new document. |
| Live upload/attach refusal or lost answer | 502, retaining state. For prepared deeds use “This deed could not be confirmed in document storage. Its preparation has been kept; review its storage outcome before retrying.” A retry never grants another upload budget. After two uncertain sends or a crash at an ambiguous boundary, park. |
| Read-back unavailable | 502 using fixed upstream prose for an unavailable read; keep payload/identity and retry only the read, never the binary upload. A checksum mismatch is 409: “This case’s stored deed does not match its prepared content. It must be investigated before this email can be sent.” Retain both identity and payload evidence. |
| Material case/correspondence input changed during remote work | 409; no stale enqueue. Fixed proposed message: “This case changed while its deed was being prepared. Review the case before sending this email.” |
| Final transaction/note/commit fails | No success receipt; database failure remains a server failure. Re-read on same-key retry; retain a confirmed attachment. |
| Fresh final enqueue commits | 200 with `id`, frozen `recipient`, `enqueued=true`; one note. Lost HTTP response is retried with the same key. |

The prepared-deed 502 text intentionally differs from `CdamDocumentUploads.UPLOAD_LOST`, whose current
caseworker-upload advice says to upload a new document. That advice must never be given for a generated
deed. Ordinary uploaded-file behavior is unchanged. These new fixed refusal messages must be pinned in
Task 3 tests and reflected in response descriptions when implemented.

Revalidation compares semantic inputs, not `modifiedOn` alone. Under the final case lock re-check current
entitlement, the same CCD binding, reservation/document ownership, template, direction and Attached state;
re-read request and exclusion-group occupants with the existing native `ON CONFLICT DO NOTHING` and
ordered resolution (request → referral group → case group → invariant fault, CaseEmailService:610).
Provisioning must still be valid before a new enqueue; preserve existing already-owed behavior.

Material values comprise trimmed recipient, accepted slots and composed personalisation, plus the chosen
template's resolved deed fields from [DeedField:203](../src/main/java/uk/gov/hmcts/reform/deedpoll/domain/deeds/DeedField.java#L203).
That covers shared reference/date received/new and old names/nationality and nationality section,
adult address/email/marital status, and minor age/parents as actually bound by the chosen template.
Use that existing mapping, not a second hand-maintained field list. Compare a durable fingerprint of the
original resolved inputs, without logging personal data; do not regenerate the binary to compare it.
[FormsForCompletionPersonalisation:130](../src/main/java/uk/gov/hmcts/reform/deedpoll/notify/FormsForCompletionPersonalisation.java#L130)
also freezes online reference and publication choice. A changed material deed input after preparation
must not silently pair the original instrument with contradictory new correspondence; refuse and preserve
the original. Notes, unrelated audit changes and other fields absent from these semantic inputs do not
invalidate the deed, reset its identity or regenerate it. Re-check template routing even when rendered
values happen to match. Immutable filename/type/checksum remain those committed at preparation.

### Crash and restart boundaries

This table specifies the target behavior; today's whole-batch rollback does not provide the send guarantees.
Every “commit” means confirmed durable completion. If a commit answer is lost, re-read authoritative state
before taking the next side effect. Never infer rollback from an exception alone.

| Boundary where the process/response is lost | Durable fact and allowed next action |
|---|---|
| Before preparation/admission commit | No committed reservation or payload: repeat local admission under the same unique key and quotas. No remote work occurred. |
| After preparation/admission commit | Reuse exact bytes/metadata and identity. Different request keys/templates cannot reserve a second deed for the same case. |
| Before upload-claim commit | No send permitted. Re-read/claim the existing reservation atomically. |
| After upload-claim commit, before/during upload | Send may have happened; a restarted worker cannot distinguish those points. Park for investigation; durable bytes are not a new upload budget. |
| First upload answer uncertain, worker still owns live request | Commit uncertainty and second-attempt count before the one permitted fresh send; failure/loss around that commit requires re-read. No third send. |
| Upload accepted, before ID/hash commit | Remote copy may exist but cannot be located safely. Park; keep payload and counted send evidence. |
| After ID/hash commit, before attach call | Known upload can continue only with exclusive ownership and valid attachment evidence; no binary replay. Expired unattached copy/token requires reviewed repair. |
| Attach sent, before handler/snapshot transaction commits | Attachment is uncertain; re-read local state/CCD evidence. Preserve 03a's bounded event submission semantics, no direct attach or unlimited restart replay. |
| Handler/snapshot commits, response lost or local caller fails | Attached wins on re-read, including after a refused/lost event response. Never demote or upload again. |
| Before/during verification read-back | No new upload; retain payload and retry read/checksum only. |
| Verified read-back, before payload-release commit | Keeping an extra copy is safe; repeat verification and conditional release. Commit identity/checksum evidence with release. |
| After payload-release commit | Read original Attached bytes from CDAM; absence or a later denial does not authorize regeneration. |
| Before final enqueue/note/clock commit | No owed email; preserve attachment and resolve retry under the same key. |
| Final enqueue commit, before HTTP answer/immediate drain | Existing matching notification is the receipt; no additional note or enqueue. |
| Before notification-claim commit | Nothing may send. Another eligible worker can claim. |
| After claim commit, before/during file read | No send-start fact. Conditional owner/generation recovery may retry after expiry within attempt cap; stale worker cannot settle or obtain send authority. |
| File ready, before send-start commit | Prepared Notify tokens are transient; acquire conditional send authority, or do not send. |
| Send-start commit, before network send | Conservatively may-have-sent, even if process actually died before sending. Do not reclaim for automatic delivery. |
| During Notify call / acceptance before outcome commit | Unknown delivery: park terminal technical/uncertain with one failure note; no provider deduplication assumed. |
| Known retryable refusal, before settlement commit | Until the conditional outcome commits, send-start still protects ambiguity; losing the commit cannot authorize resend. |
| Outcome/note transaction fails | Both roll back together; do not undo earlier notifications or proceed to another automatic send of this one. Re-read committed evidence. |
| After outcome commit | Sent/terminal is settled; retryable outcome alone authorizes bounded later attempts. Repeated terminal settlement adds no note. |
| Before stage promotion/removal/expiry reservation commit | Atomic predicate on owner, state and original expiry decides one winner. No remote work before promotion reservation. |
| After promotion reservation, around upload/attach | One local document identity; use the same remote boundaries above. Promoting/uncertain payloads cannot expire or be removed. |
| After verified promotion, before consumed/payload-release commit | Reconcile Attached/read-back and settle consumption atomically; do not recreate the document. |
| Before/after expiry or removal commit | Before: payload remains accounted for; after: payload and quota release agree atomically. Repeated cleanup is harmless; never erase promotion evidence based on a stale scan. |

Claim one notification at a time from the bounded batch. Commit worker/generation and later send-start
conditionally; stale owners cannot start or settle. Terminal state and note are one transaction. Expired
pre-send claims consume attempts and stop at the cap. No-sender drains do not claim. Old whole-batch drain
workers must be quiesced before new claims are processed unless compatibility is explicitly proved.

## Pre-case staging and citizen/web handoff

Staging is a separate opt-in domain operation, not fallback behavior for the caseworker upload route.
An existing local record may stage while awaiting its CCD link. A future citizen draft may stage only
through its durable authenticated ownership adapter. Bind owner kind plus stable owner identity and
operation key; a random draft/stage UUID is not authority. Repeated admission compares bytes/checksum and
metadata, returns the same identity and expiry, and refuses changed content under that key.

| Setting | Accepted contract |
|---|---|
| Citizen ceiling | 10 files per draft, each at most 7,340,032 bytes. Enforce across admission races. |
| Existing-record ceiling | 26,214,400 bytes per file; explicit configured staged-file count per record. |
| Storage budgets | Required finite per-owner byte limits and overall payload cap, including retained failed/uncertain preparations while bytes remain. Atomically reserve capacity; refuse new admission at capacity, never delete recovery evidence to make room. |
| Expiry | Separate required finite durations per owner kind, measured from first successful staging. Retries do not extend expiry. At/after expiry a still-Staged item cannot promote, even if cleanup has not run. |
| Cleanup | Opt-in Spring schedule enabled with staging; mandatory schedule and per-run row/byte/time limits, startup/admission refusal for incomplete configuration, safe multi-replica conditional claims. |
| Protected rows | Generated deeds awaiting recovery, Promoting and uncertain promotions are excluded from automatic expiry/removal. Account for their storage until verified release. |

The exact ceilings are sourced from [UploadPolicy:59](../src/main/java/uk/gov/hmcts/reform/deedpoll/domain/documents/UploadPolicy.java#L59)
and the read-only inspected [web uploads:17,20](../../deed-poll-web/libs/deed-poll-journey/src/journey/uploads.ts#L17).
Validate actual bytes, permitted content/metadata and count, not a claimed length alone. No production
quota, expiry duration or schedule is selected by this document. Missing settings cannot mean unlimited.
Notify's separate 2 MiB ceiling is unchanged by either upload limit.

Promotion takes verified owner authority and a confirmed local case binding supplied by the owning
application flow. Atomically reserve against removal/expiry and freeze the target case plus local document
identity, then perform upload/event outside transactions. Repeated promotion cannot transfer to another
owner/case. `Staged → Promoting → Consumed`, `Staged → Expired/Removed`, and parked uncertain promotion
express staging progress; existing document storage states remain the remote authority. Only verified
attachment/read-back permits successful consumption and payload release. Unlinked promotion refuses;
it never calls linking or changes payment/queue eligibility to manufacture a binding.

The API's [citizen controller package](../src/main/java/uk/gov/hmcts/reform/deedpoll/api/citizen/controller/package-info.java)
contains no controller. [Fees & Pay](plans/fees-and-pay-1-ways-to-pay-card.md) owns draft authentication,
submission and linking/queue admission. The read-only inspected [web submitJourney:26](../../deed-poll-web/src/submission/submit-application.ts#L26)
sends answers and citizenship, not uploaded binary contents. Its draft ID alone does not establish this
API's ownership contract. Task 6 can test both domain owner kinds with fixtures without inventing a citizen
route. The future adapter/web task must provide:

- durable authenticated draft authority, scoped admission/read/removal/promotion and authorized draft-to-case binding;
- actual binary transfer plus filename/type, stable operation key and opaque local stage identity (no CDAM locator);
- recoverable validation/quota/expiry/ownership/conflicting-key/unlinked/uncertain outcomes that the web can display;
- same-key retries after request loss, immutable original expiry, and no duplicate upload on ambiguous promotion;
- an end-to-end test moving real bytes through staging and linking to one attachment before claiming citizen readiness.

These are domain outcomes, not newly invented HTTP routes/status contracts. Keep the citizen adapter disabled
until its authority exists. Capacity/backup checks, production values, investigation/cleanup ownership,
monitoring and deployed permissions remain release gates in the
[readiness checklist](../../deed-poll-documentation/operations/deployment-readiness-checklist.md).
Expiry removes active staging bytes, not submitted cases, remote documents, audit evidence or backups.
03c must migrate existing Local files/deeds and frozen queued references before a CDAM cutover.

## Verification obligations for the implementation tasks

Task 1 changes documentation only and runs the two fresh existing build baselines; no new source test is
appropriate for an unimplemented contract. Tasks 2–6 must add the behavioral tests in the plan: unique
concurrent reservation, immutable bytes after edits/restart, migration from V25, filesystem rollback,
Spring-proxy transaction assertions, same-key and contradictory sends, both token paths, cross-case denial,
read failures before send, owner/generation races, every crash row above and bounded staging quota/expiry
races with a controlled clock. Task 7 proves both templates and staging with real local CCD/CDAM and the
existing guarded dm-store stub. Task 8 records final full/Docker-free/cftlib/drift evidence and runbooks.
Neither a Docker-free pass nor this contract proves real Postgres races, deployed CDAM authority or rollout.

## Task 3 implementation boundary — 2026-10-02

CDAM forms sends now use `CaseEmailService.send` as a coordinator with explicit `TransactionTemplate`
steps. The first step validates and resolves existing requests/group occupants before storage work.
`DeedPreparations.prepare` commits the original bytes and identity; `CdamDocumentUploads.continuePrepared`
claims that row with `DocumentStorageStates.claimPrepared`, committing its first counted send before I/O.
Only an unclaimed UploadPending row can acquire this authority. A competing request or a restart after
claim refuses; it cannot reset the attempt counter. Within the owning request the existing two-send
upload and bounded attachment rules apply. Attached rows are reused without another binary upload.

Recovery is conservative: Uploaded, AttachUncertain, UploadUncertain and Failed preparations are parked
on a later request, including a known remote copy whose unattached lifetime/token may have expired.
There is no automatic attach continuation across requests, token refresh or operator repair endpoint.
A refused upload also remains evidence; a retry is not permission to create another legal instrument.
The existing handler/snapshot behavior is unchanged, including Attached winning after a lost answer.

V27 adds an immutable fingerprint of the template's resolved deed fields using the existing DeedField
mapping. V26 reservations retain a null fingerprint: original inputs cannot safely be inferred from the
current case. They require investigation before a new forms enqueue, as do legacy Attached deeds without
preparation evidence. The fingerprint is never a public field or diagnostic value. The final short
transaction locks the case and document, revalidates entitlement, recipient, composed personalisation,
CCD binding, direction, metadata and Attached state, and compares the original deed inputs. It then
commits enqueue and its note atomically. A failed enqueue leaves the attachment and payload intact.
Filesystem sends and document-free emails retain their local transaction behavior.

This task retains temporary bytes. Task 4 supplies service-authorised read-back/checksum verification and
payload release; Task 5 still owns splitting the Notify drain. Consequently this is not yet an end-to-end
CDAM correspondence delivery or deployment claim. Production switches remain off.
