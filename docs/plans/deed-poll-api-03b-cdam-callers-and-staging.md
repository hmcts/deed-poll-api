# Plan 03b — CDAM callers: generated deeds, Notify retrieval and pre-case staging

**Execution handoff, 2026-10-02: all three design flags are resolved and accepted by Steve.**
No implementation or baseline validation was performed during this decision review. Use the appended
decision record as authoritative over the original proposals and interim open-status notes below.
Task 1 must document and validate these choices, not request the same decisions again.

| Flag | Accepted implementation direction | Still required before activation |
|---|---|---|
| 1 | Bounded Postgres payload staging; preserve original bytes until verified attachment/read-back | Capacity/backup checks, explicit production quotas and backlog monitoring |
| 2 | Per-notification claims/outcomes; no automatic resend of a possibly-sent email; one failure note | Operational investigation ownership/procedure; no new reconciliation or resend endpoint in 03b |
| 3 | Existing-record and future citizen-draft owners; accepted upload ceilings and mandatory quotas; fixed expiry by owner type; opt-in bounded scheduled cleanup | Production quotas/expiry durations, cleanup schedule/limits and responsible team; actual draft authority and web byte handoff before citizen activation |

Use explicit finite test configuration for local implementation. Missing production settings must prevent
activation, not cause invented defaults. Keep CDAM and staging off by default. External onboarding, live
smoke and 03c migration remain rollout prerequisites. ADRs 0007–0009 and the release checklist preserve
the business decisions and their release conditions. Implementation tasks remain unchecked.

Status: **draft for review, 2026-10-01. No implementation performed by this plan.**

**Current decision status, 2026-10-02:** Flags 1/2 and flag 3's owner scope, limits and expiry policy are
accepted. Only the cleanup decision remains open. Production durations and capacity values remain
activation prerequisites; [ADR-0008](../../../deed-poll-documentation/decisions/0008-staged-upload-expiry-requires-release-agreement.md)
and the product release checklist record the required pre-release consideration. The dated entries below
preserve the earlier discussion; the appended decisions supersede their unresolved statuses.

**Latest decision update, 2026-10-02:** Flags 1 and 2 are accepted. Flag 2 is also recorded as the
product business rule in [ADR-0007](../../../deed-poll-documentation/decisions/0007-uncertain-email-delivery-requires-investigation.md).
Only flag 3 remains open. The appended decision record supersedes the earlier proposed/open statuses.

**Flag 3 ownership update, 2026-10-02:** Both owner types are accepted: an existing local deed-poll
record awaiting its CCD link, and a future citizen draft. Citizen upload activation remains gated on
implemented draft ownership and authentication. Limits, expiry and cleanup remain unresolved.

**Flag 3 limits update, 2026-10-02:** Steve accepted the limits policy recorded below. Owner scope and
limits policy are settled; production capacity values remain activation prerequisites. Expiry and cleanup
still need decisions. This supersedes the earlier statement that the limits policy is unresolved.

**Decision update, 2026-10-02:** Steve accepted flag 1 (bounded Postgres payload staging), subject to
capacity checks and explicit quotas before activation. Flags 2 and 3 remain open. The decision record
below supersedes the original proposed status of flag 1; the original proposal is retained for context.

Depends on [03a](deed-poll-api-03a-cdam-core-round-trip.md), merged through PR #73 as `f4eb7db`, and
[02b](completed/deed-poll-api-02b-ccd-linking-lifecycle.md). This is the second part of
[plan 03](deed-poll-api-03-cdam-documents.md). Existing-file migration belongs to 03c.

The inspected baseline is `origin/master` at `f4eb7db`. The September 29 counts in 03a are historical
evidence, not a build of this baseline. Re-measure in Task 1. The choices marked **proposed** below are
recommendations for review, not decisions already made by Steve. Resolve flags 1–3 before executing
the tasks that depend on them; external onboarding blocks the live smoke, not the local build.

## Outcome

A caseworker can request either forms-for-completion email with CDAM selected. The API generates the
correct LOC020 or LOC022 once, attaches it to the already-linked CCD case, and freezes its local document
ID onto the notification. A later drain retrieves those same bytes through CDAM, independently of the
staff request that happened to trigger it. A restart, another replica, a refused upload or a lost answer
must not silently generate a different deed or turn an uncertain email into an automatic second send.

Documents accepted before a case is ready have durable, owned staging with explicit promotion, retry
and expiry. Staging is not a CCD attachment and is never presented as one. The implementation defines
and tests the storage contract for the future citizen API; activating citizen uploads also requires
that API's draft ownership/authentication and the web handoff.

CDAM and staging remain off by default. Completing 03b is not permission to switch an environment with
existing filesystem documents to CDAM: those documents still require 03c and a verified rollout.

## What the code does now

Paths below are relative to `src/main/java/uk/gov/hmcts/reform/deedpoll/` at the baseline. Task 1 records
the final call graph and transaction/authentication boundaries in `docs/CDAM-CALLERS.md`.

