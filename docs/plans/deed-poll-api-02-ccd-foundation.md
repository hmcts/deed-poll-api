# Plan 02 — minimal decentralised CCD foundation

Status: draft for review, 2026-09-26. No implementation tasks completed.

## Outcome

Create one genuine CCD case for an existing deed-poll record, store its reference,
and read the minimal case projection through CCD. The relational deed-poll record
remains authoritative. This is the shared foundation for payment, document handling
and migration. See the [programme overview](../../../deed-poll-documentation/plans/active/ccd-and-shared-services.md).

## Agreed constraints and proposed design

- Keep the case definition minimal: one case type, one initial technical state,
  one create/link event, linking fields and the permissions required to create/read.
  Proposed linking fields are the immutable deed-poll UUID and display reference.
  Platform metadata and any import-required configuration must also be present.
  Final field/event/state names are implementation choices, not confirmed identifiers.
- Implement a decentralised `submitHandler` and `CaseView` with the SDK. Conventional
  `about_to_submit` and `submitted` callbacks are skipped in this mode.
- Add a nullable CCD reference to `deedpoll_deedpoll`, unique when present and held as
  a string at application/API boundaries. The implementation should follow the schema's
  mixed-case naming convention (proposed `ccdCaseReference`). This is the user's
  requested `CCD_case_ref` concept. Do not make it writable through ordinary case patches.
- Use the deed-poll UUID for correlation. A display-reference prefix can change when
  the case type changes, so it must not be the sole identity used to link records.
- Commit the local record before making the outbound CCD call. CCD calls this API back
  on a separate request and must be able to read it. Do not hold a transaction/row lock
  across that network call when the handler needs the same row.
- In the handler, validate the intended local record, lock it, and bind the supplied CCD
  reference atomically with the SDK event work. The same link is safe to repeat; a
  different reference for an already-linked record is a conflict, never an overwrite.
- A non-null reference records a local binding. The durable operation must separately
  distinguish confirmed completion from an uncertain final CCD response. Reconciliation
  verifies the pointer and local link before downstream use.

## Task 0 — onboarding and pinned contract

- [ ] Record jurisdiction, case type ID, initial environment, definition import owner,
      IDAM roles/system account and credential ownership. Keep the existing placeholder
      until these are agreed; never register guessed identifiers.
- [ ] List outbound CCD S2S access, inbound `ccd_data` trust, definition role registration,
      case-type-to-service routing, callback host and network reachability per environment.
- [ ] Check the SDK version compatible with this Java 21 / Spring Boot build and pin it.
      Inspect that version's security, transaction, Flyway, event and read contracts.
- [ ] Decide explicitly whether the initial use cases require CCD search/indexing. The
      bespoke admin queue already uses the local database. Document any required indexer
      configuration and publication flags; Work Allocation is not an assumed dependency.

## Task 1 — schema and durable linking operation

- [ ] Add the nullable reference and uniqueness constraint using the next available
      Flyway migration. Define validation and protect the link from ordinary patch APIs.
- [ ] Design durable creation-attempt records: local UUID, operation identity, attempt
      status, safe retry/reconciliation evidence, timestamps and bounded failure detail.
      Reuse these records in the migration plan rather than making a second retry engine.
- [ ] Prevent concurrent creation for the same local record across API replicas. An
      expired worker claim requires reconciliation before starting another CCD creation.
- [ ] Distinguish API operation idempotency from the SDK's CCD event idempotency. CCD
      derives its key from the start-event token; starting a fresh event can mint a new
      reference. Persist/reuse valid attempt context and define token-expiry recovery.
      Keep tokens in protected storage only where needed, never in logs or reports.
- [ ] Preserve existing records and queue behaviour. The reference being null is not a
      reason to hide existing or migrated cases or to treat them as unpaid online cases.

## Task 2 — SDK, definition and security

- [ ] Add config generation and decentralised runtime dependencies; verify component
      registration and separate SDK `ccd` migrations from the service's `public` schema.
- [ ] Generate the minimal definition and validate/import it against the selected contract.
      Grant only the agreed actors access to the case type, state, event and linking fields.
- [ ] Implement the create/link `submitHandler` and the minimal `CaseView`. Keep domain
      entities behind explicit projection types; preserve UUID and authoritative reference.
- [ ] Secure `/ccd-persistence/**` for the agreed CCD caller and forwarded user identity.
      Test missing/wrong service tokens, unauthorised users, unknown types and forged local
      UUIDs. A UUID in a payload alone is not permission to bind a case.
- [ ] Verify SDK support for reads, event history, supplementary data, stable replay and
      revisions; implement the service hooks without recreating the SDK protocol.

## Task 3 — caller and recovery

- [ ] Provide one API-owned linking operation used by online submission and migration.
      Start and submit through CCD's public case/event API; never call the persistence
      controller directly to manufacture a CCD case.
- [ ] Obtain the required S2S and IDAM tokens with confirmed identities. Anonymous
      applicants remain anonymous; the API performs the system operation on their behalf.
- [ ] Reconcile success with a lost response, handler rollback, pointer failure and a
      conflicting existing link. Do not clear a binding and retry blindly on a timeout/404.
- [ ] Confirm the final reference/case type/local UUID before marking the operation ready
      for payment. Do not allow partial CCD registration to start a Service Request.

## Acceptance and validation

- [ ] A local record can be created, linked, and read through CCD with matching identities.
- [ ] Duplicate delivery replays the original event result, including after later changes.
      Two fresh concurrent start-event attempts cannot establish two durable links.
- [ ] Crash/restart after each network/commit boundary converges to one confirmed link
      or a visible repair item; the worker never silently produces another case.
- [ ] Handler/domain writes and SDK audit writes roll back together. Existing data survives
      schema migration, and existing caseworker list/count behaviour is unchanged.
- [ ] Unit, full-context security/contract and real-Postgres integration tests cover these
      risks. External calls are stubbed (WireMock); run the repository build gates.
- [ ] Prepare an operator-run non-production smoke procedure for definition import,
      create/read and authentication with provisioned test identities. Record the executed
      evidence separately; planning and stubbed tests do not establish onboarding success.

Document fields and TTL events belong to the following plans. This stage does not run
a bulk migration or enable deletion.

## Sources and follow-on plans

- [SDK/persistence contract](../../../apps/ccd/docs/reference/decentralised-callbacks.md)
- [Decentralisation recipe](../../../apps/ccd/docs/how-to/decentralise-a-service.md)
- [Definition import prerequisites](../../../apps/ccd/docs/how-to/publish-definition-to-aat.md)
- [Current entity](../../src/main/java/uk/gov/hmcts/reform/deedpoll/entity/DeedPoll.java),
  [case service](../../src/main/java/uk/gov/hmcts/reform/deedpoll/domain/deedpoll/DeedPollService.java)
- [Migration](ccd-migration.md), [payments](fees-and-pay-1-ways-to-pay-card.md),
  [CDAM](deed-poll-api-03-cdam-documents.md), [retention](retain-and-dispose.md)
