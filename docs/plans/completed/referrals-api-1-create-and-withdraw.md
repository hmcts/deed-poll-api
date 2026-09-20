# Plan: referrals to the judiciary (1 of 3) — the referral, its lifecycle, create and withdraw

> **Follow-up:** `../referrals-api-4-caseworker-completion.md` supersedes this plan's rule that every
> non-terminal referral may be withdrawn. Business testing requires `Decided` to be actioned through
> `Closed`; only `Open` and `Claimed` remain withdrawable.

## Overview

This is the **first of three api plans** for one end-to-end feature: moving the referral-to-a-Master/judge
process into the application. Today caseworkers batch ten referrals into an email twice a day, log the
reference and the judge in a Teams channel, note the case in the power app, and then poll a shared mailbox
for the returned direction. The feature replaces all of that with an in-app referral, a shared judicial
pool, a dashboard alert when a direction returns, and an in-app judicial screen. The whole feature is
**seven plans** — three in `deed-poll-api`, three in `deed-poll-admin`, one for the IDAM judicial role.

**This plan builds the foundation the other six stand on: the `Referral` entity, its full lifecycle, and
the caseworker-facing operations that exist before a judge is involved — create, list, get, withdraw.**
It deliberately stops short of the judge: claiming, recording a direction, and closing a returned referral
are **api-2 (directions)**; the judicial read surface and the shared-pool query are **api-3**. The IDAM
judicial role is its own small plan. The `deed-poll-admin` referrals tab that consumes *this* plan is
**admin-1**.

Requirements were shaped with Steve across 2026-09-17 (nineteen numbered decisions, held in the
workspace memory `deed-poll-referrals-feature`). The ones this plan implements are restated below.

### What already exists, and why this is a new entity rather than an edit

The reference Dynamics model stored a referral **inline on the case, one per case, latest-wins**. Those
columns are already ported and already exposed read-only on `CaseDetailResponse` / `SavedCaseResponse`:
`urgency` (a two-value `Urgency` enum, `Normal`/`Urgent`), `urgencyreason`, `referToMasterChoice`,
`masterName`, `masterDirections`, `masterDecision`, `masterDateOfDecision`, `dateReferred`,
`dateReadyForMaster`, `dateDecisionDirectionsReceived`, `referralNotes`, `publishToLondonGazette`.

**That flat model cannot express what the feature needs** — many referrals over a case's lifetime, a shared
pool, a claim with expiry, and the states Open → Claimed → Decided → Closed with Withdrawn as an escape
hatch. So this plan adds a **new `deedpoll_referral` table**, exactly as saved queries added
`deedpoll_savedquery` — new behaviour with nothing in `reference/` to port (decision 20). The schema is
**additive**: it touches no committed migration and no column on `deedpoll_deedpoll`. The legacy flat
columns are left untouched as the ported historical snapshot, and they are the **source for a one-time
backfill** so power-app referrals migrate into the new table.

## Decisions taken — implement these, do not revisit

1. **A new `deedpoll_referral` table, "not a port".** Like `deedpoll_savedquery`: no reference model,
   `SchemaConformanceTest` and `EntityMappingTest` do not reach it, and `ddl-auto: validate` plus a
   round-trip test hold it to the migration. Do not add anything to `src/test/resources/reference/` and do
   not look for a referral model there (decision 20).

2. **A referral belongs to a case and there may be many over its lifetime, but at most one *open* at a
   time.** "Open" here means any non-terminal state — `Open`, `Claimed` or `Decided` (i.e. decided but not
   yet closed). Create **refuses with 409** if the case already has a referral in a non-terminal state
   (decision 11). The terminal states are `Closed` and `Withdrawn`; a case with only terminal referrals may
   be referred again.

3. **The full lifecycle enum is defined now, once, even though this plan only reaches two of its states.**
   `ReferralState`: `Open`, `Claimed`, `Decided`, `Closed`, `Withdrawn`. This plan transitions into `Open`
   (on create) and `Withdrawn` (on withdraw) only; `Claimed`/`Decided`/`Closed` arrive in api-2/api-3.
   Defining the whole set now keeps the enum type additive — later plans add transitions, not migrations
   (decision 10). It is a **new Postgres enum type** created in this plan's migration (V1 is committed and
   must not be edited), mapped with `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` like every other enum column here.

