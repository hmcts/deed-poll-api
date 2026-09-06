# Plan: deed-poll-api foundation — schema, domain, caseworker API

## Overview

Plan 1 of 3. This one builds the Spring Boot service, its schema, its domain and the caseworker REST
surface. **It contains no CCD and no CDAM** — those are plans 02 and 03, deliberately separated because
they depend on external onboarding that has not happened.

The service replaces `apps/api` in the sibling repo `deed-poll-admin`, which is a TypeScript Express app
whose domain layer is the specification for this port. It will later also serve `deed-poll-web`, the
citizen-facing service, which is why the API has two endpoint namespaces from the start.

### Source material — read these, do not re-derive them

- `../deed-poll-admin/.business_context/DEED_POLL_API_PLAN.md` — every architectural decision and why.
  Read it for context; do not relitigate it. This plan is its executable form.
- `../deed-poll-admin/libs/deed-poll/src/` — the domain being ported. **This is the specification.**
- `../deed-poll-admin/libs/postgres-prisma/prisma/schema/deed-poll.prisma` — the database shape.
- `../apps/pcs/pcs-api/` — the reference Spring Boot implementation in this estate. Copy its shape.

## Validation Commands

- `./gradlew build`

> Every task, without exception. `build` runs compilation, checkstyle and unit tests via the
> `uk.gov.hmcts.java` plugin. **A task is not complete with a red build, even if its own new test
> passes** — a sister repo once shipped a handover claiming green tests on a tree with six type errors.
> Each task below also carries its own narrower `Verify` command; run both.

## Guardrails — read these every session

- **`../deed-poll-admin/` is read-only.** It is the specification. Never edit, never commit into it.
- **Never edit `src/test/resources/reference/`.** Those are vendored copies of the specification. If one
  looks wrong, stop and report — do not "fix" it.
- **One Flyway migration is never edited once committed.** Add `V{n+1}__…sql` instead. Editing a
  committed migration breaks every environment that has run it.
- **Never invent the CCD case type or jurisdiction ID.** They are unconfirmed. Use the single constant in
  `application.yaml` (`deedpoll.ccd.case-type-id: DEED_POLL_PLACEHOLDER`) and nothing else. If a task
  seems to need the real value, it belongs in plan 02.
- **No live calls to CCD, CDAM, IDAM or payments in any test.** WireMock only. There are no credentials
  and there will not be during this plan.
- **No secrets in the repo.** Everything by environment variable, with a non-working default.
- **Do not touch `apps/api`, `apps/web` or `libs/` in `deed-poll-admin`.** Retiring `apps/api` and cutting
  `apps/web` over to HTTP are later work, in a different plan and a different repo.
- **If a validation command fails for a reason this task did not cause, stop and report.** Do not widen
  scope to make it green.
- **One commit per task.** Never amend or force-push a previous task's commit.

---

### Task 0: Populate the repo from the template

This repo was **not** created from `hmcts/spring-boot-template`, and GitHub cannot apply a template to a
repo that already exists, so the contents have to be copied in by hand. Its only commit adds this plan.

- [x] **Stop if this has already run.** If `build.gradle` exists at the repo root, task 0 is done; report
      and move on rather than copying over it.
- [x] Shallow-clone the template somewhere outside this repo:
      `git clone --depth 1 https://github.com/hmcts/spring-boot-template /tmp/sbt`
- [x] Copy its contents in, excluding its git history:
      `rsync -a --exclude '.git' /tmp/sbt/ .`
      The template has no `docs/` directory, so this plan is not at risk — but verify it survived before
      committing.
- [x] `chmod +x gradlew`, then delete `/tmp/sbt`.
- [x] Commit the scaffold. Do not amend the existing commit that added this plan.

- [x] Verify: `test -f build.gradle && test -f docs/plans/deed-poll-api-01-foundation.md && ./gradlew build`
      — the unmodified template ships a working application and passing tests, so a red build here means the
      copy went wrong, not that the template is broken.
- [x] Mark completed

> **One thing for Steve, not for the agent.** `deed-poll-api`'s default branch is **`main`**, while both
> `spring-boot-template` and `deed-poll-admin` use **`master`**. HMCTS Jenkins conventions assume `master`
> in places — `pcs-api`'s Jenkinsfile has a `syncBranchesWithMaster` step, for instance. Worth confirming
> which `cnp-jenkins-config` expects while doing the Jenkins onboarding, because renaming the default
> branch is much cheaper now than after the pipeline exists. **Do not let the agent decide this.**

### Task 1: Rename off the template

- [x] Set `group = 'uk.gov.hmcts.reform'`, base package `uk.gov.hmcts.reform.deedpoll`, artifact and
      application name `deed-poll-api`. The template ships `uk.gov.hmcts.reform.demo` — rename the package
      directories to match and leave nothing behind.
- [x] Update `catalog-info.yaml`: `name: deed-poll-api`, `owner: dcd_deed-poll`, `lifecycle: experimental`.
- [x] Keep the template's `Jenkinsfile_template` and rename to `Jenkinsfile_CNP`. Leave its contents alone.
- [x] Add the plugin versions this service will need, pinned exactly as `pcs-api` pins them, but **do not
      configure them yet**: `id 'hmcts.ccd.sdk' version '6.36.0'` and
      `id 'com.github.hmcts.rse-cft-lib' version '0.19.2360'`. Commented out is fine; plan 02 enables them.

- [x] Verify: `./gradlew build` and `grep -rn "uk.gov.hmcts.reform.demo" src/ || echo clean`
- [x] Mark completed

---

### Task 2: Vendor the specification

The domain and schema are ported from TypeScript. Those sources are the spec, and a session runs in
isolation, so they must be inside this repo.

- [x] Copy `../deed-poll-admin/libs/postgres-prisma/prisma/schema/deed-poll.prisma` to
      `src/test/resources/reference/deed-poll.prisma`.
- [x] Copy the whole of `../deed-poll-admin/libs/deed-poll/src/` to `src/test/resources/reference/ts/`.
- [x] Add `src/test/resources/reference/README.md` stating: these are verbatim copies used as the port's
      specification and its conformance oracle; never edit them; refresh only by re-copying.

- [x] Verify: `test -f src/test/resources/reference/deed-poll.prisma && test -d src/test/resources/reference/ts && ./gradlew build`
- [x] Mark completed

> **Also vendored, beyond the checkboxes above.** `ts/conformance/artifacts.ts` reads the Dataverse
> exports from `.business_context/dataverse_tables/`, which is outside `libs/deed-poll/src/`. Task 11
> says "against the vendored Dataverse exports" but no task vendors them, so task 2 copied them to
> `src/test/resources/reference/dataverse_tables/` (124K, 20 files) — otherwise task 11 is blocked in an
> isolated session. Same rules apply: verbatim, never edited.

---

### Task 3: Postgres and the Flyway baseline: enums and `DeedPoll`

`deed-poll.prisma` defines 6 models and 11 enums in the `public` schema. This task does the enums and the
parent table only; children follow in task 4, so that neither task is too large to verify.

- [x] Add `docker-compose.yml` with Postgres 16 for local development, and Testcontainers Postgres for
      tests.
- [x] Write `src/main/resources/db/migration/V1__enums.sql` — all 11 enums, values **in the order the
      Prisma file declares them**, because the integer ordinals are load-bearing and are documented in its
      comments.
