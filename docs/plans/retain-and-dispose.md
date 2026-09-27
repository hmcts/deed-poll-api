# Retain and Dispose — platform and local record lifecycle

Status: draft for review, 2026-09-26. Depends on the
[CCD foundation](completed/deed-poll-api-02-ccd-foundation.md); document disposal depends on
[CDAM](deed-poll-api-03-cdam-documents.md).

## Outcome and boundaries

Coordinate disposal of CCD pointers, attached documents and local deed-poll data.
CCD's disposer does not delete this service's database rows or call a local delete
endpoint. The service must implement and verify its own cleanup process.

The agreed seven-day policy applies to unpaid submissions from the new online route
only, after payment reconciliation. Retention dates for paid, existing and migrated
cases are still business inputs. The existing `retainPermanently` flag must be mapped
explicitly and must prevent automatic expiry; do not infer a historical deletion date
from the date a legacy record was linked to CCD.

## Task 0 — retention inventory and completion contract

- [ ] Record retention triggers/durations for each applicable class of record, the
      permanent-retention rule, overrides/holds and who may change them. Paid/historical
      durations are unconfirmed; do not supply working defaults.
- [ ] Inventory application data, witnesses, notes, referrals, document metadata/binaries,
      SDK case/event snapshots, indexing/outbox rows, Notify rendered content and file
      links, recovery grants, migration mappings, payment records, logs and backups.
      Identify owners, deletion mechanisms and separately retained financial/audit data.
- [ ] Agree with the platform owners how to observe successful CCD/document disposal and
      handle partial failures. A case inaccessible to one caller is not proof of deletion.
- [ ] Define seven-day applicant wording against actual scheduling: a local timestamp
      deadline and CCD's date-based TTL/nightly processing have different precision.
      The CCD TTL must never permit deletion before local payment reconciliation.

## Task 1 — TTL integration

- [ ] Extend the definition/projection with the platform TTL type and minimal authorised
      events for scheduling, suspension and override. Set permissions for confirmed roles.
- [ ] Prove the pinned decentralised event sequence end to end: submitted TTL, CCD's
      resolved value, service persistence and pointer update. Persist the CCD-supplied
      resolved TTL as required by the contract; verify changes/holds on later events.
      Do not assume mutating TTL only inside a handler updates every platform stage.
- [ ] Keep the application payment deadline separate from CCD's effective deletion date.
      Initially withhold a disposable TTL for unpaid applications while attempts are
      unresolved; schedule disposal only after the unpaid lifecycle authorises it.
- [ ] Synchronise permanent retention and holds through authorised CCD events. Cover
      null TTL, overrides and platform guards; no direct SQL change to a CCD pointer.

## Task 2 — platform onboarding and simulation

- [ ] Prepare the LAU/platform onboarding request with confirmed case type, expected case,
      document and role-assignment volumes and the operational owner.
- [ ] Configure/verify simulation before deletion, review selected records and exclusions,
      then record explicit rollout authority before enabling hard deletion in an environment.
- [ ] Document linked-case behaviour and any platform dependency that can postpone disposal.
      Do not interpret a passed local deadline as evidence that the platform deleted a case.

## Task 3 — local cleanup and recovery

- [ ] Build bounded cleanup work from expired local resolved TTLs and authorised disposal
      intents. Check CCD with an identity proven to see these cases. The documented
      garbage-collection pattern uses a GET-case 404 after disposal; verify that result
      alongside auth/configuration health and available disposal evidence before deletion.
- [ ] A 200, 401/403, outage, unexpected 404 pattern or partial-disposal outcome must defer
      destructive local work and create an observable retry/repair item. Never treat a
      connectivity or permission failure as absence of data.
- [ ] Recheck payment eligibility/holds before authorising platform disposal and before
      local cleanup. Disable payment starts and ensure issued payment attempts are no
      longer payable before scheduling irreversible platform work. The late-payment
      exception route is defined in the unpaid lifecycle plan.
- [ ] Delete approved local data in a restartable order, including SDK snapshots and pending
      jobs that could resurrect or reindex it. Define minimal tombstone/reconciliation
      retention explicitly, without preserving applicant data by accident.
- [ ] Handle partial deletion across platform/local stores, expired unattached uploads and
      never-linked local applications. Unknown CCD creation outcomes must be reconciled
      before classifying a record as never linked.
- [ ] Add a runbook for holds, dry run, activation, pause, retries, partial failures and
      disposal evidence. Expose counts/age/failures without logging deleted personal data.

## Acceptance and validation

- [ ] Fixed-clock and stubbed-platform tests cover expired/not-expired, paid, permanently
      retained, held, overridden, legacy and unknown-payment records.
- [ ] Platform-contract tests prove TTL round trips and hold changes; document attachment
      and platform disposal are exercised in a provisioned non-production rehearsal.
- [ ] Wrong credentials, inaccessible cases, platform outages and partial deletion never
      trigger destructive cleanup. Restarting a completed/partial job is safe.
- [ ] After completed disposal, approved local data and SDK snapshots are gone; no reminder,
      callback, recovery grant or migration rerun recreates the case. Separately retained
      audit/financial data matches the explicit inventory policy.
- [ ] Simulated backlog selection matches reviewed expectations before any hard-delete
      rollout. Historical disposal remains disabled until its retention rules are settled.
- [ ] Complete API unit, security/contract, Postgres integration and build gates. Stub
      external calls in automated tests; record operator-run environment evidence separately.

## Sources

- [Enable Retain and Dispose](../../../apps/ccd/docs/how-to/enable-retain-and-dispose.md)
- [Decentralised TTL contract](../../../apps/ccd/docs/reference/decentralised-callbacks.md#resolvedttl-handling)
- [Existing permanent-retention field](../../src/main/java/uk/gov/hmcts/reform/deedpoll/entity/DeedPoll.java)
- [Unpaid application lifecycle](unpaid-online-applications.md)
- [Migration](ccd-migration.md) — original dates, identity and historical scope