4. **Referral reasons are a multi-select, stored as a validated JSON array of strings, exactly as saved
   queries store conditions.** `ReferralReason` members (decision 2, refined by Steve 2026-09-17):
   `Minor`, `DomesticAbuse`, `NonPublication`, `DeclarantRequirementsNotMet`, `SeparatedOrEstranged`,
   `Other`. A caseworker-created referral requires **at least one** reason. This follows the
   `SavedQuery.conditions` pattern — a `jsonb` column mapped with `@JdbcTypeCode(SqlTypes.JSON)`, validated
   on write against the enum — rather than a Postgres enum-array column, because that pattern is already
   proven in this repo and avoids Hibernate enum-array mapping. **`ReferralReason` is not a Postgres enum
   type**; only `ReferralState` is (it is a real, queried column).

5. **`Other` requires free text, and that free text is the referral notes field — there is one free-text
   field, not two.** Decision 2 described "a free-text field mandatory when Other, plus referral notes
   alongside"; this plan **unifies them into a single `notes` field on the referral**: mandatory when
   `Other` is among the reasons, optional otherwise. A second near-identical field would be redundant.
   *(Steve: flag if you wanted the Other-explanation and the general notes kept as separate fields — this is
   the one place this plan resolves an ambiguity in the shaping rather than restating it.)*

6. **A referral has its own urgency, reusing the existing `Urgency` enum (`Normal`/`Urgent`).** Decision 17
   named the two levels "Standard/Urgent"; `Normal` is the existing member and means the same thing, so this
   reuses the enum rather than minting a synonym — the "Standard" label is a `deed-poll-admin` display
   choice, not an api concern. `notes`-style rule: when a referral is `Urgent`, its urgency reason
   (`urgencyReason`) is **required**, so an urgent referral always says why it jumped the queue (decision
   17). This urgency is a **column on the referral**, separate from the case's own `urgency`; they may
   differ.

7. **Creation does not touch the case's legacy flat columns.** No write to `dateReferred`, `masterName`,
   `referralNotes` etc. on `deedpoll_deedpoll`. The referral is a row of its own; the flat columns remain
   the ported historical snapshot (decision 20, "leave-as-is for MVP"). Whether a *closed* referral later
   syncs back into those columns is deferred to api-2 and explicitly out of scope here.

8. **The referral is created unassigned — there is no target judge.** The form's "To: master name" was the
   manual tracking mechanism, replaced by dashboard + alerts at the application level (decision 1). Do not
   add an assignee/target column. Who picks a referral up is a `Claimed`-state concern for api-2/api-3.

9. **Create and withdraw each write a note to the case, so the referral appears in the application
   timeline where caseworkers already look.** There is no separate timeline concept in this domain — only
   the human-authored **Notes** grid — so MVP reuses it (decisions 7, 8). These two notes are authored by
   the **acting caseworker** (a real, token-identified user), via
   `NoteService.createNote(deedPollId, input, new NoteAuthor(caller.name()))`. *System*-authored notes (the
   claim-lapse trail) belong to api-2/api-3 and use `NoteAuthor.NONE`, which already stamps the service
   account (`WriteContext.SERVICE_ACCOUNT_NAME`) — that machinery exists and is not new work.

10. **Withdraw is a terminal transition, not a delete and not a field edit.** A withdrawn referral survives
    as `Withdrawn` (distinct from `Closed`: closed means a direction was actioned; withdrawn means the
    caseworker pulled it back) — decision 12. It is exposed as a dedicated sub-resource,
    `POST …/referrals/{referralId}/withdrawal`, not a `DELETE` and not a `PATCH` — there is no in-place
    editing of an open referral in MVP (amend = withdraw + re-raise). Withdraw is permitted only while the
    referral is non-terminal; withdrawing an already-terminal referral is a **409**. In this plan a referral
    can only be `Open` when withdrawn (no claim exists yet); the guard is written against "non-terminal" so
    api-2's `Claimed`/`Decided` states need no change here.