| Current source | Consequence for 03b |
|---|---|
| `notify/CaseEmailService.java:538`, `send` | Its transaction includes composition, deed generation, outbox insert and the enqueue note. Taking the annotation off `storeDeed` alone does not remove this transaction. |
| `domain/deeds/DeedDocumentService.java:126`, `storeDeed` | Reads either deed type, refuses the wrong template or direction, then generates and uploads in a transaction. Its documented read-before-write scheme can create duplicate rows under concurrent sends. |
| `domain/documents/DocumentService.java:248`, `uploadDocument` | The filesystem path joins a transaction; the remote path delegates to `CdamDocumentUploads`, which refuses an active transaction. |
| `storage/CdamDocumentUploads.java:76`, `upload` | Commits a new document row before upload. It does not yet resume an existing generated-deed reservation. Uncertain upload gets at most two sends, within the same request. |
| `domain/documents/DocumentService.java:306`, `downloadDocument` | Checks case ownership and storage state. Its short read still joins a caller's transaction; it cannot release the drain's transaction. |
| `storage/CdamDocumentStore.java:165`, `open` | Uses the current staff caller's token. With no caller it refuses; there is deliberately no system-user fallback. |
| `notify/NotificationOutbox.java`, both `drain` overloads | One `REQUIRES_NEW` transaction covers the batch, its claims, file retrieval, Notify calls, outcomes and terminal-failure notes. Moving remote work outside that transaction changes the concurrency mechanism. |
| `repository/NotificationRepository.java`, `claimNextDue`; `notify/NotificationBackoff.java` | The existing conditional claim advances `nextAttemptAt` and increments attempts. The transaction currently holds the row lock through send; a five-minute lease alone is not equivalent protection after splitting it. |
| `notify/DocumentUploads.java:104`, `resolve` | Resolves only the template's declared document keys, through a case-scoped read, into a copy of frozen personalisation. Unreadable files are `TEMPORARY`; malformed references/oversize are `TECHNICAL`. |
| `notify/NotifyEmailSender.java`, `send` | Uses the outbox UUID as Notify's reference. A reference is not proof of provider-side deduplication; a status-zero/unknown send is deliberately terminal. |
| `api/citizen/` and the Fees & Pay plan | There is no implemented citizen controller/draft ownership contract here. Pre-case staging cannot invent that authority. |

The existing local document states distinguish `Local`, `UploadPending`, `UploadUncertain`, `Uploaded`,
`AttachUncertain`, `Attached` and `Failed`. `Failed` can follow an attach refusal after a binary was stored;
it does not prove absence. Reuse that evidence instead of adding an independent, contradictory upload state.

## Established constraints

1. **Link first.** Neither generated-deed code nor staging calls the CCD linking operation. An ordinary
   caseworker upload to an unlinked record still returns the existing 409. Staged promotion waits for a
   confirmed binding supplied by the owning application flow.
2. **One deed per case.** Preserve template routing, court wording/content controls, original filename,
   MIME type, `Outgoing` direction and the existing wrong-template/wrong-direction refusals. Never regenerate
   from a later case snapshot to recover a storage failure. Never edit the vendored templates/reference files.
3. **The local document ID is the public identity.** A storage attempt, notification and CCD projection must
   agree on it. No CDAM IDs, URLs or hash tokens in a new public contract.
4. **03a's remote rules remain.** Use the existing document event and its handler; data-store performs the
   CDAM attachment. No direct `attachToCase` call from this API. No third upload after two uncertain sends,
   and no automatic replay of an upload left ambiguous by a crash. No permanent deletion.
5. **Keep staff downloads under the staff token.** Internal correspondence retrieval is a distinct authority,
   never a fallback when a staff download gets 403, 404 or 502. Keep the unmapped-staff 502 limitation explicit.
6. **Keep the email contract.** Required case-scoped `Idempotency-Key`, 200/already-owed and conflicting-content
   409, V15/V19/V20 exclusion rules, frozen recipient/slots and one note per fresh enqueue or terminal failure.
   Preview/availability never generate, upload, reserve a deed or enqueue an email.
7. **Use short committed database steps around remote I/O.** Do not suspend a transaction while keeping its
   locks/connection open across CDAM or Notify. Do not merely replace an annotation and lose atomic checks.
   Apply this to the changed send/drain paths; do not broaden the work into unrelated preview refactoring.
8. **Add migrations; never edit committed ones.** V25 is highest at the inspected baseline. Determine the next
   free number when implementing; this plan reserves none. Preserve mixed-case identifiers and SDK migration order.
9. **No live calls in tests.** WireMock and real local CCD/CDAM via cftlib with the existing dm-store stub only.
   Keep its version/spec/provenance guards and non-loopback network restriction. No test credentials or real data.

## Proposed implementation decisions

### A. Durable preparation before remote storage — flag 1

Use a separate, bounded Postgres staging table for immutable bytes and metadata, shared by generated-deed
preparation and pre-case uploads. This is a **proposed temporary store**, not a replacement for CDAM. It makes
the exact generated bytes available to another replica without provisioning a second object-storage service.
It adds database/backup volume; inspect limits and capacity before accepting the choice. Do not hide that cost
by storing base64 in outbox JSON or by writing files to a pod.

A generated-deed reservation has a database-enforced unique case key across both templates, an explicit
template identity, its local document identity and checksum. Under a short case lock, check all existing deed
rows before reserving. Existing rows are reused or refused under today's rules; do not backfill provenance
from a type name alone. Commit the generated bytes and reservation atomically before any remote call.
Concurrent requests reuse that committed preparation rather than generating another deed.

