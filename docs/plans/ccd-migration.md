# CCD migration — Dataverse ingestion

Status: draft for review, 2026-09-26. **Cut down on 2026-09-27 (Steve, Q5 (a)).** The linking half moved to
[plan 02b](deed-poll-api-02b-ccd-linking-lifecycle.md). Its sweep, driven by traffic, links every record with a
null CCD reference, whether it was created by a caseworker, already existed, or was imported. This plan now covers
Dataverse ingestion only, and it no longer blocks [plan 03](deed-poll-api-03-cdam-documents.md). It is still
blocked on the inputs below, which the business owes. Depends on plan 02b.

## Outcome and scope

Import Dataverse deed-poll records into the local database, repeatably and observably, so that plan 02b's sweep
links them to CCD. A null CCD reference identifies a record that has not been bound yet. Linking progress is
reported by plan 02b's attempt ledger and its `/caseworker/ccd-attempts` view. A reference alone does not prove that
the document migration, or the whole Dataverse migration, is complete.

Existing and migrated records keep their normal queue eligibility and established payment process. Neither the
import nor the CCD backfill may:
- create a new fee;
- trigger payment reminders;
- assign a new deed-poll display reference; or
- start a seven-day deletion clock.

## Inputs to settle before batch execution

- **Source export and record identifier.** A stable Dataverse record identifier, the export itself, and where the
  existing importer lives and who owns it. The current entity has `migratedRecord`, but that boolean does not
  identify a source row. Specify a unique mapping from source to local record before ingestion can be repeated.
- **Scale and control.** The initial scope, estimated volume, batch size and rate limits, the cutover or delta
  strategy, the environment, and the approved operators.
- **Historical references and dates.** How they are preserved, including for incomplete and duplicate source
  records. Do not run legacy imports through ordinary new-case reference allocation without a verified mapping.
- **Documents and retention.** A separate inventory of document binaries and metadata, and of retention and
  permanent-hold flags. Historical retention is informed by the original business dates, not the CCD registration
  dates.

## Tasks

- [ ] Inventory the source and local records, and produce a dry-run report. It classifies each record as already
      linked, unlinked, having a live attempt (from plan 02b's ledger), invalid source data, or a conflicting
      mapping.
- [ ] Define a repeatable import boundary: upsert by stable source identity, then persist and commit the local row
      with a null CCD reference, which is all plan 02b's sweep needs to select it. Rerunning the import must preserve
      both the local and the CCD identity.
- [ ] Decide how an import window interacts with plan 02b's sweep: either raise its per-request cap during a
      controlled window, or leave linking to caseworker traffic. The sweep, claims, reconciliation and repair all
      belong to plan 02b, so do not build a second batch runner or a second claim mechanism.
- [ ] Report the import batch ID and counts of selected, imported, skipped and invalid source rows, with safe error
      categories. Linking counts come from `/caseworker/ccd-attempts`. Keep names, exported payloads and tokens out
      of reports and logs.
- [ ] Verify that the import does not label new online records as legacy, and that an ordinary caseworker cannot
      change a record's origin to bypass the payment gate.
- [ ] Document the restart and stop procedures for the import. For CCD, point to plan 02b's repair endpoints. A
      rollback plan must not propose deleting paid cases or dropping established references.

## Acceptance and validation

- [ ] Run the same source batch twice. The local UUIDs and references are stable, the sweep produces one confirmed
      CCD link per local record, no extra payments or emails are created, and queue eligibility is unchanged.
- [ ] Interrupt the import before and after the local commit. A restart converges, with no skipped or duplicated
      local records.
- [ ] A bad source row can be isolated without abandoning the rest of a bounded batch. The totals reconcile against
      the source inventory and the local database.
- [ ] Real-Postgres tests verify source uniqueness and concurrent import runs, and the repository build gates
      complete.
- [ ] Prepare a non-production rehearsal, and compare sampled read-backs and totals before increasing batch size.
      Record the actual execution evidence and the operator decisions.

## Sources and related work

- [Foundation](completed/deed-poll-api-02-ccd-foundation.md): the shared linking and recovery operation.
- [Plan 02b](deed-poll-api-02b-ccd-linking-lifecycle.md): the sweep that links imported records, and repair.
- [Current case entity](../../src/main/java/uk/gov/hmcts/reform/deedpoll/entity/DeedPoll.java).
- [API scope gaps](scope-gap-questions.md): the existing parity work for migrated records.
- [CDAM](deed-poll-api-03-cdam-documents.md): the document migration, which tracks its progress separately.
- [Retention](retain-and-dispose.md): the historical TTL backfill, which is a separate controlled step.