- [x] Write `V2__deed_poll.sql` for `deedpoll_deedpoll`. Reproduce exactly: every `@map` column name,
      `numeric(12,2)` for `refundAmount`, `integer[]` for the three multiselects, and
      **`signaturerecieveddate` with the source's misspelling** — `deed-poll.prisma` documents it as
      deliberate.

- [x] Verify: `./gradlew test --tests "*SchemaConformance*"` — a new test that parses
      `src/test/resources/reference/deed-poll.prisma`, runs Flyway against Testcontainers, and asserts every
      model field present in the reference has a matching column. Forward coverage only, matching the
      TypeScript harness's own stated limitation.
- [x] Mark completed

> **What task 3 did beyond the checkboxes, and one thing for Steve.**
>
> - The migrations are wired into the running application, not just into the test: `application.yaml`
>   now has the datasource and `spring.flyway`. That made the template's `OpenAPIPublisherTest` need a
>   database to boot, so it now extends a new `AbstractPostgresContainerIT`. Task 5 reuses that.
> - `SchemaConformanceTest` asserts more than the checkbox asks: column **type**, `varchar` length,
>   `numeric` precision/scale, nullability, and every enum's members **in declaration order**. Both
>   halves were verified by mutation — a `numeric(10,2)` and a reordered enum member each fail it.
>   Nullability is not asserted for the three `integer[]` columns: a required Prisma list produces a
>   nullable column, so requiring `NOT NULL` would diverge from the schema being ported.
> - **Steve: this machine's Docker cannot run Testcontainers unaided.** Two separate faults, both from
>   the build running inside a container sharing the host daemon. (1) Daemon-allocated ephemeral
>   published ports are never forwarded — `docker run -p 5432` gives an unreachable port while
>   `-p 59200:5432` and plain `-P` work. `ContainerDatabase` works around this by reaching containers
>   over the Docker bridge network instead, which is the right thing for this topology anyway. (2) Ryuk
>   listens on IPv6 only and its published port is unreachable, so **`./gradlew build` needs
>   `TESTCONTAINERS_RYUK_DISABLED=true` on this machine** — every build in this plan was validated with
>   it exported. It is deliberately not set in the repo, so CI keeps the reaper; the README records it.
>   Worth fixing in Docker Desktop rather than living with, since tasks 4–16 all need a database.

---

### Task 4: Flyway baseline: the child tables

- [x] `V3__children.sql` for `deedpoll_notes`, `deedpoll_witness`, `deedpoll_supportingdocumenttype`,
      `deedpoll_supportingdocument` and `deedpoll_referencecounter`, with the foreign keys and the two
      indexes the reference declares. `DeedPollSupportingDocument.deedPollId` is `onDelete: Cascade` — the
      cascade is intentional and must be reproduced.

- [x] Verify: `./gradlew test --tests "*SchemaConformance*"` — now green for all 6 models.
- [x] Mark completed

> **What task 4 did beyond the checkboxes.**
>
> - **The reference declares three `@@index([deedPollId])`, not two** — on `DeedPollNote`,
>   `DeedPollWitness` and `DeedPollSupportingDocument`. All three are created, since the reference is the
>   specification. The checkbox's "two" appears to be a miscount rather than a decision; nothing was
>   dropped to match it.
> - **Four foreign keys, with the reference's delete rules.** The three `deedPollId` keys are
>   `ON DELETE CASCADE`. `documentTypeId` declares no `onDelete`, so it gets Prisma's default for a
>   required relation, `ON DELETE RESTRICT` — a document type still in use cannot be deleted. Every key
>   is also `ON UPDATE CASCADE`, Prisma's default, so a future `prisma migrate diff` against this schema
>   stays empty.
> - **`SchemaConformanceTest` now asserts indexes and foreign keys too**, and covers every model the
>   reference declares rather than a hardcoded list — so task 5 onwards cannot forget to widen it.
>   `PrismaSchema` gained parsing for `@@index` and `@relation`, including resolving an omitted
>   `onDelete` to Prisma's default, so the delete rules are asserted **from the reference** rather than
>   from a hand-written expectation. Both new assertions were verified by mutation: turning the
>   `documentTypeId` key into `CASCADE` and deleting the witness index each fail the test.
> - `build.gradle` sets `failFast = true`, so only the first failure is reported per run. Worth knowing
>   when a mutation check appears not to fire — it may simply not have been reached.

---

### Task 5: JPA entities

- [x] Entities under `uk.gov.hmcts.reform.deedpoll.entity` for all 6 models, and Java enums for all 11.
- [x] Map, do not redesign: `@Column(name = …)` for every mapped name, `@Enumerated(EnumType.STRING)`,
      `BigDecimal` for the numeric, arrays for the `integer[]` columns.

- [x] Verify: `./gradlew test --tests "*EntityMapping*"` — a Testcontainers test booting the context with
      `spring.jpa.hibernate.ddl-auto=validate` against the Flyway schema. Hibernate then fails the test on any
      mismatch, which is the strongest automatic check available here.
- [x] Mark completed

> **What task 5 did beyond the checkboxes, and one thing worth knowing.**
>
> - **`ddl-auto=validate` is in `application.yaml`, not only in the test**, so every environment
>   re-runs the assertion on start-up and the application refuses to boot against a schema its
>   entities disagree with. Two other settings are load-bearing rather than taste:
>   `globally_quoted_identifiers: true` and `PhysicalNamingStrategyStandardImpl`. The ported columns
>   are mixed case, and Spring Boot's default naming strategy rewrites `newForename` to
>   `new_forename` — including names given explicitly in `@Column` — while an unquoted mixed-case
>   identifier folds to lower case in Postgres. Both would break every read and write.
> - **`@Enumerated(EnumType.STRING)` alone is not enough** against native Postgres enum columns:
>   Hibernate then sends the value as `varchar` and Postgres refuses the implicit cast on write.
>   Each enum field also carries `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`.
> - **`validate` compares column names case-INSENSITIVELY** — it accepts `"newforename"` for a
>   column actually called `"newForename"`, and the quoted statement then fails at runtime. Since
>   every column in this schema is mixed case, that blind spot would have been wide, so
>   `EntityMappingTest` also writes and re-reads a case and its children. Mutation-checked in both
>   directions: a genuinely renamed column and a wrong Java type each fail context start-up, and a
>   case-only rename fails the round-trip test rather than validation.
> - **Two dependencies added.** `spring-boot-starter-data-jpa` replaces the separately-declared
>   `spring-boot-starter-jdbc` it contains, and Lombok (accessors only, version from the Boot BOM,
>   as `pcs-api` does it) — the case entity has over a hundred mapped columns and hand-written
>   accessors would bury the mapping.
> - **`AbstractPostgresContainerIT` moved from `integrationTest` to `test` as
>   `AbstractPostgresContainerTest`**, because task 5's verify runs in the `test` source set and
>   `integrationTest` has `test`'s output on its classpath but not the reverse. One base class, not
>   two copies to keep in step with `ContainerDatabase`. `OpenAPIPublisherTest` extends the moved
>   class.
> - **Relations are mapped, not just the foreign-key columns**: `@ManyToOne` to the parent and the
>   document type, with the reference's `notes`/`witnesses`/`documents` collections on the case.
>   No JPA cascade — the delete cascade stays in the database, where V3 put it — and the collections
>   are lazy. `EntityMappingTest` also asserts each of the 11 Java enums has the reference's members
>   in the reference's order, read from the vendored `.prisma` rather than from a hand-written list.
> - The three multiselect fields keep the reference's names (`iShall`, `iHaveSubscribed`,
>   `andAlsoMyOld`), which the checkstyle member-name pattern rejects for their second capital;
>   suppressed on the one class rather than renaming what every TypeScript caller and test says.