Retain the exact bytes until attachment and verified read-back, then remove the temporary payload while
retaining the identity/checksum evidence. Failed or uncertain generated deeds are not abandoned drafts:
they are held for recovery and never expired into permission to generate a replacement. A recovery path may
resume a known safe step; ambiguous upload outcomes remain parked under 03a's cap. If an attach's token has
expired, do not invent a refresh/re-upload rule. Record the evidence and require a separately reviewed repair.

### B. Prepare, attach, then atomically enqueue

Split forms-email send into a non-transactional coordinator and explicit short transactional operations:

1. Validate entitlement, address, slots, provisioning and contradictory outbox occupants before generation.
   Resolve an already-recorded matching request without new storage work. Preserve current refusal precedence.
2. Reserve/freeze the deed (A), then upload and attach outside a transaction, using its existing local identity.
3. Revalidate the state that can change while I/O runs, including template routing, recipient/slots, deed
   ownership/direction and exclusion-group occupants. Do not pair a frozen deed with contradictory later case
   data. Record which input changes cause refusal; harmless edits must not regenerate an existing deed.
4. In one transaction enqueue the frozen correspondence, write its note and any confirmation-clock change.
   Keep `ON CONFLICT DO NOTHING` and the ordered refused-insert resolution. Only an `Attached` CDAM deed is
   eligible; a pending or uncertain row never yields a successful send receipt.

An attached deed can survive a refused/rolled-back final enqueue. Keep and reuse it; rolling back the email
must not delete a potentially valid deed. This is a deliberate change from the filesystem path's single
transaction, and needs explicit regression tests. Preserve the filesystem path's existing rollback behaviour
unless a reviewed amendment explains why a shared implementation must change it. Emails without documents
retain their single local enqueue transaction.

### C. Separate authority for correspondence retrieval

Add a narrowly scoped internal reader for a claimed notification's declared, frozen document references.
It re-reads the notification and document case binding, verifies readiness, and fetches CDAM bytes with fresh
system-user and S2S tokens. It accepts neither a caller-supplied CDAM locator nor an arbitrary public request
to read as the system user. Do not store staff access tokens on the outbox or impersonate its `createdBy` name.

Keep the public staff reader separate. Both readers share ownership/storage-state checks, not an auth fallback.
Use the existing system identity only if its confirmed CCD/CDAM permissions cover this purpose; do not invent
another role. Local cftlib uses its existing system profile as a fixture. Live permission remains an onboarding
dependency. Use the current Notify size bound and retention validation; preparation tokens remain transient.

### D. Commit each outbox claim and outcome separately — flag 2

The drain becomes non-transactional orchestration. Claim one row and return an immutable send snapshot in a
short transaction; resolve its documents and call Notify outside a transaction; settle it in another short
transaction. Settlement and its terminal-failure note remain atomic. A later row's failure cannot roll back
an earlier accepted row. Retain the traffic-triggered drain and its bounded batch; this plan adds no email scheduler.

Because the current row lock no longer spans the network call, add durable claim ownership/generation and
conditional settlement. An expired/stale worker cannot settle another claim or start a send after losing its
claim. Set a durable **send-may-have-started** fact immediately before `sendEmail`, in a committed conditional
transition, and send nothing if that transition loses the claim. File retrieval failures happen before this fact.

**Proposed crash rule:** a stale claim with that fact set is not automatically sent again. Preserve it as a
terminal technical/uncertain outcome, with one failure note and evidence for operator investigation. A known
retryable Notify refusal may safely schedule the next attempt through a conditional settlement; an unknown
answer may not. A stale claim that provably never entered send may retry within the existing attempt bound.
Elapsed time alone never proves a send did not happen. Preserve the existing status-zero taxonomy.

This closes the new split-transaction crash window conservatively; it does not promise exactly-once delivery.
Notify might accept immediately before the process dies or before an outcome commit fails. No provider
deduplication or reliable lookup is assumed merely because a reference was sent. Provider receipt reconciliation
and an operator resend endpoint remain separate work. This change affects document-free emails too, so their
regressions belong in the task, not in a later Notify plan.

### E. Pre-case staging is a separate lifecycle — flag 3

Define two owners explicitly: an existing local deed-poll record awaiting its CCD link, and a future citizen
draft. The latter needs the citizen API's durable, authenticated ownership contract. A random draft/upload UUID
alone is not authority. Until that contract exists, implement/test the domain seam with fixtures and leave its
public citizen adapter absent/disabled; do not add a permissive upload endpoint to make a smoke test work.

Stage only on an explicit staging operation. The existing caseworker upload endpoint keeps its 409 behaviour.
Validate file content/metadata and per-file, per-owner and total byte/count quotas before accepting durable
bytes. The existing caseworker ceiling and citizen journey limits are different; do not silently pick one
for every caller. Return an opaque local stage identity to the owning adapter, never a CDAM locator.

Promotion binds a stage to one verified local case and then its confirmed CCD reference, once. Commit the
ownership transfer/local document reservation before remote I/O. Repeated promotion finds that same document;
wrong-owner, wrong-case, expired and already-consumed-to-another-case requests are refused. Apply 03a's bounded
upload and attachment rules. A staged payload surviving a restart is not permission to restart an ambiguous upload.

