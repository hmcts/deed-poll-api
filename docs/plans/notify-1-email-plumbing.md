# notify-1 — the GOV.UK Notify send path, proved with one email

## Overview

Gives this service the ability to send an email through GOV.UK Notify, and proves it end to end with
**exactly one template**: the *Application Rejected* email a caseworker sends when the Master has refused
enrolment. Everything here is machinery every later template reuses — the client, the error taxonomy, a
durable outbox, the drain that empties it, and the three operations the admin Email tab calls.

Read first: [`NOTIFY_TEMPLATE_TAXONOMY.md`](../../../deed-poll-admin/.business_context/NOTIFY_TEMPLATE_TAXONOMY.md)
— the analysis of the six `.docx` templates the business supplied, which merge field maps to which column, and
the design decisions this plan implements.

**The shape of the feature, decided 2026-09-18.** A caseworker does not receive an automatic email on a state
change, because three of the four templates require text a human writes. Instead: an **Email tab** in
`deed-poll-admin` where the caseworker picks an email type, is shown the template **populated from case and
referral data**, adds their text into defined slots, and sends. This plan is the api half. The tab itself is
notify-2, in the `deed-poll-admin` repo.

**Scope is the admin system.** Every send is a caseworker action and the outbox drain is pinned to one, which
is what makes a sweep-based outbox safe here — no citizen-facing send waits on a queue nobody visits.

**No GOV.UK Notify service exists for deed poll yet** — no API key, no templates, no template IDs. This plan is
therefore written the way `deed-poll-admin`'s plans 11 and 12 were: buildable and fully testable now against a
WireMock double, with the real template ID and key arriving later. Task 1 establishes the placeholder
convention. **It will not send a real email, and preview will not render, until notify-0 is done.** That is
expected, not a defect.

---

## Decisions taken — implement these, do not revisit

1. **`notifications-java-client`, version `6.2.0-RELEASE`.** The client every HMCTS Java service uses;
   `apps/pcs/pcs-api/build.gradle:437` is the reference. `apps/pcs/CLAUDE.md` says `6.0.0` and is stale —
   trust the `build.gradle`. The API surface below was verified against the `6.1.0-RELEASE` jar with `javap`,
   so check it still holds if you bump further.

2. **One `NotificationClient` bean from two properties.** `new NotificationClient(apiKey, baseUrl)` from
   `notify.api-url` and `notify.api-key`, mirroring
   `apps/pcs/pcs-api/src/main/java/uk/gov/hmcts/reform/pcs/config/NotificationsConfiguration.java`. The key is
   a secret with a non-working default; the base URL points at the WireMock double in tests.

3. **The error taxonomy is a first-class unit with its own tests, not `catch` blocks.**
   - **400, 403 → permanent.** The request is wrong or forbidden; retrying resends the same broken request.
     The outbox row is failed and never retried.
   - **429, 500 → temporary.** Rate-limited, or Notify is unwell. The row stays claimable and backs off.
   - **anything else → technical.** Failed, and loud, because nobody predicted it.

   `apps/pcs/pcs-api/.../notify/config/NotificationErrorHandler.java` is the precedent and the code should say
   so. **It is a hand-maintained reading of Notify's docs, not a quotation** — see flag 2.

4. **A durable outbox, drained by a mutating read. No scheduler.** This service deliberately has no
   `@EnableScheduling`, no Quartz and no db-scheduler — `ReferralService`'s class javadoc (around line 86) says
   so, and the referral inactivity sweep is a mutating read *because* of that. The outbox follows the same
   shape: rows are enqueued in the transaction that causes them and drained by `NotificationOutbox.drain()`
   hung off caseworker reads, exactly as `reclaimStaleClaims()` hangs off the judicial pool.

   **This is the one place this plan diverges from `pcs-api`, deliberately.** pcs uses db-scheduler with
   `MaxRetriesFailureHandler` and `ExponentialBackoffFailureHandler`. A scheduler here would be a bigger
   architectural change than the feature.

