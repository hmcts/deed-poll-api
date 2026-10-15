# Plan: an append-only audit trail of writes, case reads and security events

## Overview

Record who did what, to which case, and when, with the before and after values a caseworker-facing timeline needs.
Also record who opened a case or downloaded a document, and log authentication and authorisation failures. Today the
only history is last-writer display names (`createdBy`/`modifiedBy`), overwritten on each write, with no subject and
no record of reads. Gazette recipient settings (V33) are the one exception and are the precedent here.

This is the api half of the service's audit work. The admin app's security events and the alert rules are
separate plans. A caseworker-facing timeline, planned separately by another session, will read these tables through
an endpoint that that plan adds. **This plan adds no read endpoint.**

## Decisions: implement these, do not revisit

Agreed with Steve on 2026-10-15.

1. **Two tables, hand-written, not Hibernate Envers.**
   - `deedpoll_audit_event` holds who, when, action, entity, case and field *names*. It holds no applicant data and is
     kept for 7 years.
   - `deedpoll_audit_event_value` (`event_id`, `field`, `old_value`, `new_value`) holds the before and after values.
     It is applicant data and is disposed with the case's other applicant data.
   - Envers would copy every column into `_AUD` tables, miss JdbcTemplate writes, and still need hand-written DDL.
2. **Schema, `V36__audit_trail.sql`.** Renumber to the next free version if V36 is taken when this merges. Never edit
   V1–V35.
   - **`deedpoll_audit_event`:**
     - `id bigserial primary key`;
     - `occurred_at timestamp not null` (from `AuditClock`, UTC);
     - `actor_id varchar(255)` (IDAM subject) and `actor_name varchar(255) not null`;
     - `action varchar(64) not null`;
     - `entity_type varchar(64) not null` and `entity_id text not null`;
     - `case_id text` (null for entities with no case, such as a saved query or the Gazette settings);
     - `fields text[] not null default '{}'`;
     - `detail jsonb` (allowlisted keys only: `fromState`, `toState`, `templateId`, `outcome`, `batchId`, `documentTypeId`);
     - indexes on `(case_id, occurred_at)` and `(actor_id, occurred_at)`.
   - **`deedpoll_audit_event_value`:**
     - `event_id bigint not null references deedpoll_audit_event(id)`;
     - `field varchar(128) not null`;
     - `old_value text` and `new_value text`;
     - primary key `(event_id, field)`.
   - Add both tables to `DeedPollSchema.DEED_POLL_TABLES`.
3. **Tamper-proof until disposal, with one controlled deletion path per table.**
   - A `BEFORE UPDATE OR DELETE` row trigger on each table raises unless the transaction-local setting
     `deedpoll.audit_disposal` is `on`. A `BEFORE TRUNCATE` statement trigger always raises.
   - Two `SECURITY DEFINER` functions are the only code that sets it, with `set_config(..., true)` so it lasts one
     transaction only:
     - `deedpoll_audit_dispose_case_values(case_id text)` deletes that case's value rows;
     - `deedpoll_audit_dispose_events_before(cutoff timestamp)` deletes older event rows and their values.
   - This plan does **not** schedule either function. Retain-and-dispose will call them.
   - **Residual risk:** until a separate least-privilege runtime role exists (a later infra plan), the api's own login
     owns the tables, so it can alter the triggers. The setting stops application code deleting rows by accident.
4. **Values only where a case owns them.** Value rows are disposed by case, so a case-less entity's values could
   never be disposed. Saved queries, Gazette settings and Gazette batches record field names, and allowlisted
   `detail` where useful, but no value rows.
5. **Value encoding:** `text`. Dates are ISO-8601, enums use their name, and booleans are `true`/`false`. A null is a
   SQL NULL, so "cleared" reads as `new_value` null. A value row is written only for a field whose value changed.
   A create writes `old_value` null for each field it set.
6. **`AuditAction` is a Java enum, with no DB check constraint.** The enum is the vocabulary; adding an action is a code
   change, not a migration.
7. **`AuditTrail.record(...)` is `@Transactional(propagation = MANDATORY)`.** It runs in the same transaction as the
   change, so a change with no audit row cannot commit. If the audit insert fails, it logs `audit.write.failed`
   (action and entity id only) and rethrows, rolling the write back.
