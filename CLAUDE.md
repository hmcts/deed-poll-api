# deed-poll-api

Repo-level guidance. The workspace-level file at `../CLAUDE.md` covers navigating the CFT workspace;
this one covers the conventions of *this* clone, which are mostly non-obvious and several of which
break silently if ignored.

## What this service is

The Spring Boot 3 backend for deed poll case management, on Java 21 with Gradle. It is a **port** of
the TypeScript domain layer in the sibling repo `hmcts/deed-poll-admin` (`libs/deed-poll`), which is
the specification. It replaces that repo's `apps/api`.

There is **no CCD and no CDAM** here. Those are plans 02 and 03, both blocked on external onboarding.
See `docs/RESUME.md` for the current state and `docs/plans/` for the build plans.

## Layout

```
api/caseworker/{controller,dto}   the caseworker HTTP surface — 34 operations across 21 paths
api/judicial/{controller,dto}     the judicial surface — 5 operations, keyed on the referral, never on the case
api/citizen/{controller,dto}      deliberately EMPTY, reserved for deed-poll-web
domain/{deedpoll,notes,witnesses,documents}   the ported specification layer
domain/savedqueries               saved and shared case-list queries — NOT a port, no reference to compare
domain/referrals                  referrals to the judiciary and the applicant's confirmation — NOT a port
domain/deeds                      fills the court's two Word deed templates from a case — NOT a port
domain/auth                       CurrentUser, IdamClaims, Role — transport-independent
notify/                           the GOV.UK Notify outbox: compose, enqueue, drain — NOT a port
auth/                             IDAM resource-server wiring (transport)
entity/, repository/              JPA over a Flyway-owned schema
health/                          readiness that names the missing tables
storage/                         LocalFilesystemDocumentStore — the seam CDAM replaces
src/main/resources/deed-templates/  VENDORED Word templates: LOC020 and LOC022, plus their provenance
src/test/resources/reference/    VENDORED specification: TypeScript source and Dataverse exports
```

## Rules that break things silently if ignored

- **Never edit `src/test/resources/reference/`.** It is a verbatim vendored copy of the
  specification, and the conformance tests read it as ground truth. If one of those files looks
  wrong, stop and report it — do not "fix" it.
- **Never edit a committed Flyway migration.** Add `V{n+1}__….sql`. Editing one breaks every
  environment that has already run it.
- **The deed templates in `src/main/resources/deed-templates/` are the court's wording, and the generator may
  only change the text inside their 30 and 13 content controls.** Both are Dynamics 365 Word templates: the
  values sit in `w:sdt` controls carrying an XPath data binding, not merge fields, so `DeedGenerator` replaces
  exactly two entries of the zip — `customXml/item1.xml`, rebuilt from scratch because the supplied one holds
  all 111 Dataverse elements with 17 of them stuffed with an escaped Word document, and the cached `w:t` text
  inside each control, because a reader that does not resolve bindings shows that instead. **Everything that
  can go wrong here goes wrong silently**: a data part in the wrong namespace, one template's `ds:itemID`
  written into the other (they differ), a control the mapping does not know, or the cached text left alone — all
  produce a deed that opens cleanly and prints blanks or field names. Hence the checks in `DeedControls` and
  `DeedGenerator.checkFields`, and hence `DeedGeneratorTest` verifies the output by evaluating each binding's own
  XPath with a real XPath engine rather than by re-reading what the generator was told to write. A template
  changed only in `deed-poll-admin` never reaches production; see that directory's `README.md`.
- **Never invent the CCD case type or jurisdiction ID.** They are unconfirmed. The single constant is
  `deedpoll.ccd.case-type-id: DEED_POLL_PLACEHOLDER` in `application.yaml`.
- **No live calls to CCD, CDAM, IDAM or payments in any test.** WireMock only (`StubIdam`); there are
  no credentials.