11. **Any authenticated staff user may create, list, get and withdraw a referral — no finer role gate**
    (decision 18). There is no "may refer" permission. The only role that is different is the judicial one,
    which is fenced off entirely (it cannot create referrals) — but that role does not exist yet and is
    idam-1/api-3, so this plan adds no role logic at all. Referrals are nested under the case exactly as
    notes and witnesses are, and inherit the same caseworker authentication.

12. **A one-time backfill seeds the new table from the legacy flat columns**, so real power-app referrals
    are not lost at cutover (decision 20). For each case that carries referral data: `masterDecision` set →
    a **`Closed`** referral (a decision was returned); `dateReferred` set and `masterDecision` null → an
    **`Open`** referral (a real backlog item, into the pool — not a clean slate). A case with no referral
    data gets no row. Backfilled referrals have **no reasons** (that field did not exist) and are flagged
    with a **`migrated` boolean** so their empty reason set is honest rather than a bug — the reasons
    array is `[]` and `migrated = true`. The handful of orphaned in-flight ones get a manual clear-up
    exercise (low numbers, Steve's call); the backfill does not try to guess reasons for them.

## The endpoints

Four new operations, nested under the case like notes and witnesses. The caseworker surface moves
**25 → 29**, across **three new paths**:

| Method | Path | Who |
|---|---|---|
| `GET` | `/caseworker/cases/{id}/referrals` | any caseworker — the case's referrals, newest first (history plus the current open one) |
| `POST` | `/caseworker/cases/{id}/referrals` | any caseworker — 409 if one is already open on the case |
| `GET` | `/caseworker/cases/{id}/referrals/{referralId}` | any caseworker |
| `POST` | `/caseworker/cases/{id}/referrals/{referralId}/withdrawal` | any caseworker — 409 if the referral is already terminal |

Every referral operation is **scoped to its parent case**, exactly as notes are: a referral is fetched by
id *and* `deedPollId`, so a cross-case read or write is not expressible. There is no finder by referral id
alone, and the repository must not add one.

## Open questions — flagged, NOT answered here

- **Sync-back to the legacy flat columns on close.** Deferred to api-2 with the close transition. This plan
  leaves the flat columns as the historical snapshot and writes nothing to them.
- **Automatic referral of minor applications.** Every minor application is referred as a matter of policy,
  but auto-creating the referral needs a reliable "relates to a minor" data signal and a firing point in the
  case lifecycle, which is a rules-engine slice (decision 21). For MVP `Minor` is a manually selectable
  reason like any other; auto-referral is a follow-up.
- **Indexing the pool query.** The shared-pool read is api-3's; nothing is deployed and there are no volumes
  to measure. `V4__list_indexes.sql` is the precedent for adding one when there is evidence.

## Constraints

- **Java 21, `-Werror`, checkstyle on all five source sets.**
- **`export TESTCONTAINERS_RYUK_DISABLED=true`**, or container-backed tests fail in a static initialiser.
- **The next migration numbers are `V6__` then `V7__`.** `V1__enums`, `V2__deed_poll`, `V3__children`,
  `V4__list_indexes`, `V5__saved_queries` are committed. **Never edit a committed migration** — it breaks
  every environment that has already run it. This plan adds `V6__referrals.sql` (schema) and
  `V7__backfill_referrals.sql` (data).
- **Column names are mixed case** (`newForename`), because the reference schema's are, and
  `globally_quoted_identifiers` + `PhysicalNamingStrategyStandardImpl` are set service-wide and apply to new
  tables too — a `snake_case` column would be quoted as written and then not found. Enum columns need
  `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`; the `jsonb` column needs `@JdbcTypeCode(SqlTypes.JSON)`.
- **Table names follow the existing convention** — `deedpoll_`-prefixed, all lower case
  (`deedpoll_referral`) — while columns stay mixed case. This is the same split the saved-queries plan
  documented: the column-case mechanism is global and mechanical, whereas a table name is only a convention,
  and consistency with the existing seven tables is the whole argument. Do not "correct" the table name to
  the column style.
- **Flyway owns the schema; Hibernate validates against it** (`ddl-auto: validate`).
- **Never return an entity from a controller.** Request and response DTOs per operation under `api/…/dto`.
- **A controller test must boot the whole context.** A `@WebMvcTest` slice does not get
  `SecurityConfiguration`.
- **`DeedPollSchema.DEED_POLL_TABLES` is a literal list, not a discovery.** It holds eight table names today
  (`DeedPollSchema.java:50`); add `deedpoll_referral` or readiness passes while describing a schema it no
  longer checks.
- **`OpenAPIPublisherTest` asserts the caseworker operation count and the path list, and it runs in
  `build`** (it is in the `integrationTest` source set, which `build` executes). Both the count
  (`OpenAPIPublisherTest.java:129`, currently `25`) and `CASEWORKER_PATHS` (`:66`, thirteen entries) must be
  updated in the **same task** that adds the operations, or `build` goes red. The `@DisplayName` (`:108`)
  and the explanatory comment (`:118`) also state the number.
- **The operation count is stated in at least five places.** Grep, do not trust a list:
  `git grep -inE '\b(25|twenty-five|29|twenty-nine)\b'` reaches `OpenAPIPublisherTest`,
  `api/caseworker/controller/package-info.java`, `README.md` (prose and the table), `docs/RESUME.md`, and
  `CLAUDE.md`. Update every one that refers to the caseworker operation count; leave unrelated 25s
  (`CasePaging.DEFAULT_SIZE`, the 25 MB upload limit) alone.
- **Do not edit `src/test/resources/reference/`.** Referrals are new behaviour, not a port.
- **New container-backed tests will not run in CI.** `INTEGRATION_TESTS_ENABLED=false` on the Jenkins agent
  excludes the `AbstractPostgresContainerTest` subclasses because the agent has no Docker. Put the referral
  rules — reason validation, the one-open-per-case guard, the withdraw-terminal guard — in **plain unit
  tests that need no container**, precisely because those are the ones CI actually runs. A controller test
  that boots a context is the one class that will be excluded, and that is accepted, not forgotten.

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

`build` is compile plus checkstyle across all five source sets, plus `test`, plus `integration`
(`build.gradle:113`) — which is why `OpenAPIPublisherTest` runs here and why the operation-count task must
keep it green rather than leaving it for the end.

**Measure the baseline on this branch before Task 1**, by summing `tests="…"` across
`build/test-results/{test,integration}/*.xml` — not by reading a Gradle summary line. Record it next to the
final count in Task 4. The saved-queries plan ended at **1,688 unit / 6 integration**; this branch starts
from wherever that left the tree, so re-measure rather than quoting that.

**A worker or container startup timeout is an environment failure, not a code failure.** This devcontainer's
filesystem is slow enough that process startup races time out. If `build` fails with a startup or "timeout
waiting for" error rather than an assertion, **re-run before investigating**, and consider running from a
`git archive` copy under `/tmp`, which is an order of magnitude faster on this mount.

## Tasks

### Task 1: Schema, the backfill, and the entity

`V6__referrals.sql`, additive — it must not edit any committed migration or alter `deedpoll_deedpoll`:

- `CREATE TYPE "ReferralState" AS ENUM ('Open', 'Claimed', 'Decided', 'Closed', 'Withdrawn');` — all five
  members now, though only `Open` and `Withdrawn` are reached in this plan (decision 3).
- `CREATE TABLE "deedpoll_referral"` — lower-case table name, mixed-case columns:
  `id` uuid pk; `deedPollId` uuid not null referencing `deedpoll_deedpoll` **on delete cascade**;
  `state` `"ReferralState"` not null default `'Open'`; `reasons` jsonb not null default `'[]'`;
  `notes` text null; `urgency` `"Urgency"` not null default `'Normal'`; `urgencyReason` text null;
  `migrated` boolean not null default false; plus the four audit columns exactly as
  `V2__deed_poll.sql:141-144` declares them (`createdOn` defaulted, `modifiedOn` **not** defaulted,
  `createdBy`/`modifiedBy` `varchar(200)`).
- An index on `("deedPollId")` — every read in this plan is case-scoped.

`V7__backfill_referrals.sql`, a one-time data migration (decision 12): `INSERT … SELECT` from
`deedpoll_deedpoll`, one referral per case that has referral data. `state` = `'Closed'` where
`"masterDecision"` is not null, else `'Open'` where `"dateReferred"` is not null; skip cases with neither.
`reasons` = `'[]'`, `migrated` = true, `notes` = the case's `"referralNotes"`, `urgency` = the case's
`"urgency"`, audit columns stamped to the service account and the case's `"dateReferred"`/now. Keep it a
single statement; do not attempt to infer reasons.

Entity `entity/Referral.java` and repository `repository/ReferralRepository.java`, following the
`SavedQuery` / `SavedQueryRepository` pattern: `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` on `state` and
`urgency`, `@JdbcTypeCode(SqlTypes.JSON)` on `reasons` (a `List<String>` as in `SavedQuery.conditions`),
a "**not a port**" class javadoc mirroring `SavedQuery`'s. The repository exposes finders by
`deedPollId` (list) and by `id` **and** `deedPollId` (get) — and **no finder by id alone** (the notes
scoping rule).

`ReferralState` and `ReferralReason` enums under `entity/enums/` and `domain/…` respectively — `ReferralState`
is the DB enum (in `entity/enums/`, like `DeedPollState`); `ReferralReason` is a plain Java enum validated
in the domain, not a DB type (decision 4).

Add `deedpoll_referral` to `DeedPollSchema.DEED_POLL_TABLES` (`DeedPollSchema.java:50`).

- [x] `V6__referrals.sql` and `V7__backfill_referrals.sql` added, no committed migration edited, no
      `deedpoll_deedpoll` column altered; `Referral` validates against V6 under `ddl-auto: validate`; audit
      columns match `V2`'s types and null/default choices exactly; `state`/`urgency` mapped `NAMED_ENUM`,
      `reasons` mapped `JSON`; cascade on delete; repository has case-scoped finders and **no** find-by-id-
      alone; `deedpoll_referral` in `DeedPollSchema`'s list and readiness green; a round-trip entity test;
      `./gradlew build` green

**One deviation from the text above, taken deliberately: `"deedPollId"` is `text`, not `uuid`.** It is a
foreign key onto `deedpoll_deedpoll("id")`, which `V2` declares as `text` holding an application-generated
UUID (matching the reference so ids survive the data migration), and Postgres will not build a foreign key
between `uuid` and `text`. The cascade is the stronger requirement, so this follows `V3`'s children, all of
which key the case by `text`. The referral's own `"id"` **is** `uuid`, as specified — that row has no
counterpart in the source store. Baseline before this task, measured from `build/test-results`:
**1,696 unit / 7 integration**; after it, **1,709 / 7**.

### Task 2: The domain model and write-time validation

`domain/referrals/`: the create-referral input and its validation, and the referral service with the two
transitions this plan owns.

- **Create validation** (decision 4, 5, 6): at least one `ReferralReason`; every reason a known enum member;
  `notes` required when `Other` is among the reasons; `urgencyReason` required when `urgency` is `Urgent`.
  Each failure is the repo's existing 400 shape (`InvalidInputError` with a named `FieldError`), following
  `SavedQuerySchemas`. Put this in a plain unit test — no container.
- **The one-open-per-case guard** (decision 2): `createReferral` refuses with the repo's 409 equivalent if
  the case already has a referral whose state is non-terminal (`Open`, `Claimed`, `Decided`). Unit-test the
  guard directly; it is the rule most worth pinning and it needs no container.
- **Withdraw** (decision 10): `withdrawReferral` moves a non-terminal referral to `Withdrawn`; a referral
  already `Closed` or `Withdrawn` is a 409. Case-scoped like every other operation.
- **The two timeline notes** (decision 9): `createReferral` and `withdrawReferral` each call
  `NoteService.createNote(deedPollId, input, new NoteAuthor(caller.name()))` so the event appears on the
  case. Assert the note is written and attributed to the acting caseworker, not the service account.

The service returns entities; DTO mapping is the controller's job (the `NoteService` precedent).

- [x] Create requires ≥1 reason, rejects an unknown reason, requires `notes` when `Other`, requires
      `urgencyReason` when `Urgent` — each a 400 with the field named, all in container-free unit tests; the
      one-open-per-case guard 409s against every non-terminal state and permits a fresh referral when all
      existing ones are terminal; withdraw moves `Open`→`Withdrawn` and 409s on a terminal referral; create
      and withdraw each write a case note attributed to the caller; every operation is case-scoped;
      `./gradlew build` green

**One addition beyond the text above, and it is load-bearing rather than scope creep: the referral's two
free-text fields are capped** (`ReferralSchemas.MAX_NOTES_LENGTH` 1000, `MAX_URGENCY_REASON_LENGTH` 500).
Both columns are unbounded `text`, so these are not the database's limits — they are
`NoteForm.MAX_NOTE_LENGTH`'s. Raising a referral writes a case note that quotes that text, and the notes
column *is* bounded, so without a cap a long enough referral would be stored and *then* refused by the note
it triggers, half way through the write. The composition lives in `ReferralNotes` precisely so the arithmetic
is testable in one place, and `ReferralNotesTest` pins the worst case (every reason, both fields at their
cap) against the notes validator rather than leaving it to the reader.

Two smaller choices worth recording: an unknown *reason* does not also demand the `notes` that `Other`
requires — a misspelled `Other` is one mistake, not two — and the create path has no update sibling, because
a referral is not edited in place (amend = withdraw + re-raise), so `ReferralSchemas` has one parse method
where `SavedQuerySchemas` has two. Counts after this task, measured from `build/test-results`:
**1,768 unit / 7 integration** (from 1,709 / 7).

### Task 3: The endpoints, DTOs, and the published spec

`api/caseworker/controller/CaseworkerReferralController.java` and DTOs under `api/caseworker/dto`
(`CreateReferralRequest`, `ReferralResponse`) — request and response DTOs per operation, never the entity.
The four operations from the table. `ReferralResponse` exposes the referral's own fields (id, state,
reasons, notes, urgency, urgencyReason, migrated, audit fields) — it does **not** restate the application's
data (the case detail endpoint already carries that; a referral read is one round trip for the referral).

