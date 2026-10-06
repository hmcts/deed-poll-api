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
- The parent names V26, which is already committed for deed preparation. Use the
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

- [ ] Record clean-source baseline build results and unit/integration counts.
- [ ] Add readyForPublication to ListFilters and the controller description, with
  tests for all predicate terms, oldest-enrolled ordering and existing fallbacks.
- [ ] Run full build; verify this task's OpenAPI delta is only the filter description.
- [ ] Record results and mark parent Task 1 complete only when verified.

### Task 2: Audit schema (parent Task 2)

- [ ] Add the next unused Flyway migration, JPA entities and repositories following
  the parent's two-table schema, frozen references and attribution requirements.
- [ ] Test request-id uniqueness against real PostgreSQL and run the full build.
- [ ] Record results and mark parent Task 2 complete only when verified.

### Task 3: Role gate and history read (parent Task 3)

- [ ] Implement GET /caseworker/gazette-notices with DEEDPOLL_ADMIN enforcement,
  pagination, newest-first ordering and response DTOs that expose no subject ID.
- [ ] Test authenticated non-admin refusal, admin results, ordering, pagination,
  empty history and attribution privacy with the repository's full-context patterns.
- [ ] Run full build; record results and mark parent Task 3 complete when verified.

### Task 4: Foundation contract and handoff

- [ ] Regenerate and compare OpenAPI against the baseline: only the named filter's
  description and the history operation/required schemas may be added by this run.
- [ ] Update API documentation and record final test counts and generated-spec provenance.
- [ ] Record the pending spreadsheet, Notify wording and uncertain-send design decisions
  in the parent plan; keep its blocked tasks unchecked and the plan active.
- [ ] Confirm all foundation tests pass and the full admin contract remains unavailable
  until the batch-send task is implemented and validated.
