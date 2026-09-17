# Plan: saved and shared queries, and a case search

## Overview

`case-query-and-paging.md` left this in its open questions, and named the reason:

> **Whether a query should be saveable as a named view.** The Dynamics original had saved views, and the five
> tabs are the ported ones. Out of scope here; it needs a place to persist per-user state, which this service
> does not have.

**This plan gives it that place.** `deed-poll-admin` owns no database and never will — it is a presentation layer,
and its only local state is Redis sessions, which are transport state. A *shared* query is definitively not
session state: one user's write is another user's read. So the persistence lands here.

Two things, deliberately in one plan because they are one caseworker journey — finding cases and keeping the ways
you find them:

1. **Saved queries**, with a name and description, publishable to a library anyone can browse, added to and
   removed from each user's own list.
2. **A case search** — a single term matching a reference exactly or a name loosely — composing with the existing
   `filter` and `where`.

Requirements confirmed with Steve on 2026-09-16. The admin-side consumption is `deed-poll-admin`'s plan 11, which
is deliberately unwritten until this plan's shape is real.

### The sharing model, because it drives the schema

**A library, not a push.** Saving a query does not put it in anyone else's list. Publishing makes it *findable*;
each user chooses which found queries appear in their own navigation. Pushing a query at a named colleague is a
link in Slack — a query is already a URL, which is what decision 1 of the existing query plan bought.

**Why not named recipients**, which was the original ask: api has **no user directory**. Eleven paths, all
`/caseworker/cases*` and `/caseworker/document-types`; no user list, no whoami, no account schema. "Share with
Jane" needs a searchable list of Janes, which means a new capability backed by IDAM and a conversation with
another team. The library plus a link delivers the same outcome, handles sharing with a *group* for free, and
adds no cross-team dependency. Named recipients remain an addition to the same table later, not a redesign.

## Decisions taken — implement these, do not revisit

1. **A saved query is a *live reference*, not a copy.** Subscribers see the owner's edits. A caseworker who adds
   "Enroled this month — Ops" wants the Ops team's definition to stay current, and copy-on-add turns one shared
   query into nine diverging ones inside a month, which is the failure mode of the spreadsheets this service
   replaces. **Duplication is the escape hatch** and is deliberate rather than default — see decision 3.
2. **Only the owner may edit or delete a query — and an administrator may edit or delete any *shared* one.**
   Owner-only editing freezes a query the moment its owner goes on leave, changes team or leaves, while everyone
   subscribed carries on relying on it. Leave and secondment are far more common than departure, so any answer
   that only handles leavers answers the rare case. `DEEDPOLL_ADMIN` already exists and api already derives it
   from the token — this is the witness-delete pattern applied to a new resource, not a new permission model.
   **Refinement of what was first proposed, and deliberate:** the admin override covers **shared** queries only.
   An unshared query is nobody else's business, and an orphaned private query harms nobody because nobody else
   can see it. Do not extend the override to unshared queries "for consistency".
3. **Duplication is a client-side compose, not an endpoint.** `GET` one, `POST` a new one with a new name. There
   is no server-side invariant to protect, so an endpoint would add surface for nothing.
4. **The owner is a stable id plus a display name captured at save time.** `ownerId` from the token's subject,
   `ownerName` as it read when saved. A departed user's query then still says who owned it instead of rendering
   blank. api already stamps `createdBy`/`modifiedBy` as strings from the token; this follows that.
5. **A saved query stores `filter`, the conditions, `sort` and the search term. It does not store `page`.** Those
   are exactly the parts of the list URL that define a view. Page is already dropped when a query is applied —
   page 4 of a previous result set is not a page of this one, and a saved query reopening on page 4 of results
   that have since changed is worse than useless.