5. **The claim of an outbox row is one conditional `UPDATE`, copied in shape from `releaseStaleClaim`.**
   See `src/main/java/uk/gov/hmcts/reform/deedpoll/repository/ReferralRepository.java` — a native `@Modifying`
   statement whose `WHERE` re-asserts every precondition, returning `1` if this caller won the row and `0` if
   not. That is what makes the drain safe across replicas with no lock. **Re-read that method and its javadoc
   before Task 2.**

6. **Idempotency is a database constraint, not a check.** Unique index on `("referralId", "template")` where
   `referralId` is not null — one *Application Rejected* per referral, ever. A caseworker double-clicking, or
   two replicas racing, must not tell an applicant twice that their application was refused. A violated insert
   is a logged no-op, not an error that fails the caseworker's request. See flag 4 on whether this generalises.

7. **A blank recipient is refused at send, not skipped.** `applicantEmail` is nullable `text`
   (`V2__deed_poll.sql:54`). This differs from `pcs-api`, which skips silently — and the difference is the
   point: pcs sends automatically, so skipping is the only option, whereas here a caseworker has *chosen* to
   send and silence would leave them believing an applicant was told something. So the send operation answers
   **422** naming the missing field, and the Email tab can say "this applicant has no email address".

8. **One template in this plan, and it is *Application Rejected*.** The only one of the four needing **no
   attachment**, so it carries no dependency on document generation (a separate feature — flag 1), and its
   content already exists: the judge's reason is `Referral.directionsText`, which api-3 shipped.

9. **Three operations, and the caseworker count moves 30 → 33.** The Email tab needs to know what it may send,
   see it, and send it:

   | Method | Path | Purpose |
   |---|---|---|
   | `GET` | `/caseworker/cases/{caseId}/emails` | Which email types apply to this case, and why not for the rest |
   | `POST` | `/caseworker/cases/{caseId}/emails/{template}/preview` | Notify-rendered subject and HTML for the caseworker's current slot text |
   | `POST` | `/caseworker/cases/{caseId}/emails/{template}` | Send — resolve, enqueue, drain |

   `OpenAPIPublisherTest` asserts the caseworker count; **update it to 33 and add the three paths to
   `CASEWORKER_PATHS`**. The judicial count stays **4** and no judicial path changes. Grep before touching a
   count.

   **Preview is a `POST` despite being a read**, because the caseworker's slot text is the input and it does not
   belong in a query string — it is prose, it may be long, and a URL would log it.

10. **The preview is rendered by Notify, never reproduced by us.**
    `NotificationClient.generateTemplatePreview(templateId, personalisation)` returns a `TemplatePreview` with
    `getSubject()`, `getHtml()` and `getVersion()` — verified against the jar. So the api asks Notify to render
    and passes the HTML back; what the caseworker approves is what sends, with the GOV.UK styling and the
    service's branding already applied.

    **Do not build a local renderer, a template copy or a mock-up.** A second copy of the content is a second
    thing to drift, and the styling requirement is met by Notify's rendering rather than by us reproducing it.

11. **Structure lives in the Notify template; the caseworker fills named slots.** Notify email templates accept
    no HTML, and personalisation values are inserted as plain text — Notify does not render Markdown *inside* a
    placeholder. So a whole composed body passed as one variable would lose every heading, bullet and link, and
    would bypass the rendering that produces the official look. The template holds the structure; our
    personalisation fills values.

12. **Defined slots only, and the resolved values are not client input.** For this template the caseworker
    fills `judges_reason` (pre-populated from `directionsText`, editable) and `additional_notes` (optional).
    **Everything else is resolved server-side from the case and the referral, and a client cannot set it.**
    The request body carries *only* the slots. A request that tries to supply `reference_number` or
    `applied_online` is refused — see decision 13 — because a caseworker altering the reference an applicant is
    told, or flipping the refund branch, is a correctness and a fraud problem, not a convenience.

