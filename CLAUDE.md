# deed-poll-api

Repo-level guidance. The workspace-level file at `../CLAUDE.md` covers navigating the CFT workspace;
this one covers the conventions of *this* clone, which are mostly non-obvious and several of which
break silently if ignored.

## What this service is

The Spring Boot 3 backend for deed poll case management, on Java 21 with Gradle. It is a **port** of
the TypeScript domain layer in the sibling repo `hmcts/deed-poll-admin` (`libs/deed-poll`), which is
the specification. It replaces that repo's `apps/api`.

Plan 02 built a **minimal decentralised CCD foundation** (2026-09-27): one CCD case per deed-poll record, linked
by the record's UUID, created through data-store's public API by one linking operation. Plan 02b (2026-09-27)
calls it from a sweep on the case list request, **switched off by default**, and gives admins two repair moves out of
`NeedsRepair` under `/caseworker/ccd-attempts`. The relational record stays authoritative; CCD holds a projection of
it. It is proven against WireMock and a local cftlib stack on placeholder identifiers only — **nothing is
onboarded**, and `docs/CCD-SMOKE.md` is the AAT procedure that waits on it. Plan 03a adds CDAM uploads behind
`deedpoll.documents.store` (the filesystem stays the default), attached to the case by its document event.
Plan 03b adds durable deed preparation, service-authorised correspondence reads, per-row notification claims,
and owned staging with bounded scheduled expiry. The citizen adapter is absent; existing Local rows still
require 03c. Local proof is not onboarding or rollout approval. See
`docs/RESUME.md` and `docs/plans/`.

## Layout

```
api/caseworker/{controller,dto}   the caseworker HTTP surface — 34 operations across 21 paths, plus the REPAIR_CCD-protected
                                  /caseworker/ccd-attempts (3 operations, counted apart as CCD_ATTEMPT_PATHS)
                                  and /caseworker/gazette-notices (1 history GET, counted apart)
                                  and /caseworker/gazette-settings (Delivery Manager-only GET/PUT)
                                  plus 5 Gazette preview/request/status/member operations (Delivery Manager-only)
api/judicial/{controller,dto}     the judicial surface — 5 operations, keyed on the referral, never on the case
api/citizen/{controller,dto}      deliberately EMPTY, reserved for deed-poll-web
api/caller/{controller,dto}       GET /me, the caller's own areas and capabilities — outside both staff areas,
                                  because a caller with no deed-poll role must be told so rather than refused
domain/{deedpoll,notes,witnesses,documents}   the ported specification layer
domain/savedqueries               saved and shared case-list queries — NOT a port, no reference to compare
domain/referrals                  referrals to the judiciary and the applicant's confirmation — NOT a port
domain/deeds                      fills the court's two Word deed templates from a case — NOT a port
domain/auth                       CurrentUser, IdamClaims, Role — transport-independent
notify/                           the GOV.UK Notify outbox: compose, enqueue, drain — NOT a port
notify/DocumentUploads            turns the document ids frozen on an outbox row into Notify uploads at drain
                                  time — a time-limited download link, never an attachment; the row is read and
                                  never rewritten, because a token expires and would be at rest in the database
auth/                             IDAM resource-server wiring (transport), plus the RAS client behind deedpoll.roles.am
entity/, repository/              JPA over a Flyway-owned schema
ccd/                             the decentralised CCD case type, its persistence handler and view, the one
                                  linking operation with its attempt ledger, the case-list sweep that calls it, and
                                  the admin repairs (CcdAttemptRepairs) — NOT a port
health/                          readiness that names the missing tables
storage/                         filesystem/CDAM stores, remote progress, onboarding guard and state metrics
src/main/resources/deed-templates/  VENDORED Word templates: LOC020 and LOC022, plus their provenance
src/test/resources/reference/    VENDORED specification: TypeScript source and Dataverse exports
src/test/resources/ccd-definition/  the generated CCD definition, pinned — DeedPollDefinitionTest compares every file
src/cftlib/, src/cftlibTest/     the in-process CCD/CDAM stack and linking/document journeys; local only
```

