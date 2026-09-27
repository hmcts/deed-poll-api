# deed-poll-api

Repo-level guidance. The workspace-level file at `../CLAUDE.md` covers navigating the CFT workspace;
this one covers the conventions of *this* clone, which are mostly non-obvious and several of which
break silently if ignored.

## What this service is

The Spring Boot 3 backend for deed poll case management, on Java 21 with Gradle. It is a **port** of
the TypeScript domain layer in the sibling repo `hmcts/deed-poll-admin` (`libs/deed-poll`), which is
the specification. It replaces that repo's `apps/api`.

Plan 02 built a **minimal decentralised CCD foundation** (2026-09-27): one CCD case per deed-poll record, linked
by the record's UUID, created through data-store's public API by one linking operation that nothing yet calls. The
relational record stays authoritative; CCD holds a two-field projection. It is proven against WireMock and a local
cftlib stack on placeholder identifiers only — **nothing is onboarded**, and `docs/CCD-SMOKE.md` is the AAT
procedure that waits on it. There is **no CDAM integration** (plan 03). See `docs/RESUME.md` and `docs/plans/`.

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
notify/DocumentUploads            turns the document ids frozen on an outbox row into Notify uploads at drain
                                  time — a time-limited download link, never an attachment; the row is read and
                                  never rewritten, because a token expires and would be at rest in the database
auth/                             IDAM resource-server wiring (transport)
entity/, repository/              JPA over a Flyway-owned schema
ccd/                             the decentralised CCD case type, its persistence handler and view, and the one
                                  linking operation with its attempt ledger — NOT a port
health/                          readiness that names the missing tables
storage/                         LocalFilesystemDocumentStore — the seam CDAM replaces
src/main/resources/deed-templates/  VENDORED Word templates: LOC020 and LOC022, plus their provenance
src/test/resources/reference/    VENDORED specification: TypeScript source and Dataverse exports
src/test/resources/ccd-definition/  the generated CCD definition, pinned — DeedPollDefinitionTest compares every file
src/cftlib/, src/cftlibTest/     the in-process CCD stack's wiring and the one end-to-end test; local only
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
  changed only in `deed-poll-admin` never reaches production; see that directory's `README.md`. **One case value
  can break the package on its own**: `DeedXml.escape` is the single escaper both halves share, and a character
  XML 1.0 cannot carry at all — a C0 control other than tab/LF/CR, or an unpaired surrogate — has no escape, so it
  is replaced with a space rather than written through. `DeedPollSchemas` validates these columns by length only,
  and the generator never parses its own output, so without that the deed would be stored, frozen onto a
  notification and emailed before anybody discovered Word would not open it.
- **Never invent the CCD case type, jurisdiction ID or system role.** They are unconfirmed. Each is one key under
  `deedpoll.ccd` in `application.yaml` defaulting to `DEED_POLL_PLACEHOLDER`, read only through `CcdProperties`,
  so a grep for the literal lists every external identifier this service is waiting on.
- **Never define a `FlywayMigrationStrategy`.** The SDK's `DecentralisedFlywayAutoConfiguration` supplies one that
  runs its own `ccd`-schema migrations, with their own history table, before ours. A bean of ours replaces it
  without an error, the `ccd` tables are never created, and data-store's first callback fails in whichever
  environment first lacks them. `ConfigGenProfileTest` fails the build if one appears.
- **The CCD binding is written once and never overwritten.** `LinkDeedPollHandler.submit` runs inside the SDK's
  event transaction (`propagation = MANDATORY`), locks the record, and asks `CcdLinkRule.decide`: the same
  reference again is a no-op, a different one is a refusal, and a UUID in the payload binds nothing unless the
  record's live creation attempt is `Claimed` or `AwaitingConfirmation`. `ccdCaseReference` is mapped
  `insertable = false, updatable = false`, and its only writer is `DeedPollRepository.bindCcdCaseReference`,
  conditional on NULL. Recovery in `CcdLinkingService` is by **reading** the binding and CCD, never by clearing a
  binding or starting a fresh event: a fresh start-event token mints a fresh idempotency key in data-store and can
  create a second case for one record. What cannot be settled becomes `NeedsRepair`, visibly.
