# Gazette foundation Task 1 validation

## Baseline provenance

- Date: 2026-10-06.
- Source: `c7993140cda28d853fd5a4d729a6cb5aa31f4672`, initially clean worktree.
- Application and test assertions unchanged for the baseline. The sole harness change
  was an optional `OPENAPI_SPEC_PATH` environment override in `OpenAPIPublisherTest`,
  retaining `/tmp/openapi-specs.json` as the default. This prevents cross-worktree
  snapshot collisions. The baseline is therefore clean application source with this
  documented output-location adaptation, not a byte-for-byte unchanged checkout.
- Java 21: `JAVA_HOME=/tmp/deed-poll-jdk21`; `TESTCONTAINERS_RYUK_DISABLED=true`;
  `INTEGRATION_TESTS_ENABLED=true`; Docker-backed PostgreSQL enabled.
- `./gradlew build`: passed. `test`: 3,614 tests; `integration`: 14 tests;
  zero failures, errors or skips in both tasks. Checkstyle passed.
- Local evidence is retained under this worktree's ignored `build/gazette-evidence/`:
  `baseline-build.log`, `baseline-counts.json`, `baseline-openapi.json`.
- Baseline OpenAPI SHA-256:
  `0208a35a4d4accf2120a8a096eb7cad816183920096adc4d3fa2027ca3eeed54`.

## Implementation and contract comparison

The filter requires publication requested, no notice date and state Enroled. It
orders by `dateEnroled:asc` unless the HTTP caller supplies a sort. The existing
unpaged service path uses the same predicate and default order. Regression tests
cover each predicate term (including null flags and states and every other state),
HTTP ordering, explicit sort, query/search composition and repeated-filter fallback.
Existing tests retain coverage of the other filters and unknown-key fallback.

The first implementation build stopped on three Checkstyle variable-distance
warnings in new tests; making those fixture references final resolved them.

The semantic comparison found exactly three changes:

1. GET `/caseworker/cases` filter parameter description.
2. `CreateSavedQueryRequest.properties.filter.enum` appends `readyForPublication`.
3. `UpdateSavedQueryRequest.properties.filter.enum` appends `readyForPublication`.

The two enum changes are automatically generated from `ListFilters.filterTabs()`
by `OpenAPIWriteBodies.filterKeys()`. Saved-query validation likewise accepts every
list filter, with existing tests pinning that behavior. Suppressing those schema
additions would misdescribe accepted input; refusing the new filter in saved queries
would change the existing shared-filter behavior. On 6 October 2026 the execution
and parent plans corrected the description-only expectation to these exact three
changes. The retained snapshots have been compared again against that corrected
expectation: both enum lists only append `readyForPublication`, and no other
semantic differences exist. This resolves the technical contract discrepancy; it
does not provide new business sign-off.

The snapshots and exact before/after values are local files
`task1-openapi.json` and `task1-openapi-delta.json` in the same evidence directory.
No operation, response schema or unrelated contract field changed.

Spreadsheet layout, Notify wording and uncertain-send recovery remain unresolved.
A database rollback cannot undo Notify accepting an email. No send-path decision
is made by this filter task, and the parent plan's send tasks remain pending.

## Final build result

`./gradlew build` passed after the Checkstyle fixes, including PostgreSQL tests.
`test`: 3,619 tests (+5); `integration`: 14 tests; zero failures, errors or skips.
The successful log is `build/gazette-evidence/task1-build-retry.log`; counts are
`task1-counts.json`. All required Checkstyle checks passed.

Task 1 OpenAPI SHA-256:
`b466f4a178c2cf9699e8fc349ffdf945cb394538ffe3a5876c1e3891a0164466`.

Task 1 is complete against the corrected three-change contract expectation.
The retained successful build log, XML test counts and both snapshot hashes were
verified on resumption. No implementation changed during reconciliation, so the
passing full build was retained as explicitly directed by the execution plan.
Both plans now mark Task 1 complete; later tasks remain unchecked.
