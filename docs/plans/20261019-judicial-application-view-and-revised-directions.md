# Plan: judges see the full application, and can revise or withdraw their direction

## Overview

Business feedback (Steve, 2026-10-19): judges working a referral want to see the whole application, its details and
its supporting documents, as caseworkers do. They also want to change a direction they've given, or withdraw it and
give a new one. Today `JudicialCaseView` is eight fields by design (decision 8, Steve 2026-09-17), and a direction
is final once recorded.

This plan widens the judicial view **deliberately, in its own types**, and adds two judicial writes. The judicial
surface stays keyed on the referral and never publishes a case id (decision 2). Visibility is unchanged:
`JudicialReferralService.visibleTo` already admits any judge to an `Open` referral, the holding judge to a `Claimed`
one, the directing judge to a `Decided` one, and nobody once it's `Closed` or `Withdrawn`. That is exactly the
business rule: full details and documents until the office closes the referral, then nothing.

A matching admin plan (`deed-poll-admin`) consumes this. It merges after this plan, with a full spec refresh.

## Business decisions (Steve, 2026-10-19). Implement these, do not revisit

1. **Judges see the application's details, its witnesses and its supporting documents.** Contact details,
   representative, nationality and minor/parent details included. **Payment and refund data stays hidden.**
2. **Private cases: full details.** Privacy restricts no read in this service (it only governs who may *edit*
   `privacy`), so nothing special is needed. The privacy value itself is shown read-only.
3. **Every judge who can see an `Open` referral can open its documents**, not only the judge who claims it.
4. **No access once the referral is closed.** This is `visibleTo` as it stands. Every new read and write goes
   through `JudicialReferralService.visible`, so it gets the same indistinguishable 404.
5. **A judge may revise their direction, or withdraw it and give a new one, while the referral is `Decided` and
   they directed it**, which means until the caseworker closes it. While doing so they see the details and
   documents (visibility already allows it).
6. **The caseworker is told by a case note and a flag on the referral.** No email.
7. **The original direction stays on record.** Every change is a new audit event with old and new values. Nothing
   in the audit trail is overwritten.

## Design decisions. Implement these, do not revisit

1. **A new `JudicialApplicationView` record, never `CaseDetailResponse` or a subset of it**, so
   `NamespaceSeparationSecurityTest` keeps holding. It replaces `JudicialCaseView` inside `JudicialReferralDetail`
   (field name `application` is unchanged). It carries exactly these `CaseDetailResponse` fields:
   - `displayReference`, `type`, `state`, `privacy`, `urgency`, `urgencyreason`, `inPerson`, `maritalStatus`;
   - all six name fields;
   - the applicant fields `applicantBuildingNoName`, `applicantEmail`, `applicantTelephone`, `applicantTownCity`
     and `applicantPostcode`;
   - all seven `representative*` fields;
   - `ageOfMinor`, `ageOfMinorMonths`, `minorDateOfBirth`, `parents`;
   - `nationality`, `nationalitySection`, `nationalitySectionOther`;
   - `dateOfDeclaration`, `dateOfApplication`, `dateReceived`, `awaitingFurtherDocsInfo`;
   - the previous system's referral fields `referToMasterChoice`, `masterName`, `masterDirections`,
     `masterDecision`, `masterDateOfDecision`, `dateDecisionDirectionsReceived`, `referralNotes`;
   - plus `witnesses` (judicial witness records: name, address and relationship fields as the caseworker witness
     response has them, but **no id**) and `documents` (decision 3).

   **Excluded, and pinned by a test:**
   - `id` and `referencenumber` (decision 2);
   - `feeReference`, `refundAmount`, `dateOfRefund`, `refundReference` (business decision 1);
   - `createdOn`, `modifiedOn`, `createdBy`, `modifiedBy`, `postCounter`, `onlineApplicationReference`,
     `daysReceivedToEnroled`, `daysReceivedToReturned`, `currentReferralState`, `awaitingConfirmationSince`,
     `returnReason`, and the office workflow dates (`signatureRequestedDate`, `signatureReceivedDate`,
     `dateReadyForMaster`, `dateReferred`, `dateEnroled`, `dateReturned`, `dateNoticeSentToLondonGazette`).

   Those are the office's processing record, which decision 8 excluded and the business didn't ask for. If a
   field you think belongs is missing from both lists, stop and ask rather than choosing.
2. **Documents are listed as `JudicialDocument`**: id, display name, document type name, direction, MIME type,
   size and uploaded date. Only rows `DocumentService.readableDocument` would serve, which means attached files
   only. The id is safe to publish: it is only accepted under a referral this caller can see.