---

### Task 6: Reference-number allocation

- [x] Port `reference/ts/deed-poll/reference-number.ts` and its allocator. Format is
      `{prefix}{year}-{serial}`, prefix `KBDR` for type 5 (Deed of Relinquishment) else `KBDP`.
- [x] The serial comes from `deedpoll_referencecounter` incremented **inside a transaction with a row
      lock** (`SELECT … FOR UPDATE`). The reference file's comment explains this replaced a racy
      read-max-and-add-one; do not reintroduce that.

- [x] Verify: `./gradlew test --tests "*ReferenceNumber*"` — must include a concurrency test proving two
      parallel allocations never return the same serial.
- [x] Mark completed

> **What task 6 did beyond the checkboxes, and one decision worth reading.**
>
> - **The row lock is one statement, not a `SELECT … FOR UPDATE` followed by an `UPDATE`.**
>   `ReferenceCounterRepository.allocateNextSerial` is
>   `INSERT … VALUES (:year, 1) ON CONFLICT ("year") DO UPDATE SET "lastSerial" = … + 1 RETURNING
>   "lastSerial"`. That takes the same exclusive row lock the checkbox asks for — a second
>   transaction reaching it waits for the first to commit and then re-reads the committed row — and
>   it additionally makes safe the one case a read-then-lock cannot: the **first** allocation of a
>   year, where there is no row to lock yet and two concurrent callers would race to insert one.
>   It is also exactly what the reference does, a Prisma `upsert` with an `increment`.
> - **Both concurrency tests were mutation-checked.** Replacing the statement with the source flow's
>   racy read-then-add-one fails `parallel allocations never return the same serial` *and* `a second
>   allocation waits for the first transaction to commit`. The second test is the stronger one: it
>   asserts the lock rather than distinctness, by holding a transaction open after allocating and
>   proving the other caller cannot return until it commits.
> - **Allocation demands the caller's transaction** (`Propagation.MANDATORY`), which is the
>   reference's "must be called inside a transaction" made enforceable. The serial has to be
>   allocated under the same transaction as the row that will carry it, so a create that then fails
>   gives the serial back instead of leaving a gap in an external-facing, permanent sequence. There
>   is a test that calling outside a transaction is refused rather than silently burning a serial.
> - **`parse` and `reprefixForUpdate` return `Optional`, not null.** In TypeScript the compiler's
>   strict null checks force the caller to handle the null return; Java has no such check, so
>   `Optional` keeps the same property in the type rather than in the caller's memory. It is the only
>   deliberate deviation, and the ported tests assert `isEmpty()` where the reference asserts null.
> - **Two reference test blocks could not port.** TypeScript's `undefined` is not distinct from null
>   in Java, and a `LocalDateTime` cannot hold an invalid date the way `new Date("nonsense")` can,
>   so the invalid-date rejection has no equivalent. Both are noted at the tests that absorbed them.
>   Everything else ports one-for-one, and the allocating blocks run against the real database
>   instead of the reference's mocked Prisma client — a mock cannot hold a row lock. Two extra tests
>   assert behaviour the reference documents but, having mocked the counter, cannot check: that the
>   serial is shared across KBDP and KBDR within a year and separate between years.
> - **`errors.ts` had to be started, and no task in the plan owns it.** `reference-number.ts` throws
>   `InvalidInputError`, so `domain/InvalidInputError.java` and `domain/FieldError.java` exist now,
>   with the reference's names kept. Its other four error classes and `zodErrorToFieldErrors` are
>   **not** ported — each should arrive with the ported module that throws it, so the vocabulary stays
>   a record of what the service actually refuses. Tasks 7–15 will need them.
> - `ReferenceNumberServiceTest` is deliberately not `@Transactional` — a rollback-only test
>   transaction would make the lock unobservable — so it clears `deedpoll_referencecounter` around
>   each test instead. Worth knowing before adding a test to it that assumes rollback.

---

### Notes for tasks 7–11 — how to port the domain

Applies to each of the five tasks below. Read the TypeScript under
`src/test/resources/reference/ts/`, port the logic to `uk.gov.hmcts.reform.deedpoll.domain`, and port
**its tests too** — they are the specification, and a passing port with fewer tests is not a port. Zod
schemas become Bean Validation or explicit validators. Keep the nullable-versus-optional distinction the
reference documents: a PATCH clearing a value and a PATCH omitting it must stay different, and the
reference tests assert both directions.

### Task 7: Domain: deed-poll core

- [x] Port `reference/ts/deed-poll/`. The largest by far — roughly 267 test blocks — including the state
      cascade and the field rules.
- [x] Port its tests. The reference exports entity-specific names (`updateDeedPoll`, `getCaseFieldRules`)
      rather than `update`/`find`, because those collided across five modules once barrel-exported. Java
      packages remove that constraint, but keep the names: they are what every test and caller says.

- [x] Verify: `./gradlew test --tests "*DeedPoll*" --tests "*FieldRules*" --tests "*StateCascade*"`
- [x] Mark completed

> **What task 7 did beyond the checkboxes, and four decisions worth reading.**
>
> - **All 15 remaining modules of `reference/ts/deed-poll/` are ported, tests and all** —
>   `case-state`, `computed`, `date-parts`, `schemas`, `deed-poll-validation`, `form-presentation`,
>   `card-layout`, `form-fields`, `detail-view`, `edit-view`, `form-payload`, `form-validation`,
>   `list-filters`, `list-view`, `case-header`, `service`, plus `enums/dynamics-values`.
>   `reference-number` was task 6. The port adds **282 test methods** where the reference has 249
>   blocks for those modules; the surplus is parameterised cases counted individually plus a handful
>   of extra tests noted in each file. `./gradlew test` is 472 tests, all green.
> - **`FieldError` is used for all three of the reference's `{field, message}` types**
>   (`DateRuleError`, `ValidationError`, `FormError`). In TypeScript they are structurally identical,
>   which is why `service.ts` hands a `DateRuleError[]` straight to an `InvalidInputError` declaring
>   `FieldError[]`. Java has no structural typing, so three records would force a conversion the
>   reference does not have — and a conversion is where a field name gets lost.
> - **A patch is a keyed map (`DeedPollPatch`), not a class of nullable fields.** PATCH semantics need
>   the three-way absent / null / value distinction the reference gets free from JavaScript, and a
>   field of nullable Java types cannot express it. Only the reference schema's 68 field names are
>   writable, which is what keeps `referencenumber`, `displayReference` and the audit columns out of a
>   caller's reach — the reference's "strips unknown keys" made structural. `DeedPollFields` is the
>   name-to-accessor registry that makes it work, stated explicitly rather than by reflection so a
>   field becomes writable only by being listed.
> - **`DeedPollService` validates before it mutates the entity.** The reference builds
>   `merged = {...existing, ...data}` and hands it to Prisma; here the merge is a field map every rule
>   reads, and the managed entity is untouched until every rule has passed. A rejected write that has
>   already half-happened and depends on a rollback to be correct is a worse shape, and the ported
>   tests assert the stored row rather than a captured call — including that a refused update left the
>   row alone.
> - **`DeedPollServiceTest` runs against the throwaway Postgres, not a mocked Prisma client**, the
>   same choice task 6 made for the same reason: the reference asserts the arguments its mock was
>   called with, which is all a TypeScript unit test can do, and reference-number allocation is
>   correct because of a row lock a mock cannot hold. Two of its assertion shapes are translated,
>   noted on the class: `arg.x` being undefined becomes "the stored value is unchanged", and
>   "update was not called" becomes "the stored record is unchanged".
> - **Three renames, all forced by Java keywords**, and no others: `FormPresentation.TabVisibility`
>   has `returnTab` for the reference's `return`, `EditView` has `caseRecord` for `case`, and
>   `FieldDef`'s withers are `asReadOnly()` / `asNotNullable()` because a record cannot have a method
>   named after a component with a different return type. `CardColumn` is an enum (`LEFT`, `RIGHT`,
>   `FULL`) where the reference has `1 | 2 | "full"`.
> - **`FormFieldsTest` is not a port.** The reference gets four guarantees from the TypeScript
>   compiler that this port cannot: a field's `enumSet` is a checked key of `LABELS`, its `subgrid` is
>   a checked union, and a record can be read and written by index signature. Those become tests —
>   every `enumSet` resolves, every form field is readable, every editable one is writable — so a typo
>   fails a build rather than rendering a select with no options.
> - **`@OrderBy("createdOn ASC")` added to the three child collections** on `DeedPoll`, reproducing
>   the `orderBy` the reference declares on each of its includes. `findDeedPoll` initialises them by
>   touching each rather than by one fetch join: three `List` collections are bags, so a single
>   fetch would be a `MultipleBagFetchException`, and Prisma's `include` issues a query per relation
>   anyway.
> - **Steve: one hazard worth knowing.** A `perl -i` one-liner used mid-task to reformat single-line
>   javadocs silently blanked 24 of them, because an inner regex match inside the same statement
>   resets `$1`/`$2` before they are read. Caught and repaired before the commit, and every blanked
>   line is back verbatim — but it is the kind of edit that a green build would not have caught, since
>   a comment cannot fail a test.

