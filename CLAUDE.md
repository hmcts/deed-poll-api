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
domain/auth                       CurrentUser, IdamClaims, Role — transport-independent
notify/                           the GOV.UK Notify outbox: compose, enqueue, drain — NOT a port
auth/                             IDAM resource-server wiring (transport)
entity/, repository/              JPA over a Flyway-owned schema
health/                          readiness that names the missing tables
storage/                         LocalFilesystemDocumentStore — the seam CDAM replaces
src/test/resources/reference/    VENDORED specification: TypeScript source and Dataverse exports
```

## Rules that break things silently if ignored

- **Never edit `src/test/resources/reference/`.** It is a verbatim vendored copy of the
  specification, and the conformance tests read it as ground truth. If one of those files looks
  wrong, stop and report it — do not "fix" it.
- **Never edit a committed Flyway migration.** Add `V{n+1}__….sql`. Editing one breaks every
  environment that has already run it.
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