- **A new CCD field is declared in three places, and the build names only one of them.** `DeedPollCaseData` and
  its grants in `DeedPollCaseType`, the projection `DeedPollCaseView` composes from (`CcdCaseProjection`, read by
  `DeedPollRepository`), and the pinned JSON under `src/test/resources/ccd-definition/`. `DeedPollDefinitionTest`
  fails on the definition; a field left out of the view reads as null through CCD, and nothing fails unless `DeedPollCaseViewTest` is taught it.
  Keep the definition minimal: `DEED_POLL_SYSTEM` is granted create and read and nothing else is granted
  anything, and the test refuses growth that was not deliberate.
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
  `deedpoll_notification`.** Three of them can refuse a row — V19's `("deedPollId", "requestId")`, V15's partial
  `("referralId", "exclusionGroup")` and V20's partial `("deedPollId", "exclusionGroup")` over the groups
  `EmailExclusionGroup.exclusivePerCase()` names — and `ON CONFLICT` takes at most one inferred target, so naming
  any of them would let the others raise `DataIntegrityViolationException` and mark the caseworker's transaction
  rollback-only. The price is that zero rows now has four meanings, resolved by *reading* in
  `CaseEmailService.resolveRefusedInsert` in a fixed order: this case's row for this request id (an idempotent 200,
  or a 409 if its content disagrees), then the referral's exclusion-group occupant (200 for the same template, 409
  for the contradictory one), then — only where the referral is null and the group is exclusive per case — the
  case's occupant (the same two answers in the case's words), otherwise `IllegalStateException`. **A fourth unique
  index added without teaching that method turns an ordinary conflict into a 500**, and nothing fails the build to
  say so. Which of V15's and V20's scopes applies is stated in Java exactly once, in
  `CaseEmailService.occupantOfTheGroupSlot`, because the availability check and the refusal must agree; the enum
  flag and V20's `IN` list state the same fact twice and `EmailExclusionGroupTest` holds them together. The
  index-existence check a named target used to buy is gone too: `NotificationRepositoryTest` against real Postgres
  is the only place any conflict clause executes, and Docker-free CI does not run it.
