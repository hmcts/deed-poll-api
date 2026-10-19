# Plan: a least-privilege runtime database login (OWASP plan 07, open decision 8)

## Overview

The api connects to Postgres, and runs Flyway, as the Flexible Server administrator login (deed-poll-infra
`postgres.tf` → vault `postgres-user`/`postgres-password` → chart `DB_USER_NAME`/`DB_PASSWORD`). That login owns
every table. V36 makes the audit tables append-only with triggers, but an owner can `DROP` or `DISABLE` a trigger,
so the triggers bind the application code and nothing else.

This plan gives the api a second login, `deedpoll_runtime`, for everything except migrations. Flyway keeps the owner
login. The runtime login owns nothing and has no DDL, so it cannot touch a trigger, and it has no `UPDATE`/`DELETE` on
the audit tables at all. Disposal stays possible through V36's two `SECURITY DEFINER` functions.

**Decided by Steve, 2026-10-15 (admin `owasp-remediation-07-audit-logging.local.md`, decision 8: INCLUDED):**
SELECT/INSERT/UPDATE/DELETE on application tables, INSERT and SELECT only on the audit tables, EXECUTE on the disposal
functions; Flyway keeps running as the owner; infra (a second login and its vault secret), chart changes, and grants
managed by migration.

**What this protects against:** SQL injection, and application code going wrong, being able to drop or disable the
audit triggers, rewrite audit history, or change the schema. **What it does not protect against:** someone who
compromises the pod. The pod still holds the owner password for Flyway, and its workload identity can read the whole
`deed-poll-{env}` vault either way. That is a residual risk (see Post-Completion).

## Prerequisites — check these first, and stop if any is unmet

1. **The SDK must run its own migrations with Flyway's DataSource, not the primary one.**
   `DecentralisedFlywayAutoConfiguration.orderedFlywayMigrationStrategy` (hmcts/ccd-config-generator,
   `sdk/decentralised-runtime`) injects the primary `DataSource` and passes it to every SDK Flyway instance; upstream
   master and 7.17.0 still do. Once the datasource is the runtime login, the `ccd`-schema migrations would run without
   DDL and startup would fail. The fix is upstream and one line: use `appFlyway.getConfiguration().getDataSource()`,
   which is the owner when `spring.flyway.user` is set and the primary DataSource otherwise, so nothing changes for
   other services. **This plan does not make that change and must not work around it here** (`CLAUDE.md`: never define
   a `FlywayMigrationStrategy`). Check: the SDK version on this branch contains the fix (read the class from the
   resolved jar). If it does not, stop and say so. **Raised 2026-10-19:** hmcts/dtsse-ccd-config-generator#1289
   (master, 8.x) and #1290 (the `[7.x]` backport, the line this repo is on). Wait for the 7.x release that includes
   #1290.
2. **The SDK version bump itself is not part of this plan.** The api is on `hmcts.ccd.sdk` 7.1.0. A bump has caught
   this repo before (Jackson 3, the floated elasticsearch-java; see `verifyNoJackson3`). If prerequisite 1 needs a
   newer SDK than the branch has, stop: the bump is its own change.
3. Validation is green on the untouched branch (see Validation Commands).

## Decisions: implement these, do not revisit

1. **Role name `deedpoll_runtime`**, created `NOLOGIN` by a versioned migration, `V38__runtime_role.sql` (renumber if
   V38 is taken at merge time; Scout's audit-timeline and MI work also add migrations, so check open PRs first).
   Roles are server-wide, so create it with an existence check rather than a bare `CREATE ROLE`.
2. **No secret in any migration.** A versioned migration is checksummed and kept in history, so it never names a
   password. The login is switched on by a Flyway **Java callback** on `AFTER_MIGRATE`, registered as a Spring bean so
   Boot adds it to the application's Flyway (not the SDK's). When `deedpoll.db.runtime.password` is set, it runs
   `ALTER ROLE deedpoll_runtime WITH LOGIN PASSWORD …`. It builds the statement with Postgres `format('%I … %L')`
   from bind parameters, never Java string concatenation. It runs on every migrate, so a rotated password takes
   effect at the next deploy. It never logs the password, the statement or the exception message (log the exception
   class only). When the password is unset, it does nothing.
3. **Grants, in V38, for schemas `public` and `ccd`:**
   - `USAGE` on both schemas. No `CREATE`.
   - `SELECT, INSERT, UPDATE, DELETE` on all existing tables, then **revoke `UPDATE, DELETE, TRUNCATE`** on
     `deedpoll_audit_event` and `deedpoll_audit_event_value`. Grant **nothing** on either schema's Flyway history
     table (`flyway_schema_history`, and the SDK's own history table in `ccd`; read its name from the database, don't
     assume it).
   - `USAGE, SELECT` on all sequences (the audit tables and V2/V3 use serial ids).
   - `EXECUTE` on `deedpoll_audit_dispose_case_values(text)` and `deedpoll_audit_dispose_events_before(timestamp)`
     only. Both are `SECURITY DEFINER` with `set search_path from current`, and the runtime role has no `CREATE` on
     any schema, so nothing can be planted ahead of them on the path.
   - `ALTER DEFAULT PRIVILEGES` (for the migrating role, in both schemas) granting the same table and sequence
     privileges, so later migrations' tables are covered.