6. **Conditions are stored as `jsonb` and validated on write by the same validator that validates a live query.**
   Not a child table: ordering and a join buy nothing here, and one table keeps the resource atomic.
   **Validated on write, not on read** — a query whose field was later renamed fails when it is *run*, with api's
   existing 400, which is the same error the caseworker would get from a hand-edited URL. Do not add a
   re-validation pass on read; it would put the cost of every rename on every list load.

   **The stored shape is a JSON array of the wire strings, not structured objects.** The live-query validator is
   `CaseQuery.parse(List<String> where)` (`CaseQuery.java:122`), and it takes the raw repeated `where` parameter —
   `field:operator:value`, split on the first two colons. So `conditions` is `["state:in:ENROLED,SUBMITTED", …]`:
   exactly what the URL carried. Reuse is then literal — `CaseQuery.parse` called on the deserialised array, with
   no adapter, no second parser and no mapping layer — and a saved query round-trips a list URL byte for byte,
   which is what makes decision 3's client-side duplicate a `GET` then a `POST` of the same value.

   **Do not model conditions as structured JSON objects** (`{field, operator, values}`). It looks tidier and it
   makes "the same validator" impossible: `parse` would have to be split, or a second validator written, and
   decision 6's whole point is that there is one. It would also mean the stored form and the URL form can drift.
   `sortField`/`sortDirection` are stored decomposed rather than as `CaseSort`'s `field:asc` wire string only
   because they are two bounded scalars worth querying separately; `CaseSort.parse` (`CaseSort.java:127`) is still
   the validator, so recompose the wire string and hand it that rather than re-checking the parts by hand.
7. **Selection is a separate sub-resource, not a field on the query.** `PUT` and `DELETE` on
   `/caseworker/saved-queries/{id}/selection`, idempotent both ways. A `PATCH` on the query itself would mean
   *"editing the query"*, which is the owner's right — whereas choosing to see it is every user's right. Keeping
   them separate is what stops "add to my list" needing edit permission.
8. **The search term is one parameter, `q`, on the existing `GET /caseworker/cases`.** Extend, do not add
   `POST .../search` — same reasoning as decision 1 of the existing query plan: a search must be bookmarkable and
   survive the back button.
9. **`q` matches a reference exactly, or names loosely, and the two are ORed.** References: `displayReference` and
   `referencenumber`. Names: `newForename`, `newSurname`, `oldForename`, `oldSurname`, `parents`,
   `representativeName`. **Not addresses and not narrative** — those are excluded from the query builder too, for
   the same reason.

   **The two reference columns are different types and must be matched differently.** `displayReference` is `text`
   (`V2__deed_poll.sql:28`) and holds the rendered `{prefix}{year}-{serial}` form, so it is the one that takes the
   case-, hyphen- and whitespace-tolerant normalisation — `kbdp2026-10100` and `KBDP202610100` both finding the
   same case is a statement about *this* column. `referencenumber` is `integer` (`V2__deed_poll.sql:27`,
   `DeedPoll.java:80`) and holds only the serial component: **do not apply `lower()` or hyphen-stripping to it.**
   Match it only when the whole normalised term parses as an integer, and compare numerically. A term that does
   not parse contributes no `referencenumber` predicate at all rather than a cast that Postgres would refuse.
10. **`q` ANDs with `filter` and `where`.** So "Smith, in Enroled, received in June" works. `q` is ORed
    internally across the fields in decision 9 and ANDed as a whole — get the bracketing right, because an
    accidental flat OR makes every condition ineffective and still returns plausible-looking rows.
11. **`CaseListResponse` gains `referenceMatch`** — the id of the case whose reference the term matched exactly, or
    null. A caseworker who types a reference wants **the case**, not a list of one, and the UI must not have to
    infer that intent from `total == 1`. api states the fact; the UI decides to redirect.
12. **v1 matching is case-insensitive substring, not fuzzy.** Real typo tolerance ("Smyth" finding "Smith") needs
    `pg_trgm`, which on Azure Flexible Server must be allowlisted through the `azure.extensions` server parameter
    first — see the sequencing note below. **Build the interface against substring matching and keep the matcher
    behind one function**, so swapping it for trigram similarity later changes nothing else. Agreed with Steve
    2026-09-16.
13. **`CaseSummary` gains `newSurname`.** Today it carries Reference, Privacy, Received, Type, State and New
    forename — exactly `CASE_LIST_COLUMNS`. **Without this, a search that matches on surname returns a table that
    cannot show the surname it matched**, so a caseworker searching "Smith" gets a list of forenames. Small change,
    but it is a correctness requirement of decision 9 rather than a nicety.
14. **Name and description are bounded, and never interpolated into a query.** Name required, sensible maximum;
    description optional. They are user text rendered inside a navigation bar in another service, so length limits
    belong here rather than only in the UI. Use `Specification`, never string-built JPQL — decision 12 of the
    existing query plan, and it applies to `q` most of all, because a search term is the most obviously
    attacker-controlled value this service accepts.

## The endpoints