13. **The slot payload is validated like every other write here.** Through the existing schema idiom
    (`ReferralSchemas.parseDirection` is the closest model), so: unknown keys refused and named, each slot
    length-capped, and `judges_reason` required for this template. A caseworker-authored string that reaches an
    applicant needs bounds for the same reason `ReferralNotes.claimLapsed` truncates a judge's name — an
    unbounded value composes a payload the far end refuses, and the refusal lands somewhere unhelpful.

14. **The outbox row records what was sent, not how to rebuild it.** Personalisation is resolved at send and
    stored as `jsonb`, and the row also stores the Notify `templateVersion` the preview returned. So the
    question "what was this applicant actually told, and under which wording" is answerable from one row —
    which matters most precisely when a caseworker edited the judge's reason.

15. **The template ID is a placeholder until notify-0 lands**, following this repo's convention for an
    unconfirmed external identifier (`deedpoll.ccd.case-type-id: DEED_POLL_PLACEHOLDER`). Name it so one grep
    finds every unprovisioned value.

16. **`StubNotify`, a WireMock double, is the only Notify in any test.** This repo forbids live calls and has
    one precedent to copy: `src/test/java/uk/gov/hmcts/reform/deedpoll/auth/StubIdam.java`.
    `wiremock-standalone:3.13.2` is already a `testImplementation` dependency (`build.gradle:246`) — no new test
    dependency. It must stub **both** `POST /v2/notifications/email` and the template-preview endpoint.

---

## What the outbox row is

One table, shipped as `V11__notifications.sql` (`V10` was already taken -- see task 2). Mixed-case quoted
column names, as every table here has.

| Column | Type | Why |
|---|---|---|
| `id` | `uuid` PK | Also the Notify `reference`, so a row and a notification are mutually traceable |
| `deedPollId` | text, FK to the case | Who it is about; how a caseworker read finds it |
| `referralId` | `uuid`, FK, nullable | The referral that caused it. Nullable because later templates are not all referral-driven |
| `template` | text | The `EmailTemplate` member name, not the Notify UUID — the UUID is config and differs per environment |
| `templateVersion` | int, nullable | What Notify rendered at preview (decision 14) |
| `recipient` | text | Resolved at send, so a later change to the case cannot redirect an email already owed |
| `personalisation` | `jsonb` | Resolved at send, including the caseworker's slots. `SqlTypes.JSON`, as `SavedQuery.conditions` does it |
| `state` | `"NotificationState"` enum | `Pending`, `Sent`, `Failed` |
| `attempts` | int, default 0 | |
| `nextAttemptAt` | timestamp | The backoff. `Pending` and `nextAttemptAt <= now` is the claimable set |
| `providerNotificationId` | `uuid`, nullable | What Notify returned, so a delivery query is possible later |
| `failureReason` | text, nullable | The taxonomy's verdict plus the status code |
| `createdBy` | text | **Which caseworker sent it** — they authored part of it, so this is not ordinary audit noise |
| `createdOn`, `modifiedOn`, `modifiedBy` | | The audit columns every table here carries |

---

## Constraints

Java 21, `-Werror`, checkstyle on five source sets, `TESTCONTAINERS_RYUK_DISABLED=true`, mixed-case columns,
`@JdbcTypeCode(SqlTypes.NAMED_ENUM)` on any Postgres enum, DTOs never entities, a controller test boots the
whole context, never edit `src/test/resources/reference/`. Additional:

- **No live Notify call in any test.** `StubNotify` only. A test needing a real key belongs to notify-0.
- **Container-free where CI runs it.** The error taxonomy, the slot validation, the resolved-values-are-not-input
  rule and the recipient rule need no container and must be stated container-free — CI excludes the
  `requires-docker` classes (27 of them, 821 of 2,098 tests as measured 2026-09-18), so a rule stated only in a
  container-backed test is a rule CI does not check. The outbox SQL — the conditional `UPDATE`, the unique
  index, the `jsonb` round trip — gets container-backed repository tests, CI-excluded and accepted.
