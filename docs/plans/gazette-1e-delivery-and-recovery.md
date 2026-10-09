# Gazette one-shot delivery and recovery

Execution slice 3 of [the send/recovery contract](gazette-1b-send-and-recovery-contract.md),
parent tasks 4c/4d. Started 9 October 2026 from master `801e949` (merged PR #92),
in `.worktrees/deed-poll-api-gazette-delivery`, branch `feat/gazette-delivery-recovery`.
Other agents' checkouts and the shared documentation repository are not modified.

## Implementation

- [x] Connect confirmed HTTP submission to admission and the original caller's single
  delivery claim. Identical key/body/subject repeats perform no generation or sending.
- [x] Generate all required workbooks from the detached admission snapshot. Validate
  group completeness, counts and each file's 2 MiB limit before encoding file values.
  Use the agreed `case_count`, `adult_spreadsheet` and `minor_spreadsheet` fields.
- [x] Commit file evidence and Sending before the provider call; an unconfirmed commit
  acknowledgement never grants send authority. Keep workbook/file values in memory.
- [x] Use frozen recipient/template/reply-to/retention, download email confirmation,
  and batch UUID as Notify reference. One email carries every present group.
- [x] Commit known acceptance with provider receipt, history, all case dates and notes.
  Stop after 400/403/429 as NotSent; other failures are Uncertain. A failed acceptance
  settlement never repeats the email; retain a late receipt or log correlation IDs.
- [x] Recover at most 25 expired requests per minute/pass/replica, each in a separate
  row-locked transaction. Recovery never prepares or sends and runs with delivery off.
- [x] Keep new delivery off by default and document operational investigation/release.

No migration is needed. V34 already holds every necessary state, constraint, lease,
file evidence and receipt field; V31 remains successful sent history. No per-case
outbox, email-template enum, citizen adapter or admin contract artifact is changed.

## Transport decision and evidence

Inspection of the resolved `notifications-java-client:6.2.0-RELEASE` bytecode showed:
`createConnectionAndSetHeaders`/`getConnection` use `HttpURLConnection` with no explicit
connect/read timeouts or public transport-injection seam. `performPostRequest` has
no SDK retry loop, but delegates transport policy to the JDK and logs IO exceptions.
`internalPrepareUpload` only checks size, Base64-encodes bytes and constructs JSON;
it does not upload a separate file remotely.

The implementation therefore adjusts the design's suggested direct SDK send reuse:
`GazetteNotify` reuses the configured SDK object's credential extraction, SDK
`Authentication`, `prepareUpload` and `SendEmailResponse`, through a dedicated OkHttp
transport. The existing dependency graph resolves OkHttp **4.12.0** through
`feign-okhttp:13.6`; no dependency upgrade or global JVM networking change is made.
Existing per-case correspondence still uses its existing transport.

The dedicated client disables connection retries, redirects and SSL redirects,
uses no explicit/ambient proxy, has no custom interceptor/authentication replay,
and marks the request body `isOneShot()`. Its connect/read/write/call timeouts are
5/30/30/60 seconds. The one-shot body also refuses response-driven follow-ups, rather
than relying on connection retry settings alone. The accepted response must be 201,
fit within 1 MiB, parse through the SDK and match this batch reference/template with
a valid receipt and positive version. Failure bodies are neither read nor logged.

Local jar inspection of `RetryAndFollowUpInterceptor` and actual WireMock requests
verify connection/response retry and redirect controls. Coverage includes 301/302/
303/307/308, 401/407/408/421/429/500/502/503 (with Retry-After and Location), dropped
connections, malformed chunks, malformed acceptance and a slow-response deadline.
Every test asserts exactly one observed HTTP request. Direct 407 is a protocol error
(status unavailable); it remains uncertain. A malformed chunk's unexpected 200 is
also uncertain. The whole-call timeout is the HTTP client's budget, not a promise
that a process paused by the OS or blocking DNS code will return by a wall-clock deadline;
the durable lease/recovery fence still applies.

Ignored worktree evidence under `build/gazette-delivery-evidence/` includes SDK and
OkHttp bytecode plus Gradle dependency resolution. This establishes client behaviour,
not deployed proxy/egress policy; deployment verification remains a release prerequisite.

## Fault and concurrency evidence

The added full-context API suite uses real PostgreSQL transactions, the production
spreadsheet generator and a local WireMock Notify endpoint. It covers:

- Adult-only, minor-only and mixed requests, required links, frozen configuration,
  email confirmation/retention, completed history/dates/notes and repeat status.
- Missing/oversized files, generation/encoding failure and zero partial emails.
- Known refusals and ambiguous statuses, held/released reservations, no automatic
  repeat, and a fresh confirmation only after NotSent.
- Send-start failure before commit and lost acknowledgement after a real commit;
  both make zero provider calls and recover according to persisted state.
- Accepted email with real sent-note failure: the history/case-date transaction
  rolls back, Uncertain keeps reservations, and the late receipt is retained.
- Lost acknowledgement after successful acceptance settlement: Sent stays Sent,
  with one email and one sent note per case.
- Process death after send-start, expired preparation, expired Sending with late
  acceptance, and failed uncertainty settlement followed by recovery.
- Same-key and overlapping requests while the original send is blocked: one send.
- A 27-request recovery backlog (25 on the first pass), concurrent replicas,
  atomic failure-note rollback and later recovery, and no provider work.
- Rejection of an enclosing transaction before any preparation or sending.

The existing ledger suite additionally covers admission/note rollback, SQL constraint
and legacy migration behaviour, expired owners, conflicting receipts, edit/delete
interleavings and atomic/idempotent settlement. Existing preview tests now explicitly
mock the provider and assert no calls; their disabled-send test checks configuration
rather than the removed hardcoded HTTP guard. Test database contexts delay background
scheduling so cached contexts cannot race another test's fixtures; tests invoke the
real recovery method directly.

## Validation and contract

Generated OpenAPI has **36 paths, 51 operations and 55 schemas**. Compared with the
PR #92 artifact, the only differences are `summary` and `description` on
`POST /caseworker/gazette-notices`; all request/response schemas and other operations
are identical. Complete artifact SHA-256:
`c5465002de9a57d175cad73a9b33392b22d8e73337cd02df5ece51eec80b86cc`.
The worktree copy is `build/gazette-delivery-evidence/openapi.json`. Admin's pinned
artifact remains untouched.

Full `./gradlew build` passed on 9 October 2026 with **3,844 tests and 18 integration
tests**, zero failures/errors/skips, including Checkstyle. This adds 28 full-context
delivery/recovery cases and 22 HTTP transport/preparation cases to the merged baseline.
The run used Java 21, `TESTCONTAINERS_RYUK_DISABLED=true`,
`JAVA_TOOL_OPTIONS=-XX:UseSVE=0`, and the explicit local toolchain path.
The successful log is `build/gazette-delivery-evidence/build.log`.
Initial verification caught a fixture trigger referring to `text` instead of `note`,
and transport assertions that assumed 407/malformed chunks always preserved a status;
these were corrected before the full green run. No live services were used.

## Next step and release boundary

Refresh the admin client from the generated API artifact, then implement confirmed
selection, stable request keys, typed outcomes and request list/detail/full-membership
views. Follow [the runbook](../GAZETTE-RECOVERY.md) for the meaning of pending and
uncertain outcomes. Keep the current ready-list predicate, including reserved rows;
preview/admission explain why a reserved case cannot be sent again.

No live Notify send, deployment, activation, template provisioning, admin client refresh
or external Copilot/Opus review was performed in this slice. The external review,
operating ownership, retention/access agreement and controlled test-recipient/download
proof remain release prerequisites. The proposed review-checklist business answer
and human Welsh review remain part of the admin/release work.
