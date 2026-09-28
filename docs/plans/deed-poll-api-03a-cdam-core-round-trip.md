# Plan 03a — CDAM core round trip: upload, attach, download and delete a supporting document

Status: **draft for review, 2026-09-28.** The first of the three plans [plan 03](deed-poll-api-03-cdam-documents.md)
was split into (Steve, 2026-09-28). Its decisions are settled there and repeated below where a task depends on them.
Depends on [plan 02](completed/deed-poll-api-02-ccd-foundation.md) and
[plan 02b](completed/deed-poll-api-02b-ccd-linking-lifecycle.md), both merged. Followed by 03b (callers: generated
LOC020/LOC022 deeds, Notify's drain-time retrieval, pre-case staging) and 03c (migration of existing documents).

## Overview

Supporting documents live on the pod's filesystem today (`LocalFilesystemDocumentStore`), behind a seam that takes
bare bytes and returns a key: `DocumentStore.save(byte[])`. This plan:

1. Evolves the seam so a store is told what it is storing and for which case.
2. Adds a CDAM-backed store. Upload, attach to the record's CCD case, download through CDAM's CCD-backed permission
   check, and CDAM soft delete.
3. Records each document's remote progress in local states, so a lost response is never read as success and never
   answered by uploading the binary again.
4. Extends the CCD projection with the document references, and adds the event that attaches them.
5. Proves the round trip in cftlib against real CDAM and CCD, with dm-store stubbed in WireMock and four guards
   against that stub drifting.

**It is buildable before onboarding**, as plans 02 and 02b were. Every identifier is the `DEED_POLL_PLACEHOLDER`
placeholder, and the CDAM store ships **switched off**. Only the operator-run AAT round trip waits on onboarding.

## Decisions taken — implement these, do not revisit

1. **Link first** (Steve, 2026-09-27). Document code only ever handles a record that already has a CCD case. With
   the CDAM store on, an upload to a record with a null `ccdCaseReference` is refused as "case not ready" (a 409
   with fixed prose). It never calls the linking operation. Staging an upload that arrives before its case exists
   is 03b's.
2. **The cftlib tests use a WireMock dm-store, with real CDAM and CCD** (Steve, 2026-09-28). cftlib runs CDAM
   in-process but points it at AAT's dm-store (`DM_STORE_BASE_URL`, `LibRunner.java:54`), and tests make no live
   calls. The hash token, the attach step and the CCD-backed permission check stay real. Rejected: a containerised
   dm-store (too heavy on top of cftlib's ~5.1 GB peak), and stubbing CDAM, which would prove none of the attach or
   permission rules.
3. **Four guards against the stub drifting from dm-store** (Steve, 2026-09-28). Task 6 builds them:
   1. The stub records the cftlib version it was built against (`0.19.2377`, which also pins CDAM) and the
      `cnp-api-docs` commit of the spec. A unit test fails when the cftlib plugin version in `build.gradle` changes
      and the recorded version doesn't.
   2. A copy of `cnp-api-docs/docs/specs/document-management-store-app.json` lives in test resources. A unit test
      checks that every request the stub accepts exists in it: method, path, required headers and multipart fields.
   3. The operator-run AAT round trip captures dm-store's real response bodies, and the stub is rebuilt from those
      captures. Until then the stub's bodies come from dm-store's source, and each one says so.
   4. A manual Gradle task compares the copy of the spec with `platops/cnp-api-docs` and reports differences. It
      isn't scheduled, because api has no nightly pipeline.
4. **Build on `DEED_POLL_PLACEHOLDER`** (Steve, 2026-09-28). In cftlib, the placeholder is added to CDAM's
   service-to-case-type permissions as well as CCD's.
5. **A caseworker's delete is a CDAM soft delete** (Steve, 2026-09-28). The row and the case's document reference
   go. After the commit, the api calls `DELETE /cases/documents/{id}` with `permanent=false`. A failed call is logged
   with both ids and does not fail the delete, which matches today's best-effort file cleanup. `permanent=true` is
   never sent: permanent removal belongs to the [retention plan](retain-and-dispose.md). A test proves a
   soft-deleted document can no longer be downloaded.
6. **The CDAM client is `com.github.hmcts:ccd-case-document-am-client:1.59.2`.** 2.0.0 is built on Spring Boot 4.1,
   and this service is on 3.5.16. 1.59.2 is on the Boot 3 line, and data-store, bulk-scan, et and adoption all pin
   it. Its `CaseDocumentClientApi` has upload, binary download, metadata, `permanent`-flagged delete and
   `attachToCase`.
7. **The local document's identity never changes.** Callers keep using the deed-poll document id. The CDAM document
   id is a storage reference on the row, and API responses never expose it or its URL.
8. **The hash token is transient.** It is kept on the row only between upload and attach, then cleared. It never
   appears in a log line, a response, the CCD projection or an event summary.

## Dependencies — outside this plan, and not ralphex work

- **Onboarding** (Steve's): the real jurisdiction and case type, the api's S2S identity, and **a CDAM entry for the
  api's service in `ccd-case-document-am-api`'s service-to-case-type permissions** (read, create, update and
  attach). Needed only before the CDAM store is switched on in an environment, and before the AAT round trip.
- **The operator-run AAT round trip**, which is guard 3's capture. Task 7 prepares the procedure but doesn't run it.
- **Upload limits.** CDAM's own file limits and allowed types in AAT and prod, which Task 1 reads from source. If
  they're tighter than `UploadPolicy`'s 25 MB, stop and flag it. Don't lower our limit without a decision.

## Constraints

- Everything in [`CLAUDE.md`](../../CLAUDE.md), in particular:
  - never edit a committed migration (the next free one is **V25**);
  - no live calls to CCD, CDAM, dm-store, IDAM or S2S in any test — WireMock or cftlib only;
  - no secrets;
  - never return an entity from a controller;
  - a controller test boots the whole context;
  - the CCD binding is written once and never overwritten.
- **No remote call inside a database transaction.** Uploads, attaches and deletes happen outside the transaction.
  The local state records where each one got to, as `CcdLinkingService` does (plan 02 decision 9).
- **The filesystem store stays the default and keeps working.** Generated deeds and Notify move onto CDAM in 03b.
  Until then they call the evolved seam and behave exactly as today on the filesystem store. Any existing test
  that changes its expectations about those callers is a regression, not an update.
- **Docker-free CI does not run container-backed tests.** Every rule proved against Postgres or cftlib needs a
  container-free companion where it can be expressed without either: the state transitions, the "case not ready"
  refusal, the hash-token lifecycle, and the delete's best-effort rule.
- **Run every Gradle build in the foreground, never in the background.** Give the Bash call its maximum timeout
  (`600000` ms). The full build takes about 7 minutes. A background build ends your session, because you run
  non-interactively. The next session then starts a second build over it, which deletes the first build's
  `build/test-results` and invalidates every count. Before starting any build, check that none is already running
  with `pgrep -f 'gradlew|GradleWorkerMain'`, and if one is, wait for it. If a build genuinely runs past the
  10-minute cap, stop and record that. Do not fall back to a background build.
- The published OpenAPI document must not change unless a task says so. Task 4 changes only the "case not ready"
  response.

## Validation Commands

**These run after every task, so everything here must be green after every task, not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew cleanTest cleanIntegration build
```

`cftlibTest` is deliberately **not** here. Tasks 5–7 run it, and Task 8 records it.

**Count from `build/test-results/`, never from the exit code.** `cleanTest cleanIntegration` is there because a
run that skips a source set leaves the previous run's result files in place. A worker or container startup timeout
is an environment failure, not a code failure: re-run it before investigating.

**Baseline: `master` at `b2679fe`** (#70 and #71 merged). `./gradlew check` on #71's code measured **3304** `test`
and **14** `integration`, all green, on 2026-09-28. Task 1 re-measures both, Docker and Docker-free, before
changing anything.

## Tasks

### Task 1: Record the sequence before building it

- **Before changing anything**, measure the baseline:
  - `./gradlew cleanTest cleanIntegration build`
  - `INTEGRATION_TESTS_ENABLED=false ./gradlew cleanTest cleanIntegration build`

  Record both here.
- Write `docs/CDAM-SEQUENCE.md` from source: `apps/ccd/ccd-case-document-am-api`, `apps/ccd/ccd-data-store-api`
  and the cftlib 0.19.2377 jars, with `file:line` citations. Cover:
  - upload: the request, the metadata, the response, and what the hash token is computed over;
  - attach, and **which stage performs it for a decentralised case type**. Data-store's centralised submit calls
    CDAM's `attachToCase`. Find whether the decentralised path through `/ccd-persistence` does too, or whether
    this service must call `attachToCase` itself. Do not assume a handler-only mutation triggers the centralised
    hook;
  - download: which permission CDAM checks, and against what in CCD;
  - soft delete: its effect on download, and whether it's reversible through the API;
  - the S2S and user-token requirements of each call, and CDAM's file-size and MIME-type limits;
  - how cftlib sets `DM_STORE_BASE_URL`, and **how a test can override it**. If it can't be overridden without
    patching cftlib, **stop and flag it**: decision 2 depends on this.
  - where cftlib reads CDAM's service-to-case-type permissions, so Task 5 can add the placeholder.
- Add nothing to `src/` in this task.

- [ ] baseline recorded, `CDAM-SEQUENCE.md` written with citations, the attach stage and the dm-store override
      answered (or flagged), and the build is green

### Task 2: Evolve the seam

- Replace `DocumentStore.save(byte[])` with a method that takes a small value type carrying the content,
  filename, MIME type and the owning record (its id and, when set, its `ccdCaseReference`). Return a result the
  row can record (a storage key, plus whatever the CDAM store needs later), not a bare `String`.
- Adapt the one direct caller, `DocumentService` (`save`, `open` and both `delete` paths). `DeedDocumentService`,
  `DocumentUploads` and `CaseworkerDocumentController` reach the store only through it, so they need no change,
  and a change to any of them means the seam leaked. `LocalFilesystemDocumentStore` ignores the new context and
  behaves exactly as today.
- Keep storage concerns out of the deed generator.
- Tests: the existing document, deed and Notify tests must pass unchanged, apart from call sites. Add one test per
  new value type, including that `toString` never prints content.

- [ ] the seam carries filename, type and case context, every caller is adapted with no change in behaviour, and
      the build is green

### Task 3: V25, and the document states

- Add `V25__document_storage_state.sql` to `deedpoll_supportingdocument`, with the same comment density as V23:
  - `storageState varchar(…)`, not null. Existing rows are backfilled `Local`;
  - `cdamDocumentId uuid`, nullable;
  - `cdamHashToken varchar(…)`, nullable. Transient, per decision 8, and the column comment says so;
  - `storageAttemptedOn timestamp(3)`, nullable.
- A `DocumentStorageState` enum, modelled on `CcdCreationAttemptStatus`, with an explicit `successors()` table:
  - `Local`: on the filesystem store, terminal;
  - `UploadPending`: claimed, nothing sent yet;
  - `UploadUncertain`: the upload may have reached CDAM, and the response was lost;
  - `Uploaded`: CDAM id and hash token held, not yet attached;
  - `AttachUncertain`;
  - `Attached`: terminal for this plan, and the only state a download serves from CDAM;
  - `Failed`: definitely nothing stored.
- **Recovery rule:**
  - an uncertain upload is never retried by uploading again;
  - an uncertain attach is retried, because attaching is idempotent for the same document and case (confirm this
    in Task 1's sequence, and if it isn't, stop and flag it);
  - a document still `UploadUncertain` is reported as needing attention. It is never served and never reported
    as stored.
- Tests:
  - container-free: the transition table, and that nothing moves out of `Attached` or `Local`;
  - container-backed: the migration and its backfill against real Postgres.

- [ ] V25 applied with its backfill proved, the state table and recovery rule tested, and the build is green

### Task 4: The CDAM store

- A `storage/CdamDocumentStore`, selected by `deedpoll.documents.store: ${DOCUMENT_STORE:filesystem}`. The
  allowed values are `filesystem` and `cdam`, refused at startup if anything else. It uses the pinned client
  (decision 6), with the api's S2S token and a system user's token, as the linking operation does.
- Upload: refuse "case not ready" (decision 1) before sending anything. Commit `UploadPending`, upload outside the
  transaction, then commit `Uploaded` with the id and token, or `Failed`, or `UploadUncertain` on a timeout or an
  unreadable response.
- Attach: submit the document event (Task 5), or call `attachToCase` if Task 1 found that this service must. Commit
  `Attached` and clear the hash token, or `AttachUncertain`.
- Download: case-scoped. The record must own the document row, and the row must be `Attached`. The bytes stream
  from CDAM under the caller's own token, so CDAM's permission check applies to the caller, not to the system user.
  Never accept a CDAM id or URL from a request.
- Delete: decision 5. The row and the case reference go in the transaction, then the soft delete runs after the
  commit, best effort.
- Tests, all WireMock (no cftlib):
  - each happy path;
  - each lost response lands in its uncertain state;
  - "case not ready" sends nothing;
  - a document owned by another record is refused before CDAM is called;
  - `permanent=true` is never sent;
  - the hash token appears in no log line, which needs a log-capture test.

- [ ] the CDAM store uploads, attaches, streams and soft-deletes behind the switch, every failure lands in a
      defined state, and the build is green

### Task 5: The projection field and the document event

- Extend `DeedPollCaseType` with a collection of typed document references (the SDK's `Document` type: URL, binary
  URL, filename and hash where the SDK carries it). Add the smallest authorised event that sets it, alongside
  `linkDeedPoll`. The projection is built from the local rows in `Attached`, so it survives a restart and any
  replica.
- Delete also submits the event, so CCD's event snapshot and search index don't keep a removed reference. A direct
  domain write alone refreshes neither.
- Update the SDK snapshots and local definition metadata together.
- In cftlib, add the placeholder case type to CDAM's service-to-case-type permissions, using what Task 1 found.
- Tests:
  - container-free: the projection from rows, including that no uncertain or `Failed` row appears and no hash
    token is ever projected;
  - snapshot tests updated.

- [ ] the case carries its attached documents, add and remove each go through the event, cftlib grants the
      placeholder its CDAM permissions, and the build is green

### Task 6: The WireMock dm-store and its guards

- A WireMock dm-store for `cftlibTest`, serving only what CDAM calls: upload, metadata, binary, delete and TTL
  patch, as `CDAM-SEQUENCE.md` records. Each response body is labelled with its source file and line in
  `document-management-store-app` (guard 3, until AAT captures replace them).
- Guard 1: the recorded cftlib version and spec commit, and the unit test tying the version to `build.gradle`.
- Guard 2: the copy of the spec in `src/test/resources`, and the unit test that each stubbed request exists in it.
- Guard 4: a `checkDmStoreSpecDrift` Gradle task, not wired into `check`, that diffs the copy against
  `../platops/cnp-api-docs` and reports differences. If that clone is absent, it says so and does nothing else.
- Guards 1 and 2 are unit tests and run in `build`. Guard 4 is run once here and its output recorded.

- [ ] the stub serves CDAM's calls, guards 1 and 2 run in `build`, the drift task runs and reports, and the build
      is green

### Task 7: Prove it end to end in cftlib

- Add a `DocumentCdamCftlibTest`, with `DOCUMENT_STORE=cdam` in the cftlib profile only and `DM_STORE_BASE_URL`
  pointed at Task 6's stub. Each journey runs over HTTP as a caseworker:
  - create a record, let the sweep link it, upload a document, and download the original bytes;
  - download again after restarting the api's context: the bytes come from CDAM, not memory or local disk;
  - a caseworker without the case type's read access is refused by CDAM, not by us;
  - a document on another record is refused, and so is an upload to a record with no case yet;
  - delete, then prove the document is no longer downloadable through CDAM (decision 5), and that CCD's case no
    longer lists it.
- Record the wall-clock time an upload adds.
- Write `docs/CDAM-SMOKE.md`, the operator-run AAT round trip. It covers the same journey plus capturing
  dm-store's real response bodies for guard 3, and the onboarding it needs first. Do not run it.

- [ ] every journey passes in cftlib against real CDAM and CCD with no live calls, the smoke procedure is written,
      and the build is green

### Task 8: Configuration, runbook and handoff

- Configuration:
  - `DOCUMENT_STORE`, and the CDAM base URL (`CASE_DOCUMENT_AM_URL`), in `application.yaml`, the README's table
    and the chart's values;
  - refuse `cdam` at startup when the CDAM URL or the case type is still the placeholder, so a deployed
    environment can't start on half an onboarding;
  - safe metrics: counts per storage state, with no ids, names or tokens.
- A recovery runbook in `docs/`: what each uncertain state means, what an operator can safely do, and that only
  the dm-store team can restore a soft-deleted document.
- `CLAUDE.md`, with at least these rules, which would otherwise break silently:
  - the hash token is transient, and must never be logged or projected;
  - never upload again from `UploadUncertain`;
  - never send `permanent=true`;
  - the CDAM store is off by default, and switching it on before onboarding refuses every upload.
- Record the final counts, Docker and Docker-free, and the `cftlibTest` count, against Task 1's baseline. Update
  `docs/RESUME.md`.

- [ ] configuration validated, runbook and CLAUDE.md written, counts recorded, and the build is green

## Deliberately not in this plan

- Generated LOC020/LOC022 deeds on CDAM, Notify's retrieval from CDAM, and pre-case staging, retry and expiry: 03b.
- Migrating existing filesystem documents to CDAM: 03c.
- Permanent deletion and disposal: the [retention plan](retain-and-dispose.md).
- A local soft delete or an undelete, both rejected in decision 5.
- Streaming uploads. The seam still buffers, up to `UploadPolicy.MAX_UPLOAD_BYTES`.
- The admin UI's view of the uncertain states.

---

## Flags for Steve

1. **The CDAM store ships off, and the filesystem store stays the default.** It's the same approach as the 02b sweep:
   switching it on before onboarding would refuse every upload. It also means a deployed environment keeps
   writing to pod storage until onboarding. Plan 03's "configure deployed environments to require the intended
   backend" is met by Task 8's startup refusal, not by removing the filesystem store.
2. **An uncertain upload is parked, not retried.** CDAM can't be searched by our id, so there's no safe way to
   tell whether a lost upload landed. Task 3 parks it as `UploadUncertain` and reports it. The caseworker uploads
   the file again as a new document. The alternative, trying to find the orphan in CDAM, would need an API CDAM
   doesn't offer.
3. **The client is pinned at 1.59.2, not 2.0.0**, because 2.0.0 needs Boot 4 (decision 6). Moving to it becomes
   part of any future Boot 4 upgrade.
