# Plan: a field-level case query, with sorting and paging

## Overview

`GET /caseworker/cases` takes one parameter, `filter`, resolving to one of five ported Dynamics system views.
There is **no search, no field-level query, no sort and no paging** — the endpoint returns every matching case in
`createdOn desc`.

This adds a query surface: **conditions on named case fields**, plus sort and page. It is the api half; the
builder UI is `deed-poll-admin/docs/plans/08-case-query-builder.md` and depends on this.

### Why api and not the UI

`deed-poll-admin/.claude/rules/backend.md` is explicit: *"Ask api to filter, sort and page. It resolves a filter
key; do not fetch a list and narrow it here."* A query built and applied in the browser would also break the
moment paging arrives, because you cannot page a set you have already truncated.

### Keep the surface query-shaped, because the backend will move

Decentralised CCD ships an **Elasticsearch indexer** — Postgres `LISTEN`/`NOTIFY` feeding ES, configured by
`ELASTIC_SEARCH_HOSTS` (`libs/ccd-config-generator` README). When plan 02 lands, search infrastructure arrives
with it. **So the wire format must describe *what* is being asked, never *how* it is answered.** Postgres today,
ES later, and `deed-poll-admin` unchanged either way. That is the same discipline the `filter` key already uses:
the client sends a key, api decides what it means.

## Decisions taken — implement these, do not revisit

1. **Extend `GET /caseworker/cases`; do not add a `POST .../search`.** A search must be bookmarkable and must
   survive the back button — a caseworker returning to a result set is the normal case, not the exception. A GET
   form also produces these parameters natively.
2. **Conditions are repeated `where` parameters, each `field:operator:value`.** Split on the **first two** colons
   only; the value is URL-encoded, so a colon inside a value arrives as `%3A`.
   *Why one repeated name rather than `?type.is=Adult`:* every condition is then enumerable and validatable
   without discovering arbitrary parameter names, and adding a case field never adds a parameter name.
3. **`filter` keeps working, unchanged, and composes with `where`.** The five tabs are ported system views and are
   pinned by `ListFiltersTest`, `DeedPollServiceTest`, `DeedPollRepositoryTest` and `CaseworkerReadControllerTest`.
   `filter=open&where=type:is:Adult` means both. **Do not reimplement the tabs as conditions** — that would change
   what a bookmarked `?filter=open` means.
4. **Validate every condition against `FormFields`, and reject what does not match.** api has its own field
   metadata — 70 `FieldDef`s with `name`, `label`, `kind`, `enumSet` — so it must never trust a client-supplied
   field name, operator or enum member. An unknown field is a 400 with the field named, not silently dropped.
   *This is the security-relevant decision in the plan:* field names reach a query, so an unvalidated name is the
   shape of an injection.
5. **Operators are per `FieldKind`**, and a mismatch is a 400:

   | Kind | Operators |
   |---|---|
   | `TEXT`, `TEXTAREA` | `is`, `contains`, `startsWith`, `isEmpty`, `isNotEmpty` |
   | `DATE` | `is`, `before`, `after`, `between`, `isEmpty`, `isNotEmpty` |
   | `NUMBER` | `is`, `gt`, `lt`, `between`, `isEmpty`, `isNotEmpty` |
   | `ENUM` | `is`, `isOneOf`, `isEmpty`, `isNotEmpty` |
   | `MULTISELECT` | `contains`, `isEmpty`, `isNotEmpty` |
   | `BOOLEAN` | `is` |

   `between` and `isOneOf` take comma-separated values. No text operator takes a list, so a comma in a text value
   is not ambiguous.
6. **Queryable is not the same as editable.** All 70 fields are queryable including the 5 `readOnly` ones —
   `referencenumber` and `state` are among the most useful things to query and neither is editable. Do not reuse
   an "is this writable" check to decide queryability.
7. **`contains` and `startsWith` are case-insensitive.** A caseworker typing a surname should not have to match
   the stored capitalisation. Use `lower(column) like lower(?)`; note this cannot use a plain B-tree index, which
   is a deliberate trade for v1 — see the open question on indexing.