Use an explicit state table, for example `Staged -> Promoting -> Consumed`, with `Staged -> Expired/Removed`
and a separately visible parked promotion. Document states remain the authority for remote progress. Atomic
claims settle races between promotion, removal and expiry; a crash during promotion is reconciled before
any bytes can be discarded. Read-back/checksum verification precedes payload deletion after consumption.

Pre-case expiry is configurable with **no invented production duration**. It is separate from CDAM's unattached
upload TTL, Notify's link retention and the seven-day unpaid-submission policy. Implement a bounded, multi-replica
safe cleanup operation and test it with a controllable clock. **Proposed trigger:** an opt-in Spring scheduled
invocation for staging cleanup only, enabled together with staging and validated at startup, so abandonment
does not depend on caseworker visits. Bound each run's rows/bytes/time and leave ambiguous promotion alone.
Agree the trigger, limits and operational ownership before deployment. Deleting staged payloads does not
dispose a submitted case or a remote document; backups and retained audit metadata need their own policy.

## Dependencies and scope boundaries

- **External onboarding:** confirmed CCD identifiers, S2S registration/allowlists, system account ownership,
  CDAM CREATE/READ/UPDATE permissions for this service, and staff CCD READ access. Data-store owns ATTACH.
  Configuration checks cannot prove these permissions. No live smoke until they are confirmed.
- **Notify provisioning:** verify the existing service/template/reply-to/retention settings when preparing the
  smoke. This plan neither assumes they are missing nor treats local mocks as proof of live configuration.
- **Citizen adapter:** [Fees & Pay](fees-and-pay-1-ways-to-pay-card.md) owns citizen draft/authentication and
  submission. That flow owns linking and queue admission. 03b owns the staged-byte contract; it must not create
  placeholder applications or mark an unpaid application queue-eligible to obtain a CCD reference.
- **Web handoff:** the inspected web `submitJourney` sends answers/citizenship; it does not establish a transfer
  of upload bytes to this API. Record the required upload/promotion/error contract and a separate web task.
  Do not claim an end-to-end citizen upload until that adapter, ownership and actual bytes are exercised together.
- **03c:** existing filesystem files, including previously generated deeds and their queued notifications,
  need migration. Do not introduce an accidental fallback that makes pod-local files appear safe across replicas.
- **Retention:** permanent remote deletion, submitted/unpaid case expiry and historical disposal remain in
  [retain-and-dispose](retain-and-dispose.md) and [unpaid-online-applications](unpaid-online-applications.md).
- No new document-delete HTTP route, general recovery console, template wording changes, payment implementation,
  blanket changes to staff roles, platform upgrades or source edits in sibling repositories.

## Validation commands