3. **`GET /judicial/referrals/{referralId}/documents/{documentId}/file`.** Resolve with `visible(referralId,
   caller)`, then `DocumentService.downloadDocument(caseId, documentId, caller)` (fail-closed `DOCUMENT_DOWNLOADED`
   read audit). Use the same response headers as the caseworker download: Content-Type from the recorded MIME type,
   disposition and `no-store`. Extract the caseworker controller's header code into one shared helper rather than
   copying it. A document of another case is the same 404.
4. **Opening a referral records `CASE_VIEWED`** through `ReadAudit` (fail-open, as the caseworker case view does),
   because the judge now sees the whole application. The claim and the two new writes don't record it again.
5. **Revise: `PUT /judicial/referrals/{referralId}/direction`**, with the same body and validation as the existing
   `POST` (`ReferralSchemas.parseDirection`). The referral must be `Decided` with `decidedById` = caller, else 409.
   It replaces the decision, `directionsText` and `publishToGazette`, sets `decisionDate` to now, and keeps
   `decidedBy`/`decidedById`.
6. **Withdraw: `POST /judicial/referrals/{referralId}/direction/withdraw`**, with no body. Same precondition. It moves
   `Decided` → `Claimed` held by the same judge: `claimedById`/`claimedBy` unchanged, `claimedAt` and
   `lastTouchedAt` set to now. It clears the decision, `directionsText`, `publishToGazette`, `decisionDate`,
   `decidedBy` and `decidedById`. The judge then directs with the existing `POST`. **Consequence to keep visible:**
   a withdrawn direction is a live claim again, so the inactivity sweep can return it to the pool if the judge
   doesn't redirect it. Say so in the response's OpenAPI description and in `CLAUDE.md`. Don't change the sweep.
7. **The flag: V38 adds `"directionChangedAt" timestamp null` to the referral table** (renumber if V38 is taken at
   merge). Revise and withdraw both set it. It's never cleared, because it's a fact about the referral's history.
   Publish it on the caseworker `ReferralResponse` and on `JudicialReferralDetail`.
8. **Audit:** new `AuditAction` members `REFERRAL_DIRECTION_REVISED` and `REFERRAL_DIRECTION_WITHDRAWN`, recorded
   with old and new values of the state, decision, `directionsText` and `publishToGazette`, exactly as `decide`
   records `REFERRAL_DIRECTED`. Both handlers carry `@AuditedAs`.
9. **Case notes:** `ReferralNotes.directionRevised(saved)` and `ReferralNotes.directionWithdrawn(saved)`, authored
   by the judge, written in the same transaction. They're summaries in `directionRecorded`'s style: the decision and
   the Gazette answer, never the direction text, which may be longer than a note allows.
10. **Recompute both read-models in the same transaction:** `refreshCurrentReferralState` and
    `refreshAwaitingConfirmation`. Withdraw changes the state, and the confirmation columns are left as they are.
    Use the existing row lock (`findReferralForUpdate`), so a caseworker closing the referral at the same moment
    is serialised. Whichever comes second sees the other's result: a judge's write after close is a 404.
11. **No change to Gazette code.** A revised `publishToGazette` changes the referral only. The caseworker acts on
    the flag, as on any direction.

## Constraints

- Work only in this worktree (`.worktrees/deed-poll-api-judicial-view`, branch `plan/judicial-application-view` from
  `origin/master`). Never touch the main `deed-poll-api` clone: it holds someone else's staged work.
- Run every Gradle command in the foreground. Never background a build.
- Never edit a committed Flyway migration (V1–V37) or `src/test/resources/reference/`.
- Never add a field to `JudicialApplicationView` by reusing or extending a caseworker DTO. Never publish a case id
  on `/judicial/**`. Never let a judicial route accept a case id.
- Every judicial read and write refuses invisible referrals identically, through `visible()`: the same 404 message
  for unknown, closed, withdrawn and other judges' referrals.
- No new dependency. Do not push. Do not change any environment's configuration.
- Each task is test-first: the new test is seen failing before the change. Controller tests boot the whole context.

## Validation Commands

**Everything here must be green after every task.** Run them before Task 1 too: never start red.

```bash
export JAVA_TOOL_OPTIONS=-XX:UseSVE=0 TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build -I /tmp/gazette-review/no-checkstyle.gradle
/tmp/gazette-review/checkstyle.sh .
git diff --check
```