8. **Conditions combine with AND only.** No OR, no grouping, no nesting in v1. A flat AND list covers the real
   caseworker question ("Adult applications received in June still awaiting enrolment") and keeps both the wire
   format and the UI honest. **OR is not a small addition** — it changes the representation from a list to a tree,
   so it is deliberately out of scope rather than deferred vaguely.
9. **Sort: `sort=<field>:<asc|desc>`, one field, default `createdOn:desc`** — today's behaviour, so an existing
   caller sees no change. Sortable = any single-valued kind; `MULTISELECT` is not sortable and is a 400.
10. **Page: `page` is 1-based, `size` defaults to 25 and is capped at 100.** 1-based because the number appears in
    a URL a human reads and shares. The cap is not negotiable by the client: an uncapped `size` is how a list
    endpoint becomes a denial of service against its own database.
11. **`CaseListResponse` gains `page`, `size`, `total` and `totalPages`.** A result set you cannot page through is
    not paged. `filter` and `cases` keep their meaning. `deed-poll-admin` is the only consumer and moves with it.
12. **Use `Specification<DeedPoll>`, not string-built JPQL.** Add `JpaSpecificationExecutor<DeedPoll>` to
    `DeedPollRepository`. Criteria API binds parameters, so a value can never be concatenated into a query. The
    two existing named methods stay exactly as they are — decision 3.

## Open questions — flagged, NOT answered here

- **Indexing.** None of the 70 columns is indexed for this. `lower(col) like ?` needs an expression index per
  searched column to stay fast, and 70 of those is not sensible. **Not resolved because there is no data to
  measure** — nothing is deployed. Revisit when real volumes exist, and note the CCD/ES move may make it moot.
  Recorded so the first slow query is not a surprise.
- **Whether a query should be saveable as a named view.** The Dynamics original had saved views, and the five
  tabs are the ported ones. Out of scope here; it needs a place to persist per-user state, which this service
  does not have.

## Constraints

- **Java 21, `-Werror`, checkstyle on all five source sets.**
- **`export TESTCONTAINERS_RYUK_DISABLED=true`**, or container-backed tests fail in a static initialiser.
- **Do not change `ListFilters`, `CaseState` or `CaseStateInput`.** All are ports pinned by conformance tests.
- **Do not edit `src/test/resources/reference/`.** It is the vendored specification.
- **Never concatenate a request value into a query.** Decision 12.
- **`GET /caseworker/cases` with no new parameters must behave exactly as it does today** — same cases, same
  order. That is what `CaseworkerReadControllerTest` already asserts, and those assertions must not be relaxed.

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

`build` is compile plus checkstyle across all five source sets, plus `test`, plus `integration`
(`build.gradle:113`). **Take the baseline from master immediately before starting** — it was 1,294 tests across
155 suites plus 5 integration at `62c76f4`, but the witness and enrolment work has been landing steadily.

Deliberately **not** here: test counts and baseline comparisons, which are only true once the plan is finished.

## Tasks

### Task 1: The query model

A `CaseQuery` in `domain/deedpoll`: a list of conditions, each a field name, an operator and its values. Parse it
from the repeated `where` parameters, splitting on the first two colons only.

Validate against `FormFields`: unknown field, operator not permitted for that field's kind, wrong value count for
the operator, or an enum value not in the named set — each a `FieldError` naming the offending condition, collected
into one `InvalidInputError` rather than failing on the first.

- [x] Parses, validates against `FormFields`, and reports every bad condition at once rather than the first

`CaseQuery` + `QueryOperator` in `domain/deedpoll`; errors addressed to `where.0`, `where.1`, … because the
parameter repeats and a position is the only thing that identifies which condition was wrong. Two departures
from decision 6, both recorded here so task 2 does not have to rediscover them:

- **68 of the 70 fields are queryable, not all 70.** `daysReceivedToEnroled` and `daysReceivedToReturned` are
  computed at read time and have no column, so there is nothing to query them against — they are refused with
  *"is derived, not stored"*. Queryability is `FormFields.FIELD_BY_NAME` ∩ `DeedPollFields.readableFields()`
  (a new accessor), which is deliberately **not** `isWritable`: all five read-only fields, including
  `referencenumber` and `state`, are queryable exactly as the decision requires.
