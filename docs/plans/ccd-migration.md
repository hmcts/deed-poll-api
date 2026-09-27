# CCD migration — bounded, resumable case linking

Status: draft for review, 2026-09-26. Depends on [plan 02](completed/deed-poll-api-02-ccd-foundation.md).

## Outcome and scope

Link existing and Dataverse-imported deed-poll records to CCD in observable batches,
using the foundation's creation operation. A null CCD reference identifies records
that have not yet been bound; creation-attempt status identifies work in progress,
uncertain outcomes and failures. A reference alone does not prove document migration
or the entire Dataverse migration is complete.

Existing and migrated records retain their normal queue eligibility and established
payment process. CCD backfill must not create a new fee, trigger payment reminders,
assign a new deed-poll display reference or start a seven-day deletion clock.

## Inputs to settle before batch execution

- Source export and stable Dataverse record identifier; existing importer location and
  ownership. The current entity has `migratedRecord`, but that boolean does not identify
  a source row. Specify a unique source-to-local mapping before repeatable ingestion.
- Initial scope, estimated volume, batch size, concurrency/rate limits, cutover or delta
  strategy, environment and approved operators.
- How historical display references and dates are preserved, including incomplete and
  duplicate source records. Do not run legacy imports through ordinary new-case reference
  allocation without a verified mapping.
- Separate inventory for document binaries/metadata and retention/permanent-hold flags.
  Original business dates, rather than CCD registration dates, inform historical retention.

## Tasks

- [ ] Inventory source/local records and produce a dry-run report: already confirmed,
      unlinked, active/uncertain attempt, invalid source data and conflicting mapping.
- [ ] Define a repeatable import boundary: upsert by stable source identity, persist and
      commit the local row, then enqueue or select it for the shared CCD linking operation.
      Rerunning the import must preserve both local and CCD identities.
- [ ] Build an operator-controlled batch runner with explicit selection, maximum records,
      bounded concurrency, rate limiting and pause/resume. Use stable pagination/claiming;
      changing the set of null references must not cause offset pagination to skip rows.
- [ ] Coordinate claims with online/manual linking so two workers cannot register the
      same local record. Resume crashed work by reconciliation, not fresh blind creation.
- [ ] Reconcile uncertain outcomes before retrying. Detect conflicting links and stale
      pointers as repair items; retain evidence and never silently replace a reference.
- [ ] Report batch ID and counts for selected, confirmed, pending, uncertain, failed and
      skipped work, with safe error categories. Keep names, exported payloads and tokens
      out of reports/logs. Explain the difference between linked and confirmed counts.
- [ ] Verify that migration selection does not label new online records as legacy, and
      that an ordinary caseworker cannot change origin to bypass the payment gate.
- [ ] Document restart, reconciliation, repair and stop procedures. Stop claiming new
      work on pause; account for requests already in flight. A rollback plan must not
      propose deleting paid cases or dropping established references.

## Acceptance and validation

- [ ] Run the same source batch twice: stable local UUIDs/references, one confirmed CCD
      link per local record, no extra payments/emails and unchanged queue eligibility.
- [ ] Interrupt before/after source import, CCD submission and local commit. Restart
      converges without skipped records or duplicate confirmed links.
- [ ] Simultaneous workers and concurrent online/manual creation cannot duplicate links.
- [ ] A bad source row can be isolated without abandoning the rest of a bounded batch;
      totals reconcile against the source inventory and the local database.
- [ ] Real-Postgres tests verify source uniqueness and worker concurrency; WireMock covers
      CCD outages/timeouts and conflicting results. Complete the repository build gates.
- [ ] Prepare a non-production rehearsal and compare sampled read-backs plus totals before
      increasing batch size. Record actual execution evidence and operator decisions.

## Sources and related work

- [Foundation](completed/deed-poll-api-02-ccd-foundation.md) — the shared linking and recovery operation
- [Current case entity](../../src/main/java/uk/gov/hmcts/reform/deedpoll/entity/DeedPoll.java)
- [API scope gaps](scope-gap-questions.md) — existing migrated-record parity work
- [CDAM](deed-poll-api-03-cdam-documents.md) — document migration has separate progress
- [Retention](retain-and-dispose.md) — historical TTL backfill is a separate controlled step