8. **The actor is the caller's IDAM subject and display name.** Add `userId` to `WriteContext`, from
   `CurrentUser.userId()`. A user-initiated write with no subject is a bug, and `AuditTrail` throws. A system actor
   (no subject) records `actor_name` = `CurrentUser.SERVICE_ACCOUNT_NAME`, with `actor_id` null. That applies only to
   explicitly system-initiated writes.
9. **Read audit: `CASE_VIEWED` and `DOCUMENT_DOWNLOADED` only.** Not lists or searches. A read row is written by
   `AuditTrail.recordRead`, in its own `REQUIRES_NEW` transaction. **A case view fails open:** the read succeeds, and
   the failure is logged as `audit.write.failed`. **A document download fails closed:** no row, no download. A read
   writes no value rows.
10. **Write endpoint classification.** All 27 `@Post|Put|Patch|DeleteMapping` methods under `api.caseworker` and
    `api.judicial` are either audited or exempt. The exempt ones change no case state:
    - `POST /caseworker/cases/{id}/emails/{template}/preview`;
    - `POST /caseworker/gazette-notice-previews`;
    - `PUT` and `DELETE /caseworker/saved-queries/{id}/selection`, a per-user UI preference.

    Every other mapping is audited.
11. **Gazette.** `PUT /caseworker/gazette-settings` records `GAZETTE_SETTINGS_CHANGED`, `fields={recipientEmail}`, no
    value rows. V33's history table stays as it is. A Gazette send records one `GAZETTE_NOTICE_SENT` event per member
    case when the batch settles as sent, with `detail.batchId`. The actor is the batch's admitted submitter, never
    the recovery worker.
12. **Auth and authz failure logging** uses SLF4J with MDC keys `security.event`, `subject`, `path` and `reason`, cleared
    after each request. There are three sources:
    - a delegating `AuthenticationEntryPoint` (`auth.unauthenticated`; `reason` is the OAuth2 error code, never token
      text);
    - an `AccessDeniedHandler` on both security chains (`authz.denied`);
    - `DomainErrorHandler.forbidden` (`authz.denied`, with the subject from the security context).

    Status codes and `WWW-Authenticate` are unchanged.
13. **Never log or audit tokens, secrets, session ids, cookies or email addresses.** The event row, `detail` and every
    log line hold identifiers only. Values go in the value table only.

## Constraints

- Run every Gradle command in the foreground. Never background a build: a session that ends mid-build is restarted,
  and the restart builds over it and wipes its results.
- Never edit a committed Flyway migration (V1–V35).
- Never edit `src/test/resources/reference/`.
- Do not change existing HTTP contracts. This plan adds no endpoint, so `openapi.json` should not change. If it does,
  stop and explain.
- Do not push, and do not change any environment's configuration.
- Each task is test-first: the new test is seen failing before the change.

## Validation Commands

**Everything here must be green after every task.**

```bash
export JAVA_TOOL_OPTIONS=-XX:UseSVE=0 TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build -I /tmp/gazette-review/no-checkstyle.gradle
/tmp/gazette-review/checkstyle.sh .
git diff --check
```

This devcontainer's JVM needs `-XX:UseSVE=0`, and Gradle's Checkstyle worker daemon starts without it and dies with
exit 134. The init script disables only the Checkstyle tasks; `checkstyle.sh` runs the same jar and config over the
same source sets, and fails on any warning, as the Gradle task does. Neither file lives in this repo. Do not change
`build.gradle` or the Checkstyle configuration to work around this. Record the actual test counts after each task.

## Implementation Steps

### Task 1: The audit tables, their triggers and the disposal functions

**Files:** new `src/main/resources/db/migration/V36__audit_trail.sql`; `health/DeedPollSchema.java`; a new
container-backed test on `AbstractPostgresContainerTest`.

