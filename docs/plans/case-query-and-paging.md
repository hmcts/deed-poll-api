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

- [ ] Parses, validates against `FormFields`, and reports every bad condition at once rather than the first

### Task 2: Specifications

`JpaSpecificationExecutor<DeedPoll>` on the repository, and a translation from `CaseQuery` to
`Specification<DeedPoll>` — one predicate per condition, combined with `and`. Parameters bound by the Criteria
API, never concatenated. `contains`/`startsWith` lower both sides.

Compose with the existing state predicate so `filter` and `where` work together, without touching `ListFilters`.

- [ ] Every operator in decision 5 translated; `filter` composes; the two existing named repository methods untouched

### Task 3: Sort and page

`sort=<field>:<asc|desc>` and 1-based `page` with `size` capped at 100. Default `createdOn:desc`. Reject a
`MULTISELECT` sort field and an unknown sort field with a 400.

- [ ] Defaults reproduce today's behaviour exactly; the size cap cannot be exceeded by any client input

### Task 4: The response

`CaseListResponse` gains `page`, `size`, `total`, `totalPages`. Wire it through the controller.

- [ ] New fields populated correctly, including the last partial page and the empty-result case

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

- [ ] All present, including the multi-error case and the value-with-delimiter cases

### Task 6: Full validation

- [ ] `./gradlew clean build` green — checkstyle across all five source sets, `test` and `integration`
- [ ] **`./gradlew test --rerun`**, never bare `./gradlew test` — a bare run reports `:test UP-TO-DATE`,
      **executes nothing**, and still prints `BUILD SUCCESSFUL`
- [ ] Count from the XML, not Gradle's summary:
      `grep -ho 'tests="[0-9]*"' build/test-results/test/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END{print s}'`
      (`bc` is not installed here)
- [ ] `grep -l 'failures="[1-9]\|errors="[1-9]' build/test-results/test/*.xml` → no output
- [ ] `git diff --name-only master -- src/main/java/uk/gov/hmcts/reform/deedpoll/domain/deedpoll/ListFilters.java src/main/java/uk/gov/hmcts/reform/deedpoll/domain/deedpoll/CaseState.java` returns **nothing**
- [ ] `git status --short -- src/test/resources/reference` clean
- [ ] `git grep -nE '"select |\+ *filter|\+ *field' -- src/main/java/uk/gov/hmcts/reform/deedpoll/domain/deedpoll` shows
      no query built by concatenation
- [ ] Mark completed

> The suite is container-backed and takes about eight minutes. **One red run is not proof of a break** — re-run
> once and read the XML rather than the console.

## Deliberately not in this plan

- **The builder UI.** `deed-poll-admin/docs/plans/08-case-query-builder.md`, which depends on this merging.
- **OR and grouping.** Decision 8 — a tree, not a list, and a different plan if ever wanted.
- **Free-text search across fields.** A single `q=` searching several columns at once is a different feature from
  named conditions, and mixing them would make relevance and validation ambiguous.
- **Saved views.** See the open questions; needs per-user persistence this service does not have.
- **Indexing.** See the open questions; needs data to measure.
- **Anything to do with CCD or Elasticsearch.** The point of decision 2 is that this surface survives that move,
  not that it anticipates it.