4. **A future append-only table must revoke explicitly**, because default privileges grant `UPDATE`/`DELETE`. A test
   enforces it (Task 4). Say so in `CLAUDE.md`.
5. **Ships off.** `deedpoll.db.runtime.enabled` (`${DB_RUNTIME_ROLE_ENABLED:false}`). When false, the datasource and
   Flyway both use `DB_USER_NAME`/`DB_PASSWORD` exactly as today. When true:
   - the datasource uses `DB_RUNTIME_USER_NAME`/`DB_RUNTIME_PASSWORD`;
   - `spring.flyway.user`/`password` use `DB_USER_NAME`/`DB_PASSWORD`;
   - the callback has the runtime password.
   With the flag on and either runtime value missing, **refuse startup** with a message naming the missing variable,
   not the value. V38 runs either way. A `NOLOGIN` role with grants is inert.
6. **No new dependency.** Flyway's `Callback` API and Boot's `spring.flyway.user` are already on the classpath.

## Constraints

- Work only in this worktree (`.worktrees/deed-poll-api-runtime-role`, branch `plan/runtime-db-role` from
  `origin/master`). **Never touch the main `deed-poll-api` clone**: it holds someone else's staged work.
- Run every Gradle command in the foreground. Never background a build: a session that ends mid-build is restarted,
  and the restart builds over it and wipes its results.
- Never edit a committed Flyway migration (V1–V37). Never edit `src/test/resources/reference/`.
- Never define a `FlywayMigrationStrategy`. Never remove the `CoreCaseDataClientAutoConfiguration` exclusion.
- No secret in a migration, a log line, a test fixture that looks real, or the chart. Test passwords are obviously
  fake and live only in test configuration.
- The HTTP contract does not change. If `openapi.json` changes, stop and explain.
- Do not push, and do not change any environment's configuration.
- Each task is test-first: the new test is seen failing before the change.

## Validation Commands

**Everything here must be green after every task.** Run them before Task 1 too: never start red.

```bash
export JAVA_TOOL_OPTIONS=-XX:UseSVE=0 TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build -I /tmp/gazette-review/no-checkstyle.gradle
/tmp/gazette-review/checkstyle.sh .
git diff --check
```

This devcontainer's JVM needs `-XX:UseSVE=0`, and Gradle's Checkstyle worker daemon starts without it and dies with
exit 134. The init script disables only the Checkstyle tasks; `checkstyle.sh` runs the same jar and config over the
same source sets, and fails on any warning, as the Gradle task does. Neither file lives in this repo (both present on
2026-10-19). If either is missing, stop and say so rather than editing `build.gradle`. Record the actual test counts
after each task.

## Implementation Steps

### Task 0: Prerequisites and baseline

- [ ] Prerequisite 1: show, from the resolved SDK jar on this branch, which `DataSource` the SDK migrations use. If it
  is the injected primary one, stop here and report.
- [ ] Run the Validation Commands on the untouched branch and record the counts.

### Task 1: V38 creates the role and its grants

**Files:** new `V38__runtime_role.sql`; `SdkMigrationOrderTest` (expects V1 to V38); a container-backed test on
`AbstractPostgresContainerTest`.

- [ ] Container test (red first), reading privileges from the catalogue (`has_table_privilege`,
  `has_sequence_privilege`, `has_function_privilege`, `has_schema_privilege`) for **every** table in `public` and
  `ccd`:
  - application tables have all four DML privileges;
  - the two audit tables have exactly `SELECT` and `INSERT`;
  - both Flyway history tables have none;
  - every sequence has `USAGE`;
  - both disposal functions have `EXECUTE`;
  - neither schema has `CREATE`;
  - the role is not a superuser, does not have `CREATEROLE`, and owns nothing.
- [ ] Default privileges: a table created afterwards by the migrating role, in each schema, is granted DML to
  `deedpoll_runtime`.
- [ ] Running V38's role creation twice against one server does not fail (roles are server-wide).
- [ ] Write V38 (decisions 1, 3). `EntityMappingTest` and `DeedPollSchemaHealthTest` still pass.

### Task 2: The login callback

**Files:** a `RuntimeLoginCallback` (Flyway `Callback`) and its properties record, under `config/` or a new
`db/` package; `application.yaml`.

