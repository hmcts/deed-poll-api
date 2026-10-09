# Gazette preview, admission validation and status APIs

Execution slice 2 of [the send/recovery contract](gazette-1b-send-and-recovery-contract.md),
parent task 4b. Implementation date: 9 October 2026. Worktree branch:
`feat/gazette-preview-status`.

## Scope and dependency

This branch includes the ledger commit `911c941` from PR #91. That PR merged into
`docs/gazette-send-recovery-contract` after PR #90 had reached master; master
`f065223` did not contain the ledger. The implementation PR therefore targets master
and carries both the prerequisite ledger and this slice. Existing worktrees were
not repurposed, and no other agents' changes were reverted.

## Implementation checklist

- [x] Add admin-only preview with strict selection syntax, complete readiness/type/
  reference/reservation checks and recipient/configuration validation. Preview uses
  one consistent read snapshot and writes no requests, reservations, notes or files.
- [x] Issue a versioned SHA-256 token covering canonical IDs, readiness, all mapped
  spreadsheet values and its embedded 30-minute expiry. It is a comparison token,
  not an authentication credential or checklist-completion proof.
- [x] Recompute confirmation inside locked admission and capture immutable mapped
  generation inputs. Production `admit` chooses current group configuration only
  after resolving an existing request key. Only a fresh request receives the inputs
  and owner claim; repeated requests receive neither.
- [x] Generate spreadsheets directly from captured values, without rereading live
  cases. Inputs exist only in memory; neither full snapshots nor workbook bytes are
  persisted. Input values are immutable strings/date-times/nulls and redact `toString`.
- [x] Add typed Gazette refusal codes and member reasons without applicant values
  or provider prose. Strict JSON types, keys, selection cap and canonical UUID header.
- [x] Add request list, new/legacy detail and database-paged full frozen membership.
  Keep missing legacy submission/recipient/group facts null. Add only `batchId` to
  the existing sent-history summary; its reference preview remains bounded at 100.
- [x] Publish all five operations and their DTOs in generated OpenAPI. Keep caller
  subjects, keys, claims, provider receipts, configuration and input snapshots private.
- [x] Expose retry resolution but refuse **every fresh HTTP send** with 503
  `GAZETTE_UNAVAILABLE`, regardless of configuration. This slice has no sender or
  recovery scheduler. Resolving a pending record returns 202 plus a status Location;
  terminal records return 200, which does not by itself mean sent.
- [x] Add V35 indexes for request order/state and request membership pagination;
  retain all committed migrations unchanged.
- [x] Record the full build and measured OpenAPI delta below.

## Public operations

| Operation | Behaviour |
|---|---|
| `POST /caseworker/gazette-notice-previews` | Recipient/revision, group counts, bounded references, opaque token and expiry; oldest enrolment first, case ID tie-breaker. |
| `POST /caseworker/gazette-notices` | Strict original body plus UUID Idempotency-Key resolves recorded status; fresh requests remain unavailable. |
| `GET /caseworker/gazette-notice-requests` | Request ledger only, optional exact state filter; newest submitted time then descending UUID. |
| `GET /caseworker/gazette-notices/{batchId}` | Request status or legacy sent summary. Sent means accepted, not delivered or published. |
| `GET /caseworker/gazette-notices/{batchId}/cases` | All frozen membership, reference then case ID; default 25, cap 100. |

The existing ready-list/count predicate is unchanged. Preparing, sending and
uncertain cases can still satisfy it; preview/admission report `CASE_RESERVED`.
No archive/download endpoint or uncertain-reservation release operation is added.

## Configuration and activation boundary

`GAZETTE_ENABLED` defaults false and enables provisioned previews in this slice.
`NOTIFY_TEMPLATE_GAZETTE_ADULT`, `NOTIFY_TEMPLATE_GAZETTE_MINOR` and
`NOTIFY_TEMPLATE_GAZETTE_MIXED` default to placeholders. Reuse the existing Notify
API key, reply-to ID and document-retention settings. Only the template required by
the actual groups is needed for that preview. UUID configuration must be canonical,
retention 1–78 weeks, recipient configured, and download email confirmation remains
fixed true in the ledger. No default mailbox or retention period is invented.

This is local configuration validation, not evidence of Notify provisioning. No
chart, environment, secret or live service was changed. Setting the flag true does
not bypass the fresh-HTTP-submission refusal. Slice 3 must replace that refusal only
when one-shot delivery and independent bounded recovery are both implemented.

## Lock and writer review

The [slice 1 inventory](gazette-1c-request-ledger-and-guards.md#transactions-and-writer-inventory)
was rechecked. Case PATCH is the existing-case writer of readiness/exported values;
its reservation guard still runs after acquiring the case lock. Referral projection
updates and CCD reference binding are narrow writes to non-exported fields. Witness,
document and note rows are not spreadsheet inputs. No new bypass writer was found.

Admission locks settings then canonical case IDs, verifies readiness/reservations and
the mapped snapshot, chooses current configuration and writes the request/notes in
one short commit. Input display/generation order is oldest-enrolled-first independently
of lock/fingerprint order. Recipient revision detects an address changed away and back.
Recorded-key resolution precedes expiry, eligibility and configuration checks.

## Validation evidence

Focused Gazette tests cover the prior ledger races/rollback tests and new full-context
HTTP authentication/role checks, strict JSON, selection cap, all 27 adult mapped fields,
minor omitted fields, readiness/recipient changes, expiry, immutable workbook inputs,
canonical ID ordering, configuration groups, duplicate identity, expired-token repeats,
privacy, disabled fresh sends, stable request pagination and 103-member legacy history.
The OpenAPI integration assertions cover the added routes, bodies, error/status schemas,
private-type exclusions and the existing history's sole field addition.

Full `./gradlew build` passed on Java 21 on 9 October 2026: **3,794 tests and
18 integration tests**, zero failures, errors or skips; all Checkstyle tasks passed.
This includes 61 new full-context API cases and the configuration test, plus the
existing ledger, spreadsheet, migration, security and architecture coverage. The
run used `TESTCONTAINERS_RYUK_DISABLED=true` and `JAVA_TOOL_OPTIONS=-XX:UseSVE=0`.

The full run exposed an existing Notify
claim-audit assertion comparing `timestamp(3)` with JVM nanoseconds. Its lower bound
now uses milliseconds, retaining the assertion that claim time is after the batch
cutoff. No delivery production code changed. The committed-defaults test also now
expects the three unprovisioned Gazette template IDs and disabled flag.

The generated OpenAPI has **36 paths, 51 operations and 55 schemas**, compared with
32/46/46 at the ledger baseline. It adds exactly five operations across four new
paths and nine schemas. Every pre-existing operation and all metadata are unchanged;
the sole existing schema change is `batchId` on `GazetteNoticeBatchResponse`.
The complete artifact SHA-256 is
`c570a3309b46c1f4ce3d6afaee0148470dc5dc1a8fb4a0d071c1b11481974a00`.
A worktree-local copy is in `build/gazette-preview-evidence/openapi.json`, compared
against the recorded ledger artifact. Admin's pinned artifact has not been edited.
No live Notify send, deployment, external Copilot/Opus review or admin client update
has been performed for this slice.

## Next executable slice

Implement one-shot Notify delivery and the bounded recovery scanner, using these
immutable inputs and ledger transitions. Prove file preparation failures, known refusal,
ambiguous sends, accepted-but-unsettled outcomes, crashes and zero automatic resends.
Then refresh the admin client from generated OpenAPI and implement selection,
confirmation, stable retry keys and request/status views. Provisioning, support ownership
and controlled test-recipient delivery remain release prerequisites.
