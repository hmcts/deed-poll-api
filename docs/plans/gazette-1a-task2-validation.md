# Gazette foundation Task 2 validation

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
- Starting source: `0bc3f925078a1f807b9f206bd55776b8b8951b30`, clean worktree.
- The clean-source baseline and Task 1 results remain recorded in
  `gazette-1a-task1-validation.md`: baseline 3,614 test / 14 integration;
  Task 1 3,619 / 14, all passing without skips.
- V31 is the next unused migration; no committed migration was changed.
- The two audit tables follow the parent design, with one required type correction:
  `deed_poll_id` is text, matching the existing V2 case primary key. PostgreSQL
  cannot enforce the originally proposed UUID-to-text foreign key. The parent plan
  now records this correction and the actual migration number.
- The batch records the caller's request UUID, explicit send timestamp, separate
  subject ID and display-name attribution, and case count. Membership records freeze
  the reference rather than reading the case's current display reference.
- JPA updates cannot overwrite the recorded evidence columns. Database foreign
  keys have no delete cascade. This is persistence protection, not a claim that
  privileged SQL cannot alter audit rows.
- The readiness table list, exact entity-registration assertion and migration
  history assertion now include the new schema.

## Tests and build

Ten new tests: eight PostgreSQL repository tests (including parameterized cases)
and two database-free composite-key tests. They prove:

- A second distinct batch with the same request UUID is refused by the actual
  PostgreSQL unique index, bypassing any repository lookup.
- Batch fields round-trip, with subject and display name kept separately.
- A frozen reference survives a subsequent change to the case's display reference.
- Membership reads are batch-scoped; unknown batch/request lookups are empty.
- One case may occur in different batches, but not twice in one batch.
- Both foreign keys require an existing parent; deleting either parent is refused
  while audit membership exists.
- Ordinary JPA updates cannot rewrite evidence.
- Composite identity includes both batch and case and supports empty JPA keys.

Validation command, run from this worktree:

```sh
set -eu
mkdir -p build/gazette-evidence
rm -f /tmp/openapi-specs.json build/gazette-evidence/task2-openapi.json
JAVA_HOME=/tmp/deed-poll-jdk21 \
TESTCONTAINERS_RYUK_DISABLED=true \
INTEGRATION_TESTS_ENABLED=true \
./gradlew build --rerun-tasks
test -s /tmp/openapi-specs.json
cp /tmp/openapi-specs.json build/gazette-evidence/task2-openapi.json
```

The initial compile exposed incorrect case fixture accessor/type assumptions; the
fixtures were corrected to the existing String ID and displayReference. The next
run passed all ten new tests but found three existing schema inventories requiring
updates. A subsequent run caught a line-length warning in the readiness comment;
wrapping that comment resolved it. No tests were disabled or weakened.

Final full build: successful. `test`: 3,629 (+10 from Task 1); `integration`: 14.
Zero failures, errors or skips in either task. All required Checkstyle tasks passed.
PostgreSQL-backed repository tests live in the `test` source set and ran in addition
to the separate `integration` suite.

Local evidence under ignored `build/gazette-evidence/`:
`task2-build-passed.log`, `task2-counts.json`, `task2-openapi.json`, and
`task2-openapi-delta.json`. Earlier failed build logs remain alongside these.

## Contract and handoff

The generated OpenAPI was compared as parsed JSON against the retained Task 1
snapshot: no semantic changes. Both snapshots have SHA-256
`b466f4a178c2cf9699e8fc349ffdf945cb394538ffe3a5876c1e3891a0164466`.
The Task 1 snapshot's provenance is in its validation record; this snapshot is
from the starting commit above plus the Task 2 changes in the accompanying commit.
No shared `/tmp/openapi-specs.json` artifact was used.

Only foundation Task 2 and parent Task 2 are newly complete. History, sending,
spreadsheet layout and Notify wording remain pending. A database rollback cannot
undo Notify accepting an email; the send/recovery design remains unresolved and
this schema does not implement or validate it.