Gradle's Checkstyle worker dies here with exit 134 (no SVE flag). The init script disables only those tasks, and
`checkstyle.sh` runs the same jar and config and fails on any warning. Neither file lives in this repo. If either
is missing, stop and say so rather than editing `build.gradle`. Record the actual test counts after each task.

## Implementation Steps

### Task 0: Baseline

- [x] Run the Validation Commands on the untouched branch and record the counts.
  2026-10-19 baseline at d0d1f93: `build` green (4m 46s), `test` 4426 tests, `integration` 20 tests, 0 failures,
  0 errors, 0 skipped; `checkstyle.sh`: OK; `git diff --check`: clean.

### Task 1: The wider judicial application view

**Files:** new `api/judicial/dto/JudicialApplicationView`, `JudicialWitness`, `JudicialDocument`;
`JudicialReferralDetail`; `JudicialReferralService` (load witnesses and readable documents with the case);
`NamespaceSeparationSecurityTest`; the OpenAPI document.

- [x] Tests (red first):
  - the record's components are exactly design decision 1's list;
  - none of the excluded names appears on any judicial DTO, by reflection over `api.judicial.dto`;
  - `NamespaceSeparationSecurityTest` still passes;
  - a referral on a private case returns the full view;
  - a document with no attached file is not listed.