### Task 8: Domain: notes

- [x] Port `reference/ts/notes/`, roughly 44 test blocks, and its tests.
- [x] `createNoteSchema` trims **and** delegates to `validateNoteText`. Both are load-bearing: without the
      trim, `"   "` is accepted as a note and whitespace is stored.

- [x] Verify: `./gradlew test --tests "*Note*"`
- [x] Mark completed

> **What task 8 did beyond the checkboxes, and three decisions worth reading.**
>
> - **All four modules of `reference/ts/notes/` are ported, tests and all** — `note-form`, `schemas`,
>   `notes-view` and `service` — into `uk.gov.hmcts.reform.deedpoll.domain.notes`, plus
>   `repository/DeedPollNoteRepository`. 49 test methods where the reference has 44 blocks; the five
>   extras are each noted at the test that carries them. `./gradlew test` is 521 tests, all green.
> - **The trim and the delegation were both mutation-checked.** Returning the untrimmed value from
>   `NoteSchemas` fails "stores the trimmed value" and "trims a present note"; sorting the grid
>   ascending fails "orders newest-modified first"; dropping the parent check from `findNotes` fails
>   "404s listing notes for a case that does not exist". A green build did not have to be taken on
>   trust for any of the three.
> - **`validateNoteText` returns `Optional<String>`**, the same deviation task 6 made and for the same
>   reason: TypeScript's strict null checks force the caller to handle the null return and Java has
>   none, so the obligation stays in the type. The ported tests assert `isEmpty()` where the reference
>   asserts null.
> - **`UpdateNoteInput` carries `Optional<String>`, not a nullable field.** The note column is
>   `NOT NULL`, so absent and present are the only two states a note patch has and `null` is refused
>   rather than stored — the same absent-versus-null care `DeedPollPatch` takes for the case, in the
>   shape one non-nullable field needs. `createdBy` is not expressible in either input record, so an
>   amend cannot rewrite who created a note; the reference deletes the key, this port has nothing to
>   delete.
> - **The reference's double scoping ports as a structural one.** It guards with a `findFirst` and
>   *also* repeats `deedPollId` in the `update`/`delete` `where`, because with only the guard
>   cross-case safety is a convention a later method could omit. Here every write goes through the
>   entity that guard loaded — fetched by id **and** parent — and `DeedPollNoteRepository` has no
>   unscoped finder, so an unscoped write is not expressible. An extra test asserts a wrong parent
>   cannot delete somebody else's note.
> - **`NoteServiceTest` runs against the throwaway Postgres**, like tasks 6 and 7, and is not
>   `@Transactional`: the writes must commit for the assertions to read stored rows, so it clears the
>   three tables around each test. Its ordering test backdates `createdOn` explicitly rather than
>   trusting two creates to land in different microseconds.
> - **`NoteAuthor` is kept as its own type** rather than folded into `WriteContext`, as the reference
>   keeps it: a note write has no admin-only field, so nothing there should be able to ask whether the
>   caller is an admin. It reads `SERVICE_ACCOUNT_NAME` from `WriteContext` rather than restating it.

### Task 9: Domain: witnesses

- [x] Port `reference/ts/witnesses/`, roughly 15 test blocks, and its tests.

- [x] Verify: `./gradlew test --tests "*Witness*"`
- [x] Mark completed

> **What task 9 did beyond the checkboxes, and three decisions worth reading.**
>
> - **Both modules of `reference/ts/witnesses/` are ported, tests and all** — `schemas` and `service` —
>   into `uk.gov.hmcts.reform.deedpoll.domain.witnesses`, plus `repository/DeedPollWitnessRepository`.
>   27 test methods where the reference has 15 blocks; each of the 12 extras is noted at the test that
>   carries it. `./gradlew test` is 548 tests, all green.
> - **`WitnessSchemas` deliberately does NOT trim**, which is the one place this port diverges from the
>   habit task 8 established. The notes payload trims because a blank note passes an emptiness check and
>   stores a row the grid renders blank; the witness schema declares plain `z.string().max(...)` and its
>   test asserts `parse(valid)` returns exactly what it was given, so trimming here would make the port
>   store something different from the specification it is being asserted against. Same reasoning for
>   there being no emptiness check: `z.string()` accepts `""`, and the reference's "requires every field"
>   test removes keys rather than blanking them. If witnesses should trim, that belongs in the reference
>   first — the comment on the class says so, so the next session does not read the absence as an
>   oversight.
> - **Four mutation checks, all of which fired.** Replacing `findByIdAndDeedPollId` with `findById` fails
>   "refuses to remove a witness through another case"; coercing `declarant` with `Boolean.parseBoolean`
>   fails "requires declarant to be a boolean, not a truthy string"; dropping the parent check from
>   `findWitnesses` fails "404s listing witnesses for a case that does not exist"; and flipping the
>   repository's sort to descending fails the ordering test. Note `failFast = true`, so each was run on
>   its own.
> - **`declarant` is a primitive `boolean` on the create input and `Optional<Boolean>` on the patch.** All
>   four columns are `NOT NULL`, so an explicit null is refused rather than stored — the same
>   absent-versus-null care `DeedPollPatch` and `UpdateNoteInput` take, in the shape four non-nullable
>   fields need. `UpdateWitnessInput.EMPTY` and `isEmpty()` exist so a caller can tell a no-op patch from
>   a change; a witness has no author column to move, so unlike a note an empty patch really is a no-op,
>   and there is a test for it.
> - **No `WitnessAuthor`, because `deedpoll_witness` has no `createdBy` / `modifiedBy`** — only the two
>   timestamps, per the reference schema. So no author is threaded through the service, and attribution
>   for a witness change is the case's own `modifiedBy` plus the declaration on file.
> - **The reference's double scoping ports as a structural one**, exactly as task 8 did it: every write
>   goes through the entity the guard loaded by id *and* parent, and `DeedPollWitnessRepository` has no
>   unscoped finder, so an unscoped write is not expressible. What the ported test asserts instead of the
>   `where` clause is the outcome scoping buys — the right row moved and the other case's did not.
> - **Nothing in plan 01 calls the three write methods.** The caseworker surface is eleven operations and
>   witness writes are deliberately not among them. They are ported because the reference and the source
>   form have them, so a later consumer finds a scoped implementation rather than inventing an unscoped
>   one — stated on the class so it does not read as dead code someone should delete.
> - **There is no witnesses view model to port**: the reference has none, because witnesses are rendered
>   as part of the case detail page, which is `DetailView`'s job. One extra test asserts a witness created
>   through the service is visible on `DeedPoll.witnesses`, since the detail page reads the collection
>   rather than calling `findWitnesses` and a mapping that wrote the foreign key without the relation
>   being navigable would pass every other test here.