Then the spec, **in this same task** so `build` stays green:

- Add the three new paths to `CASEWORKER_PATHS` (`OpenAPIPublisherTest.java:66`):
  `/caseworker/cases/{id}/referrals`, `/caseworker/cases/{id}/referrals/{referralId}`,
  `/caseworker/cases/{id}/referrals/{referralId}/withdrawal`.
- Change the asserted count `25` → `29` (`:129`), and the `@DisplayName` (`:108`) and the comment (`:118`).
- Update every prose statement of the caseworker operation count found by
  `git grep -inE '\b(25|twenty-five|29|twenty-nine)\b'`: `package-info.java`, `README.md` (the sentence and
  the endpoint table — add the four rows), `docs/RESUME.md`, `CLAUDE.md`. Leave `CasePaging.DEFAULT_SIZE`
  and the 25 MB upload constants alone.

- [x] Four operations live, DTOs not entities, referral read is a single round trip; `CASEWORKER_PATHS` has
      the three new paths and the count is `29`; `OpenAPIPublisherTest` green; every prose operation count
      updated by grepping, not from a list; controller test boots the context and covers create (incl. the
      409), list newest-first, get, and withdraw (incl. the terminal 409); `./gradlew build` green

**One piece of work this task's text does not mention, and it was mandatory rather than optional:
`CreateReferralRequest` is the tenth keyed write body, and `OpenAPIPublisherTest.documentsEveryKeyedWriteBody`
scans the classpath for `KeyedRequestBody` subclasses** — so a new body that `OpenAPIWriteBodies` does not
describe fails `build` on the commit that introduces it, publishing `CreateReferralRequest: {}` to
`cnp-api-docs` otherwise. `describeReferralBody` is that description, generated from `ReferralReason` and
`ReferralSchemas` rather than restated, and the two conditional rules are carried in prose because OpenAPI
cannot express "required when another field has a particular value" in a form a generated client would honour.
That test was added *because* the saved-query bodies shipped empty, and it did its job here.