- **Conditions are capped at `MAX_CONDITIONS` (20)**, for the reason `size` is capped: each one is another
  predicate against a table with no index for it.

A condition's values arrive already coerced to the field's kind (`LocalDateTime`, `BigDecimal`, `Boolean`,
`Integer` for a multiselect option, the validated member *name* for an enum), so task 2 binds them rather than
parsing them. `between` bounds are refused when reversed rather than swapped.

### Task 2: Specifications

`JpaSpecificationExecutor<DeedPoll>` on the repository, and a translation from `CaseQuery` to
`Specification<DeedPoll>` — one predicate per condition, combined with `and`. Parameters bound by the Criteria
API, never concatenated. `contains`/`startsWith` lower both sides.

Compose with the existing state predicate so `filter` and `where` work together, without touching `ListFilters`.

- [x] Every operator in decision 5 translated; `filter` composes; the two existing named repository methods untouched

`CaseSpecifications` in `domain/deedpoll`, with `DeedPollService.findDeedPolls(filter, query)` as the entry
point. Four things task 3 should not have to rediscover:

- **The column is widened, not the value narrowed.** A `NUMBER` condition compares `cast(col as numeric)`
  against the `BigDecimal` task 1 coerced. Narrowing to reach `ageOfMinor` (an `integer`) would have to
  decide what `gt:7.5` means against a column that cannot hold 7.5, and truncating to 7 answers a
  different question with no caller left to tell. It forgoes an index, which none of these columns has.
- **`ENUM` binds the column's own constant**, resolved from the validated member name through
  `Path.getJavaType()`. That is why the translation never names one of the ten enum types.
- **`MULTISELECT` uses Hibernate's `arrayContains`/`arrayLength`**, not raw `array_position` SQL.
  `isEmpty` accepts three shapes of nothing — a NULL column, an empty array, and the NULL that Postgres's
  `array_length` returns for an empty array — because the entity defaults the column to `[]` and the
  schema permits NULL.
- **LIKE wildcards in a caseworker's own value are escaped.** Without it, `contains:100%` would match
  "1000 Others". Same class of surprise as an unvalidated field name, though not a security one.

**An empty query still takes today's path**, so a bare list is the same SQL it always was and the two named
finders keep their consumer. `CaseSpecifications.inState` restates their state criterion — including "a case
with no state counts as open" — and `CaseSpecificationsTest` pins the two statements of it together, per tab,
so the duplication cannot drift. Task 3 needs a total and therefore has to route every request through the
specification; that test is what makes the switch safe.

### Task 3: Sort and page

`sort=<field>:<asc|desc>` and 1-based `page` with `size` capped at 100. Default `createdOn:desc`. Reject a
`MULTISELECT` sort field and an unknown sort field with a 400.

- [x] Defaults reproduce today's behaviour exactly; the size cap cannot be exceeded by any client input

`CaseSort`, `CasePaging` and `CaseResults` in `domain/deedpoll`, with
`DeedPollService.findDeedPolls(filter, query, sort, paging)` as the entry point — the controller is task 4.
Five things task 4 should not have to rediscover:

- **Sortable is not queryable, in both directions.** A `MULTISELECT` field is queryable and refused as a
  sort ("holds several values at once"), because ordering option-value sets means deciding whether
  `{1,3}` precedes `{2}`. The four audit columns are sortable and *not* queryable — `FormFields` does not
  describe them — and `createdOn` has to be among them, or "oldest first" is the one order that cannot be
  asked for. The set is `CaseSort.sortableFields()`, computed from the registries; `id` and
  `displayReference` are deliberately excluded (no list header offers them, and `displayReference` sorts
  as text rather than as the reference numbers it renders).
- **Every order carries an `id` tiebreak.** Without a unique second key Postgres may return two rows with
  equal `createdOn` in either order between two queries, so a row can appear on page 1 *and* page 2 while
  another appears on neither. It refines today's order rather than changing it, and
  `pagesATieDeterministically` pins it.