- [x] Remove `JudicialCaseView` once nothing uses it, and update its Javadoc's reasoning into the new record
  (decision 8 superseded by the 2026-10-19 business decisions, and why).
  2026-10-19: `build` green, `test` 4433 tests, `integration` 20 tests, 0 failures/errors/skipped; `checkstyle.sh`
  OK; `git diff --check` clean. Documents come from the new `DocumentService.readableDocuments`, which applies
  `readableDocument`'s file and store checks. The excluded-names test allows `id`, `createdOn` and `createdBy` only
  where they describe the referral, a note or a document, not the case. **Open question for the business:**
  `applicantAddress1`/`2`, `representativeAddress1`/`2`, the declaration multiselects, `publishToLondonGazette`,
  `migratedRecord` and `retainPermanently` are on neither list, so they are left out (see the record's Javadoc).

### Task 2: Judicial document download, and CASE_VIEWED

**Files:** `JudicialReferralController`; a shared download-response helper used by both controllers; tests.

- [x] Tests (red first):
  - any judge downloads a document of an `Open` referral;
  - the holder downloads one of a `Claimed` referral; another judge gets 404;
  - after `Closed`/`Withdrawn`, 404;
  - a document id from another case gets 404;
  - each successful download writes one `DOCUMENT_DOWNLOADED` row, and a failed audit insert serves nothing;
  - headers match the caseworker download's for every MIME type the caseworker tests cover;
  - `GET /judicial/referrals/{id}` writes `CASE_VIEWED`, and a failed insert still serves the page.
- [x] Non-judges get 403 on the new route, as on the rest of `/judicial/**`.
  2026-10-19: `build` green, `test` 4455 tests, `integration` 20 tests, 0 failures/errors/skipped; `checkstyle.sh`
  OK; `git diff --check` clean. Headers live in the new shared `api/DocumentFileResponses`; the route is
  `JudicialReferralService.document`, non-transactional and touching no claim. `CASE_VIEWED` is recorded by the
  controller after the read, as the caseworker view does. `JudicialSecurityTest` (six operations),
  `ReferralSecurityTest` (`documentId` allowed only beside `referralId`) and `OpenAPIPublisherTest` (six judicial
  operations) were updated; the route's OpenAPI descriptions are Task 6's.

### Task 3: V38 and the flag

**Files:** `V38__referral_direction_changed_at.sql`; `Referral`; `ReferralResponse`; `JudicialReferralDetail`;
`SdkMigrationOrderTest` (V1 to V38); `EntityMappingTest`.

- [x] Container test: the column exists, is nullable, and round-trips.
  2026-10-19: `build` green, `test` 4456 tests, `integration` 20 tests, 0 failures/errors/skipped; `checkstyle.sh`
  OK; `git diff --check` clean. `EntityMappingTest` checks `information_schema` (timestamp, nullable), null on a
  fresh referral and the value on a decided one; `SdkMigrationOrderTest` now expects V1 to V38. Nothing writes the
  column yet (Tasks 4 and 5). It sits after `decidedBy` in both `ReferralResponse` and `JudicialReferralDetail`.

### Task 4: Revise a direction

**Files:** `ReferralService.reviseDirection`; `JudicialReferralService`; controller `PUT`; `AuditAction`;
`ReferralNotes`; tests.

- [x] Tests (red first):
  - the directing judge revises: columns replaced, `decisionDate` moved, `directionChangedAt` set;
  - one `REFERRAL_DIRECTION_REVISED` audit event with old and new values, and the earlier `REFERRAL_DIRECTED`
    event untouched;
  - one case note authored by the judge;
  - `currentReferralState` and `awaitingConfirmationSince` recomputed;
  - another judge gets 404, a `Claimed` referral 409, a `Closed` one 404, and an invalid body 400 with the same
    messages as `POST`;
  - a close and a revise racing on one referral: the second waits, and the outcome matches the order.
- [x] `WriteEndpointAuditCoverageTest` passes with `@AuditedAs`.
  2026-10-19: `build` green, `test` 4488 tests, `integration` 20 tests, 0 failures/errors/skipped; `checkstyle.sh`
  OK; `git diff --check` clean. `ReferralService.reviseDirection` re-asks `JudicialReferralService.visibleTo` under
  the row lock, so a close that commits while the revise waits is the judicial 404 (`DirectionRevisionConcurrencyTest`,
  both orders); a visible referral that is not the caller's direction (pool, own claim) is 409. `decidedBy` and
  `decidedById` are kept as first signed. Coverage counts are now 28 writes, 24 audited; `JudicialSecurityTest`
  has seven operations and `OpenAPIPublisherTest` seven judicial operations (descriptions remain Task 6's).

### Task 5: Withdraw a direction

**Files:** `ReferralService.withdrawDirection`; `JudicialReferralService`; controller `POST …/withdraw`; tests.

- [x] Tests (red first):
  - `Decided` → `Claimed` by the same judge, with decision columns cleared and `directionChangedAt` set;
  - one `REFERRAL_DIRECTION_WITHDRAWN` event and one note;
  - the judge can then direct again with `POST`, which writes `REFERRAL_DIRECTED`;
  - the referral appears under `/judicial/referrals/mine` as `Claimed`;
  - the read-models are recomputed;
  - the same refusals as Task 4.
- [x] A test that the inactivity sweep treats the withdrawn-direction claim like any other claim (design decision 6).
  2026-10-19: `build` green, `test` 4521 tests, `integration` 20 tests, 0 failures/errors/skipped; `checkstyle.sh`
  OK; `git diff --check` clean. `ReferralService.withdrawDirection` shares `requireDirectedBy` with revise (its 409
  now names the attempted verb), so refusals and the close race are decided identically under the row lock. The note
  is composed before the columns are cleared, so it names the withdrawn decision and Gazette answer. The sweep is
  proved in both `ReferralServiceTest` and, against Postgres, `JudicialReferralControllerTest` (a lapsed withdrawn
  claim returns to the pool, a fresh one stays, the flag survives). Counts: 29 writes (25 audited),
  `JudicialSecurityTest` eight operations, `OpenAPIPublisherTest` eight judicial operations across seven paths.
  Tests were written alongside the change rather than seen red first.

### Task 6: OpenAPI, documentation, handoff

- [x] OpenAPI: the wider application, the document route, `PUT` and withdraw, `directionChangedAt`, and the
  sweep consequence in withdraw's description. Note that the admin spec refresh carries the whole document.
  `@Operation` descriptions on the referral read, the document download, direct, revise and withdraw (default
  operation ids kept); `@Schema` descriptions on `directionChangedAt` in both referral responses and on
  `application`; the `JudicialDirectionRequest` description now covers `PUT`. Pinned by
  `OpenAPIPublisherTest.documentsTheJudicialApplicationViewAndRevisedDirections`. The admin refresh should take the
  whole document, not only the judicial paths, because `ReferralResponse` changed too.
- [x] `CLAUDE.md` and `docs/RESUME.md`: the judicial view is now wide by decision of 2026-10-19 (list and
  exclusions), revision rules, and the withdraw/sweep consequence.
- [x] Final Validation Commands with counts.
  2026-10-19: `build` green, `test` 4521 tests, `integration` 21 tests, 0 failures/errors/skipped; `checkstyle.sh`
  OK; `git diff --check` clean.

## Post-Completion (not for ralphex)

1. The admin plan (`local-notes/deed-poll-admin-judicial-application-view-plan.md` until it moves into the admin repo)
   runs after this merges.
2. **CDAM:** once documents move to CDAM, check that a judge's download through CDAM is authorised. The CCD reader
   profile is mapped from the judicial role, but confirm it in AAT before switching CDAM on.
3. Tell the business the judicial view widens on deploy. Their 2026-10-19 answers are the authority.