### Task 10: Domain: documents, metadata only

- [x] Port `reference/ts/documents/`, roughly 60 test blocks, and its tests — **metadata and the upload
      policy only.**
- [x] **Do not port `file-storage.ts`.** Real storage is CDAM, in plan 03. Define a `DocumentStore`
      interface with one local-filesystem implementation for development, and nothing else behind it.
- [x] Keep the upload policy's own framing in a comment: the accepted-type list is the client's declared
      MIME type, so it is hygiene rather than security, and 25 MB is an unresearched figure.

- [x] Verify: `./gradlew test --tests "*Document*"`
- [x] Mark completed

> **What task 10 did beyond the checkboxes, and four decisions worth reading.**
>
> - **All five metadata modules of `reference/ts/documents/` are ported, tests and all** — `schemas`,
>   `upload-policy`, `document-types`, `service` and `documents-view` — into
>   `uk.gov.hmcts.reform.deedpoll.domain.documents`, plus `repository/DeedPollSupportingDocumentRepository`
>   and `…TypeRepository`. 98 test methods where the reference has 57 blocks for those modules; each
>   surplus is noted at the test that carries it. `./gradlew build` is 646 tests, all green.
> - **`PatchField<T>` is new vocabulary, in `domain/`, and it was unavoidable.** `UpdateDocumentInput` is
>   the first ported patch whose columns include nullable ones: clearing `storageKey` detaches a blob from
>   its metadata row, and that must stay distinct from not mentioning it. `Optional` cannot express it —
>   there is no room for a legitimate null — and a nullable field collapses the two. `DeedPollPatch` keeps
>   the distinction by being a keyed map, which is right for 68 fields and wrong for six, so this is the
>   same three states with the types kept. The three NOT NULL fields use it too, so one patch does not read
>   in two idioms.
> - **`DocumentStore` has three methods and one implementation, and `file-storage.ts` really is not
>   ported.** CDAM is a different shape — content in, document id and hash token out, downloads through it
>   rather than through this service's disk — so porting the reference's local storage as the production
>   path would have to be undone. `LocalFilesystemDocumentStore` is written rather than translated, keyed by
>   a generated UUID (two applicants uploading `birth.pdf` must not collide, and a caller-supplied name in
>   a path is how a traversal gets in) and it keeps the traversal guard the reference has, because a
>   storage key lives in a database column and columns get seeded and migrated.
> - **`isAllowedUploadType` needed a null guard the reference does not.** `Set.of(...).contains(null)`
>   throws rather than answering false, and a client may legitimately send a multipart part with no
>   `Content-Type` — so the straight port would have been a 500 for a file that should have been a 400.
>   Found by writing the test, which is why there is one.
> - **`DocumentServiceTest` uses a Mockito *spy* on `DocumentStore`, not a mock.** Every test but the
>   cleanup-failure pair runs the real local store against a temporary directory, so the metadata row and
>   the blob are asserted to agree — a store that truncated a PDF would pass a metadata-only assertion. The
>   two exceptions induce an I/O failure, which is exactly the case a real store cannot be asked for. Its
>   log assertion reads a Logback `ListAppender`, since "logged it with both ids so the orphan can be
>   found" is the actual requirement and a swallowed exception with no log would pass otherwise.
> - **`ConflictError` arrived, as `InvalidInputError` and `NotFoundError` did before it** — thrown only by
>   `removeDocumentType`, whose restrict-on-delete key already refuses but as a driver error the
>   reference's route layer answered 500 for. `ForbiddenError` and `UnauthorizedError` are still unported;
>   task 12 will need them. The two existing error javadocs that enumerated what was missing were corrected
>   rather than left to go stale.
> - **`findDocuments` and `findDocument` initialise the type relation before the entities leave the
>   transaction**, reproducing the reference's `include: {documentType: true}`. Without it `DocumentsView`
>   fails on a lazy proxy with the session closed — a runtime failure where the reference has a query
>   option. One query per document rather than a fetch join, as Prisma's `include` does it, and the same
>   handful of types recur across a case anyway.
> - **Eleven mutation checks, all of which fired.** Dropping the parent check from `findDocuments`;
>   `findById` in place of `findByIdAndDeedPollId`; rethrowing the blob-cleanup failure as the reference's
>   own bug did; dropping `initialiseType`; deleting a type without counting its references; counting every
>   document rather than the type's; listing types descending; treating every patch field as nullable;
>   matching a MIME type by prefix; dropping the store's traversal guard; and ordering a case's documents
>   newest-first. Note `failFast = true`, so each was run on its own.
> - **Nothing in plan 01 calls `updateDocument`, `removeDocument` or the four document-type writes.** The
>   caseworker surface is eleven operations, and of this module only `GET /caseworker/document-types`, the
>   upload and the download are among them. They are ported because the reference and the source form have
>   them, so a later consumer finds a scoped implementation that already refuses the delete rather than
>   inventing one that 500s — stated on each class so it does not read as dead code.
> - **`/data` is gitignored** and `deedpoll.documents.local-store-root` (`DOCUMENT_STORE_ROOT`) defaults to
>   `data/documents`, so a fresh checkout uploads without being configured. **Steve: this must not reach a
>   deployed environment** — local disk does not survive AKS, replicas do not share a volume, and the
>   chart has no volume for it. It is a development convenience until plan 03 lands CDAM.

### Task 11: Domain: the conformance harness

- [x] Port `reference/ts/conformance/`, roughly 32 test blocks, against the vendored Dataverse exports.
      This is the proof the migration did not silently drop a column, so it is the last thing that should
      be allowed to lapse.
- [x] **Carry its caveat into a comment on the test class:** the Dataverse exports are alphabetically
      truncated at A–R despite declaring 70 columns, so this asserts forward coverage only — every column
      present must map — and must never be read as a complete column set.

- [x] Verify: `./gradlew test --tests "*Conformance*"`
- [x] Mark completed