## Staff permissions

`caseworker-deedpoll-team-leader` grants OVERRIDE_PRIVACY, DELETE_NOTE, DELETE_WITNESS,
MANAGE_SHARED_QUERIES and REPAIR_CCD. `caseworker-deedpoll-delivery-manager` inherits all five
and alone grants ACCESS_GAZETTE. The deprecated `caseworker-deedpoll-admin` temporarily grants
only the first five. Resolve capabilities from verified roles; the retained privacy boolean is derived.
Every Gazette operation and the named `readyForPublication` worklist/count (including saved-query
links) requires ACCESS_GAZETTE, with 403 before reads or side effects. Ordinary field conditions
and case visibility remain unchanged. Neither new role grants judicial or system authority.
Saved-query private visibility and ownership still use the subject. Background Gazette completion
uses durable admitted authority; never rewrite historical attribution. See the
[permission plan](docs/plans/completed/20261009-team-leader-delivery-manager.md) for migration and rollout.

### `GET /me`, the one route outside a staff area

`SecurityConfiguration.ANY_AUTHENTICATED_PATHS` is exactly `{"/me"}`, matched `.authenticated()` ahead of
`anyRequest()`, and `StaffAccessRulesTest` asserts both that set and that every other route sits in a `StaffArea`.
Adding a path there is a reviewed change, not a way round the H1 check. `/me` is not public: no token is 401.
- **The flags reflect roles, never the StaffGate mode.** `staff` and `judicial` are `StaffArea.admits` over the
  authenticated authorities, so in `report` mode a roleless caller still reaches `/caseworker/**` while `/me` says
  `staff: false`. That is deliberate: the admin app is right for the day `enforce` arrives.
- **No identity.** No subject, name, raw role names, AM assignments or role source, and `Cache-Control: no-store`.
  Admin has identity from IDAM; a second route publishing the subject is the leak the Gazette review found.
- Capabilities are `RoleCapabilities.forRoles` in `Capability` declaration order, so a new `Capability` reaches
  admin with no change here, and `CallerAccessOpenApiTest` holds the published enum to `Capability.values()`.

### AM as a second role source (off by default)

`deedpoll.roles.am.enabled` (`ROLE_ASSIGNMENT_ENABLED`, default false) makes `IdamAuthenticationConverter`
union the caller's AM organisational role assignments into the IDAM-derived roles. It adds no new `Role` or
`Capability`, and subject and name still come from IDAM. The mapping is pure, in `domain/auth/AmRoles`:

| RAS assignment | Role |
|---|---|
| `ctsc` | `CASEWORKER` |
| `ctsc-team-leader` | `TEAM_LEADER` |
| `ctsc-team-leader` whose `authorisations` holds `ROLE_ASSIGNMENT_GAZETTE_SKILL` | also `DELIVERY_MANAGER` |
| `judge`, `fee-paid-judge` | `JUDICIAL` |

Every other name maps to nothing. An assignment counts only if it is `ORGANISATION`, not `SPECIFIC`, inside
its begin/end window, and its `attributes.jurisdiction` equals `ROLE_ASSIGNMENT_JURISDICTION` (default
`DEEDPOLL`). **Never drop the jurisdiction filter**: `ctsc` and `ctsc-team-leader` are shared across services,
and without it a Probate team leader becomes a deed poll Team leader. With the skill empty (the default), AM
never yields a Delivery Manager.