- **No secrets.** Everything by environment variable, with a non-working default.
- **A case note written from the outbox drain must be impossible to refuse.** `NotificationOutbox.drain` runs a
  whole batch in one `REQUIRES_NEW`, and the note the transition into `Failed` writes is not guarded — so a note
  the notes column or validator rejects rolls back rows Notify has *already accepted*, and the next drain emails
  those applicants again. `EmailNotes` therefore composes from fixed prose plus a name bounded at 200, never from
  `failureReason` or any other external text, and `EmailNotesTest` pins both notes under `NoteForm.MAX_NOTE_LENGTH`
  for *every* `EmailTemplate` member, so a new template with a verbose `displayName()` fails a test instead of
  failing mid-drain. The sent note is in `CaseEmailService.send`'s own transaction for the same reason, not in the
  drain. `NotificationOutboxTest.oneRefusedNoteTakesTheBatchWithIt` is what keeps that cost visible.
- **Every email event puts exactly one marker on the Notes grid** — one on a fresh enqueue, one on the transition
  *into* `NotificationState.Failed` (the transition, not a `PERMANENT` verdict, which would miss the
  exhausted-retry route), and none for a transient failure or an idempotent repeat send. A new send or settle path
  that skips the note leaves the case's only timeline silent and no existing test fails.
- **`NotificationRepository.enqueue` names no `ON CONFLICT` target, so it infers *every* unique index on
  `deedpoll_notification`.** Two of them can refuse a row — V19's `("deedPollId", "requestId")` and V15's partial
  `("referralId", "exclusionGroup")` — and `ON CONFLICT` takes at most one inferred target, so naming either would
  let the other raise `DataIntegrityViolationException` and mark the caseworker's transaction rollback-only. The
  price is that zero rows now has three meanings, resolved by *reading* in `CaseEmailService.resolveRefusedInsert`
  in a fixed order: this case's row for this request id (an idempotent 200, or a 409 if its content disagrees),
  then the referral's exclusion-group occupant (200 for the same template, 409 for the contradictory one),
  otherwise `IllegalStateException`. **A third unique index added without teaching that method turns an ordinary
  conflict into a 500**, and nothing fails the build to say so. The index-existence check a named target used to
  buy is gone too: `NotificationRepositoryTest` against real Postgres is the only place either conflict clause
  executes, and Docker-free CI does not run it.

## Conventions worth knowing before writing code

- **Column names are mixed case** (`newForename`), because the reference schema's are. That is why
  `spring.jpa.properties.hibernate.globally_quoted_identifiers: true` and
  `PhysicalNamingStrategyStandardImpl` are load-bearing: Boot's default strategy would rewrite them
  to `new_forename` and stop finding them. Enum columns also need
  `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`, or Hibernate sends the value as an integer ordinal.
- **Flyway owns the schema; Hibernate validates against it** (`ddl-auto: validate`). Entities are
  checked against the migrations, never the other way round.
- **A patch is three-state: absent / null / value.** Omitting a field leaves it alone; an explicit
  null clears the column. A record of nullable components cannot express that, so the idioms are
  `DeedPollPatch` (a keyed map), `PatchField<T>` and `Optional<T>` — never nullable fields. `DeedPollPatch`
  also places no *type* constraint on its values: a value may be a domain type or the text a form
  produced, so read one through `DeedPollFields.asType` rather than casting.
- **Never return an entity from a controller.** Separate request and response DTOs per operation
  under `api/…/dto`.
- **The judicial surface is scoped by visibility, not by the case.** A caseworker read names its parent
  (`/caseworker/cases/{id}/…`); a judicial URL carries a referral id and no case id at all, so
  `JudicialReferralService.visibleTo` stands where the parent id stands — the pool, plus this judge's own
  `Claimed`/`Decided` referrals — which `GET /judicial/referrals/mine` is the list of. Anything else is **404**,
  in the same words an unknown id gets; the only 403 is the `Role.JUDICIAL` gate. Its five operations must not
  reuse a caseworker DTO, and an ArchUnit rule fails the build if one does.
- **Ownership is the token's IDAM subject, never a display name.** `claimedById`/`decidedById` (V14) are what
  `claim`, `requireHeldBy`, `visibleTo` and the personal list compare; `claimedBy`/`decidedBy` are attribution
  only, and neither id is published in a DTO, a note or a log. A null owner id belongs to nobody — no fallback
  to the name, or a namesake regains access exactly where identity is uncertain.