Two smaller choices: withdrawal answers **200 with the withdrawn referral** rather than 204, because the row
survives the transition and its new state is what a client re-rendering the tab needs; and the controller test
drives `Claimed`/`Decided`/`Closed` straight into the repository to prove the two guards are written against
terminal-ness and not against `Open`, which is the one assertion that cannot wait for api-2's transitions.
Counts after this task, measured from `build/test-results`: **1,798 unit / 7 integration** (from 1,768 / 7).

### Task 4: Security and full validation

Security tests in the `*Security*` pattern: namespace separation (the citizen surface stays empty of
referrals), a caller with no roles is still authenticated as a caseworker and may refer (decision 11 — there
is no referral role gate, so this asserts the *absence* of one is deliberate), and a referral of one case is
never reachable through another case's path (the scoping rule). Put the scoping assertions in a
container-free unit test on the service where possible, so CI runs them.

- [x] Security tests for namespace separation, no-roles-still-a-caseworker, and cross-case isolation;
      `./gradlew build` green from cold with `TESTCONTAINERS_RYUK_DISABLED=true`; checkstyle clean on all
      five source sets; test and integration totals recorded next to the baseline; the count with
      `INTEGRATION_TESTS_ENABLED=false` also recorded, so it is visible how much of this plan's coverage CI
      actually runs