- **Do not edit a committed Flyway migration.** Latest committed is `V9__current_referral_state.sql`; if a
  `V10` already exists when you start, use `V11` and say so.
- **`deed-poll-admin` is not touched by this plan.** Its Email tab is notify-2 and its contract is this plan's
  OpenAPI output.

---

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

`build` is compile + checkstyle across five source sets + `test` + `integration`, so `OpenAPIPublisherTest`
runs here. **Measure the baseline on this branch before Task 1** by summing `tests="…"` across
`build/test-results/{test,integration}/*.xml` and record it next to the final count in Task 6. The figure after
api-3 was `test` 2098 + `integration` 8 = 2106; confirm rather than assume, since master has moved.

**A worker or container startup timeout is an environment failure, not a code failure** — re-run before
investigating.

---

## Tasks

### Task 1: The client, the configuration and the error taxonomy

Adds the dependency, the client bean, the configuration, the failure classification and the WireMock double.
**Nothing sends and nothing previews yet**; this task is the vocabulary the rest of the plan speaks.

Read first: `apps/pcs/pcs-api/src/main/java/uk/gov/hmcts/reform/pcs/config/NotificationsConfiguration.java`,
`apps/pcs/pcs-api/src/main/java/uk/gov/hmcts/reform/pcs/notify/config/NotificationErrorHandler.java`,
`src/test/java/uk/gov/hmcts/reform/deedpoll/auth/StubIdam.java`, `src/main/resources/application.yaml`.

- `notifications-java-client:6.2.0-RELEASE` in `build.gradle`.
- `NotifyConfiguration` with the single `NotificationClient` bean.
- `application.yaml`: `notify.api-url`, `notify.api-key` (secret, non-working default),
  `notify.templates.application-rejected` set to the placeholder of decision 15.
- `NotificationFailure` — decision 3's taxonomy as an enum plus `classify(int status)`, whose javadoc says it
  is a reading of Notify's documented status codes rather than a quotation.
- `StubNotify` in `src/test/java/.../notify/`, modelled on `StubIdam`: stubs the send and the preview endpoints
  with Notify-shaped bodies, and can be told to answer any status so the taxonomy is provable over real HTTP.

- [x] `notifications-java-client` on the compile classpath, one `NotificationClient` bean from config, the
      placeholder template ID greppable alongside `DEED_POLL_PLACEHOLDER`, `NotificationFailure.classify`
      covering 400/403/429/500 and a default with a container-free test per branch, `StubNotify` serving both
      endpoints and an arbitrary error, and `./gradlew build` green

  Notes for the tasks that follow, verified rather than assumed:
  - **A send must be answered `201`, not `200`.** The client compares the status to 201 exactly and treats a
    200 as a failure carrying status 200 -- which `classify` calls technical. `StubNotify.stubSend` answers 201;
    the preview endpoint is an ordinary 200.
  - **Paths, read off the jar's constant pool:** `POST /v2/notifications/email` and
    `POST /v2/template/{templateId}/preview`.
  - **`NotificationClientException`'s status-carrying constructor is package-private**, so the only way to get
    one with a status on it is a real HTTP round trip -- hence `StubNotifyTest` as well as
    `NotificationFailureTest`, and hence `stubSendStatus`/`stubPreviewStatus`.
  - **An empty API key does not fail construction** (checked against the 6.1.0 jar), so the service starts
    unprovisioned and fails at the first send with a 403. `getHttpResult()` is `0` for a call that never reached
    Notify, which falls to technical -- deliberately terminal, since a retry might duplicate a delivered email.
  - `StubNotify` echoes the request's `reference` back through WireMock response templating, so task 5's "the
    Notify reference is the row id" is assertable from the response.
  - Totals after this task: `test` 2132, `integration` 8 (baseline on this branch was 2098 + 8). All 34 new
    tests are container-free, so `build.gradle`'s `requires-docker` figures are unchanged.