Seven new operations, taking the caseworker surface from **18 to 25**:

| Method | Path | Who |
|---|---|---|
| `GET` | `/caseworker/saved-queries` | any caseworker — returns shared queries plus their own, each flagged with whether it is selected |
| `POST` | `/caseworker/saved-queries` | any caseworker |
| `GET` | `/caseworker/saved-queries/{id}` | any caseworker, if shared or theirs |
| `PATCH` | `/caseworker/saved-queries/{id}` | owner, or `DEEDPOLL_ADMIN` if shared |
| `DELETE` | `/caseworker/saved-queries/{id}` | owner, or `DEEDPOLL_ADMIN` if shared |
| `PUT` | `/caseworker/saved-queries/{id}/selection` | any caseworker who can see it — idempotent |
| `DELETE` | `/caseworker/saved-queries/{id}/selection` | as above — idempotent |

`q` is **not** a new operation; it is a parameter on the existing list.

## Open questions — flagged, NOT answered here

- **Ordering within a user's selection.** Not asked for, so v1 orders by name. Reordering needs a position column
  per selection and a reorder operation; recorded so adding it later is a known small change rather than a
  surprise.
- **Deleting a shared query that others have selected.** Decision 2 permits it. Whether the owner should be
  *warned* — "three people have this in their list" — is a content and UX question, and it needs a count api does
  not currently expose. Worth deciding before plan 11 renders a delete button.
- **Indexing.** Inherited unresolved from the existing query plan, and `q` makes it sharper: a
  `lower(col) like '%term%'` over six columns cannot use a B-tree at all. Still nothing deployed and no volumes to
  measure. `V4__list_indexes.sql` exists, so there is a place for it when there is evidence.
- **Whether an administrator should see unshared queries in order to tidy them.** Decision 2 says no on privacy
  grounds. If retention or records management later needs it, that is a deliberate change with a reason, not a
  consistency fix.

## Sequencing — one dependency outside this repo, and it is not blocking

**Fuzzy matching needs an infra change that has not been made.** `deed-poll-infra/postgres.tf` passes no server
configuration, so the module default applies (`backslash_quote` only) and no extension is allowlisted.
`CREATE EXTENSION pg_trgm` will fail. The module *does* support it —
`terraform-module-postgresql-flexible`'s `pgsql_server_configuration` takes a list of `{name, value}` — so it is a
few lines in `postgres.tf`, not a module change or a PlatOps request. The cost is the **apply**, which took three
PRs and three distinct failures on 15 September.

**Decision 12 exists so this plan does not wait for it.** Substring matching needs no extension. Do not add the
extension as part of this plan.

## Constraints

- **Java 21, `-Werror`, checkstyle on all five source sets.**
- **`export TESTCONTAINERS_RYUK_DISABLED=true`**, or container-backed tests fail in a static initialiser.
- **The next migration is `V5__`.** `V1__enums`, `V2__deed_poll`, `V3__children`, `V4__list_indexes` are
  committed. **Never edit a committed migration** — it breaks every environment that has already run it.
- **Column names are mixed case** (`newForename`), because the reference schema's are. New tables follow that;
  `globally_quoted_identifiers` and `PhysicalNamingStrategyStandardImpl` are load-bearing. Enum columns need
  `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`; the `jsonb` column needs `@JdbcTypeCode(SqlTypes.JSON)`.
- **Flyway owns the schema; Hibernate validates against it** (`ddl-auto: validate`).
- **Never return an entity from a controller.** Request and response DTOs per operation under `api/…/dto`.
- **A controller test must boot the whole context.** A `@WebMvcTest` slice does not get `SecurityConfiguration`.
- **Do not edit `src/test/resources/reference/`.** It is the vendored specification. Saved queries are **not** in
  it — they are new behaviour, not a port — so do not look for a reference implementation and do not add one.
- **`GET /caseworker/cases` with no `q` must behave exactly as it does today.** `CaseworkerReadControllerTest`
  asserts that and those assertions must not be relaxed.
- **Never concatenate a request value into a query**, `q` above all.
- **New container-backed tests will not run in CI.** `INTEGRATION_TESTS_ENABLED=false` on the Jenkins agent
  excludes 609 tests in 22 classes, because the agent has no Docker. A new repository test extending
  `AbstractPostgresContainerTest` inherits the tag and is excluded too. **That is accepted, not forgotten** —
  see `docs/plans/completed/tag-container-backed-tests.md`. Put as much of the permission logic as possible in
  plain unit tests that need no container, precisely because those are the ones CI will actually run.

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

