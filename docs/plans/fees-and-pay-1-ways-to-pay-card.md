# Fees & Pay 1 — Ways2Pay card payments for citizen applications

Read the [2026-09-26 amendment](#amendment-2026-09-26--ccd-and-queue-admission)
before implementation; it resolves the case/reference direction and adds the queue rule.

## Overview

Implement the backend half of the public deed-poll payment journey. `deed-poll-api` resolves the
application fee, creates and reuses a Fees & Pay Service Request, starts GOV.UK Pay card attempts,
records their references and status, receives asynchronous payment callbacks, and exposes the result
through the citizen API consumed by `deed-poll-web`.

This is deliberately an API plan. The browser-facing service already has the fee, redirect, pending,
failed and confirmation pages, but they currently run against an in-memory mock owned by
`deed-poll-web`. It must never call Fees & Pay directly or hold a GOV.UK Pay credential.

The plan includes the minimum citizen application surface needed to anchor a payment. That namespace
is currently empty, and a payment cannot safely be implemented against an application identifier that
the real API neither creates nor owns.

The linked web cutover is
[`../deed-poll-web/docs/plans/fees-and-pay-2-web-cutover.md`](../../../deed-poll-web/docs/plans/fees-and-pay-2-web-cutover.md).
Do not start that cutover until this plan publishes a tested contract.

### Source material — read these, do not re-derive them

| What | Where |
|---|---|
| Current consumer contract and mock | `../deed-poll-web/libs/deed-poll-api-client/openapi.yaml`, `src/mock/` |
| Current browser payment flow | `../deed-poll-web/src/payment/`, `src/pages/(payment)/` |
| HMCTS integration recipe | `../apps/payment/docs/how-to/integrate-from-a-service.md` |
| Callback delivery and payload | `../apps/payment/docs/reference/payment-status-callbacks.md` |
| Payment platform source of truth | `../apps/payment/ccpay-payment-app/` |
| Java client surface | `../libs/payments-java-client/` |
| Card orchestration and system-user precedent | `../apps/pcs/pcs-api/src/main/java/uk/gov/hmcts/reform/pcs/feesandpay/` |
| Fee Register lookup precedent | `../apps/pcs/pcs-api/src/main/java/uk/gov/hmcts/reform/pcs/feesandpay/client/PCSFeesClient.java` |
| Service Request precedent | `../apps/nfdiv/nfdiv-case-api/src/main/java/uk/gov/hmcts/divorce/payment/service/PaymentService.java` |

## Stop gates — answer before implementation

These are not details an implementation session may guess. Record each answer in this plan before
Task 1 starts.

1. **When does the case become `Received`?** The current web flow submits before payment and already
   shows a `KBDP{year}-{serial}` reference. The API domain has casework states but no draft/submitted/paid
   payment lifecycle. Decide whether an unpaid citizen submission is already a `DeedPoll`, or whether a
   separate citizen application becomes a case only after payment. This decides the schema, reference
   allocation and what a caseworker can see.
2. **What is the 16-digit `ccd_case_number` for a non-CCD service?** Fees & Pay validates this field on
   every Service Request. A synthetic stable value was proposed in `deed-poll-web`, but it has not been
   accepted by Fees & Pay or Finance. Confirm the derivation and reconciliation treatment. It must be
   stable per application, not per payment attempt.
3. **What fee is being charged?** Obtain the approved fee code or the complete Fees Register lookup tuple,
   its volume/amount rule, the service-request action, responsible-party wording, and the Reference Data
   `hmcts_org_id`. No plausible placeholder may enter production configuration.
4. **Which identities are registered?** Confirm `deed_poll_api` as the outbound S2S caller trusted by
   `ccpay-payment-app`, `deed_poll_web` as the inbound caller trusted by this API, and `payment_app` as the
   caller allowed to PUT payment callbacks. Confirm the exact spelling with the S2S team.
5. **Where can Fees & Pay deliver callbacks?** Confirm the environment-specific, internally reachable
   callback URL and that `ccpay-callback-function` can resolve it. The callback is an HTTP PUT carrying a
   `ServiceAuthorization` token for `payment_app`; this service does not consume the Service Bus topic.
6. **Has card-payment onboarding completed?** Record the GOV.UK Pay service/account, lower-environment API
   key placement, Reference Data service mapping, `ServiceToTokenMap` mapping if needed, Finance engagement,
   trusted-service entries in every target environment, and live merchant setup.
7. **Which IDAM system user calls `POST /service-request`?** Payment App's security filter admits a missing
   user header as anonymous, but its Reference Data path dereferences the `Authorization` header while
   resolving `hmcts_org_id`; omitting it currently produces a 500. Confirm the account, required role,
   credential owner and lower-environment provisioning. The card-create and status endpoints use Payment
   App's service-only chain and do not need this token.
8. **How long is a fee snapshot valid?** Confirm with Fees & Pay/Finance when an uncharged application must
   re-resolve its fee, and what happens when a Service Request already exists for a fee version that has
   since expired. Do not invent a duration or silently charge an obsolete version.

If any gate is unanswered, Task 0 may proceed and produce evidence, but schema and runtime tasks remain
blocked.

## Decisions taken — implement these, do not revisit

1. **Use Ways2Pay Service Requests, not `POST /card-payments`.** Create the Service Request with
   `POST /service-request`, then start a card attempt with
   `POST /service-request/{reference}/card-payments`.
2. **The web app never calls Fees & Pay.** It sees only a fee in pence, a `nextUrl`, and a normalised
   payment status from this API.
3. **Use a dedicated IDAM system user only where Payment App requires it.** `POST /service-request` needs
   the bearer token because its Reference Data lookup dereferences that header even though the security
   filter itself accepts an anonymous user. Card creation and status lookup are on the service-only chain:
   send S2S there and do not spread the system-user token to endpoints that do not need it.
4. **Use the existing outbound `AuthTokenGenerator`.** Mint `ServiceAuthorization` for each downstream
   call. Never store or configure a static leased token.
5. **The citizen API is a separate namespace.** Routes live under `/citizen/**`, use citizen-specific
   DTOs, and are authenticated with inbound S2S independently of the IDAM caseworker and judicial chains.
   Do not reuse caseworker DTOs or weaken the existing bearer-token surface.
6. **Fees & Pay owns card processing; this API owns payment state for the application.** Persist the Fee
   Register snapshot, Service Request reference, every card attempt reference, current normalised status
   and callback evidence. A browser return URL is never evidence that money was taken.
7. **One Service Request per application.** A failed or cancelled card attempt may be retried against the
   same Service Request. A successful payment permanently closes the create-payment operation.
8. **Payment status is monotonic.** A terminal success cannot be overwritten by a delayed pending or failed
   callback. Duplicate callbacks and repeated browser polling are idempotent.
9. **Store money as integer pence internally.** Convert to the decimal pounds required by Fees & Pay only
   at the adapter boundary, with an exact two-decimal conversion.
10. **The fee shown and charged is one snapshot.** Resolve the fee before creating the Service Request,
    persist code, version, amount and description, and use that same snapshot for both `GET .../fee` and
    payment creation. A fee change must not make the card page charge a different figure from the one the
    applicant saw.
11. **Polling transfers recovery responsibility to this service.** Reading
    `GET /card-payments/{reference}` can update Payment App without publishing a callback; its scheduled job
    then skips the no-longer-initiated payment. Persist the queried result before returning it and reconcile
    unresolved or uncertain attempts on this service's own schedule. A callback is still accepted, but it
    is not a safety net after polling.
12. **A Ways2Pay callback proves success, not terminal failure.** `PaymentStatusDto` says only whether the
    Service Request is `Paid`, `Partially paid` or `Not paid`; `Not paid` cannot distinguish pending,
    abandoned, declined or cancelled. Only a direct card-payment query may produce a terminal unpaid result.
13. **The API publishes a concrete contract artifact.** Generate
    `docs/openapi/deed-poll-api.json` from the full-context Springdoc test, fail if the committed file differs,
    and record the source commit in the web repo when it vendors the document. Keep `/tmp/openapi-specs.json`
    for Jenkins compatibility, but do not call it published while nothing consumes it.
14. **No applicant PII in payment logs.** Structured logs may contain application id, service-request
    reference, payment reference, downstream status and correlation id. They must not contain names,
    addresses, answers, callback bodies, tokens or redirect URLs.

## Constraints

- Java 21, `-Werror`, Checkstyle, Flyway-owned schema, Hibernate validation.
- Never edit an existing migration. Take the next free number at implementation time; live Notify plans
  may consume the number that is currently next.
- Never edit `src/test/resources/reference/`.
- No live Fees Register, Payment App, IDAM or S2S calls in tests. Use WireMock.
- Controller tests boot the complete Spring context.
- A downstream timeout or malformed success is not a successful application response.
- Do not add CCD or CDAM as a shortcut. Their plans remain separate and their identifiers are unconfirmed.
- Do not expose raw Fees & Pay payloads or statuses through the citizen contract.

## Validation commands

Run after every task:

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

Use focused tests while developing, but the full build is the task gate. Re-measure and record the unit
and integration test totals at the start and end of the plan.

## Task 0: Close the integration and lifecycle gates

- [ ] Meet Fees & Pay/Finance with the six stop gates above and append dated answers to this plan
- [ ] Provision and prove the dedicated system IDAM user for `POST /service-request`; prove separately that
      card creation and status calls work with S2S and no `Authorization` header. Record status codes without
      recording credentials or tokens
- [ ] Confirm the approved Fee Register lookup tuple and test it through Fees Register
- [ ] Confirm the synthetic/non-CCD `ccd_case_number` treatment and its stable derivation
- [ ] Confirm the callback URL, PUT method, `payment_app` S2S identity and network route
- [ ] Confirm the fee-snapshot validity and stale-Service-Request policy
- [ ] List every external PR/request and owner: S2S registration, Payment App trusted callers and chart
      bump, every Flux environment, Reference Data, `ServiceToTokenMap`, GOV.UK Pay account/key, Finance,
      and live merchant configuration
- [ ] Decide the unpaid application/case lifecycle and amend Tasks 1–2 with the selected model before code
- [ ] Mark completed

## Task 1: Establish the citizen contract and security boundary

- [ ] Implement the citizen application routes needed by the current web contract under `/citizen`:
      create/read draft, submit, fee, create/get payment status, and document upload. Keep document storage
      behind the existing `DocumentStore` seam; this plan does not solve CDAM
- [ ] Define citizen request/response DTOs explicitly. Do not accept the mock's generic answer bag directly
      into an entity; map and validate every supported journey field at the boundary
- [ ] Implement a separate service-only security chain for `/citizen/**`, accepting only configured trusted
      callers and proving that an IDAM bearer token alone cannot enter it
- [ ] Add a separate service-only callback route, for example
      `PUT /callbacks/payments/service-request`, accepting `payment_app` and no citizen/caseworker token
- [ ] Keep `/caseworker/**` and `/judicial/**` on their current IDAM resource-server chain
- [ ] Extend security and namespace-separation tests in both directions
- [ ] Add every citizen and callback path to `OpenAPIPublisherTest`; assert operations and security schemes,
      not only a total path count
- [ ] Mark completed

## Task 2: Persist the citizen and payment lifecycle

- [ ] Add new Flyway-owned tables/columns following the Task 0 lifecycle decision. Do not overload
      `DeedPollState` with payment states; it is the casework lifecycle imported from the reference system
- [ ] Persist a fee snapshot: code, version, amount in pence, description, lookup time and lookup inputs
- [ ] Persist one Service Request reference per application with the stable 16-digit payment case reference
      and the applicant-facing deed-poll reference
- [ ] Persist card attempts separately so retries retain history: internal reference, RC reference when
      available, provider status, normalised status, created/updated/paid timestamps and last status source
- [ ] Add database uniqueness for one Service Request per application and for downstream references
- [ ] Add an explicit in-progress claim/state so two simultaneous Continue requests cannot both call Payment
      App. Design crash recovery before using a network call inside a transaction
- [ ] Add repository integration tests for uniqueness, retries, duplicate callbacks, terminal-state
      monotonicity and concurrent create-payment requests
- [ ] Add the new tables to readiness/schema checks
- [ ] Mark completed

## Task 3: Resolve and snapshot the application fee

- [ ] Add the supported Fees Register client dependency or a narrow typed adapter if the published client
      cannot express the confirmed lookup
- [ ] Put the confirmed lookup tuple in validated configuration. Do not hardcode an amount
- [ ] Map the response to an immutable fee snapshot and reject a missing, ambiguous, non-positive or
      over-precision result
- [ ] Implement `GET /citizen/applications/{id}/fee` from the persisted snapshot; resolve it exactly once
      when the lifecycle first requires it
- [ ] Return integer `amount_pence` to the web contract
- [ ] Cover success, no matching fee, malformed response, timeout and unavailable dependency with WireMock
- [ ] Mark completed

## Task 4: Build the Fees & Pay adapter

- [ ] Reuse `payments-java-client` where its contract matches Ways2Pay; wrap it behind a deed-poll-owned
      port so Payment App DTOs do not leak into domain or controller code
- [ ] Create a Service Request from the persisted fee snapshot, stable 16-digit reference,
      applicant-facing reference, confirmed `hmcts_org_id`, action/responsible party and callback URL
- [ ] Start card payments with amount, `GBP`, the applicant's language and the web-supplied absolute return
      URL. Validate the return URL against configured deed-poll-web origins before sending it downstream
- [ ] Send the system-user bearer token on Service Request creation and omit it from the service-only calls;
      send a freshly generated S2S token to both
- [ ] Persist the Service Request and RC payment references, then retrieve the payment by RC reference and
      persist its internal reference before returning `next_url`; the Ways2Pay create response does not carry
      the internal reference needed by the callback-triggering status endpoint
- [ ] Normalise Payment App failures: authentication/configuration, validation, already paid, payment in
      progress, rate limit, timeout and provider failure. Do not collapse them all to 500
- [ ] Cover request headers and exact wire bodies with WireMock; assert the bearer token is present only on
      Service Request creation and is absent, not empty, on service-only calls
- [ ] Mark completed

## Task 5: Make payment creation retry-safe

- [ ] Implement `POST /citizen/applications/{id}/payment` so it creates or reuses the Service Request and
      starts at most one unresolved card attempt
- [ ] Return 409 for an unsubmitted application, an application already paid, and a concurrent/in-progress
      attempt; map retryable downstream failures to 503
- [ ] Reconcile an attempt whose downstream call may have succeeded but whose local response/save failed
      before allowing another charge. Use Service Request/payment lookup evidence; do not guess from a
      timeout
- [ ] Do not automatically retry card creation downstream. Payment App cancels a live `CREATED` GOV.UK Pay
      session when another attempt is created on the same Service Request, so the API-side in-progress guard
      is the protection for an applicant already on the card page
- [ ] Permit a new attempt only after the previous attempt is terminal and unpaid, reusing the Service
      Request and fee snapshot
- [ ] Prove double-click, two-tab, timeout-after-downstream-create, failed-attempt retry and already-paid
      behaviour in integration tests
- [ ] Mark completed

## Task 6: Status polling and asynchronous callbacks

- [ ] Implement `GET /citizen/applications/{id}/payment` from persisted state, returning 404 only when no
      attempt exists
- [ ] For unresolved attempts, query `GET /card-payments/{reference}` by the persisted RC reference and
      apply its response through the payment transition before returning it to the web app
- [ ] Treat `GET /card-payments/{internal-reference}/status` as a best-effort callback re-trigger only after
      the internal reference exists. Its 400 response when fee apportionment is absent means “not resolved”,
      not terminal failure
- [ ] Parse the Service Request `PaymentStatusDto` callback strictly enough to reject a wrong application,
      Service Request, amount or payment reference, while tolerating documented additive fields
- [ ] Treat callback `Paid` as success; treat `Partially paid` and `Not paid` as unresolved until a direct
      card-payment query supplies a terminal payment status
- [ ] Make callback and poll processing idempotent and monotonic; return 2xx for a valid duplicate and commit
      a polled transition before returning it to the caller
- [ ] Add a scheduled reconciliation job owned by this service for unresolved and poll-result-uncertain
      attempts, because Payment App's scheduled job may skip an attempt after this service has queried it
- [ ] Document `PATCH /jobs/card-payments-status-update` as the Payment App manual-remediation route, not as
      this service's normal recovery mechanism
- [ ] Transition the citizen application/case according to the Task 0 lifecycle decision only after verified
      payment success
- [ ] Record operational evidence without logging the callback body or applicant data
- [ ] Cover delayed success, duplicate delivery, out-of-order delivery, unknown reference, mismatched amount,
      malformed body and invalid S2S
- [ ] Mark completed

## Task 7: Configuration, deployment and observability

- [ ] Add validated configuration for Payment App, Fees Register, callback base URL, allowed web return
      origins, `hmcts_org_id`, fee lookup, fee-snapshot validity, system-user token acquisition, and
      downstream timeouts. Defaults must not accidentally work in a deployed environment
- [ ] Add the `deed-poll-api` S2S secret to `deed-poll-infra`/Key Vault and map it through the chart only
      after the external registration exists
- [ ] Add the system-user credentials to the correct secret store and chart mapping only after the account
      and ownership are approved; never commit them or give them a working local default
- [ ] Add dependency health/readiness detail without making a transient Payment App or Fee Register outage
      kill liveness
- [ ] Add metrics for fee lookup, Service Request creation, payment attempt creation, callback outcome,
      status reconciliation and unresolved-attempt age; no high-cardinality applicant identifiers
- [ ] Update `docs/RESUME.md`, deployment documentation and the external dependency record with completed
      and still-blocked steps
- [ ] Mark completed

## Task 8: Contract and end-to-end proof

- [ ] Generate and commit `docs/openapi/deed-poll-api.json`, make `OpenAPIPublisherTest` fail on drift, and
      update the consumer examples with real endpoint paths, security and normalised statuses
- [ ] Add a full WireMock-backed integration test: citizen draft → submit → fee → Service Request → card
      redirect → pending → callback success → paid status
- [ ] Add failure journeys for declined/cancelled payment, retry on the same Service Request, applicant
      abandonment followed by delayed callback, and unavailable dependencies
- [ ] Hand the published contract and test fixtures to the linked web plan
- [ ] Record final test totals and mark completed

## Success criteria

- A submitted citizen application can be charged once through Ways2Pay and retried safely after an unpaid
  terminal attempt.
- The displayed and charged amounts come from one approved Fee Register snapshot.
- Browser redirects never decide payment state.
- Payment success survives browser abandonment and arrives through the authenticated callback.
- Duplicate, concurrent and uncertain attempts cannot create a second charge.
- `deed-poll-web` needs no Fees & Pay URL, fee code, S2S identity for Payment App, GOV.UK Pay key or Service
  Bus access.
- The published API contract is the source used by the web cutover.

## Out of scope

- PBA, telephony, cash/cheque and refunds.
- CCD, CDAM and document-storage replacement.
- Changing the established browser-page design except where the real contract proves it incorrect.
- Treating a payment callback as proof that GOV.UK Pay settled funds beyond the status semantics supplied by
  Fees & Pay.

## Amendment 2026-09-26 — CCD and queue admission

Steve confirmed the following direction during the decentralised CCD planning session.
This amendment takes precedence over conflicting earlier text; original reasoning is
retained above. Implementation tasks remain unchecked.

### Resolved decisions

1. A new online submission creates a deed-poll record before payment and uses the
   [CCD foundation](completed/deed-poll-api-02-ccd-foundation.md) to establish a real CCD reference
   before creating its Fees & Pay Service Request. This resolves stop gates 1 and 2's
   architecture questions. It does not claim CCD/payment onboarding is complete.
2. New online submissions enter the normal caseworker work queue only after verified
   payment. Casework state remains date-derived; payment/admission is a separate concern.
3. Existing, migrated and manually entered cases using the established payment process
   remain eligible under their existing queue filters. CCD backfill and a missing online
   payment record cannot hide them. Record origin/payment route explicitly: the boolean
   `migratedRecord` is insufficient, and missing payment evidence must not automatically
   label a newly submitted online application as a legacy case.
4. `feeReference` is optional free text in both the current entity and captured Dataverse
   schema. Preserve it for its existing purpose; it is not verified online payment status.
5. Online submissions have a seven-day payment deadline, one day-three reminder and a
   secure payment return route. The [unpaid lifecycle plan](unpaid-online-applications.md)
   owns those behaviours, and the [retention plan](retain-and-dispose.md) owns disposal.
   Existing/migrated cases are excluded from this unpaid policy.

### Changes to execution tasks

- Task 0: replace the synthetic-reference question with proof that CCD onboarding and the
  shared linking operation work for the agreed case type. Retain the unanswered fee,
  service identity, callback, system-user and fee-validity questions (the original list
  contains eight gates, despite Task 0's older reference to six).
- Tasks 1–2: make submission durably idempotent across web/API replicas and restarts.
  The browser's process-local double-submit latch is not sufficient. Persist the online
  origin, first accepted submission time and deadline once; reuse the same local record,
  display reference and CCD linking operation on retry.
- Task 2: backfill existing rows explicitly to the established route without labelling
  them as paid online. Protect route/reference/payment fields from ordinary patches.
  Do not treat nullable CCD registration as an admission flag.
- Tasks 4–5: require confirmed CCD registration before starting a Service Request; use
  its actual reference as `ccd_case_number`. Gate new card attempts on deadline and
  lifecycle state as well as existing payment-attempt concurrency checks.
- Task 6: a validated successful payment atomically admits the online record to normal
  queue filters and stops unpaid lifecycle work. Delayed pending/failed callbacks cannot
  reverse success. Reconcile success arriving after the deadline before any disposal.
- Task 8: prove the queue rule through the API and deed-poll-admin. Audit paged and
  unpaged queries, totals, saved queries, reference-match shortcuts and every queue
  entry point; filtering only the rendered page is insufficient. Keep operational
  reconciliation access separate from the normal work queue, with explicit permissions.
- Task 8 (added 2026-09-27): publish `ccdCaseReference` read-only in the caseworker contract,
  with deed-poll-admin's spec refresh landing alongside it. Plan 02 stores the reference but
  deliberately publishes it nowhere, so this plan is the first that changes the contract for
  it — and the refresh is atomic in admin (`contract.test.ts`), so it cannot land api-only.

### Additional acceptance criteria

- [ ] Unpaid new online cases do not appear in the normal queue, its counts or saved-query
      results; paid ones appear once and remain subject to normal casework filters.
- [ ] Existing/manual/migrated cases retain their prior eligibility with an empty
      `feeReference`, null CCD reference or no online payment record.
- [ ] Origin cannot be supplied or patched by an applicant to bypass the payment gate.
- [ ] Duplicate submission, CCD uncertainty and payment retries preserve one application
      and its original deadline. Payment cannot start while CCD registration is uncertain.
- [ ] API and admin regression tests demonstrate the eligibility matrix. The web uses
      the published contract and does not infer payment success from redirects.

The earlier exclusion of implementing CCD/CDAM inside this payment plan still separates
ownership: those have dedicated plans. The prerequisite is now a real CCD link. A
production journey promising reminders and deletion must also complete the unpaid
lifecycle and disposal plans; this amendment does not mark them as shipped.
