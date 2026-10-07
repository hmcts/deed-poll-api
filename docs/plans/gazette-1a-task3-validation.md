# Gazette foundation Task 3 validation

## Current-tree reruns (2026-10-07)

The results, hashes and no-shared-artifact statements below describe the original
run with the then-present output override. The command below is updated for the
current fixed `/tmp/openapi-specs.json` publisher: it removes stale output, forces
execution and copies only after success. Run without another publisher using the
same temporary path. Preserve historical snapshots before rerunning; current output
includes the bounded reference preview and `referencesTruncated` contract amendment.
Historical hashes and equality with the original Task 3 snapshot no longer apply.

## Provenance and scope

- Date: 2026-10-06.
- Starting source: `baacbc85c1be2dd2e6eebf57555ba1d6ab57340b`, clean worktree.
- The clean-source baseline remains recorded in `gazette-1a-task1-validation.md`:
  3,614 test / 14 integration. Task 2 finished at 3,629 / 14.
- Only foundation Task 3 and parent Task 3 are newly complete.

`GET /caseworker/gazette-notices` requires `Role.DEEDPOLL_ADMIN` in the domain
service. Refusal logs a fixed reason without identifying the caller. The controller
returns separate response DTOs containing `batches`, `page`, `size`, `total` and
`totalPages`; each batch contains `sentAt`, `sentBy`, `caseCount` and
`referenceNumbers`. Subject IDs and request IDs are absent.

The endpoint uses the existing 1-based `CasePaging` parser: 25 rows by default,
capped at 100, with invalid values refused. Batches sort by send time descending,
then UUID descending for stable ordering when timestamps match. Frozen membership
references are loaded in one query for the requested page and sorted by reference;
they are never reconstructed from current case data. No custom filter or sort is
exposed. No migration, sending operation or Notify configuration was added.

## Validation

Command, from this worktree:

```sh
set -eu
mkdir -p build/gazette-evidence
rm -f /tmp/openapi-specs.json build/gazette-evidence/task3-openapi.json
JAVA_HOME=/tmp/deed-poll-jdk21 \
TESTCONTAINERS_RYUK_DISABLED=true \
INTEGRATION_TESTS_ENABLED=true \
./gradlew build --rerun-tasks
test -s /tmp/openapi-specs.json
cp /tmp/openapi-specs.json build/gazette-evidence/task3-openapi.json
```

Final full build: successful, in 2m 48s. `test`: 3,641 (+12 from Task 2);
`integration`: 15 (+1). Zero failures, errors or skips in both source sets.
Required Checkstyle checks passed. Real PostgreSQL tests were enabled throughout;
IDAM alone was stubbed for the new full-context controller tests.

The twelve new controller test cases cover authentication, three authenticated
non-admin roles, empty history/default paging, frozen attribution and references,
exact response field sets and absence of private identities, newest-first ordering,
batch-scoped references, pagination beyond the last page, equal-timestamp ordering,
the size cap and invalid page/size values. Fixtures amend live case references to
prove that the response uses the audit record. The additional integration test
pins the single GET operation and its public schemas.

The first build exposed the existing exact OpenAPI path inventory needing the new
route. That inventory now includes the admin-only route, with its sole GET asserted
separately. The second build completed with three fixture failures: the new tests
assigned the same amended display reference to multiple cases, violating the real
unique constraint. Each amended fixture reference is now unique. The final build
passed without disabling or weakening any test.

Ignored local evidence under `build/gazette-evidence/`:
`task3-build.log`, `task3-build-retry.log`, `task3-build-final.log`,
`task3-counts.json`, `task3-openapi.json`, and `task3-openapi-delta.json`.

## Contract and handoff

Parsed JSON comparison against the retained Task 2 document adds exactly:

- `/caseworker/gazette-notices`, containing only GET;
- `GazetteNoticeBatchResponse`;
- `GazetteNoticeHistoryResponse`.

No existing contract changes. SHA-256:

- Task 2: `b466f4a178c2cf9699e8fc349ffdf945cb394538ffe3a5876c1e3891a0164466`.
- Task 3: `22ebf3093cd326b2a6835aaa0f02ddf5ff0d3da8ffe2dc0e211c63deecf35ddb`.

The Task 3 snapshot was generated from the starting source above plus the code in
this commit, and retained inside this worktree; no shared `/tmp/openapi-specs.json`
artifact was used. Task 2 provenance is in its validation record.

Foundation Task 4 remains pending. The history endpoint does not provide the full
admin contract: spreadsheet requirements, Notify wording and the uncertain-send
recovery design remain unresolved. Database rollback cannot undo email acceptance;
this task implements no send path and makes no delivery guarantee.