- **Clamped for too large, refused for unintelligible.** `size=1000` is clamped to 100 (a caller asking
  for as much as they can have); `size=lots` and `page=0` are 400s. `page` and `size` are read together
  and report both errors at once. The record's own constructor refuses an over-cap size, so no caller in
  this codebase can get past the cap either.
- **The bare list now goes through the specification**, because a total is a count of the criterion the
  page came from. The two unpaged `findDeedPolls` methods are untouched and keep their consumers;
  `theDefaultsAreTodaysList` asserts the paged default is the same ids in the same order as the unpaged
  finder.
- **`totalPages` is 0 for an empty result set**, not 1 — "page 1 of 1" would claim a page with no rows on
  it. A page past the end is an empty page and not an error.

`everyFieldPublishedAsSortableReallySorts` sweeps every published sortable name against the real database, which
is what stops a published sort option becoming a 500 on an entity attribute that does not exist.

### Task 4: The response

`CaseListResponse` gains `page`, `size`, `total`, `totalPages`. Wire it through the controller.

- [x] New fields populated correctly, including the last partial page and the empty-result case

`CaseListResponse` gains `page`, `size`, `total`, `totalPages` as flat members, populated from `CaseResults`
through a single `of(filter, results)` factory — the old `of(filter, List)` is gone, so there is no way to
build a response without the paging numbers. Three things task 5 should not have to rediscover:

- **`filter` still binds as a single `String`; `sort`, `page` and `size` are read from lists through
  `single`.** Each of those three changes the answer, and Spring resolves a repeated parameter by silently
  taking the first value — the wrong-answer-as-right-answer failure the `field-rules` parameters already
  guard against. `where` is the one parameter that legitimately repeats. `filter` is left alone deliberately:
  a repeated `filter` arrives as a non-key and falls back to "all", which is pinned behaviour.
- **`filter` resolves; everything else refuses.** A tab's fallback shows a caseworker something, whereas an
  unreadable condition, order or page number has no defensible fallback — answering an unfiltered list to
  `where=stat:is:Received` presents every case as the matches of a query nobody can see was dropped.
- **A bare `GET /caseworker/cases` is now page 1 of 25, not every row.** Same cases in the same order, plus
  the envelope, but the default size is a real change to an unbounded endpoint and is the point of decision
  10. The existing `CaseworkerReadControllerTest` assertions are untouched and green.

The `size` echo is what makes the clamp survivable: a caller that asked for 1,000 and got 100 must be able to
see it, or it reads a full page as the whole result set and stops paging. `CaseListPaging` (8 tests) pins the
bare default, the partial last page, the empty result set at `totalPages` 0, a page past the end, the clamp,
`total` counting the filter's rows rather than the table's, the `where`/`sort` wiring with its 400s, and the
refusal of a repeated `sort`, `page` or `size`.

### Task 5: Tests

Booting the whole context for the HTTP surface.

- **A bare `GET /caseworker/cases` is byte-identical to today** — the regression that matters most
- Each operator, per kind, including `isEmpty`/`isNotEmpty` against a null column
- `between` with reversed bounds, and with one bound — both 400
- Case-insensitivity of `contains` and `startsWith`
- An unknown field, a kind/operator mismatch, and an invalid enum member — each 400 naming the condition
- **Several bad conditions in one request return all of their errors**, not just the first
- `filter=open&where=…` applies both
- Sort ascending and descending; unknown sort field 400; `MULTISELECT` sort 400
- Paging: first, middle, last partial page, a page beyond the end (empty, not an error), `size` over the cap
  clamped rather than honoured
- **A value containing a colon and a value containing a comma** both survive the round trip
- **A field name crafted to look like SQL is rejected by validation**, not passed to a query

- [x] All present, including the multi-error case and the value-with-delimiter cases

Two new nested classes in `CaseworkerReadControllerTest` — `CaseListQuery` (13 tests, 29 of them parameterised
cases in one) and `CaseListOrder` (4) — plus a middle-page test in `CaseListPaging`. They boot the whole
context, as every controller test here must. Three things task 6 should not have to rediscover:

- **The comma case found a real defect in task 4's wiring, now fixed.** `@RequestParam List<String> where`
  cannot carry the wire format: Spring hands a collection-typed parameter the single `String` when a request
  sent one value, and converting a `String` to a `List<String>` splits it on commas — so
  `?where=type:isOneOf:Adult,Minor` arrived as two conditions, the second the bare word `Minor`, and **every
  list-valued operator and every value containing a comma was a 400**. It only bit a request that sent `where`
  exactly once, because two occurrences arrive as an array and are not split, which is why the task 4 wiring
  tests passed. `where`, `sort`, `page` and `size` now come from a `MultiValueMap` populated from
  `getParameterMap`, which does no conversion; `CaseQuery` does the only splitting there is. `filter` is
  untouched.
- **A fixture's state is set explicitly, not derived.** The derivation runs on a write through the service, so
  a case written directly with `dateEnroled` is still `Received` — and would then be admitted by the open tab,
  which is `filter=open&where=…` passing for the wrong reason.
- **The bare-list regression is asserted against the unpaged finder, not a transcription of it.**
  `bareListMatchesTheUnpagedFinder` compares the `cases` node of a bare request with the untouched
  `findDeedPolls(filter)` mapped by the same DTO, through the application's own `ObjectMapper`. "The same as
  today" is a claim about that method, so that method is what it is compared against.

Fixtures are written through `DeedPollFields` after the create (`queryCase`), so a row can carry an
`ageOfMinor` on an Adult case: what is under test is the query, not what a form would have allowed. Every
fixture is backdated to a distinct time, because two cases created in the same millisecond tie on `createdOn`.

### Task 6: Full validation

- [x] `./gradlew clean build` green — checkstyle across all five source sets, `test` and `integration`
- [x] **`./gradlew test --rerun`**, never bare `./gradlew test` — a bare run reports `:test UP-TO-DATE`,
      **executes nothing**, and still prints `BUILD SUCCESSFUL`
- [x] Count from the XML, not Gradle's summary:
      `grep -ho 'tests="[0-9]*"' build/test-results/test/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END{print s}'`
      (`bc` is not installed here)
- [x] `grep -l 'failures="[1-9]\|errors="[1-9]' build/test-results/test/*.xml` → no output
- [x] `git diff --name-only master -- src/main/java/uk/gov/hmcts/reform/deedpoll/domain/deedpoll/ListFilters.java src/main/java/uk/gov/hmcts/reform/deedpoll/domain/deedpoll/CaseState.java` returns **nothing**
- [x] `git status --short -- src/test/resources/reference` clean
- [x] `git grep -nE '"select |\+ *filter|\+ *field' -- src/main/java/uk/gov/hmcts/reform/deedpoll/domain/deedpoll` shows
      no query built by concatenation
- [x] Mark completed

> The suite is container-backed and takes about eight minutes. **One red run is not proof of a break** — re-run
> once and read the XML rather than the console.

**1,488 tests across 185 suites, plus 5 integration, green first time** — no failures or errors in any XML, from
1,294 across 155 suites at the `62c76f4` baseline. `clean build` passed checkstyle on all five source sets.
`ListFilters` and `CaseState` are byte-identical to master and the vendored reference tree is clean. The
concatenation grep matches four lines and **none of them is a query** — they are the text of exception messages
(`operator.wire() + " does not apply to "`, `"Not a sortable case field: " + field`), which is what the pattern
`\+ *field` cannot distinguish from a query fragment. Every predicate is a Criteria API expression, so the plan's
"never concatenate a request value into a query" holds by construction rather than by the grep.

## Deliberately not in this plan

- **The builder UI.** `deed-poll-admin/docs/plans/08-case-query-builder.md`, which depends on this merging.
- **OR and grouping.** Decision 8 — a tree, not a list, and a different plan if ever wanted.
- **Free-text search across fields.** A single `q=` searching several columns at once is a different feature from
  named conditions, and mixing them would make relevance and validation ambiguous.
- **Saved views.** See the open questions; needs per-user persistence this service does not have.
- **Indexing.** See the open questions; needs data to measure.
- **Anything to do with CCD or Elasticsearch.** The point of decision 2 is that this surface survives that move,
  not that it anticipates it.