**Counts, measured from `build/test-results` on a cold `clean build`: 1,813 unit / 7 integration**, from a
branch baseline of 1,696 / 7 before Task 1 — 117 tests for the plan, none of them integration, because
referrals add no new source set. `ReferralSecurityTest` is the fifteen of those added here (from 1,798 / 7).

**With `INTEGRATION_TESTS_ENABLED=false`, the figure CI actually runs: 1,072 of the 1,813.** The 741 excluded
sit in 26 container-backed classes — 25 inherit the tag from `AbstractPostgresContainerTest` and
`SchemaConformanceTest` carries it directly — and three of those classes are this plan's
(`ReferralRepositoryTest`, `ReferralBackfillTest`, `CaseworkerReferralControllerTest`). So of this plan's 117,
CI runs everything except those three, which is why every referral *rule* was written as a mocked unit test:
`ReferralSchemasTest`, `ReferralServiceTest`, `ReferralNotesTest`, `ReferralStateTest` and
`ReferralSecurityTest` all run on the agent. `build.gradle:87-91` held the pre-referrals measurement and now
holds this one, since its own comment says to re-measure rather than trust it.

**Two additions this task's text does not ask for, both of which state a rule over declarations rather than
behaviour**, because `ReferralServiceTest` already states the behaviour and a re-implementation is where a
scope goes missing: `ReferralRepository` is asserted to declare no finder whose name omits `DeedPollId` (the
inherited `findById` cannot be removed, so what is provable is that nothing here reaches a referral without
naming its case), and every controller handler is asserted to take the case id — and both ids, where it
addresses one referral. The no-role-gate assertions run the same four operations as an ordinary caseworker and
as an administrator and require the same answer each time, including the *refusals*: a single caller cannot
tell "no rule" from "a rule this caller happens to satisfy", and the saved-query resource's administrator
override is exactly the thing that must not have leaked in here.

## Deliberately not in this plan

- **Claiming a referral, and the claim's inactivity expiry.** api-2/api-3 — a referral cannot be claimed
  until there is a judge. The `Claimed` state exists in the enum now (decision 3) but nothing transitions
  into it here.
- **Recording a direction** (approved/rejected, directions text, Gazette flag) and the `Decided` state.
  api-2 — this is the judge's half of the form.
- **Closing a returned referral**, and any sync-back to the legacy flat columns. api-2, with the direction
  loop.
- **The shared judicial pool query and the read-through to application details/notes.** api-3.
- **The judicial IDAM role and any role logic.** idam-1; this plan adds none.
- **System-authored timeline notes** (the claim-lapse trail). api-2/api-3, using `NoteAuthor.NONE` — the
  machinery exists; only this plan's *caseworker-authored* create/withdraw notes are here.
- **Automatic referral of minor applications.** A follow-up; `Minor` is a manual reason for MVP (decision
  21).
- **Any UI.** `deed-poll-admin` admin-1, written once this plan's shape is real.
