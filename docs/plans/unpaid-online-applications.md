# Unpaid online applications — reminder, recovery and expiry

Status: draft for review, 2026-09-26. Depends on the amended
[payment plan](fees-and-pay-1-ways-to-pay-card.md), [CCD foundation](deed-poll-api-02-ccd-foundation.md)
and Notify provisioning. Disposal depends on [retain and dispose](retain-and-dispose.md).

## Agreed lifecycle

Only submissions from the new online route use this policy. Existing, migrated and
manually entered cases using the established payment process stay under their current
queue rules and are excluded from these reminders and this expiry policy.

| Condition | Required behaviour |
|---|---|
| Accepted online submission | Persist once; establish CCD link before payment; withhold from normal work queue |
| Still unpaid at day three | Send one payment reminder with a secure return link and the deadline |
| Verified payment received | Admit to normal queue filters; stop unpaid reminders/expiry and revoke recovery grants |
| Seven days after submission | Stop new payment attempts; reconcile outstanding attempts before disposal eligibility |
| Payment outcome uncertain | Keep out of normal queue, defer destruction and surface an operational exception |
| Confirmed unpaid, no payable live attempt | Request coordinated disposal and report its progress |

Persist the first accepted submission instant and its seven-day deadline. Duplicate
requests, fresh browser sessions, CCD retries and card retries never restart the clock.
Planning baseline: seven elapsed days from that instant, stored in UTC and displayed
with an explicit date/time understandable to applicants. Write and review the final
copy, including the effect of unresolved payments and scheduled deletion processing.

`DeedPollState` remains date-derived. Submission/payment/expiry status is a distinct
lifecycle. A saved draft that has never been submitted needs a separate cleanup rule;
this policy does not invent one or automatically apply the submission deadline to it.

## Task 1 — durable policy and scheduled work

- [ ] Persist origin/payment route and submission/deadline data via the payment plan.
      Backfill pre-existing records to the established route explicitly; new online
      creation must set its route server-side and ordinary patches cannot change it.
- [ ] Reuse verified payment evidence and one lifecycle transition shared by callbacks,
      polling and scheduled reconciliation. A browser redirect or free-text fee reference
      cannot admit a case or cancel expiry.
- [ ] Implement bounded reminder/expiry work with durable claims and restart behaviour
      across replicas. Track attempts and late/missed work without resetting deadlines.
- [ ] Define reminder timing when delivery was delayed: never send after payment or expiry,
      and do not send stale copy promising more time than remains. Decide handling for an
      absent/undeliverable email before enabling this policy for that application route.

## Task 2 — secure payment return after session expiry

- [ ] Publish an API contract for a narrowly scoped, unpredictable, expiring recovery
      grant tied to one online application. Store a digest/revocation state rather than
      a reusable plaintext bearer secret; exclude grants from logs and diagnostics.
- [ ] Limit the grant to payment resumption and necessary status/reference information.
      It must not reopen answers, documents, other cases or general case-edit operations.
      The public deed-poll/CCD reference alone is never a recovery credential.
- [ ] Design the web-to-API exchange with S2S plus the recovery grant. Establish a short
      scoped browser binding; remove the emailed secret from subsequent URLs, prevent
      referrer leakage and revoke it on payment, expiry or replacement.
- [ ] Avoid consuming a one-time grant or starting a payment on a GET that an email
      scanner may follow. Require an explicit user action for exchange/payment initiation
      and define safe retry behaviour for that action.
- [ ] Reuse the existing application and Service Request, checking unresolved attempts
      before permitting another charge. The current 2h30 payment-return cookie is not
      sufficient for this multi-day recovery route; retain the normal session timeout.
- [ ] Hand the pinned contract and fixtures to the
      [web plan amendment](../../../deed-poll-web/docs/plans/fees-and-pay-2-web-cutover.md#amendment-2026-09-26--payment-deadline-and-secure-return).

## Task 3 — reminder delivery

- [ ] Provision the payment reminder template, sender/support details and approved copy
      with the actual payment deadline and recovery link. Route to the intended applicant
      or representative address according to the accepted application contract.
- [ ] Extend Notify using the existing outbox guarantees. Prevent duplicate enqueue for
      the same policy reminder across workers and re-check eligibility before dispatch.
- [ ] Handle token lifetime securely at dispatch/retry. Inspect how the existing outbox
      stores personalisation; do not put a long-lived recovery secret into persisted
      rendered email content, case notes or correspondence history by accident.
- [ ] Record reminder outcomes and suppress further work after verified payment. Cover
      delivery failure and the unavoidable race with a payment confirmed while an email
      is already being sent; the link must then safely report the paid outcome.

## Task 4 — expiry and payment races

- [ ] At the deadline, atomically prevent new attempts and reconcile all known attempts,
      including downstream creation whose local result is uncertain. Local expiry does
      not itself invalidate a card page already issued by the payment provider.
- [ ] Establish with the supported payment API how outstanding attempts become terminal
      or non-payable. Wait/reconcile where cancellation is unavailable. A timeout, outage,
      `Not paid` callback or missing callback is not proof that deletion is safe.
- [ ] Serialize expiry authorisation with payment success locally. A confirmed payment
      before disposal cancels unpaid expiry and admits the case; persist that outcome
      even if its callback arrived after the deadline.
- [ ] Hand only confirmed disposable applications to the retention coordinator. Retain
      minimal reconciliation/audit information under an explicit financial retention rule.
      Define alert/refund/manual handling if contradictory payment evidence arrives after
      disposal; never silently recreate a deleted application or discard the evidence.

## Acceptance and validation

- [ ] Fixed-clock tests prove reminder/deadline boundaries, retries preserving the deadline,
      payment suppression and exclusion of every established payment route.
- [ ] Postgres concurrency tests cover duplicate schedules, worker crashes, late callbacks,
      and payment racing with expiry. No unresolved payable attempt reaches disposal.
- [ ] Contract/security tests cover invalid, expired, replaced and reused grants, wrong
      application binding, email-scanner GETs and payment after an expired deadline.
- [ ] With stubbed external services, a fresh browser resumes payment on day three, pays
      the same application, and sees its confirmation without recovering personal answers.
- [ ] Unknown payment or dependency outages cause observable deferred deletion. Successful
      disposal is tested across the boundaries in the retention plan, not just a row flag.
- [ ] Run API build gates and the web plan's contract/E2E gates for their respective changes.

## Sources

- [Existing fee reference and origin-related fields](../../src/main/java/uk/gov/hmcts/reform/deedpoll/entity/DeedPoll.java)
- [Existing Notify outbox](../../src/main/java/uk/gov/hmcts/reform/deedpoll/notify/NotificationOutbox.java)
- [Current browser payment binding](../../../deed-poll-web/src/payment/payment-binding.ts)
- [Payment plan](fees-and-pay-1-ways-to-pay-card.md) — verified statuses and reconciliation