- **A case column that mirrors the referral table is recomputed from truth, never pushed.**
  `currentReferralState` (V9) and `awaitingConfirmationSince` (V17) are read-models on `deedpoll_deedpoll`,
  because the case list can only filter, sort and render columns of that one entity. Every write that could
  change the answer calls the single recompute — `ReferralService.refreshCurrentReferralState` /
  `refreshAwaitingConfirmation`, the second public only because `CaseEmailService.send` is in another package —
  inside the same transaction. The rule itself lives in a class with no database in it (`CurrentReferralState`,
  `ConfirmationWait`) so that CI, which has no Docker, runs it. **A new write path that skips the recompute
  leaves the column wrong for ever and no existing test fails.**
- **A send's identity is the caller's `Idempotency-Key`, and it is transport rather than correspondence.**
  `POST /caseworker/cases/{id}/emails/{template}` requires the header — one compose journey mints one UUID and
  reuses it for every retry of that POST, a later journey mints another — and it is stored as
  `Notification.requestId`: NOT NULL, bound explicitly by `enqueue`, with V19's `gen_random_uuid()` default there
  only to carry a pre-V19 instance through a rolling deployment and deliberately **no `@PrePersist` fallback**,
  because a writer that cannot say which request it is should fail rather than invent one. It is the only header
  bound anywhere in the service, which is why `DomainErrorHandler.missingHeader` handles
  `MissingRequestHeaderException` and not its parent. The id is published nowhere — no DTO, no note, no log line —
  asserted by an ArchUnit `noFields()` rule plus its counterpart that the column does exist on the entity. Every
  lookup is case-scoped (`findByDeedPollIdAndRequestId`): widening it to the request id alone would answer one
  caller with another case's receipt.
- **A new email template is declared in a handful of places, and two of them fail silently.** The `EmailTemplate`
  member (key, display name, `EmailExclusionGroup`, `invitesReply`, resolved keys, slots), a personalisation
  record, and four `switch` *expressions* in `CaseEmailService` — `cause`, `compose`, `reasonNotApplicable`,
  `startsConfirmationClock` — which have no `default` and so refuse to compile until somebody says what the new
  template's rule is. What fails silently is a missing `notify.templates.<key>` in `application.yaml` or a missing
  env var in the chart: the template then reads unprovisioned and never sends. A template is either
  **referral-driven or case-driven** and `EmailCause` says which — a case-driven one stores a null `referralId`,
  sits outside V15's partial index, and so has *no* database protection against a repeat; the request id is all
  there is. A required `EmailSlot` carries the refusal sentence itself (`requiredBecause`) rather than a boolean
  plus a message, and `OpenAPIWriteBodies` renders that same sentence, so the document and the refusal cannot
  drift. Slot defaults come from `EmailSlots.defaults` and never from `parse` of an empty payload: a slot that is
  required and pre-populated from nothing would otherwise make `GET /caseworker/cases/{id}/emails` answer 400 for
  every case the template exists for.
- **A new case column has to be declared in four places**, or the build fails somewhere that does not name the
  cause: a reader in `DeedPollFields` (readable / writable / queryable are three separate decisions — `CaseSort`
  admits only readable names and `CaseQuery` only queryable ones), both `CaseDetailResponse` and
  `SavedCaseResponse`, and the shared `CaseResponseFields` test fixture.
- **A controller test must boot the whole context.** A `@WebMvcTest` slice does not get
  `SecurityConfiguration` — its include list covers `SecurityFilterChain` beans, and a
  `@Configuration` that merely *declares* one is not itself one — so a slice asserts Boot's defaults
  in both directions: it refuses a public path and admits a protected one under `@WithMockUser`.

## Build and test

```bash
./gradlew build                            # compile + checkstyle + unit tests + integration
./gradlew test --tests "*Conformance*"     # the port against the vendored specification
./gradlew test --tests "*Security*"        # authentication and namespace separation
./gradlew integration                      # the integration source set alone
```

`build.gradle` pins a **Java 21** toolchain; an older JDK fails before compiling. `-Werror` is on.

On this machine, export `TESTCONTAINERS_RYUK_DISABLED=true` — Ryuk listens on IPv6 inside its
container and this Docker configuration does not forward that. `ContainerDatabase` already reaches
containers over the Docker bridge network in preference to a published port, for the same reason.
`failFast` is set on `integration` but deliberately **not** on `test`, so a unit run reports every
failure rather than only the first.