### Task 2: The outbox table and its drain

The durable half: table, entity, atomic claim. **Still nothing sends** — `drain()` exists and is exercised, but
what it hands each row to is a seam a test fills.

Read first: `ReferralRepository` (all of `releaseStaleClaim` and `findStaleClaims`, including the javadoc
arguing why the `WHERE` is not redundant), `V8__referral_claim_and_direction.sql`,
`ReferralService.reclaimStaleClaims`.

- `V10__notifications.sql` — the table above, the `"NotificationState"` Postgres enum, the FKs, the unique
  index of decision 6, and an index serving the claimable predicate (`state`, `nextAttemptAt`).
- `Notification` entity with `NAMED_ENUM` on the state and `SqlTypes.JSON` on the personalisation.
- `NotificationRepository.claimNextDue(...)` — decision 5's conditional `UPDATE`, whole precondition restated
  in the `WHERE`.
- `NotificationOutbox.drain(limit)` — claims up to `limit` rows, passes each to a `NotificationSender` seam;
  backoff on temporary, terminal on permanent, `attempts` incremented either way.
- Tests: container-backed proving the claim is exclusive (two claims of one row, one wins), a future
  `nextAttemptAt` is not claimable, the unique index refuses a duplicate, and `jsonb` round-trips;
  container-free for the backoff arithmetic and the state machine.

