# Gazette foundation Task 4 validation

## Provenance and scope

- Date: 2026-10-06.
- Starting source: `88bba9efd41bbdb682554ed934a66feb7505fd8a`, clean worktree.
- Application source is unchanged from that commit. This task adds one OpenAPI
  regression test and updates API documentation, the resume handoff and both plans.
- Baseline source: `c7993140cda28d853fd5a4d729a6cb5aa31f4672`, with the documented
  output-location harness adaptation described in `gazette-1a-task1-validation.md`.
- All commands ran in this worktree. No sibling checkout, remote or deployment
  was changed. No shared `/tmp/openapi-specs.json` artifact was read or written.

## Regenerated contract

The full build regenerates `build/gazette-evidence/task4-openapi.json` through
`OpenAPIPublisherTest`. Its source is the starting commit above plus the test change
in this commit. The retained baseline hash was checked against the Task 1 record.
SHA-256:

- Baseline: `0208a35a4d4accf2120a8a096eb7cad816183920096adc4d3fa2027ca3eeed54`.
- Final: `22ebf3093cd326b2a6835aaa0f02ddf5ff0d3da8ffe2dc0e211c63deecf35ddb`.

Parsed JSON comparison permits exactly six changes from baseline:

1. GET `/caseworker/cases` filter parameter description.
2. `readyForPublication` appended to `CreateSavedQueryRequest`'s filter enum.
3. `readyForPublication` appended to `UpdateSavedQueryRequest`'s filter enum.
4. `/caseworker/gazette-notices`, containing only GET.
5. `GazetteNoticeBatchResponse` schema.
6. `GazetteNoticeHistoryResponse` schema.

The comparison restores just those changed values/removes those additions and
asserts equality with the entire baseline document. Thus it checks all other
parameters, operations, schemas and metadata for unexpected changes. It also
asserts semantic equality with the Task 3 snapshot. Both checks passed. The two
new schemas are the response and its nested batch type; they expose neither the
sender's subject ID nor the request UUID. No POST or send request schema exists.

The new integration test pins the existing filter vocabulary and the appended
publication key in both saved-query write schemas. Existing tests continue to
cover the GET-only history contract, response field sets, role gate, paging,
ordering, frozen references, privacy, ready predicate and PostgreSQL constraints.

Reproducible local comparison and evidence, retained in ignored
`build/gazette-evidence/`: `verify-task4-contract.py`, `baseline-openapi.json`,
`task4-openapi.json`, `task4-openapi-delta.json`, `task4-build.log` and
`task4-counts.json`. Run the comparison with
`python3 build/gazette-evidence/verify-task4-contract.py`.

## Validation

Command, with Java 21 and real PostgreSQL tests enabled:

```sh
JAVA_HOME=/tmp/deed-poll-jdk21 \
TESTCONTAINERS_RYUK_DISABLED=true \
INTEGRATION_TESTS_ENABLED=true \
OPENAPI_SPEC_PATH="$PWD/build/gazette-evidence/task4-openapi.json" \
./gradlew build --rerun-tasks
```

`--rerun-tasks` forces fresh execution, including the unchanged foundation tests
and regenerated OpenAPI, rather than accepting up-to-date results.

The first full build passed in 3m 12s; all 24 Gradle tasks executed. Required
Checkstyle checks passed. Counts were summed from the fresh JUnit XML reports:

| Stage | Test source set | Integration source set | Failures / errors / skips |
|---|---:|---:|---|
| Baseline | 3,614 | 14 | 0 / 0 / 0 |
| Task 1 | 3,619 | 14 | 0 / 0 / 0 |
| Task 2 | 3,629 | 14 | 0 / 0 / 0 |
| Task 3 | 3,641 | 15 | 0 / 0 / 0 |
| Task 4 final | 3,641 | 16 | 0 / 0 / 0 |

PostgreSQL-backed repository and controller tests in the `test` source set ran as
well as the separate integration suite. The Gazette repository suite ran eight
tests and its controller suite twelve, all passing without skips. No tests were
disabled or weakened, and no validation failure required a retry. Foundation
coverage is green; the added integration test accounts for the increase from Task 3.

## Handoff boundary

README now describes the seven filter keys, publication ordering, access rules,
history paging/shape, frozen attribution and local OpenAPI output override.
The parent plan remains active with only Tasks 1–3 checked. Spreadsheet layout,
Notify wording/provisioning and ready-predicate reconfirmation remain unchecked.
The send and full-contract validation tasks remain unchecked as well.

The parent now explicitly qualifies its transaction proposal: database rollback
cannot undo Notify acceptance, and the request-ID unique index alone cannot settle
an accepted send whose response or database commit was lost. Evidence retention,
reconciliation and retry policy must be agreed before send implementation. This
task chooses no recovery design and makes no all-or-nothing delivery guarantee.
There is no production audit writer, spreadsheet or Gazette Notify configuration.
The full admin plan 16 contract remains unavailable until batch send is implemented
and validated; completion here is foundation completion only.