- [ ] Tests (red first): with a password configured, after migrate the role can log in with it, and logging in with
  a different one fails. Without one, the role stays `NOLOGIN`. A password containing `'`, `\` and `$$` works,
  which proves the quoting. Nothing written to the log contains the password: capture the logger and assert.
- [ ] The callback is attached to the application's Flyway and not to the SDK's. Show it runs once per migrate.
- [ ] Implement decision 2.

### Task 3: Two credentials, behind the flag

**Files:** `application.yaml`; a small configuration class if Boot's binding can't express decision 5 alone; a
startup check.

- [ ] Tests (red first):
  - flag off: datasource and Flyway user are both `DB_USER_NAME`, as today, which is the default;
  - flag on: the datasource user is `DB_RUNTIME_USER_NAME` and the Flyway user is `DB_USER_NAME`;
  - flag on with either runtime value missing: startup fails, and the message names the variable and holds no value.
- [ ] Implement decision 5. The local docker-compose default (`deedpoll`, flag off) still works unchanged.

### Task 4: Prove the application runs as the runtime role, and that the role cannot tamper

**Files:** `ContainerDatabase` / `AbstractPostgresContainerTest`; a new tamper test.

- [ ] Run the **whole container-backed suite** with the flag on: Flyway as the container's owner user, the
  application as `deedpoll_runtime`. Any test that fails is a privilege the runtime genuinely needs. Report it,
  decide whether it belongs in decision 3, and never grant ownership or DDL to make a test pass. This is the real
  evidence for the plan. Record the counts.
- [ ] Tamper test, connected as `deedpoll_runtime` (each must be refused with a permission error):
  - `UPDATE` and `DELETE` on both audit tables, including after `SET LOCAL deedpoll.audit_disposal = 'on'`;
  - `TRUNCATE` on both;
  - `ALTER TABLE … DISABLE TRIGGER` and `DROP TRIGGER`;
  - `CREATE TABLE` in `public` and `ccd`;
  - `INSERT` into either Flyway history table.
- [ ] As `deedpoll_runtime`, both disposal functions **do** delete what they should.
- [ ] Guard test: every table carrying a `BEFORE UPDATE OR DELETE` trigger that raises (V26, V36, and any later)
  grants `deedpoll_runtime` neither `UPDATE` nor `DELETE`. This enforces decision 4.

### Task 5: Chart, off

**Files:** `charts/deed-poll-api/values.yaml`; `Chart.yaml`; `DeploymentChartConformanceTest`.

- [ ] Add `DB_RUNTIME_ROLE_ENABLED: "false"` to the environment. **Do not add the vault secrets yet.** A chart naming
  a secret the vault does not hold fails to deploy, and the secrets come from deed-poll-infra first (Post-Completion).
  Leave a comment saying which two secrets and aliases the enabling change adds.
- [ ] Bump `Chart.yaml`'s version: a values change without one never reaches an environment (Flux reconciles on chart
  version).

### Task 6: Documentation and handoff

- [ ] `CLAUDE.md`, "Rules that break things silently if ignored": two logins, which one Flyway uses, decision 4, and
  why the SDK prerequisite matters.
- [ ] `docs/RESUME.md`: what shipped, that it ships off, and the Post-Completion steps.
- [ ] Final Validation Commands run with counts.

## Post-Completion (not for ralphex)

1. **deed-poll-infra:** `random_password` (already approved for this repo, `special = false`), plus vault secrets
   `postgres-runtime-user` (`deedpoll_runtime`) and `postgres-runtime-password`. Apply to AAT.
2. **Check in AAT before enabling** (AAD Postgres access, DTS Deed Poll group): the administrator login can create
   roles (`select rolcreaterole from pg_roles where rolname = current_user`). If it can't, V38 fails at startup.
   Deploying V38 itself is safe either way, because the role it creates is `NOLOGIN`. This check is for V38's
   `CREATE ROLE`.
3. **Enable in AAT:** a chart change adding the two secrets (aliases `DB_RUNTIME_USER_NAME`/`DB_RUNTIME_PASSWORD`)
   and setting the flag on, with a chart version bump. Verify: `pg_stat_activity.usename` for the api's pool is
   `deedpoll_runtime`; a case edit, a document upload and the audit timeline still work; App Insights shows no
   permission errors.
4. **Residual risk, for the plan 07 record:** a pod compromise still reaches the owner password, through the mount
   and through the workload identity's read on the whole vault. Closing that needs migrations run by a separate
   identity (for example a deploy-time job with its own vault or access policy). That's a larger change to raise
   with Platform Ops, not part of this plan.
5. The upstream SDK change (prerequisite 1), if not yet done: a PR to hmcts/ccd-config-generator, then the api's SDK
   bump as its own reviewed change.