- **A deed is generated once per *case*, and which `DeedPollType` takes which deed is stated once.**
  `DeedDocumentTypes.caseTypes` is the one statement; `DeedRouting.deedFor` searches it, `FormsForCompletion` is the
  only place a `DeedTemplate` meets an `EmailTemplate`, and a second copy of the adult/minor lists is how an
  applicant swears the wrong legal instrument. `DeedDocumentService.storeDeed` looks across *both* deed types and
  **refuses** when the case holds the other one — it neither regenerates (the stored deed may already be signed in
  wet ink) nor hands the wrong one back (the caller is about to email it under the other deed's covering words).
  That contradiction is reachable, which is why it refuses rather than warns: V20's index, like V15's, excludes a
  `Failed`/`PERMANENT` row, so a case whose first forms email failed permanently and whose type was then corrected
  is offered the other one with nothing in the outbox in the way. Where the outbox *can* see the contradiction it
  gets first say, so `CaseEmailService.deedDocumentId` reads the group slot before generating anything and the
  caseworker is shown the same sentence the Email tab greyed the option out with. The deed's
  `DeedPollSupportingDocumentType` row is **created on first use and found by name** — no migration seeds it, and
  because the row is real, `GET /caseworker/document-types` publishes it and the upload endpoint takes it as any
  other `documentTypeId`. So **the name cannot tell a generated deed from a caseworker's upload filed under it** —
  and the obvious thing to file under "LOC020 Change of name deed" is the signed deed coming back. The direction
  decides: the court's deed went `Outgoing`, and `storeDeed` refuses anything else under a deed's type in the same
  words, because answering with it would email the applicant their own returned paperwork as the deed to swear.
  `existingDeed` still reads **both** directions on purpose — narrowing the query would hide a generated deed whose
  direction a caseworker patched, and the next send would store a *second* legal instrument, which is the one
  failure the generate-once rule exists to prevent. Refusing costs one correction; a sworn deed cannot be undone.
- **A read-only `@Transactional` method that throws still poisons its caller's transaction**, unless it says
  otherwise. Spring's default marks a *participating* transaction rollback-only, so the caller's `catch` decides
  nothing and its commit fails anyway. That is why `DocumentService.downloadDocument` carries
  `noRollbackFor = Exception.class`: `DocumentUploads` catches an unreadable document and answers a retryable
  refusal inside the drain's single transaction, and without it one missing blob rolled back rows Notify had
  already accepted and emailed those applicants again on the next drain.

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
  member (key, display name, `EmailExclusionGroup`, `invitesReply`, resolved keys, slots, **document keys**), a
  personalisation record, four `switch` *expressions* in `CaseEmailService` — `cause`, `compose`,
  `reasonNotApplicable`, `startsConfirmationClock` — and a fifth in `FormsForCompletion.deedOf`, which is the only
  place an `EmailTemplate` meets a `DeedTemplate`. None has a `default`, so all five refuse to compile until
  somebody says what the new template's rule is. What fails silently is a missing `notify.templates.<key>` in
  `application.yaml` or a missing env var in the chart — the template then reads unprovisioned and never sends;
  `EmailTemplateTest.Configuration` now holds the enum's keys against `application.yaml`'s text so the first of
  those fails a test. A template that carries a document has a third such setting, `notify.document-retention-weeks`:
  `isProvisioned` asks three questions (template id, reply-to if `invitesReply`, retention if it carries a
  document), and a retention that is unset or outside the 1–78 weeks Notify accepts reads as unconfigured, so the
  drain refuses the send. The upper bound is in `NotifyProperties.retention` and not left to Notify because the
  client checks only the 2 MB size locally: a chart value of 104 would read as provisioned, the case's deed would be
  generated and stored, and only then would the drain take a 400 it classifies `PERMANENT`. A
  template is either **referral-driven or case-driven** and `EmailCause` says which — a case-driven one stores a
  null `referralId` and sits outside V15's partial index, so its only database protection is V20's key, and only
  if its group is one `exclusivePerCase()` names; otherwise the request id is all there is. A required `EmailSlot`
  carries the refusal sentence itself (`requiredBecause`) rather than a boolean
  plus a message, and `OpenAPIWriteBodies` renders that same sentence, so the document and the refusal cannot
  drift. Slot defaults come from `EmailSlots.defaults` and never from `parse` of an empty payload: a slot that is
  required and pre-populated from nothing would otherwise make `GET /caseworker/cases/{id}/emails` answer 400 for
  every case the template exists for.
- **A document reaches an applicant as a Notify download link, resolved at drain time and never stored.**
  `DocumentUploads` swaps the supporting-document ids the caseworker's send froze for `prepareUpload` tokens in a
  *copy* of the personalisation: a token expires and would be at rest in the database, so the row is read and
  never rewritten. The ids it resolves come from `EmailTemplate.documentKeys()` rather than from the map — an
  arbitrary personalisation value is not a document id — and they are read only through the case-scoped
  `DocumentService.downloadDocument`, because the value came out of a stored `jsonb` column and would otherwise be
  a way to make one applicant's email carry another's deed. `confirm_email_before_download` is always true. **No
  failure here is `PERMANENT`** except an unconfigured retention, because `PERMANENT` is the one verdict whose row
  can be replaced and a replaced forms email is a second deed for one change of name. The rest divides on whether
  another attempt could answer differently, and `TECHNICAL` is **not** the retryable one — only `TEMPORARY` is. A
  document that could not be *read* is therefore `TEMPORARY`: `TECHNICAL` would fail the row on its first attempt,
  and a terminally failed row still occupies V15's and V20's indexes, so `resolveRefusedInsert` answers the
  caseworker's re-send with an idempotent 200 on a dead row and one unmounted volume costs a case its deed for ever.
  Retrying is safe because every read happens *before* `sendEmail`, and an exhausted retry lands on `Failed` with a
  `TEMPORARY` verdict, which those indexes count exactly as they counted a `TECHNICAL` one. What stays `TECHNICAL`
  is what no attempt fixes: a personalisation with no id for a key its template declares, a document over 2 MB, a
  client refusing one for an unenumerated reason.
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
./gradlew cftlibTest                       # the in-process CCD stack, end to end — local only, not in build
```

`build.gradle` pins a **Java 21** toolchain; an older JDK fails before compiling. `-Werror` is on.

On this machine, export `TESTCONTAINERS_RYUK_DISABLED=true` — Ryuk listens on IPv6 inside its
container and this Docker configuration does not forward that. `ContainerDatabase` already reaches
containers over the Docker bridge network in preference to a published port, for the same reason.
`failFast` is set on `integration` but deliberately **not** on `test`, so a unit run reports every
failure rather than only the first.