`build` is compile plus checkstyle across all five source sets, plus `test`, plus `integration`
(`build.gradle:113`).

**The baseline is measured, on this branch, at the point this plan starts:** `./gradlew build` green with
**1,488 unit tests in 185 classes** and **6 integration tests in 3 classes**. Measured 2026-09-17 by summing
`tests="…"` across `build/test-results/{test,integration}/*.xml`, which is how Task 7 should re-measure rather
than reading a Gradle summary line. Note the integration figure: `tag-container-backed-tests.md` said 5, and it is
6 — so re-measure any number this plan or another quotes, including this one.

**A worker or container startup timeout is an environment failure, not a code failure.** This devcontainer's
filesystem is slow enough that process startup races time out — the sibling repo's vitest suite failed 22 workers
this way on 2026-09-16 while every test passed. If the build fails with a startup or "timeout waiting for" error
rather than an assertion, **re-run before investigating**, and consider running from a `git archive` copy under
`/tmp`, which has measured an order of magnitude faster on this mount.

## Tasks

### Task 1: Schema and entities

`V5__saved_queries.sql`: two tables, **lower-case prefixed table names and mixed-case columns**. Both halves of
that matter and they come from different reasons — see the naming note below before writing the DDL.

`deedpoll_savedquery` — `id` uuid pk, `name` text not null, `description` text null, `shared` boolean not null
default false, `ownerId` text not null, `ownerName` text not null, `filter` text null, `conditions` jsonb not null,
`sortField` text null, `sortDirection` text null, `searchTerm` text null, plus the audit columns this schema
already uses.

`deedpoll_savedqueryselection` — `savedQueryId` uuid not null referencing `deedpoll_savedquery` **on delete
cascade**, `userId` text not null, composite primary key of the two. Cascade matters: decision 2 allows an owner to
delete a query others have selected, and orphaned selection rows would resurface as broken navigation entries.

**On the naming, because the two conventions here have different justifications and an earlier draft of this plan
got it wrong.** Table names follow the existing six exactly — `deedpoll_`-prefixed, all lower case
(`deedpoll_deedpoll`, `deedpoll_notes`, `deedpoll_witness`, `deedpoll_supportingdocument`,
`deedpoll_supportingdocumenttype`, `deedpoll_referencecounter`). **Columns stay mixed case**, because
`globally_quoted_identifiers` and `PhysicalNamingStrategyStandardImpl` are set service-wide and apply to these
tables too: a `snake_case` column here would be quoted as written and then not found. So this is not a case of
copying the reference schema — saved queries are new behaviour with nothing in the reference to port — it is that
the *mechanism* enforcing mixed-case columns is global, whereas nothing mechanical constrains a table name and
consistency with the existing six is therefore the whole argument. Agreed with Steve 2026-09-17. **Implement, do
not revisit**, and in particular do not "correct" the table names to match the column style.

"the audit columns this schema already uses" means, exactly, the four at `V2__deed_poll.sql:141-144`:
`"createdOn" timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP`, `"modifiedOn" timestamp(3) NOT NULL` with **no**
default, `"createdBy" varchar(200)` and `"modifiedBy" varchar(200)`. `modifiedOn` is deliberately not defaulted —
the application always writes it — so copy that rather than adding a default. Note `createdBy`/`modifiedBy` hold a
display name, which is why decision 4's `ownerId` is a *separate* column and not a reuse of `createdBy`.

Entities under `entity/`, repositories under `repository/`, both following the existing pattern.

**The readiness check is a list that must be edited, not a check that discovers tables.** `DeedPollSchema.java:49`
holds the six expected table names literally; add both new tables there or readiness will pass while describing a
schema it is no longer checking.

- [x] `V5__saved_queries.sql` added and no committed migration edited; both entities validate against it under
      `ddl-auto: validate`; audit columns match `V2`'s types and null/default choices exactly; `conditions` mapped
      with `@JdbcTypeCode(SqlTypes.JSON)`; selection cascades on delete; both new tables added to
      `DeedPollSchema`'s expected list and readiness green

### Task 2: The domain model and write-time validation

`domain/savedqueries/`: the saved-query type, and validation of a submitted one. **Reuse the existing validators,
do not write second ones.** Decision 6: validate on write, not on read.