- **No cache**, for `IdamUserInfoClient`'s reason: a cached role outlives its removal. One RAS call per
  authenticated request (`GET /am/role-assignments/actors/{subject}`, the caller's own token plus S2S).
- **Failure adds nothing.** `AmRoleSource` turns RAS unreachable, non-2xx or an unreadable body into a WARN and
  no AM roles. The WARN gives a 12-hex-digit hash of the subject and the status, never the token, the body or
  the cause (its message carries the URL, which carries the subject). A 404 means no assignments, with no WARN.
- **The CCD system user never calls RAS**, and `/ccd-persistence/**` (`CcdCallerAuthenticationConverter`) stays
  IDAM-only. CCD document access for AM-only users needs a later definition change.

Activation, none of it done in this repo: flux sets `ROLE_ASSIGNMENT_URL` and mounts the S2S secret; RAS adds
`deed_poll_api` to `ROLE_ASSIGNMENT_S2S_AUTHORISED_SERVICES`; `ROLE_ASSIGNMENT_GAZETTE_SKILL` is set once RD
allocates the service code; CRD/JRD profiles exist; only then `ROLE_ASSIGNMENT_ENABLED=true`. See
`docs/plans/20261014-am-role-source.md`.

## Rules that break things silently if ignored

- **The CDAM hash token is transient.** Keep it only while `Uploaded`/`AttachUncertain`; never log, project or
  publish it. An uncertain upload gets exactly one fresh upload within the same request (two sends total), then
  stays uncertain. There is no background retry or operator resume endpoint in 03a. Never send `permanent=true`:
  caseworker cleanup is a soft delete, and only the dm-store team can arrange restoration.
- **CDAM ships off by default.** `CdamOnboarding` refuses unconfirmed CCD identifiers and local/default CDAM
  endpoints when CDAM is enabled; only `test`/`cftlib` against loopback permit placeholders. These are local test
  profiles, never deployment profiles. The configuration check cannot prove external onboarding or staff access.
  Generated deeds and Notify retrieval now work locally on CDAM; existing-file migration remains 03c.
  Production cutover still requires onboarding, migration and the operator smoke. Read `docs/CDAM-RECOVERY.md`
  before acting on an uncertain document.
- **Prepared bytes are recovery evidence, not disposable drafts.** V26/V27 reserve one deed identity, checksum
  and input fingerprint per case before remote work. Retain its payload until Attached read-back verifies it;
  a failed final enqueue never authorises regeneration. The shared `DOCUMENT_STAGING_MAX_TOTAL_BYTES` cap
  also applies when owned staging is disabled. Zero refuses new preparations.
- **Owned staging is a trusted domain seam, not a citizen upload API.** V29 binds owner kind/ID, authenticated
  subject, operation UUID and original expiry. Only Staged rows expire; Promoting and generated preparations
  retain their bytes. Admission, promotion and cleanup use short commits and preserve ambiguous outcomes.
  All production quotas, durations and cleanup budgets need release agreement. No retry extends expiry.
  Before disabling staging, quiesce its adapters/promoters and account for the stopped cleanup scheduler.
  Recovery, monitoring queries, rollout boundaries and 03c inputs are in `docs/CDAM-RECOVERY.md`.
- **cftlib stubs only dm-store.** Its fixture is compiled from `src/dmStoreStub/java` into `test` and `cftlibTest`;
  never add the whole `test.output` to cftlib's classpath, because its package scan would run the entire unit suite
  inside the CFT stack. Update the pinned spec, response provenance and stub together after a verified change.

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
- **Never remove the `CoreCaseDataClientAutoConfiguration` exclusion** in `application.yaml`. The client's own
  auto-configuration adds a health indicator that calls data-store, and the unauthenticated `/health` aggregates
  every indicator, so a data-store outage would read as this service being down. `CcdConfiguration` registers
  `CoreCaseDataApi` instead.
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
- **The linking sweep is off by default, and turning it on before onboarding fails every record.**
  `CCD_LINKING_SWEEP_ENABLED` defaults to `false`, and while it is false the case list calls nothing in `ccd/`.
  Every identifier is still `DEED_POLL_PLACEHOLDER`, so in an environment that is not onboarded every attempt is
  refused before submission. Each list request then spends a try on the oldest unlinked records, and within
  `CCD_LINKING_MAX_ATTEMPTS` tries each one is `NeedsRepair`. Nothing is created, so an admin can retry them all
  from scratch, but only one at a time. Switching it on is the last step of `docs/CCD-SMOKE.md`'s preconditions,
  not a default to flip in yaml.
- **`submissionMayHaveReached` is committed before `submitCaseCreation`, and is never reset.** It is the only thing
  that makes `POST /caseworker/ccd-attempts/{id}/retry` safe. `CcdCreationAttempts.recordSubmission` writes it, and
  only ever writes true, on the worker's own `Claimed` row after `startCase`. If that write matches nothing, the
  claim was lost and nothing is submitted. A submission path that calls CCD before the write, or a statement that
  sets the flag back to false (including the retry itself), lets an admin re-send something that may already have
  created a case. The retry's SQL repeats the `= false` condition that `CcdLinkingRule.mayRetryFromScratch` states,
  so a stale read cannot slip past it. V23 backfilled existing rows to true for the same reason. Do not reset the
  flag to unstick an attempt.
- **`provenNothingCreated` (V24) is the flag's one exception, and it has exactly one writer of true.** A refused
  submission sets the flag too, so an attempt whose tries ran out on refusals would otherwise be stuck.
  `CcdCreationAttempts.exhaust` moves such a claim to `NeedsRepair` and sets the fact. It is called only from
  `CcdLinkingService.nothingCreated`, when the tries are used up. `retryFromScratch` accepts either condition, in
  `mayRetryFromScratch` and in its SQL. `reconcileAgain` keeps the fact, because reconciliation never submits.
  `retryFromScratch` clears it: the fresh run of claims must earn its own. It is safe only because `release` and
  that retry are the only ways back to `Pending`. A new path into `NeedsRepair` must not set it, and a new way back
  to `Pending` would make it unsound.
- **The case list request links records, so a new caller of the sweep must be outside any transaction.**
  `CaseworkerReadController.cases` calls `CcdLinkingSweep.sweep()` after the response is computed and after
  `outbox.drain()`. The sweep commits each claim before CCD is called, and data-store's callback has to read that
  claim and lock the record. Inside a caller's transaction the claim is not yet committed, so the callback
  cannot see it and binds nothing. `sweep()` throws `IllegalStateException` when a transaction is active, rather
  than logging it away. The sweep never throws anything else into the list: CCD failures are logged in fixed prose,
  by exception class name only. The repair endpoints are non-transactional for the same reason.
- **A new CCD field is declared in three places, and the build names only one of them.** `DeedPollCaseData` and
  its grants in `DeedPollCaseType`, the projection `DeedPollCaseView` composes from (`CcdCaseProjection` and
  `CcdDocumentProjection`, read by `DeedPollRepository` and `DeedPollSupportingDocumentRepository`), and the pinned
  JSON under `src/test/resources/ccd-definition/`. `DeedPollDefinitionTest` fails on the definition; a field left out
  of the view reads as null through CCD, and nothing fails unless `DeedPollCaseViewTest` is taught it.
  Keep the definition minimal: `DEED_POLL_SYSTEM` holds CR on `linkDeedPoll` and its
  two fields, CRU on the case type, the state and `updateSupportingDocuments`, and D on `supportingDocuments` alone,
  through `SupportingDocumentsAccess`, because data-store checks D for a removed collection item and a D on the event
  would be carried onto the state. `DEED_POLL_READER` holds R only on the case type, Registered state and
  supportingDocuments, mapped from the five staff roles. It holds no events, history or identifier fields.
  The test refuses growth that was not deliberate.
- **The case's documents are its `Attached` rows, and the document event's handler is what attaches them.**
  `SupportingDocumentsHandler` moves a row to `Attached` inside the event's transaction (`attachedOnTheCase`, only
  when the payload URL's id equals the row's `cdamDocumentId`), so the SDK's snapshot shows it and a lost answer is
  settled by reading the row: `CdamDocumentUploads` reads `stateOf` after **every** failure before calling anything
  `Failed` or uncertain. The view never projects the hash token and never projects a non-`Attached` row. A delete sends
  the event again with the rest, after the commit and ahead of the soft delete, best effort.