- [x] `V10` applies cleanly and Hibernate validates against it, `claimNextDue` proven exclusive against real
      Postgres, the unique index proven to refuse a second row, backoff and state transitions stated
      container-free, and `./gradlew build` green

  **Shipped as `V11__notifications.sql`, not `V10`.** `V10__backfill_current_referral_state.sql` was already
  committed, and this plan's own constraint says to take the next free number and say so.

  Notes for the tasks that follow, decided or verified rather than assumed:
  - **Claiming a row is a lease, not a state.** There is deliberately no `Claiming` member on
    `"NotificationState"`. `claimNextDue` is one conditional `UPDATE` that re-asserts `Pending` and due,
    increments `attempts` and pushes `nextAttemptAt` to `now + NotificationBackoff.LEASE` (5 minutes). So a
    replica that dies mid-send leaves a `Pending` row with its attempt counted, claimable again when the lease
    expires -- not a row stuck in a transient state. `attempts` moving at claim time is what stops a row that
    kills the process being retried for ever.
  - **`drain` is `REQUIRES_NEW`**, which settles task 5's contingency in advance: the case-list read may be
    `readOnly` and does not need widening, and a failed send cannot roll back the read that triggered it. The
    cost, stated in the javadoc: a concurrent drain **blocks** on a shared row rather than skipping it, then
    takes nothing when the lock frees. Task 6's contention test should expect one email and serialisation, not
    `SKIP LOCKED`.
  - **The sender seam is `Optional<NotificationSender>`, and there is no implementation yet.** With no bean, a
    drain claims nothing and every row stays `Pending` -- which is exactly what task 4's "a send leaving exactly
    one `Pending` row" needs, and it means task 5 starts sending by adding one `@Service`. Nothing needs a
    placeholder sender and none was written.
  - **Task 4's enqueue should be `INSERT ... ON CONFLICT ("referralId", "template") WHERE "referralId" IS NOT
    NULL DO NOTHING`**, which is why the unique index is partial rather than a table constraint. That is the only
    way to honour decision 6's "a violated insert is a logged no-op" *inside the caller's transaction*: catching
    a `DataIntegrityViolationException` marks the transaction rollback-only, and `REQUIRES_NEW` would commit the
    row outside the transaction that caused it, against decision 4. `0` rows affected is the no-op to log.
  - **`createdBy` is `NOT NULL` here**, unlike every other `createdBy` in this schema, because the caseworker
    authored part of the content rather than merely touching a record.
  - **A retryable failure is bounded**: `NotificationBackoff.MAX_ATTEMPTS` is 8, 1/2/4/8/16/32/60/60 minutes, and
    the row is then `Failed` saying it gave up. pcs's `MaxRetriesFailureHandler` is the precedent; without it a
    permanently-500ing row would be a cost on every caseworker read for ever. Constants rather than properties,
    and the javadoc says when that should change.
  - The parents are **bare ids, not `@ManyToOne`** -- the one deviation from `DeedPollNote` and `Referral`, and
    the reason is the drain: it reads across every case and needs nothing off either parent. Both foreign keys
    and both cascades are still the database's.
  - `deedpoll_notification` was added to `DeedPollSchema.DEED_POLL_TABLES`, so readiness names it too.
  - Totals after this task: `test` 2170, `integration` 8 (2132 + 8 after task 1). 38 new tests, of which 10 are
    container-backed -- `build.gradle`'s exclusion comment is re-measured to **831 of 2,170 across 28 classes**,
    1,339 running with `INTEGRATION_TESTS_ENABLED=false`.

### Task 3: The template, its resolved values and its slots

The content model, and the rule that keeps a client out of it. No HTTP yet.

Read first: `NOTIFY_TEMPLATE_TAXONOMY.md` sections 2 and 3, `ReferralSchemas.parseDirection` (the validation
idiom), `src/main/java/uk/gov/hmcts/reform/deedpoll/entity/DeedPoll.java`.

- `EmailTemplate` enum with its single member `APPLICATION_REJECTED`, carrying the config key that resolves its
  Notify UUID and declaring which slots it takes.
- `ApplicationRejectedPersonalisation` — resolved values from the case and referral (`reference_number`,
  `online_application_reference`, `old_forename`, `old_surname`, `applied_online` from `postCounter`) plus the
  two slots, and a `toMap()`.
- `EmailSlots.parse(...)` — decision 13: unknown keys refused and named, lengths capped, `judges_reason`
  required. Pre-population of `judges_reason` from `Referral.directionsText` happens here, so the tab gets a
  sensible starting value without the resolver trusting the client for it.
- Tests, container-free: every `postCounter` value maps to the right `applied_online`; an unknown slot key is
  refused and named; an over-long slot is refused; a missing `judges_reason` is refused; **a request supplying
  `reference_number` is refused rather than honoured** (decision 12); `directionsText` reaches the slot
  unaltered, including when null.

- [ ] Resolved values come only from the case and referral, slots are the only client input and an attempt to
      set a resolved value is refused, slot validation names every offending field at once, and `./gradlew
      build` green

### Task 4: The three operations

The Email tab's contract. Preview goes to Notify; send enqueues.

Read first: `src/main/java/uk/gov/hmcts/reform/deedpoll/api/caseworker/controller/CaseworkerReferralController.java`
for the controller idiom, and the api-3 judicial controller for how thin a handler should be.

- `CaseworkerEmailController` at `/caseworker/cases/{caseId}/emails` with the three handlers of decision 9.
- `GET` returns the applicable templates with a reason for each that is not — for this plan, *Application
  Rejected* is applicable when the case has a referral whose `decision` is `Rejected`.
- `POST …/preview` resolves, calls `generateTemplatePreview`, returns subject, HTML and version. **No outbox
  row is written by a preview.**
- `POST …/{template}` resolves, refuses a blank recipient with 422 (decision 7), enqueues, then drains.
- DTOs under `api/caseworker/dto`, never entities. The response to a send names the row id so the tab can
  report it.
- **Contingency:** with the placeholder template ID, preview against real Notify would 400. That is correct
  behaviour, not a bug — assert it through `StubNotify` and leave a note in the handler javadoc.
- Tests: container-backed controller tests for all three over HTTP — 401 unauthenticated, 403 without the
  caseworker role, 404 for an unknown case, 422 for a blank applicant email, 409 when no rejected referral
  exists, 200 with subject and HTML from the stub, and a send leaving exactly one `Pending` row.
- `OpenAPIPublisherTest`: add the three paths, move the caseworker count to **33**, leave judicial at 4.

- [ ] Three operations live and documented, the caseworker count is 33 with judicial still 4, preview returns
      Notify's own rendering and writes no row, send enqueues exactly one row, 422 on a blank recipient, and
      `./gradlew build` green

### Task 5: Drain — the send

Connects the outbox to Notify and pins the drain to caseworker reads.

Read first: Task 1's `NotificationFailure`, Task 2's `NotificationOutbox`, `JudicialReferralService.pool` for
the javadoc pattern that explains why a read writes.

- `NotifyEmailSender implements NotificationSender` — `sendEmail(templateId, recipient, personalisation, reference)`
  with the row id as the `reference`; stores `providerNotificationId` on success.
- Failures through `NotificationFailure`: permanent → `Failed` with the reason; temporary → stays `Pending`
  with a backed-off `nextAttemptAt`; technical → `Failed`, logged at error.
- The drain is pinned to **the caseworker case-list read**, the most frequent caseworker action and so the
  analogue of the judicial pool, *and* to the send itself so the happy path does not wait.
- **Contingency:** if the case-list read is a `readOnly = true` transaction, do not widen it — drain in its own
  transaction and say so in the javadoc. A read-only list is worth keeping.
- Tests: container-backed through `StubNotify` — a `Pending` row becomes `Sent` and records the provider id; a
  400 becomes `Failed` and is not retried; a 500 stays `Pending`, increments `attempts` and moves
  `nextAttemptAt`; a drain with nothing due sends nothing. Container-free: the classification wiring, and that
  the Notify `reference` is the row id.

- [ ] A pending row sends through `StubNotify` and records its provider id, a 400 fails terminally, a 500
      retries with backoff, the drain is reachable from a caseworker read whose javadoc explains the mutation,
      no test touches real Notify, and `./gradlew build` green

### Task 6: Security, contention and full validation

- `NotificationSecurityTest`, container-free: the personalisation map contains **only** this template's keys,
  asserted against the record's components so a field added to it fails rather than silently reaching an
  applicant; the recipient is always `applicantEmail` and never `representativeEmail` for this template; the
  API key appears in no log line and no DTO; and a caseworker cannot reach another case's email operations
  (the paths are case-scoped, so api-1's scoping rule covers it — assert it rather than assume it).
- An ArchUnit rule that nothing outside the notify package calls `NotificationClient` directly — the outbox and
  the preview resolver are the only routes to Notify, exactly as `JudicialReferralService` is the only caller of
  the unscoped referral finders.
- A container-backed contention test: two concurrent drains over one pending row send exactly one email.
- Full validation: cold `./gradlew build` with `TESTCONTAINERS_RYUK_DISABLED=true`, checkstyle clean on all five
  source sets, `test` and `integration` totals recorded next to this branch's baseline, and the count with
  `INTEGRATION_TESTS_ENABLED=false`. Re-measure `build.gradle`'s exclusion comment if this plan adds a
  container-backed class, as that comment instructs.

- [ ] Personalisation-key, recipient and case-scoping rules stated container-free, the ArchUnit rule proves the
      client has one route, two concurrent drains send one email, `./gradlew build` green from cold, checkstyle
      clean on five source sets, and both totals recorded with the `INTEGRATION_TESTS_ENABLED=false` figure

---

## Deliberately not in this plan

- **notify-0, the provisioning.** The Notify service, the API key into `deed-poll-infra`'s key vault, HMCTS
  email branding, the Helm wiring, and authoring the templates. A checklist with external dependencies, not
  ralphex work: [`NOTIFY_PROVISIONING_REQUEST.md`](../../../deed-poll-admin/.business_context/NOTIFY_PROVISIONING_REQUEST.md).
  **This plan cannot send or preview a real email until it is done.**
- **notify-2, the Email tab** in `deed-poll-admin`. Consumes this plan's three operations; needs this plan's
  OpenAPI published and `openapi.json` refreshed first, exactly as admin plans 11 and 12 did.
- **The other three emails.** *Request confirmation to proceed* (notify-3) is next and needs little new — but
  it forces the discrimination question in taxonomy §4, which is why it is not here. The two *forms for
  completion* emails (notify-4) are blocked on document generation.
- **Document generation — `LOC020` / `LOC022`.** A separate feature (Steve, 2026-09-18). Notify cannot
  mail-merge a Word document.
- **Sending files.** `prepareUpload` and the secure-link rewording. Confirmed as the approach; it belongs with
  the templates that have attachments. `RetentionPeriodDuration` makes the expiry configurable, which those
  templates will need.
- **The Gazette notice email.** Promised by two templates, no template supplied.
- **Delivery receipts.** No callback and no polling for `delivered`. `providerNotificationId` is stored so this
  is additive; pcs's `VerifyEmailTaskComponent` is the model and it needs a scheduler.
- **A scheduler.** Decision 4.
- **Welsh.** `welsh-draft.csv` exists in the admin business context, so bilingual content is coming. In Notify
  that is a second template per language — additive.

---

## Flags for Steve — pick these up on return

1. **Templates ① and ② are blocked on a feature that does not exist yet.** Separating document generation was
   right, but it means the two highest-volume emails cannot ship until it lands, and the choice — Docmosis via
   `doc-assembly-client`, as pcs does, or a Java PDF library in-process — reshapes notify-4 completely. Worth
   deciding before notify-3 rather than after.

2. **The error taxonomy is inherited, not verified.** `docs.notifications.service.gov.uk` is blocked from this
   devcontainer, so 400/403-permanent and 429/500-temporary come from reading `pcs-api`, not Notify's docs. The
   method signatures in decisions 1, 10 and 16 *were* verified, with `javap` against the
   `6.1.0-RELEASE` jar in the Gradle cache — the taxonomy is the one load-bearing claim I could not check at
   source. **If you can open the Notify docs, the page is the Java client's error table.**

3. **Preview calls Notify, so it counts against the rate limit.** A tab that previews on every keystroke would
   burn the allowance and hit the 429 branch in normal use. notify-2 should preview on an explicit action, and
   notify-0 should record the actual rate limit once the service exists.

4. **Idempotency is per referral, which suits this template and may not generalise.** The unique index is
   `("referralId", "template")`. A later template that legitimately sends twice for one referral — a reminder —
   needs a different key. Flagged so the constraint is not read as a universal rule.

5. **The reply-to address blocks three of four templates.** ① ② ④ all invite a reply, so notify-0 needs a
   monitored inbox registered and verified with Notify and its `emailReplyToId` configured. This plan's single
   template does not reply, so it does not surface the need — the kind of thing discovered late.

6. **`MasterDecision` is `Approved | Rejected` only, and two different emails hang off `Rejected`.** Taxonomy
   §4 has the detail. This plan dodges it by shipping only the enrolment-refused email; notify-3 cannot. With
   the Email tab the caseworker is choosing the email anyway, which resolves it neatly — but confirm that is
   the intent before notify-3 is written, because the alternative is modelling per-reason outcomes.

7. **Whether the caseworker edits the judge's reason or sends it verbatim is still unconfirmed.** The design
   works either way: the slot is pre-populated from `directionsText` and editable. But if editing is normal, the
   stored personalisation is the *only* record of what the applicant was told, which is why decision 14 stores
   it rather than recomputing. If editing turns out to be forbidden, the slot becomes a resolved value and
   decision 12 gets simpler.
