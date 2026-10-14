# Gazette request ledger and case guards

> Superseded access decision (9 October 2026): [Team leader and Delivery Manager contract](20261009-team-leader-delivery-manager.md)
> governs staff permissions. All Gazette entry points, including the named ready worklist/count and
> saved-query execution, require Delivery Manager alone. Team leader and temporary legacy admin retain
> non-Gazette casework capabilities only. Earlier admin-access wording below is historical; other
> delivery, idempotency and recovery decisions remain applicable.

Execution slice 1 of [the send/recovery contract](../gazette-1b-send-and-recovery-contract.md),
parent task 4a. Implementation date: 9 October 2026.

## Scope and remaining boundary

Implement durable internal admission, ownership, file evidence and local outcome
transitions. There is no HTTP admission/send/status endpoint, Notify caller or
recovery scheduler in this slice. No runtime email or download link is produced.
The generator and recipient editor remain independently available as before.

The internal `reserve` method accepts a trusted fingerprint and configuration. It
checks current readiness, recipient revision and reservations under locks, but does
not yet verify the preview fingerprint or freeze detached spreadsheet inputs.
Slice 2 must add both **inside that locked admission transaction**, before evidence
is written. It must also provide public DTOs and typed errors, pagination and the
history batch ID. Do not expose `GazetteRequest`, owner claims or caller subjects
through controllers. Public sending remains unavailable until slice 3 is complete.

## Implementation checklist

- [x] Add V34 request, frozen membership, case reservation and file-evidence tables.
  Preserve V31/V32 and backfill one permanent reservation per historically sent
  case, including cases present in more than one historical batch.
- [x] Serialize same-key requests; normalize sorted distinct selection, fingerprint
  and recipient revision into a length-delimited SHA-256 identity. Bind repeats to
  the original authenticated subject. Refuse legacy keys and changed identities.
  A repeated request returns recorded state without granting another claim.
- [x] Lock the recipient setting and selected case rows in stable ID order. Recheck
  whole-selection readiness and reject excluded/unclassified/reserved members.
  Persist recipient/revision, template/reply-to IDs, retention and confirmation,
  original subject/display name, references, groups and a five-minute owner lease.
- [x] Commit `Preparing` and submitted notes together; commit all required file
  metadata and `Sending` once. No file bytes, download tokens or applicant exports
  are stored in the ledger.
- [x] Settle acceptance, V31 history, all case dates and fixed notes in one transaction.
  Keep case writes narrow. A failed audit insert rolls the local transition back.
- [x] Release reservations only for `NotSent`. Retain them for `Uncertain`/`Sent`.
  Expired preparation becomes not-sent; expired sending becomes uncertain. Late
  accepted receipts remain evidence and never turn uncertainty into a resend or
  automatic success. Terminal notes and successful settlement are idempotent.
- [x] Block case PATCH during `Preparing`/`Sending`; allow corrections after an
  uncertain outcome without allowing another batch. Prevent deletion whenever
  request evidence exists, including failed requests whose reservation was released.
- [x] Verify real PostgreSQL races, migration/backfill, rollback and database constraints.
- [x] Record the full build and contract validation below before opening the PR.

## Transactions and writer inventory

`GazetteRequestLedger` owns short committed transactions and refuses an enclosing
caller transaction, so send authority cannot escape before its marker commits.
Request transitions lock the request row; admission also takes a transaction-scoped
advisory lock for the request key. Readiness locks use `FOR NO KEY UPDATE` to retain
compatibility with child foreign-key checks. Case PATCH/delete retain the existing
case-lock protocol and check Gazette evidence after taking that lock.

The source inventory at this slice found:

| Writer | Gazette impact and treatment |
|---|---|
| `DeedPollService.updateDeedPoll`, including its delegated overload | Full mapped case fields, reference and readiness changes; guard every PATCH while preparing/sending. |
| `DeedPollService.createDeedPoll` | Creates a new case; cannot mutate a reserved case. |
| `DeedPollService.removeDeedPoll` | Reject any retained request membership; restrictive FKs also preserve evidence. |
| `GazetteRequestRepository.sentEvidence` | Only notice date and modified time/name; executes with the accepted outcome and notes in one transaction. |
| Referral case-projection writers | Only `currentReferralState` and `awaitingConfirmationSince`; neither is exported or part of Gazette readiness. Referral `publishToGazette` is separate from case `publishToLondonGazette`. |
| CCD reference binding | Only `ccdCaseReference`, which is not a spreadsheet column/readiness field; no full entity save. |
| Witness, document and note writers | Child records are not spreadsheet inputs; their existing behavior is retained. |

Future writers of exported or readiness fields must participate in the same case
lock/guard protocol. Slice 2 must repeat this inventory when adding input snapshots.

## Validation evidence

Focused tests cover same-key and overlapping-key races, one send-start authority,
concurrent acceptance/recovery, edit-first and admission-first edit/delete orderings,
known refusal versus ambiguity, lease expiry/late receipt, frozen recipient and
references, role/identity conflicts, and admission/success/failure audit rollback.
The migration test upgrades populated V33 history, preserves every old membership,
backfills distinct reservations and validates repeat migration. No provider is called.

Full `./gradlew build` passed on Java 21 on 9 October 2026: **3,732 tests and
17 integration tests**, zero failures, errors or skips. This includes 29 ledger
cases plus the populated-database upgrade test; all build Checkstyle tasks passed.
The execution used `TESTCONTAINERS_RYUK_DISABLED=true`, the local Java 21 toolchain
and `JAVA_TOOL_OPTIONS=-XX:UseSVE=0`. The unchanged application baseline is merged
API `7ff8352`; the branch starts at design commit `783f21f` (PR #90).

No existing Flyway migration or vendored reference file was edited.

`OpenAPIPublisherTest` regenerated the contract during the integration stage. Its
complete parsed JSON equals the admin artifact at `bdcfc88` (recipient editor):
32 paths, 46 operations and 46 component schemas, with no metadata differences.
The generated artifact SHA-256 is
`aacee4f1d3eac6890a4499c2248803ea4328a4623f612df67b22cdfd76b85ec1`.
A worktree-local copy is retained under `build/gazette-ledger-evidence/openapi.json`;
the shared `/tmp/openapi-specs.json` remains the existing publishing output.
External Copilot/Opus review and controlled test-recipient delivery remain later
release evidence; neither has been run for this slice.

## Next executable slice

Implement preview, locked fingerprint/input verification, public request/status and
complete-membership reads in a separate plan and PR. Then connect one-shot delivery
and bounded recovery. Admin selection/confirmation and recovery views depend on
those API contracts. This PR alone does not complete parent task 4 or admin plan 16.
