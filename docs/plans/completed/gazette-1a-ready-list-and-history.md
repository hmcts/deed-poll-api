# Gazette API: executable foundation tasks

## Overview

Execute the independently runnable Tasks 1–3 of
`docs/plans/gazette-1-ready-list-and-batch-send.md`, which is the parent design.
The user requested running the API plan before admin plan 16 on 6 October 2026.
The parent explicitly permits these tasks before the missing business inputs.
Read that complete plan and CLAUDE.md before implementation.

## Scope and boundaries

- Implement the named ready filter, audit schema and admin-only history read.
- Use the parent's working predicate: publication requested, notice date absent,
  state Enroled; default order dateEnroled ascending. Do not claim new business sign-off.
- The parent originally named V26, which is already committed for deed preparation. Use the
  next unused migration number on this branch; never edit existing migrations.
- Spreadsheet columns/order and Notify wording are not agreed. Do not implement
  the spreadsheet, batch-send endpoint, Notify provisioning or a placeholder send.
- The documented transaction design also needs resolution: a database rollback
  cannot undo Notify accepting an email. Record this unresolved issue; this run
  must not invent a send/recovery design or claim all-or-nothing email delivery.
- Preserve the parent plan in its active location. Mark only its Tasks 1–3 when
  genuinely completed; leave business inputs, sending and full-contract completion
  unchecked. Completion of this execution plan does not complete the parent or
  unblock the full admin plan.
- Work only in this worktree. Other agents and user work may exist in the workspace;
  do not revert or edit sibling checkouts, push, merge or deploy.

## Validation

Use Java 21, with JAVA_HOME=/tmp/deed-poll-jdk21 in this container, and export
TESTCONTAINERS_RYUK_DISABLED=true. Docker is available with sandbox escalation.
Run `./gradlew build` for the baseline and after each implementation task, including
the real PostgreSQL integration tests. Record unit/integration counts and failures
honestly. Do not skip integration or weaken tests to claim success.

Keep generated OpenAPI snapshots within this worktree so the shared
`/tmp/openapi-specs.json` artifact is not confused with another run's output.
Record source commit/provenance and compare semantic contract deltas.

## Tasks

### Task 1: Baseline and named ready filter (parent Task 1)

- [x] Record clean-source baseline build results and unit/integration counts.
- [x] Add readyForPublication to ListFilters and the controller description, with
  tests for all predicate terms, oldest-enrolled ordering and existing fallbacks.
- [x] Run full build; verify this task's OpenAPI delta is exactly the filter description
  and readyForPublication appended to the CreateSavedQueryRequest and
  UpdateSavedQueryRequest filter enums.
- [x] Record results and mark parent Task 1 complete only when verified.

Task 1 execution evidence: `gazette-1a-task1-validation.md`. Full build is green
(3,619 test / 14 integration, zero failures or skips; baseline 3,614 / 14).
The initial description-only contract expectation was incorrect. On 6 October the
coordinating agent verified the exact three-path semantic delta against the saved-query
schema generator and validation code: both derive their accepted keys from ListFilters.
The two additive enum entries are a necessary part of the requested filter and preserve
that existing behavior. This is a corrected technical expectation, not new business
sign-off. Task 1 is now reconciled: the retained snapshots have exactly these three
changes, both hashes match, and the build log and XML reports confirm the counts
above. The baseline and full build were not repeated solely for this documentation
correction. Parent Task 1 is also marked complete; Tasks 2–4 remain pending.

### Task 2: Audit schema (parent Task 2)

- [x] Add the next unused Flyway migration, JPA entities and repositories following
  the parent's two-table schema, frozen references and attribution requirements.
- [x] Test request-id uniqueness against real PostgreSQL and run the full build.
- [x] Record results and mark parent Task 2 complete only when verified.

Task 2 execution evidence: `gazette-1a-task2-validation.md`. V31 adds the audit
tables; the case foreign key uses text to match V2. Full build is green
(3,629 test / 14 integration, zero failures, errors or skips), and OpenAPI is
unchanged from Task 1. Parent Task 2 is complete; later tasks remain pending.

### Task 3: Role gate and history read (parent Task 3)

- [x] Implement GET /caseworker/gazette-notices with DEEDPOLL_ADMIN enforcement,
  pagination, newest-first ordering and response DTOs that expose no subject ID.
- [x] Test authenticated non-admin refusal, admin results, ordering, pagination,
  empty history and attribution privacy with the repository's full-context patterns.
- [x] Run full build; record results and mark parent Task 3 complete when verified.

Task 3 execution evidence: `gazette-1a-task3-validation.md`. The admin-only history
read is implemented with frozen references and no subject or request identity on
the wire. Full build is green (3,641 test / 15 integration, zero failures, errors
or skips). Parent Task 3 is complete; foundation Task 4 remains pending.

### Task 4: Foundation contract and handoff

- [x] Regenerate and compare OpenAPI against the baseline: only the named filter's
  description, its addition to the two saved-query request filter enums, and the
  history operation/required schemas may be added by this run.
- [x] Update API documentation and record final test counts and generated-spec provenance.
- [x] Record the pending spreadsheet, Notify wording and uncertain-send design decisions
  in the parent plan; keep its blocked tasks unchecked and the plan active.
- [x] Confirm all foundation tests pass and the full admin contract remains unavailable
  until the batch-send task is implemented and validated.

Task 4 execution evidence: `gazette-1a-task4-validation.md`. Fresh full build
passed (3,641 test / 16 integration, zero failures, errors or skips); Checkstyle
passed. Regenerated OpenAPI has exactly the six permitted baseline changes and
is semantically unchanged from Task 3. All foundation tasks are complete. The
parent plan remains active with business inputs, batch sending and full-contract
validation unchecked; the full admin plan 16 contract is still unavailable.