- **No live calls to CCD, CDAM, IDAM or payments in any test.** WireMock only (`StubIdam`, `StubS2s`); there are
  no credentials. The one exception is `cftlibTest`, which runs CCD in-process on placeholders and is local only.
- **No secrets.** Everything by environment variable, with a non-working default.
- **The outbox must run outside any caller transaction.** `NotificationDeliveryTransactions` commits claims,
  send-start and outcomes separately; document reads and Notify calls run outside them. Owner UUID plus attempt
  generation and an unexpired lease fence send-start and settlement. Expired pre-send claims consume the eight
  attempts; a row with `sendStartedAt` is never automatically delivered again unless a known temporary refusal
  was committed. Unknown sends stop as `Failed`/`TECHNICAL` for investigation (ADR-0007). Outcome plus its one
  failure note commit atomically; a later failure cannot undo an earlier send. `EmailNotes` keeps the note bounded
  so it cannot prevent settlement. Do not reset evidence to unstick a row. Old drain binaries must be fully stopped
  before V28/new workers, and cannot resume on this ledger without reviewed reconciliation. See `CDAM-CALLERS.md`.
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
  is the only place any conflict clause executes, and explicitly Docker-free local runs do not run it.
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
- **A read-only `@Transactional` method that throws still poisons a participating caller transaction.**
  `DocumentService.downloadDocument` uses a short read template and throws refusals after it, keeping remote
  reads outside database transactions. The drain now rejects an outer transaction and commits only the local
  claim/outcome steps. Do not wrap a document read or the drain in a new outer transaction.