> **What task 11 did beyond the checkboxes, and four decisions worth reading.**
>
> - **All five modules of `reference/ts/conformance/` are ported, tests and all** — `artifacts`,
>   `schema`, `state-cascade`, `field-rules`, `enum-values` and `conditional-gates` — into
>   `uk.gov.hmcts.reform.deedpoll.conformance`, plus `reference/DataverseArtifacts` as the loader.
>   **248 tests** where the reference has 32 blocks: most of the difference is the reference's own
>   loops over the column maps counted individually (its one block over 5 tables is 72 tests here),
>   the rest is the additions below, each noted at the test that carries it. `./gradlew build` is 894
>   tests, all green; `--tests "*Conformance*"` is 387, which also picks up the task 3/4 schema test.
> - **The truncation caveat is on `DataverseArtifacts` and on the test class, not only in a comment
>   by the guard.** Both say the same thing the checkbox asks for: forward coverage only, and the
>   export must never be read as a complete column set. The reference's own guard is ported verbatim
>   — assert `deedpoll_Type`, `deedpoll_State` and `deedpoll_Urgency` are absent and
>   `deedpoll_RefundReference` present — so a fuller export trips the test rather than quietly
>   widening what "covered" means.
> - **`DataverseSchemaConformanceTest` is the rename.** `db.SchemaConformanceTest` already held the
>   plain name for the other half of the same question: that one asserts Flyway against the vendored
>   `.prisma`, this one asserts the `.prisma` against the Dataverse exports it was derived from.
>   Together they run from the source system to the live database; separately neither is enough.
> - **Four additions, all of the same kind: read the specification instead of a transcription of
>   it.** The reference's tests hand-copy things the vendored exports state in full, and a
>   transcription only agrees with its source on the day it is written.
>   (1) Every mapped field name is checked to exist in the reference schema — the reference's maps
>   claim 69 field names and nothing verified one of them. (2) The Returned/Enroled contradiction
>   message is read out of `business_rules.md` rather than retyped. (3) The whole 4-branch, 8-field
>   required matrix is parsed out of `business_rules.md` by `BusinessRule` and asserted from it — the
>   largest hand-copied thing in the port — with the parser strict enough that a rule gaining a
>   branch or a field stops the build. (4) The Dynamics integers are checked against the `// n`
>   comments the reference schema documents them in, which needed `PrismaSchema.enumDynamicsValues()`.
> - **Three smaller additions.** `declaredColumnTotal` is exported by the reference and never called,
>   so nothing pinned the parser against the export's own count; it is a test now, and a regex that
>   silently stopped matching rows would otherwise shrink every coverage assertion to a green
>   nothing. `hasNoBusinessRules` is asserted **false** for the case table, because an emptiness check
>   that could only ever answer "empty" passes all four child tables while proving nothing. And the
>   enum table is checked to cover every enum the reference declares, since a hand-written table can
>   simply omit one.
> - **`DynamicsValues.reverse` is called at its concrete type, via a supplier.** Eleven differently
>   typed value maps share one test table by being re-keyed to member names, but `reverse` takes
>   `Map<E, Integer>` and a wildcard will not compile — so each case carries a
>   `Supplier<Map<Integer, ? extends Enum<?>>>` that calls it where the type is still known. The
>   alternative was asserting uniqueness by hand and never running the method under test.
> - **One reference block could not port: "invalid dates are treated as unset".** A JavaScript
>   `Date` can hold an unparseable value that is still not null; a `LocalDateTime` cannot, so "unset"
>   has one representation instead of two and there is no second case. It is absent rather than
>   approximated, with a note where it would have been and the null case it collapses to asserted in
>   its place — the same deviation `CaseStateInput` and task 6 already record.
> - **Eight mutation checks, all of which fired.** Dropping a column from the forward map; pointing a
>   mapped field at a name the schema does not declare; a parser that skips a row; the Adult branch no
>   longer mandating marital status; a reworded contradiction message; a wrong Dynamics integer (which
>   fails both the transcription and the schema-comment test); two members sharing an integer; and the
>   minor gate clearing three fields instead of four. Note `failFast = true`, so each was run on its
>   own.
> - **Steve: the harness is now three-deep and worth knowing as one thing.** Dataverse export →
>   `deed-poll.prisma` → Flyway/JPA. Task 11 covers the first arrow, tasks 3–5 the second. Nothing
>   covers the columns the export never reached, and nothing can until a complete export exists —
>   which is the one gap in the "did not silently drop a column" claim, and it is in the artifact
>   rather than in the port.

---

### Task 12: Authentication and the two namespaces

- [x] Accept a forwarded IDAM bearer token on `/caseworker/**` — Spring Security as an OAuth2 resource
      server, issuer from configuration. `../deed-poll-admin/apps/api/src/auth/` is the reference for what
      it validates.
- [x] Authenticate by default: every route requires a token unless explicitly permitted, so a route added
      later is protected by omission rather than by memory. `apps/api/src/auth/authenticate.ts` documents
      this reasoning.
- [x] Create an **empty** `/citizen/**` namespace with its own controller package and its own DTO package.
      Nothing in it yet. It exists so plan 04 cannot accidentally share a caseworker DTO.
- [x] Add `AuthTokenGenerator` from `service-auth-provider-java-client` for outbound S2S, configured but
      unused, with a non-working default secret.

- [x] Verify: `./gradlew test --tests "*Security*"` — must include a test that an unauthenticated request
      to a `/caseworker/**` route is rejected, and an architecture test asserting no class in the citizen
      controller package imports from the caseworker DTO package.
- [x] Mark completed

> **What task 12 did beyond the checkboxes, and three things worth reading.**
>
> - **The implementation landed in the previous session's commits (`9cec400`, `d76c4d6`) with its
>   checkboxes deliberately unticked**, so this session re-entered at task 12 and verified the work
>   rather than assuming it. `--tests "*Security*"` is 24 tests across `SecurityConfigurationTest` and
>   `NamespaceSeparationSecurityTest`, all green; `./gradlew build` is **986 tests**, 0 failures. Every
>   claim in `9cec400`'s message was checked against the tree, not taken from the message.
> - **`./gradlew build` was red, and task 12 caused it.** The template's `GetWelcomeTest` is a
>   `@WebMvcTest` slice, and a slice **does not** get `SecurityConfiguration`: its include list covers
>   `SecurityFilterChain` beans, and a `@Configuration` that *declares* one is not itself one, so the
>   slice falls back to Boot's default chain, which authenticates every path — including `/`, which this
>   service allowlists. It is now a full-context test extending `AbstractPostgresContainerTest`, like
>   `OpenAPIPublisherTest`, and asserts the thing Azure's Always-On probe actually needs: 200 with no
>   credential.
> - **`SecurityConfiguration`'s javadoc said the opposite, and was corrected.** It claimed the IDAM beans
>   live there because "a `@WebMvcTest` slice imports `SecurityFilterChain` beans" — the failure above
>   disproves it. The beans stay where they are for the other reason given (one place to read how
>   authentication is wired), and the paragraph now records the trap instead: a slice test asserts Boot's
>   defaults in **both** directions, refusing a public path and admitting a protected one under
>   `@WithMockUser`, so tasks 13–15's controller tests must boot the whole context to be asserting this
>   service's rules at all.
> - **The two assertions the checkbox names are present and are the narrower half of what is tested.**
>   Unauthenticated `/caseworker/**` is refused, and ArchUnit checks the namespace separation as a
>   bytecode rule rather than an import grep — so a fully-qualified name, a generic parameter or a field
>   type is caught too. The citizen packages are empty, so all three ArchUnit rules pass vacuously today;
>   `bothNamespacesExist()` is what stops that from making the class meaningless, asserting the four
>   `package-info.java` files exist since a documentation-only package compiles to no class file.
> - **`ServiceAuthConfigurationTest` does not match `*Security*`**, so the S2S checkbox is verified by
>   `./gradlew build` rather than by the task's own verify command. It asserts the generator bean builds
>   (which is also the only proof the Feign client the library needs is actually on the classpath), that
>   the secret default is empty, and that no inbound `ServiceAuthFilter` is registered.