Run from this repository, with no other Gradle build active. Keep builds in the foreground and wait for
completion; do not start overlapping runs. Use fresh XML and record failures/errors/skips as well as totals.

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew cleanTest cleanIntegration build --no-watch-fs
INTEGRATION_TESTS_ENABLED=false ./gradlew cleanTest cleanIntegration build --no-watch-fs
./gradlew checkstyleCftlibTest cftlibTest --no-watch-fs
./gradlew checkDmStoreSpecDrift --no-watch-fs
```

Task 1 records both build baselines. Each implementation task runs focused behavioural tests and the normal
full build; Task 8 records Docker, Docker-free and cftlib results on the final code. Run cftlib when changing
the remote lifecycle/authentication and for final proof. A Docker-free run does not prove Postgres claims or
concurrency. Preserve meaningful container-free companions for state/permission/failure decisions.

## Tasks

### Task 1: Record the caller contract and settle the design flags

- Inspect current `master`, migrations and any work since this baseline. Record both fresh build baselines here.
- Write `docs/CDAM-CALLERS.md` with source citations: send/preview/drain entry points, transaction propagation,
  lazy entity accesses, ownership/authentication, document states and current notification claim behaviour.
- Record the three proposed decisions and their accepted alternatives before dependent implementation.
  Include a crash table for before/after each local commit, upload, attach and Notify send.
- Specify which state/input changes invalidate final enqueue, and exact retry/refusal responses within the
  existing HTTP contract. No success receipt before a real enqueue or an existing matching notification.
- Pin pre-case owner, quotas, expiry/cleanup contract and the citizen/web handoff. If citizenship/draft auth is
  not yet implemented, name that adapter dependency without blocking the local staging domain tests.
- [x] Baseline and sequence recorded; flags resolved or the affected task explicitly gated; no source changes.

Task 1 evidence — 2026-10-02:

- [Caller contract](../CDAM-CALLERS.md) audits the source entry points, transactions and lazy reads,
  staff/system authority, storage states and current claim SQL. It specifies final-enqueue validation,
  HTTP retry/refusal semantics, local/remote crash boundaries, accepted staging policy and citizen/web handoff.
- Inspected HEAD and locally recorded `origin/master`: `f4eb7dbb1c7ba5673ed01fa57e9de1e6666b7823`.
  Local `master` is stale at `b2d594e5d4e92aa7d3637370b745860d7584e638`; no fetch/reset/push was performed.
  No source changes since the inspected plan baseline; V25 remains highest. Existing unrelated documentation
  edits in RESUME and plans 03/03a were preserved outside this task's commit.
- All design flags are accepted by the final decision record, including ADRs 0007–0009. No design decision
  was requested again. Production quotas/durations/schedule, capacity and backup evidence, operational
  ownership, onboarding, citizen draft authority/web byte transfer and 03c remain activation dependencies.
- Fresh validation ran sequentially from this repository with Java 21.0.12.1 and Docker 27.5.1.
  `TESTCONTAINERS_RYUK_DISABLED=true` was set for both builds and cftlib. Counts below come from fresh XML,
  including failures/errors/skips; timings are Gradle wall-clock reports, not summed test durations.

| Command | Result and duration | Fresh XML counts |
|---|---|---|
| `./gradlew cleanTest cleanIntegration build --no-watch-fs` | PASS, 1m 26s | test: 3,457; integration: 14; failures 0, errors 0, skipped 0 in both |
| `INTEGRATION_TESTS_ENABLED=false ./gradlew cleanTest cleanIntegration build --no-watch-fs` | PASS, 35s | test: 2,339; failures 0, errors 0, skipped 0; integration not run |
| `./gradlew checkstyleCftlibTest cftlibTest --no-watch-fs` | PASS, 35s | 9 tests; failures 0, errors 0, skipped 0 |
| `./gradlew checkDmStoreSpecDrift --no-watch-fs` | PASS, 3s | dm-store OpenAPI has no differences from the pinned copy |

Full-run XML suite start timestamps span 12:54:32–12:55:51 UTC on 2026-10-02. Docker-free XML spans
12:56:32–12:57:01; cftlib XML is stamped 13:02:24. Full XML was copied to `/tmp/03b-task1-full-xml`
before the Docker-free clean removed it. Logs for this execution are `/tmp/03b-task1-full.log`,
`/tmp/03b-task1-docker-free.log`, `/tmp/03b-task1-cftlib.log` and `/tmp/03b-task1-drift.log` (ephemeral).
Docker-free filtering excludes `requires-docker` tests rather than reporting them as skipped; it does not
prove database claims/concurrency. The local cftlib baseline exercises the existing 03a journeys, not the
unimplemented 03b callers. It uses real local CCD/CDAM with the guarded dm-store stub and the existing
non-loopback HTTP restriction. No deployed permission or live smoke is claimed. Gradle deprecation and
JVM SVE warnings were non-failing. Source-link existence/line bounds and `git diff --check` also passed.
No new source tests were added because Task 1 expressly requires no source changes; implementation and
its new behavioral tests begin in Task 2. Tasks 2–8 remain untouched.


### Task 2: Persist a single deed preparation and its original bytes

- Add forward migrations for the accepted staging/reservation design; enforce uniqueness across LOC020/LOC022
  for one case. Keep payload reads out of ordinary case/document-list queries and responses.
- Implement immutable payload metadata/checksum and transactional reservation. Inspect existing deed rows in
  both directions before generating. Reuse a valid existing deed; refuse an incompatible or unavailable one
  without regeneration. Legacy duplicates are evidence to resolve, not rows to delete opportunistically.
- Prove concurrent different request IDs and different templates cannot reserve distinct new deeds for one case.
  Prove later case edits/restarts preserve the original bytes, and no payload or hash leaks through `toString`,
  logs, metrics or DTOs. Test migration on an existing V25 database and the filesystem path's rollback behaviour.
- [x] Durable preparation preserves one identity and exact bytes; migration and concurrency tests pass.

Task 2 evidence — 2026-10-02:

- V26 adds a case-unique reservation across both templates and separate immutable payload storage with
  byte-count/SHA-256 constraints. Existing rows are not backfilled or deleted. A real Flyway V25-to-V26
  upgrade preserves populated legacy documents and validates migration history; repeat migration is a no-op.
- `DeedPreparations` commits the document identity, original metadata/checksum and bytes together under a
  case lock. Concurrent independent requests reuse one identity; competing templates produce one refusal.
  Explicit shared capacity admission serialises across replicas and counts retained uncertain payloads.
  The default capacity is zero, so no production quota or activation is implied.
- Existing deeds in both directions are inspected; duplicates, incompatible or unavailable documents are
  refused without regeneration. Case edits and another service instance retain the exact original bytes.
  Removed/refiled preparations cannot cause filesystem regeneration. Filesystem rollback still removes
  the row and blob. Payloads are absent from ordinary entities/DTOs; diagnostic and architecture tests
  guard bytes, checksums and applicant metadata from preparation diagnostics and public/metric types.
- Focused behavioural tests plus `checkstyleMain checkstyleTest`: PASS, 37 tests, zero failures/errors/skips,
  24s. The newly added real-upgrade fixture initially used a nonexistent type-table column; corrected before
  the passing run. Log: `/tmp/03b-task2-focused.log`.
- `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew cleanTest cleanIntegration build --no-watch-fs`: PASS,
  1m 29s, test 3,481 and integration 14, zero failures/errors/skips; Checkstyle passed. Fresh test XML
  timestamps span 13:49:08–13:50:21 UTC; integration 13:48:58–13:49:05. Log: `/tmp/03b-task2-full.log`.
  Gradle deprecation and JVM SVE warnings were non-failing. `git diff --check` passed.
- Remote orchestration/authentication is unchanged, so cftlib is not repeated at this task boundary.
  Preparation is an internal persistence seam, not a usable-document receipt; Task 3 wires remote
  continuation/enqueue, and Task 4 verifies read-back before payload release. Tasks 3–8 remain unimplemented.
  Existing unrelated RESUME and plans 03/03a edits are preserved outside this task's commit.

### Task 3: Attach prepared deeds and split the email enqueue transaction

- Adapt `DeedDocumentService`, `CaseEmailService` and the remote upload seam to continue a reserved document;
  do not call the new-row upload path on every retry. Use small projections/values outside transactions.
- Implement B's prepare/attach/revalidate/enqueue sequence. Keep notes and the outbox insertion atomic.
  Add a transaction-active assertion at remote orchestration boundaries and test calls through Spring proxies.
- Cover same-key retries, changed content, contradictory email/template, a case edit during upload, failed
  final enqueue and request loss after successful enqueue. Preserve attached deeds across the last two cases.
- Cover no CCD link, upload refusal, each uncertain upload, attach refusal/lost response, expiry of an unattached
  remote copy, and crash after claim. No uncertain row is returned as a usable deed; no extra upload budget.
- Preserve 03a's handler/snapshot semantics. Keep staged bytes at this task boundary; Task 4 supplies the
  service-authorised read-back/checksum verification required before releasing them.
- Update response descriptions only where the new refusal is observable; no new email route or success shape.
- [ ] Both forms emails can enqueue attached CDAM deeds; remote calls hold no database transaction.

### Task 4: Give the Notify document reader explicit service authority

- Add C's internal reader and share the local ownership/readiness checks with the staff reader. Wire only the
  correspondence path to the privileged reader; no public controller can choose that authority.
- Prove a drain with no security context and one triggered by unrelated staff both use the system token.
  Prove ordinary staff downloads still use the staff token, and never retry a refusal as the system user.
- Reject a frozen reference to another case before any CDAM call; reject non-attached/local-under-CDAM rows.
  Prove fresh token acquisition failure and CDAM read failures produce a retryable pre-send outcome, with no
  Notify send and no rollback poisoning. Preserve the existing size/retention/technical-error classifications.
- Keep notification personalisation unchanged and resolve only declared keys. Close streams on every path;
  enforce the Notify size limit without widening the public upload limit.
- Wire service-authorised read-back/checksum verification into successful deed preparation before its temporary
  payload is released. A read-back failure retains the payload and never starts another binary upload.
- [ ] Retrieval works without a staff session and preserves case scope, token separation and frozen content.

### Task 5: Split the drain with durable send ownership and crash handling

- Implement D through real transactional collaborators/templates, not self-invoked annotations. Add claim
  ownership and send-start evidence with migration/backfill rules for already pending/sent/failed notifications.
- Preserve retry limits/backoff and no-sender behaviour. Claim only one row at a time from the bounded batch;
  acquire send authority just before the network call and condition settlement on the same owner/generation.
- Prove two replicas cannot both acquire send authority, a stale worker cannot send/settle, and expired pre-send
  claims respect the attempt cap. A claim that may have sent is not reclaimed for automatic delivery.
- Fault-inject death after claim, after document read, after send-start commit, after Notify acceptance and
  before/after outcome commit. Test a lost/failed outcome commit without proceeding to another automatic send.
- Replace tests that pin whole-batch rollback with per-notification atomicity: a later file/note/database failure
  never unsets an earlier committed send. Terminal state plus its one note either commit together or neither does.
- Test every document-free template too. Document the technical/uncertain recovery limitation and mixed-version
  rollout: old drain code must be stopped before new claims are processed, unless explicit compatibility is proved.
- [ ] Concurrency and crash tests prove claim ownership, bounded retries and no blind resend of ambiguous sends.

### Task 6: Implement owned pre-case staging, promotion and expiry

- Implement E using the accepted backing store and a separate staging domain API. Make admission opt-in and
  refuse startup/admission with incomplete limits/expiry/cleanup configuration; do not default to unlimited bytes.
- Persist owner binding, immutable bytes/metadata/checksum, original expiry and idempotent operation identity.
  Repeated admission/promotion cannot extend expiry or transfer a stage to a different owner/case.
- Reserve promotion atomically with removal/expiry; resolve the confirmed CCD binding without calling linking.
  Reuse one local document row across retries and replicas. Park ambiguous promotion instead of deleting evidence.
- Implement the bounded cleanup operation and its accepted trigger. Generated-deed preparations use their
  recovery rule, not draft expiry. Do not describe application deletion or backup erasure as achieved here.
- Test restart, another replica, wrong owner/case, quota races, changed content under one idempotency key,
  expiry boundary, promotion-versus-expiry/removal, and crash at each remote boundary with a controlled clock.
- Publish the future citizen adapter contract in `CDAM-CALLERS.md`. No citizen HTTP route until its actual
  draft authority exists. If it does exist by implementation time, add the adapter with full-context security
  and OpenAPI tests and record its explicit contract changes; do not modify the web repository in this task.
- [ ] Durable staging, promotion and cleanup are tested; unfinished citizen/web activation is named explicitly.

### Task 7: Prove caller journeys against real local CCD/CDAM

- Extend the existing cftlib fixtures; use real CCD/CDAM, the guarded dm-store stub and stubbed Notify/auth.
  Keep all network traffic local. Do not add the entire unit-test output to cftlib's classpath.
- For both deed templates: create/link a case, request forms, verify its attachment/snapshot, replace the API
  context before draining, and prove Notify receives the original bytes/filename through CDAM. Exercise the
  deferred drain directly when the public send's immediate drain must be held back; do not add a test-only route.
- Prove a repeated send reuses the same local document and no second successful binary upload; later case edits
  do not rewrite it. Prove the service-reader path with no staff context and a genuine CCD READ refusal.
- Exercise staged admission before linking, refusal to promote while unlinked, then one promotion after the
  existing linking flow completes. Test with a domain owner fixture if the citizen adapter is still pending.
- Keep wrong-case/staff-denial coverage separate from system-reader success. Record wall-clock timings and
  distinguish local fixture proof from deployed access. Compare fresh test XML, not a task exit code alone.
- [ ] Deed/Notify/staging journeys pass with real local permission/attachment checks and no live calls.

### Task 8: Record operational limits and the handoff to 03c

- Update `CDAM-RECOVERY.md`, `CDAM-SMOKE.md`, README configuration, `CLAUDE.md` and `RESUME.md` for prepared deeds,
  privileged correspondence reads, claims/unknown sends, staging limits and cleanup. Correct comments that still
  assert a whole-batch transaction or a single transaction around generation/upload/enqueue.
- Prepare an operator smoke for forms → attachment → later drain → received Notify file, staff access checks,
  and staged promotion/expiry where its owner adapter exists. Require synthetic data, onboarding and real
  dm-store response captures; preparing the procedure does not run it or enable CDAM.
- Document safe upgrade/rollback boundaries for new outbox workers and staged rows. Simply switching back to
  filesystem cannot serve attached CDAM documents. Identify how workers are quiesced before a mixed-version rollout.
- Record bounded metrics for preparation/staging backlog, oldest age, uncertainty and expiry failures; no
  case/document/user IDs, names, tokens or payload labels. Identify operational ownership and capacity checks.
- Specify 03c's handoff: local document identities/checksums, previously generated deeds, queued email references,
  source preservation and verification requirements. Existing `Local` rows remain a cutover dependency.
- Run final full/Docker-free/cftlib/drift checks and record exact commit, fresh counts and limitations. Leave
  production switches off; distinguish local completion, citizen adapter readiness, onboarding and rollout.
- [ ] Final evidence and runbooks agree; 03c and the citizen/web handoff have concrete inputs.

## Flags for Steve

1. **Temporary durable bytes.** Proposed: bounded Postgres payload staging, including generated-deed recovery,
   removed after verified CDAM attachment/read-back. Alternative: a separately provisioned shared object store.
   This determines Tasks 2/3/6 and capacity/backup policy; pod disk is not a valid deployed alternative.
2. **Outbox crash tradeoff.** Proposed: per-row committed claims/outcomes, and park a possibly-sent email as
   technical/uncertain rather than automatically resending after a lease expires. This changes crash recovery
   for all email templates. Confirm before Task 5; provider reconciliation would be additional work.
3. **Pre-case admission and expiry.** Confirm the supported owner(s), limits, duration and cleanup trigger.
   Proposed: the reusable staging domain in 03b, with citizen activation dependent on real draft authority;
   opt-in bounded scheduled cleanup, separate from the traffic-triggered email drain. No production expiry
   number or citizen authentication scheme is assumed by this draft.

## Source and related plans

- [03a completion and handoffs](deed-poll-api-03a-cdam-core-round-trip.md)
- [CDAM protocol and permission evidence](../CDAM-SEQUENCE.md)
- [Current recovery limits](../CDAM-RECOVERY.md)
- [Generated-deed decisions](completed/notify-4b-generate-the-deed.md)
- [Notify document preparation](completed/notify-4a-attach-files-to-an-email.md)
- [Email idempotency](completed/notify-6-chase-missing-documents.md)
- [Citizen API and payment ownership](fees-and-pay-1-ways-to-pay-card.md)
- [Unpaid lifecycle](unpaid-online-applications.md)
- [Retention and coordinated disposal](retain-and-dispose.md)

## Decision record — 2026-10-02

### Flag 1 accepted: bounded Postgres payload staging

Steve accepted a separate, bounded Postgres staging table for generated-deed preparation and pre-case
uploads. Persist the original bytes, local document identity and checksum durably before remote storage
work, so restarts and retries preserve the exact document rather than regenerating from changed case data.
Remove temporary payloads only after confirmed CDAM attachment and successful read-back/checksum verification;
retain failed or uncertain generated-deed preparations for recovery under section A's rules.

Database and backup capacity checks, explicit storage quotas and backlog monitoring are required before
activation. This decision accepts the backing store, not an unbounded allocation or particular production
quota/expiry values. Those values and pre-case admission/cleanup remain part of flag 3. No separate object
store is to be provisioned for this plan. Flags 2 and 3 remain unresolved; no implementation was started.

### Flag 2 accepted: investigate uncertain email delivery before resend

Steve accepted section D's per-notification committed claims/outcomes and the rule that a possibly-sent
email must not be automatically retried. He explicitly requested a product business-process decision;
[ADR-0007](../../../deed-poll-documentation/decisions/0007-uncertain-email-delivery-requires-investigation.md)
is the durable record of that rule and its applicant-facing tradeoff.

The rule applies to all email templates. Known safe-to-retry failures retain bounded retries; an unknown
send outcome is preserved as terminal technical/uncertain with one case note for investigation. An expired
claim or elapsed time does not justify a resend. Each outcome and its terminal-failure note remain atomic,
and a later notification's failure cannot undo an earlier success. This accepts that an email which never
went out may remain stopped until investigated; it does not promise exactly-once delivery.

Task 5 implements the durable claim/send-start/settlement behaviour and crash tests. Task 8 must link the
business decision and document the investigation limits and operational ownership still to be assigned.
Provider reconciliation and an operator resend endpoint remain separate work. This entry supersedes the
earlier statement that flag 2 is unresolved; only flag 3 remains open. No implementation was started.

### Flag 3 partially accepted: staging owner types

Steve accepted support in the staging domain for both an existing local deed-poll record awaiting its
CCD link and a future citizen draft. The citizen adapter remains absent/disabled until its actual durable
draft ownership and authentication flow exists. Domain tests may use owner fixtures; they do not establish
that a citizen can upload or that an arbitrary draft/upload UUID grants access.

This settles the owner scope in section E and Task 6. It does not enable staging, add a citizen endpoint,
change the existing caseworker upload endpoint's unlinked-case refusal, or settle quotas, expiry duration
or the cleanup trigger. Those remaining parts of flag 3 still need decisions before dependent work.

### Flag 3 limits policy accepted

Steve accepted preserving the existing upload ceilings: citizen uploads allow at most 10 files per draft,
each up to 7 MB; caseworker uploads retain their 25 MB per-file limit and require an explicit configurable
staged-file count limit per record. Preserve the exact existing byte values behind those user-facing labels:
7,340,032 bytes in `deed-poll-web/libs/deed-poll-journey/src/journey/uploads.ts` and 26,214,400 bytes in
this API's `UploadPolicy.MAX_UPLOAD_BYTES`.

Staging also requires configured per-owner byte limits and an overall storage cap. Missing required limits
prevent staging activation; there is no unlimited fallback. Enforce quotas atomically under concurrent
admission. A reached cap refuses new uploads rather than deleting retained documents or preparations awaiting
recovery. Retained payloads must remain accounted for while they occupy storage.

Production capacity values, including the caseworker staged-file count, owner byte budgets and total cap,
remain prerequisites for activation after capacity/backup checks. Explicit finite test settings allow local
implementation and tests to proceed without inventing production values. This does not change Notify's
separate 2 MB file limit or enable citizen uploads. The owner scope and limits policy are now accepted;
expiry and cleanup remain unresolved. No implementation was started.

### Flag 3 expiry policy accepted; production durations require release agreement

Steve accepted expiry measured from first successful staging, with retries preserving the original expiry.
Citizen drafts and existing-record uploads have separately configurable durations, with explicit production
values required before activation. Generated deeds awaiting recovery and uploads with promotion in progress
or uncertain are excluded from automatic expiry deletion. Staged-upload expiry is separate from the seven-day
payment deadline, CDAM unattached-document lifetime and Notify link retention.

Steve explicitly requested that this decision be recorded and considered before release.
[ADR-0008](../../../deed-poll-documentation/decisions/0008-staged-upload-expiry-requires-release-agreement.md)
is the durable business decision. The
[release checklist](../../../deed-poll-documentation/operations/deployment-readiness-checklist.md#staged-upload-expiry-release-gate--2026-10-02)
tracks agreement of each production duration, rationale/owner, effective configuration and verification.
Task 8 must carry these open items into the release handoff. Implementation may use finite test durations;
it must not invent production values or claim readiness to activate staging while these items remain open.

This supersedes the earlier unresolved expiry-policy status. Only the cleanup decision remains open under
flag 3; production values and operational evidence remain activation prerequisites. No implementation started.

### Flag 3 cleanup accepted; all design flags resolved

Steve accepted an opt-in scheduled cleanup job that runs independently of caseworker visits, limits each
run's rows/bytes/time and safely coordinates across replicas. It leaves generated deeds awaiting recovery
and uploads with promotion in progress or uncertain untouched. Use section E's Spring scheduled invocation
for staging cleanup only, enabled with staging and validated at startup; the email drain remains
traffic-triggered. Production schedule, operating limits and the responsible team must be confirmed before
activation, with monitoring and operational verification recorded in the release handoff.

[ADR-0009](../../../deed-poll-documentation/decisions/0009-staged-upload-cleanup-runs-on-a-bounded-schedule.md)
records this acceptance and follows ADR-0008's expiry decision. The product release checklist separates
the accepted cleanup approach from its still-unverified production configuration and operational ownership.

Flags 1, 2 and 3 are now resolved. This entry supersedes every earlier statement that a design flag is open.
Task 1 retains its caller/crash analysis and fresh build baselines; Tasks 2–8 implement and verify the
accepted design. Finite test settings support local work while production values remain activation gates.
No implementation task was completed, production setting chosen or service enabled during this review.