- [x] Write decision 2's tables and indexes, and decision 3's triggers and functions.
- [x] Container tests: an insert into each table works. UPDATE and DELETE on each table raise, and TRUNCATE raises.
  The two functions delete exactly what they should (one case's values; events before the cutoff, and their values).
  After a function returns, a plain DELETE in the same transaction still raises.
- [x] `DeedPollSchemaHealthTest` and `EntityMappingTest` pass.
- [x] Mark completed

### Task 2: `AuditTrail`, `AuditAction` and the actor

**Files:** new `domain/audit/AuditAction.java`, `AuditEntry.java`, `AuditValue.java` and `AuditTrail.java`
(JdbcTemplate); `domain/deedpoll/WriteContext.java` and its callers.

- [x] Add `userId` to `WriteContext` (decision 8), and update every construction.
- [x] `AuditTrail.record(entry, values)` with `MANDATORY` propagation (decision 7). `recordRead(entry)` with
  `REQUIRES_NEW` (decision 9).
- [x] Tests:
  - `record` outside a transaction throws `IllegalTransactionStateException`;
  - a user write with no subject throws;
  - `audit.write.failed` is logged and rethrown when the insert fails;
  - the encoding rules in decision 5 are covered;
  - no value rows are written for an unchanged field.
- [x] Mark completed

### Task 3: Audit case, note, witness and document writes, with values

**Files:** `domain/deedpoll/DeedPollService.java`, `domain/notes/NoteService.java`, the witness service, and
`DocumentService`'s upload. Their tests.

- [x] Case create and update: `fields` are the request's set field names, the values are the changed fields' before
  and after, and `detail` carries the state transition.
- [x] Note create, update and delete, and witness create, update and delete: the same pattern.
- [x] Document upload: the document's metadata fields only. Never the file content.
- [x] Service tests: one event per write, with the subject, name, action, ids and field names, and value rows only for
  changed fields.
- [x] An integration test: a forced audit failure rolls back the case update.
- [x] Mark completed

### Task 4: The remaining writes, and the classification guard

**Files:** referral, judicial, email, saved-query, Gazette settings and send, and CCD-attempt services. A new
`api/WriteEndpointAuditCoverageTest.java`, plus a fixture controller in the test source set.

- [x] Audit the following:
  - referral create, withdraw, close and confirm;
  - judicial claim and direction;
  - email send (`detail.templateId`, no value rows for the email body);
  - saved-query create, update and delete (field names only, decision 4);
  - Gazette settings and send (decision 11);
  - CCD attempt reconciliation and retry.
- [x] `WriteEndpointAuditCoverageTest` reflects over every write mapping and asserts that each maps to an
  `AuditAction` or is in decision 10's exemption list. Prove it fails on the fixture controller's unclassified mapping.
- [x] All 27 current mappings are classified.
- [x] Mark completed

### Task 5: Read audit

**Files:** `api/caseworker/controller/CaseworkerReadController.java` (`GET /caseworker/cases/{id}`) and
`CaseworkerDocumentController.java` (the file download). Their tests.

- [ ] `CASE_VIEWED` on a case read; `DOCUMENT_DOWNLOADED` on a file download (decision 9).
- [ ] Tests: one row per read. A forced failure still returns the case and logs `audit.write.failed`. A forced failure
  on download refuses the download.
- [ ] Mark completed

### Task 6: Authentication and authorisation failure logging

**Files:** `config/SecurityConfiguration.java` (both chains), `api/DomainErrorHandler.java`; MockMvc tests.

- [ ] Wire a delegating entry point and an access-denied handler into both chains, delegating to Spring's
  `BearerTokenAuthenticationEntryPoint` and `AccessDeniedHandlerImpl` (decision 12).
- [ ] Log the subject in `DomainErrorHandler.forbidden`.
- [ ] Tests:
  - a 401 still carries `WWW-Authenticate: Bearer`, and its log event has a reason but no token text;
  - a 403 logs the subject and path;
  - the MDC is cleared after the request.
- [ ] Mark completed

### Task 7: Documentation and handoff

- [ ] Document the audit tables, the vocabulary, the controlled disposal path and its residual risk in `CLAUDE.md`
  ("Rules that break things silently if ignored": a new write path must call `AuditTrail`, and the guard test enforces
  it).
- [ ] Record the validation counts and the branch SHA in Execution notes. Do not claim any live verification.
- [ ] Mark completed

## Execution notes

Baseline, 2026-10-15, on `e8907d0` (#105's merge), with the validation commands above: 4,176 test / 19 integration,
no failures, errors or skips. Both test tasks executed, and Checkstyle was clean. The highest migration is V35, and
there are 27 write mappings.

Task 1, 2026-10-15: V36 adds both tables, the append-only triggers and the two disposal functions (each turns the
setting off again before returning; `EXECUTE` is revoked from `PUBLIC`). `AuditTrailMigrationTest` (12 cases) was
seen failing first. `SdkMigrationOrderTest` now expects V1 to V36. Validation: 4,188 test / 19 integration, no
failures, errors or skips; Checkstyle clean.

Task 2, 2026-10-15: `WriteContext` carries `userId`, set only by `CurrentUser.writeContext()` (the defaults carry
none). `domain/audit` adds `AuditAction` (each action marked read or write, so `record` and `recordRead` refuse each
other's), `AuditActor` (a user, or `system()` as the service account), `AuditEntry` (detail keys allowlisted at
construction) and `AuditValue` (decision 5's encoding; `changed()` compares the encoded forms). `AuditTrail` also
refuses value rows on an entity with no case (decision 4), and its `audit.write.failed` line carries the exception's
class name but never its message. `AuditTrailTest` (11 container cases) and `AuditValueTest` (9) were seen failing
first. `AuditTrailTest`'s read case commits one row, under a fresh UUID, by design. Validation: 4,208 test /
19 integration, no failures, errors or skips; Checkstyle clean; `openapi.json` unchanged.

Task 3, 2026-10-15: `DeedPollService`, `NoteService`, `WitnessService` and both upload paths record one event in the
write's own transaction. A case event names the request's fields; its values cover those and every field the gates
changed, plus `displayReference`, and `detail` carries `fromState`/`toState` only when the derived state moved. A
context with no subject is refused (`WriteContext.admin()` is gone); the no-context short forms are the service
account. `NoteAuthor` now carries the subject and a `service` flag: the outbox's failure note is a service write, and
the Gazette ledger's notes are the submitter's. Witness create and update take the caller. `DocumentUploadAudit`
records upload metadata only (name, direction, type, original name, MIME type, size) in the transaction that first
writes the row, which for CDAM is `DocumentStorageStates.claim`. Notes and witness deletes write the old values with a
null new value. `CaseWriteAuditTest` (9 container cases) and the integration `AuditFailureRollbackTest` (a 256-character
subject that only `actor_id` refuses) were seen failing first. Validation: 4,217 test / 20 integration, no failures,
errors or skips; Checkstyle clean; `openapi.json` unchanged. Task 4 still needs an action for case delete and for
document metadata create, update and delete, which the vocabulary does not have yet.

Task 4, 2026-10-15: referral create, withdraw, close, confirm, claim and direction record one `Referral` event each,
with the columns set, their values and `fromState`/`toState` (a confirmation carries `outcome` and no transition);
`touch` and the lapse sweep record none. A fresh email enqueue records `EMAIL_SENT` with `detail.templateId` and no
fields or values; an idempotent repeat records nothing. Saved-query create, update and delete record field names only,
with no case; selection records nothing. A Gazette settings change records `GAZETTE_SETTINGS_CHANGED` on entity id `1`,
`fields={recipientEmail}`, no values. A settlement as sent records one `GAZETTE_NOTICE_SENT` per member with
`detail.batchId`, as the admitted submitter. A CCD repair shares one short transaction with its event
(`CcdAttemptRepairs` wraps the ledger move in a `TransactionTemplate`), so a lost race records nothing. Task 3's gap
is closed too: `CASE_DELETED` and `DOCUMENT_CREATED`/`UPDATED`/`DELETED` were added, recorded as the service account
because no route reaches them. Each audited handler carries `@AuditedAs(action)` (`api/AuditedAs`, a statement that
records nothing); `WriteEndpointAuditCoverageTest` scans `api.caseworker` and `api.judicial` for `@RestController`s,
finds 27 write mappings, 23 audited and decision 10's 4 exempt, and refuses an exempt mapping that also claims an
action. It was seen failing on `UnclassifiedWriteFixture`'s unannotated `DELETE`, and, with one annotation stripped
for a run, on `PUT /caseworker/gazette-settings`. `RemainingWriteAuditTest` (8 container cases) plus new assertions in
`GazetteRequestLedgerTest` and `SendIdempotencyTest` were written with the change rather than seen failing first.
Three mocked `save` stubs now allocate an id, as a real save does. Validation: 4,229 test / 20 integration, no
failures, errors or skips; Checkstyle clean; `openapi.json` unchanged.

## Post-Completion

- Retain-and-dispose schedules the two disposal functions and records both retention periods in its Task 0 inventory
  (7 years for events; with the case for values).
- A later infra plan adds a least-privilege runtime DB role (decision 3's residual risk).
- The caseworker timeline's read endpoint is that plan's work.