---

### Notes for tasks 13–15 — how to build the caseworker surface

**Eleven operations, and only eleven.** They are derived from what `deed-poll-admin`'s 8 data-access files
actually call. **Do not add others** — in particular no deletes, no witness writes, no document-type
writes and no `validate` endpoint. They have no consumer. `apps/api` built all of them anyway from an
imagined consumer, and not repeating that is the point.

Separate request and response DTOs per operation under `…deedpoll.api.caseworker.dto`. **Never return an
entity from a controller.**

### Task 13: Caseworker reads

- [x] `GET /caseworker/cases` — the filtered list.
- [x] `GET /caseworker/cases/{id}` — one case **with its nested notes, witnesses and documents**. The
      reference does this in one query and the UI depends on it; do not split it into four calls.
- [x] `GET /caseworker/cases/field-rules`
- [x] `GET /caseworker/document-types`

- [x] Verify: `./gradlew test --tests "*CaseworkerReadController*"`
- [x] Mark completed

> **What task 13 did beyond the checkboxes, and five decisions worth reading.**
>
> - **The four reads are one controller, `CaseworkerReadController`**, with nine DTOs in
>   `api.caseworker.dto`. `--tests "*CaseworkerReadController*"` is 25 tests, all green; `./gradlew build`
>   is **1021 tests**, 0 failures. The writes of tasks 14 and 15 get a controller per resource, so grouping
>   the reads does not leave a class that is about nothing.
> - **The error layer had to be built, and no task in the plan owns it.** `GET /caseworker/cases/{id}` cannot
>   404 without one. `api/DomainErrorHandler` is a `@RestControllerAdvice` porting the mapping in
>   `../deed-poll-admin/apps/api/src/errors.ts` — 400 / 404 / 401+`WWW-Authenticate` / 403 / 409 for the five
>   domain errors, plus a 400 for a query parameter Spring cannot convert — and `api/ApiError` is that file's
>   `{error, errors}` body. Three of the six branches have no route that can reach them yet and are tested
>   anyway; tasks 14 and 15 reach two of them.
> - **There is deliberately no catch-all `Exception` handler, which the reference has.** Express leaks a stack
>   trace from an unhandled rejection, so `errors.ts` intercepts everything; Spring Boot already answers 500
>   with no message and no trace, and a handler broad enough to catch an unrecognised failure would also catch
>   `NoResourceFoundException` and turn a correct 404 into a 500. `SecurityConfigurationTest` already asserts
>   that 404, so the omission is pinned rather than merely reasoned about.
> - **`ApiError` and `DomainErrorHandler` live in `api`, not in either namespace.** Not a violation of the
>   namespace rule and not an accident of placement: the ArchUnit slices rule matches `api.(*)..`, so a shared
>   sub-package *would* fail it, while `api` itself is not a slice. An error envelope carries no case data,
>   which is what the rule is about.
> - **The case list publishes six columns and an id, not a whole case.** `ListView.CASE_LIST_COLUMNS` names
>   exactly those, and returning the full record per row would hand out every applicant's address, telephone
>   number and referral notes to render a table that shows none of them. Asserted rather than described —
>   `publishesOnlyTheListColumns` checks the absences. The response is an object rather than a bare array so
>   it can echo the filter that was actually applied: `ListFilters.filterFor` falls back silently for an
>   unrecognised key, which is right, but a fallback a caller cannot detect is how a tab renders as active
>   while listing everything.
> - **`CaseDetailResponse` is 79 components, and its field set is asserted against `DeedPollFields`.** The
>   detail page reads the record by field name to build every tab, so the whole case is the operation. The set
>   is the registry's 74 readable names plus the two derived counts and the three child collections — which
>   excludes `placeOfBirth` and the parent block, the citizen-journey columns no caseworker form carries, and
>   a test names those explicitly. A 79-argument canonical constructor is the hazard that buys: thirty-five
>   are `String`, so two transposed compile silently. `CaseDetailResponseFieldsTest` populates every field
>   with a value unique to it and compares field by field. **The four flags needed two runs** — four fields
>   and two values cannot all differ in one — with vectors chosen so each flag has a distinct pair; the
>   mutation checks below show the second vector earning its place.
> - **A document's `storageKey` is not published; `hasFile` replaces it.** The key is an internal locator, a
>   filename today and a CDAM document id after plan 03, and the only question a consumer asks of it is
>   whether there is anything to download — a metadata row can legitimately exist before its file arrives.
> - **`?type=` on field-rules is bound as a `List<String>` and resolved in the controller.** Spring converts a
>   repeated parameter by silently taking the first value, so `?type=Adult&type=Minor` would answer the Adult
>   rules — the reference's "wrong answer presented as a right one" reached by a different route from the
>   prototype's unvalidated cast. All three inputs are exposed where the sibling TypeScript API exposes only
>   `type`, because the detail page passes all three and over HTTP it has to send them.
> - **Six mutation checks, all of which fired.** Two transposed `String` arguments; two transposed flags that
>   only the *first* vector catches; two that only the *second* catches; a field dropped from the DTO;
>   letting a repeated parameter pick the first value; and dropping `findDeedPoll`'s initialisation of the
>   documents relation, which fails with `LazyInitializationException` at the HTTP boundary rather than
>   serialising a proxy. Note `failFast = true`, so each was run on its own.
> - **Three error javadocs were corrected rather than left to go stale.** `NotFoundError`,
>   `InvalidInputError` and `ConflictError` each still said `ForbiddenError` and `UnauthorizedError` were
>   unported, and each said "the route layer maps it to" a status that had nowhere to be read. Task 12 landed
>   the two errors; this task is that route layer, so all three now name `DomainErrorHandler`.
> - **`CaseDetailResponseFieldsTest` and `DomainErrorHandlerTest` do not match `*CaseworkerReadController*`**,
>   so they are verified by `./gradlew build` rather than by the task's own verify command — the same
>   situation task 12 recorded for `ServiceAuthConfigurationTest`. Renaming them to match would have been
>   dishonest about what they test.
> - **Steve: `springdoc.packagesToScan` is still `…deedpoll.controllers`**, so these four routes are absent
>   from the published OpenAPI document. Deliberately left for task 16, which owns the spec, rather than
>   widened here — but it is a one-line change and worth not forgetting, since a spec that silently omits the
>   whole caseworker surface is worse than no spec.

### Task 14: Caseworker case writes

- [x] `POST /caseworker/cases` — allocates a reference number via task 6. A create without `dateReceived`
      cannot allocate one and must fail with a 400 rather than a 500; the reference records this as a known
      sharp edge.
- [x] `PATCH /caseworker/cases/{id}`
- [x] Field-level validation errors must come back attributed to the field, not as one message. The
      reference carries `FieldError[]` for exactly this reason and the UI renders them against individual
      inputs.

- [x] Verify: `./gradlew test --tests "*CaseworkerCaseController*"`
- [x] Mark completed