The validators to call, by name, because "the existing one" has been ambiguous once already:

- **Conditions** — `CaseQuery.parse(List<String> where)` (`CaseQuery.java:122`), on the deserialised jsonb array.
  It already enforces the 20-condition cap (`MAX_CONDITIONS`) and already throws `InvalidInputError` carrying one
  `FieldError` per bad condition, addressed to `where.{index}`. That is the 400 shape; do not rebuild it.
- **Sort** — `CaseSort.parse(String sort)` (`CaseSort.java:127`), given the recomposed `field:direction` string.
  `CaseSort.sortableFields()` is the authority on which fields are sortable; do not duplicate that set.
- **Filter** — `ListFilters.filterFor(Object key)` (`ListFilters.java:81`), against `LIST_FILTERS`.

Name required and bounded; description optional and bounded — those two are the only genuinely new validation here.

- [x] Conditions validated by `CaseQuery.parse` itself and sort by `CaseSort.parse`, neither reimplemented nor
      wrapped in a second parser; name/description bounds enforced; an unknown filter key, an unsortable sort field
      and an invalid condition each 400 with the field named; the condition cap applies **because `CaseQuery`
      applies it**, not via a new check; unit tests needing no container for all of it

### Task 3: The CRUD endpoints and their permissions

`api/caseworker/controller` and `api/caseworker/dto`: the five query operations from the table above.

Visibility on read: a query is visible if `shared` or `ownerId` is the caller. `GET` list returns visible queries
each flagged with whether the caller has selected it. Edit and delete: owner, **or** `DEEDPOLL_ADMIN` when the
query is shared — decision 2, including the refinement that the override does not reach unshared queries.

**On the ordering guard, and why this resource inverts the witness-delete one.** Witness delete could check
permission before resolving the entity because its check is a *role* check — entity-independent, so it can run
first. **A saved query's permission is a property of the entity** (`shared`, and whether `ownerId` is the caller),
so it cannot be checked before the entity is read. Do not attempt it, and do not invent a role that would make it
possible.

The property that guard was protecting is still required, and is achieved the other way round: **resolve, then
refuse — and return the same status for "invisible" as for "absent".** A caller who cannot see a query gets
**404**, identically to a caller naming an id that has never existed, so the two are indistinguishable and no id is
discoverable. Concretely:

- Not visible (not shared, not theirs) → **404**, for every one of the five operations.
- Visible but not editable (someone else's shared query, caller is not an admin) → **403**. The caller can already
  see it, so there is nothing left to conceal, and 404 here would be a lie a client cannot act on.
- Visible, shared, caller is `DEEDPOLL_ADMIN` → permitted for edit and delete.
- Visible, unshared, caller is `DEEDPOLL_ADMIN` but not the owner → cannot arise: an admin does not see another
  user's unshared query at all, so it is the 404 case. Test it as such, and note the visibility rule is what
  enforces decision 2's refinement — there is no second check to write.

**Implement this, do not revisit.** Both statuses are deliberate and a reviewer who has not read this paragraph
will read the 404 as a bug.

- [x] Five operations; visibility is shared-or-mine; edit/delete allow owner and allow admin **only when shared**;
      an admin naming another user's unshared query gets 404 and cannot distinguish it from an id that never
      existed; a visible-but-not-editable query gets 403; a caller with no roles fails closed; DTOs not entities

### Task 4: Selection

`PUT` and `DELETE` on `/caseworker/saved-queries/{id}/selection`, both idempotent — a second `PUT` is not a
conflict and a `DELETE` of something unselected is not a 404. Permitted for any caller who can see the query,
which deliberately includes queries they do not own and may not edit (decision 7).

Selecting a query that is later unshared by its owner: the selection row survives, and the query stops being
visible. Decide and test which it is — **the caller should stop seeing it**, because visibility is decision 3's
rule and a selection is not a grant.

**As built, the two verbs differ on visibility, which is a deliberate refinement of "permitted for any caller who
can see the query" above.** `PUT` requires visibility and 404s without it, because selecting must not be a way to
discover a private query. `DELETE` requires none and answers 204 for any id: the row is keyed by the caller, so
the delete reaches one row that is theirs by construction and no check could make it safer — the scoping is in
the key, not in a guard. What a check *would* add is a state a caller cannot leave, and it is exactly the state
the paragraph above creates: a query unshared after selection leaves a row its owner may re-share at any time,
resurfacing the query in a list the caller removed it from, and requiring visibility to unselect is what would
stop them clearing it. Nothing is disclosed, because the answer does not vary with the id. Both halves are
tested.

- [x] `PUT`/`DELETE` idempotent both ways; permitted without edit rights; selecting a query the caller cannot see
      is refused; a query unshared after selection stops appearing for the subscriber; selections are per-user and
      one user's cannot be read or written by another

### Task 5: Search on the case list

Extend `GET /caseworker/cases` with `q`. Decisions 8 to 12.

Reference match on `referencenumber` and `displayReference`, normalising case, hyphens and surrounding whitespace.
Name match, case-insensitive substring, on `newForename`, `newSurname`, `oldForename`, `oldSurname`, `parents`,
`representativeName`. The two ORed; the whole ANDed with `filter` and `where`.

**Keep the matcher in one function** so decision 12's substring-to-trigram swap touches nothing else.

`CaseListResponse` gains `referenceMatch` (decision 11). `CaseSummary` gains `newSurname` (decision 13).

**Get the bracketing right.** A flat OR across `q` and the `where` conditions returns plausible rows while making
every condition ineffective — write a test that would fail if the grouping were flattened, not only one that
passes when it is correct.

- [ ] `q` ORs across the six name fields and the two reference fields, ANDs with `filter` and `where`; a test that
      fails if the bracketing is flattened; reference match tolerant of case and hyphens; `referenceMatch` set on
      an exact reference hit and null otherwise; `newSurname` on `CaseSummary`; `GET` with no `q` byte-identical
      to today; matching isolated in one function; `Specification` only, nothing concatenated

### Task 6: Security and the spec

Security tests in the pattern `*Security*` already uses: namespace separation, a caller with no roles, one user
reaching another's selections, and an unshared query reached by a non-owner.

Then the published document. **`OpenAPIPublisherTest` asserts the caseworker operation count outright**, so it
fails if this is missed — which is the point. The count moves 18 → 25.

**Updating the number alone will not make it pass.** The count is summed *over a hard-coded path list* —
`CASEWORKER_PATHS` at `OpenAPIPublisherTest.java:61`, ten entries today — and a second test asserts the published
document contains all of them (`:96`). Three paths are new and must be added to that list, or the sum silently
misses all seven operations and the assertion fails against 18 no matter what number is written:

```
/caseworker/saved-queries
/caseworker/saved-queries/{id}
/caseworker/saved-queries/{id}/selection
```

The list-and-count pairing is deliberate — the comment at `:113` explains that neither alone can distinguish a new
verb on a known path from a new path — so update both, and keep the comment's arithmetic true rather than leaving
it describing eighteen.

**Grep for the number rather than trusting a list.** It is stated in at least six places: `OpenAPIPublisherTest`
(both the path list and the `@DisplayName`), `api/caseworker/controller/package-info.java`, `README.md`,
`CLAUDE.md`, and two files under `docs/plans/`. The witness plan's enumeration of these was incomplete and only
the instruction to grep kept it from going stale:

```bash
git grep -inE '\b(18|eighteen|25|twenty-five)\b'
```

- [ ] Security tests for namespace separation, no-roles, cross-user selection and unshared access; the spec
      publishes all seven new operations; every stated operation count updated by grepping, not from a list;
      `OpenAPIPublisherTest` green

### Task 7: Full validation

- [ ] `./gradlew build` green from cold with `TESTCONTAINERS_RYUK_DISABLED=true`; checkstyle clean on all five
      source sets; test and integration totals recorded next to the baseline; the count with
      `INTEGRATION_TESTS_ENABLED=false` also recorded, so it is visible how much of this plan's coverage CI
      actually runs

## Deliberately not in this plan

- **`pg_trgm` and true fuzzy matching.** Needs the `azure.extensions` allowlist and an infra apply. Decision 12
  keeps the swap to one function.
- **Sharing with named individuals.** No user directory exists. Additive to the same table later.
- **A duplicate endpoint.** Decision 3 — the client composes `GET` then `POST`.
- **Reordering a user's selected queries.** Open question; v1 orders by name.
- **Warning an owner that others have selected a query they are deleting.** Needs a count api does not expose,
  and a content decision.
- **Any UI.** `deed-poll-admin` plan 11, written once this plan's shape is real.
- **Indexing for `q`.** Nothing deployed, no volumes to measure.