## Conventions worth knowing before writing code

- **Column names are mixed case** (`newForename`), because the reference schema's are. That is why
  `spring.jpa.properties.hibernate.globally_quoted_identifiers: true` and
  `PhysicalNamingStrategyStandardImpl` are load-bearing: Boot's default strategy would rewrite them
  to `new_forename` and stop finding them. Enum columns also need
  `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`, or Hibernate sends the value as an integer ordinal.
- **Gazette audit tables (V31) use explicit snake_case table and column mappings**, unlike the ported
  mixed-case schema. Their restrictive foreign keys preserve membership; case deletion returns a conflict.
- **Gazette request evidence (V34) is separate from successful V31 history.** `GazetteRequestLedger`
  owns short committed transactions and cannot join a caller transaction. Preparing/Sending block case
  PATCH; every request membership prevents deletion. NotSent releases the reservation; Uncertain/Sent
  retain it. Repeated keys grant no delivery claim or generation inputs. Production admission verifies
  the preview token under the case locks and captures immutable mapped spreadsheet values. `GazetteDelivery`
  prepares every file and commits Sending before the single Notify call. A lost commit acknowledgement grants
  no send authority; uncertain remote/local outcomes never allow resend. `GazetteNotify` reuses the SDK's local
  encoding/authentication/parser through dedicated non-replaying, bounded HTTP, because the SDK transport has
  no public timeout/retry controls. Do not replace it with `NotificationClient.sendEmail` without equivalent
  evidence. Recovery runs every minute, at most 25 rows, independently of `GAZETTE_ENABLED` (default false).
  Never expose ledger projections, claims, file values or provider responses as HTTP DTOs. See
  `docs/GAZETTE-RECOVERY.md` before investigating an uncertain request.
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
  `ConfirmationWait`), so it can be tested independently of persistence. **A new write path that skips the recompute
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
  internal `CorrespondenceDocumentReader`, because the value came out of a stored `jsonb` column and would otherwise be
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
  `SavedCaseResponse`, and the shared `CaseResponseFields` test fixture. `DeedPollColumnsTest` checks the reverse: every
  stored column has a reader or a written-down exemption, which is where a column that must never be published
  (`ccdCaseReference`) goes.
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
./gradlew generateCCDConfig                # the CCD definition, under SPRING_PROFILES_ACTIVE=config-gen
./gradlew cftlibTest                       # the in-process CCD stack, end to end — local only, not in build
```

`build.gradle` pins a **Java 21** toolchain; an older JDK fails before compiling. `-Werror` is on.

On this machine, export `TESTCONTAINERS_RYUK_DISABLED=true` — Ryuk listens on IPv6 inside its
container and this Docker configuration does not forward that. `ContainerDatabase` already reaches
containers over the Docker bridge network in preference to a published port, for the same reason.
`failFast` is set on `integration` but deliberately **not** on `test`, so a unit run reports every
failure rather than only the first.