> **What task 14 did beyond the checkboxes, and five decisions worth reading.**
>
> - **The two writes are one controller, `CaseworkerCaseController`**, with three new DTOs
>   (`CreateCaseRequest`, `UpdateCaseRequest`, `SavedCaseResponse`) plus `CaseWriteRequest`, the base the
>   two request types share. `--tests "*CaseworkerCaseController*"` is 24 tests, all green;
>   `./gradlew build` is **1050 tests**, 0 failures. Task 15's notes and documents writes get their own
>   controller, so a write stays with the resource it writes.
> - **The request DTOs are not records of nullable components, and could not be.** A case patch turns on
>   the three-way absent / null / value distinction — clearing `dateEnroled` un-enrols a case, omitting it
>   must leave it alone — and a record of nullable components collapses the first two, so every PATCH
>   would silently clear the sixty-odd fields the caller never mentioned. So `CaseWriteRequest` carries the
>   body as a keyed map via `@JsonAnySetter` and hands it to `DeedPollSchemas`, which is exactly where the
>   reference puts the same decision: its handlers pass `req.body` to a Zod schema whose `z.object()`
>   strips unknown keys and reports the rest per field. **Mutation-checked**: making the any-setter drop
>   null values fails "clears a field sent as null, and leaves an omitted one alone", which asserts both
>   directions in one test.
> - **`SavedCaseResponse` publishes `CaseDetailResponse`'s fields minus the three child collections, and
>   the absence is deliberate rather than an economy.** A write does not read the children — the
>   reference's create and update are a plain Prisma `create`/`update` with no `include`, where only
>   `findDeedPoll` asks for them — so publishing them empty would tell a caller patching a case with three
>   notes that it has none, and publishing them truthfully would mean three queries the domain
>   deliberately does not issue. It is also the only shape that works: they are a lazy collection on a
>   managed entity, and reading one after the transaction closes fails rather than answers.
> - **One response type for both writes, where the plan asks for a type per operation.** A create and a
>   patch return the same thing, and the reference returns the identical shape from both, so two identical
>   seventy-six-component records would be a duplication with no reader and two places to add a column in.
>   The *request* types are separate, because those really do differ in what an absent field means. Stated
>   on the class so it does not read as a shortcut.
> - **`SavedCaseResponseFieldsTest` pins the new record against `DeedPollFields` AND against
>   `CaseDetailResponse`** — same names in the same order, minus the children, exactly. Each record's own
>   coverage test would stay green while a column was published on the read and forgotten on the write,
>   which would leave a client with a field that appears and disappears depending on which call last
>   returned it. The populating machinery both tests share moved to `CaseResponseFields`;
>   `CaseDetailResponseFieldsTest` now reads it from there rather than carrying its own copy, so the two
>   DTOs cannot end up checked with different care. **Mutation-checked**: two transposed `String`
>   arguments in `SavedCaseResponse.from` fail both flag vectors.
> - **`DomainErrorHandler` gained a sixth branch, for an unreadable body.** `POST` needed it and no task
>   owns the error layer — task 13 recorded the same about building it in the first place. Without it a
>   client gets `ApiError` for every refusal this service reasons about and Boot's default error
>   attributes for the one it does not, which is two things to parse for one status. **The reference gets
>   this case wrong**: its body-parser error reaches `errorHandler()` unrecognised and falls to the
>   catch-all, so unparseable JSON is answered 500 — a client error reported as a server error. Mapping
>   the one framework signal narrowly fixes that without reintroducing the catch-all, whose own problems
>   task 13 set out. The handler takes no exception parameter, which is how "the framework's message is
>   not echoed" is enforced rather than intended. **Mutation-checked**: pointing the annotation at another
>   exception type fails the test.
> - **Four more mutation checks, all of which fired.** Passing `WriteContext.DEFAULT` instead of the
>   caller's context fails "honours a privacy an admin sets on an existing case" — which is the check that
>   the admin bit reaches the domain at all, not just that the domain honours it. The other two are in the
>   bullets above. Note `failFast = true`, so each was run on its own.
> - **What the write tests assert is the stored row, not the response.** A response is built from the same
>   objects the write path just mutated, so a mapping that never reached the database would still answer
>   correctly — the failure mode the reference's own tests could not see, having asserted the arguments a
>   mocked Prisma client was called with. Both refusal tests also assert the row was left alone, which is
>   what `DeedPollService` validating before it mutates buys and would otherwise be a claim resting on
>   rollback.
> - **`SavedCaseResponseFieldsTest` does not match `*CaseworkerCaseController*`**, so it is verified by
>   `./gradlew build` rather than by the task's own verify command — the same situation tasks 12 and 13
>   recorded. Renaming it to match would be dishonest about what it tests.
> - **Steve: `springdoc.packagesToScan` is still `…deedpoll.controllers`**, so these two routes are absent
>   from the published OpenAPI document along with task 13's four. Still task 16's, still a one-line
>   change, and now six endpoints rather than four are missing from a spec that says it describes the
>   service.

### Task 15: Caseworker notes and documents

- [ ] `GET` and `POST /caseworker/cases/{id}/notes`, and `GET`/`PATCH
      /caseworker/cases/{id}/notes/{noteId}`.
- [ ] `POST /caseworker/cases/{id}/documents` — multipart upload, writing through the `DocumentStore`
      interface.
- [ ] `GET /caseworker/cases/{id}/documents/{documentId}/file` — streams from `DocumentStore`. Set
      `Content-Disposition: inline` for `application/pdf`, `image/jpeg` and `image/png`, `attachment`
      otherwise, plus `X-Content-Type-Options: nosniff` and `Cache-Control: no-store`. The inline decision
      and its reasoning are in the API plan; the allowlist is what makes it safe.
- [ ] A child list under a non-existent parent case must 404, not return an empty list with 200.

- [ ] Verify: `./gradlew test --tests "*CaseworkerNote*" --tests "*CaseworkerDocument*"`
- [ ] Mark completed

---

### Task 16: Health, readiness and the OpenAPI spec

- [ ] Readiness must check the database **and name the missing tables** if any are absent — being paged for
      "readiness DOWN" with no further detail wastes the diagnosis.
      `../deed-poll-admin/libs/deed-poll/src/health.ts` is the reference, including why the table list
      cannot be derived.
- [ ] Liveness stays empty on purpose: the process answering is the signal. A database blip must not make
      Kubernetes restart every pod, because restarting cannot fix someone else's database.
- [ ] Publish an OpenAPI spec via springdoc, and wire the `SwaggerPublisherTest` pattern the template
      ships so it can later publish to `hmcts/cnp-api-docs`.

- [ ] Verify: `./gradlew build && ./gradlew test --tests "*Health*"` — including a test that readiness
      returns 503, not 200, when the database is unreachable. Returning 200 on a failed probe is a real defect
      this codebase has already had once.
- [ ] Mark completed

---

## Success criteria

- `./gradlew build` green, and every task's own `Verify` command green.
- The Flyway schema and the JPA entities both agree with the vendored `deed-poll.prisma`, proved by the
  schema-conformance test and by Hibernate's `ddl-auto=validate` rather than by inspection.
- The domain port carries **all** of the reference's test blocks, not a subset. A port with fewer tests is
  not a port.
- Eleven caseworker endpoints exist and no twelfth.
- `/citizen/**` exists, is empty, and shares no DTO with `/caseworker/**`.
- Nothing in the repo makes a live call to CCD, CDAM, IDAM or payments, and no secret is committed.

## Out of scope for this plan

Stated so no session tries to be helpful:

- **CCD** — `decentralised = true`, `CCDConfig`, `CaseView`, `/ccd-persistence/*`, the case-type
  definition. Plan 02.
- **CDAM** — real document storage, hash tokens, the attach cycle. Plan 03.
- **`deed-poll-web`** — citizen endpoints, payments, anonymous submission. Its own plan, later.
- **Retiring `apps/api` and cutting `apps/web` over to HTTP.** Different repo, and it must not happen
  until this service is deployed and serving.
- **Deployment.** The chart and Jenkins onboarding are Steve's; nothing here should try to deploy.
