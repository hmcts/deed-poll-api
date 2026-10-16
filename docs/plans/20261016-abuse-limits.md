# Plan: abuse limits on search, email and upload (OWASP M6, L13)

## Overview

Nothing bounds how much one signed-in identity can do. A compromised session, a stuck script or a held-down key can:

- run name searches without limit, each a `like '%…%'` over unindexed columns against the product database;
- send many 25 MB uploads, each held in memory;
- spend the Notify allowance that previews and real sends share, so that real sends to applicants start failing;
- send the same repeatable email (`CHASE_MISSING_DOCUMENTS`) to one applicant as often as they like, because each
  compose journey mints a new `Idempotency-Key`.

This is the **api half** of OWASP plan 06 (deed-poll-admin `owasp-remediation-06-abuse-limits.local.md`). The api is
authoritative: admin's limiter, a later plan, is for UX and admin's own pods only. **I3** (four-eyes on irreversible
emails) is not built.

Current state at `origin/master` `474e409`: no limiter, throttle or bucket4j anywhere; no statement timeout except
`OwnedStaging`'s; the latest migration is V36.

## Decisions: implement these, do not revisit

Agreed with Steve on 2026-10-15 (the plan) and 2026-10-16 (the numbers).

1. **Six routes, four buckets, keyed on (IDAM `sub`, bucket):**

   | Bucket | Routes | Limit (default) |
   |---|---|---|
   | `search` | `GET /caseworker/cases` carrying `q` or any `where` | 120 per 1 min |
   | `email-preview` | `POST /caseworker/cases/{id}/emails/{template}/preview`; `POST /caseworker/gazette-notice-previews` | 30 per 10 min |
   | `email-send` | `POST /caseworker/cases/{id}/emails/{template}`; `POST /caseworker/gazette-notices` | 20 per 1 h |
   | `upload` | the case document upload `POST` | 30 per 10 min |

   Unfiltered list reads and every other route are not limited, because admin's dashboard makes up to four list
   calls per view. The two Gazette routes joined the email buckets because they send email through the same Notify
   allowance, and they were not on master when OWASP plan 06 was written.
2. **The store is a Postgres fixed-window counter**, migration `V37__request_budget.sql`. Renumber to the next free
   version if V37 is taken when this merges.
   - `deedpoll_request_budget("subject" text, "bucket" text, "windowStart" timestamp(3), "count" integer)`, primary
     key `("subject","bucket")`. Add it to `DeedPollSchema.DEED_POLL_TABLES`.
   - One statement does the work: `INSERT … ON CONFLICT ("subject","bucket") DO UPDATE SET "count" = CASE WHEN
     "windowStart" = EXCLUDED."windowStart" THEN "count" + 1 ELSE 1 END, "windowStart" = EXCLUDED."windowStart"
     RETURNING "count", "windowStart"`. The window start is the request time truncated to the bucket's window.
   - The table never grows past users × 4, so there is no cleanup job. It is correct across replicas and survives
     restarts. Not bucket4j (a new dependency, and per-pod) and not Redis (the api has no Redis client).
3. **One servlet filter, `RateLimitFilter`, in `SecurityConfiguration.securityFilterChain` only, after
   `BearerTokenAuthenticationFilter`.**
   - It reads `sub` from the authenticated JWT. A token with no `sub` uses one shared `no-subject` bucket.
   - It must be a filter, not a `HandlerInterceptor`: `DispatcherServlet.checkMultipart` parses the 25 MB body before
     interceptors run. Nothing before it in the chain may read request parameters of a multipart upload.
   - It is **not** on `ccdPersistenceFilterChain`, because CCD callbacks are service traffic.
   - Route matching uses the same path patterns as the controllers. A bucket list that names a route the controllers
     no longer have must fail a test.
4. **A refusal is 429 with `Retry-After`** (whole seconds to the end of the window) and an `ApiError` body in fixed
   api wording that never quotes request input. Add 429 to the six operations in the published OpenAPI document.
5. **Modes: `off | observe | enforce`**, `deedpoll.rate-limit.mode`, env var `RATE_LIMIT_MODE`, default `observe` in
   `application.yaml` and the chart. `observe` counts and logs exactly what `enforce` would refuse, and refuses
   nothing. Per-bucket limits and windows are properties too (`deedpoll.rate-limit.buckets.<bucket>.limit` and
   `.window`), defaulting to decision 1's numbers.
6. **One structured log line per refusal or would-refuse:**
   `rate_limit bucket=<b> mode=<m> subject=<sub> count=<n> limit=<l>`. No case data, query text, slot or filename.
7. **A per-pod in-flight upload cap**: a `Semaphore` in the same filter, `deedpoll.rate-limit.upload-in-flight`,
   default 4 permits. Refuse with 429 and `Retry-After: 5`. Release it in `finally`. **It enforces from day one,
   whatever the mode**, because it protects the heap, not throughput. Streaming to disk is out of scope (plan 05).
8. **A per-case, per-template email cap in `CaseEmailService.send`**, `deedpoll.email.max-per-case-per-template-per-day`,
   default 3.
   - Count `deedpoll_notification` rows for (`deedPollId`, template) created in the last 24 hours, excluding rows that
     are `Failed` with verdict `PERMANENT`.
   - Over the cap is a `ConflictError` (409) in fixed wording: "This email has already been sent 3 times for this case
     today". Not 429: it is a business rule, and admin's `send.ts` already renders api's 409 sentence.
   - **The idempotent replay check comes first.** A repeat of an already-used `Idempotency-Key` still answers 200
     with `enqueued: false`, never 409.
   - It enforces from day one, whatever the mode. Two concurrent sends with different keys may overshoot by one,
     unless the send already locks the case row, in which case count under that lock. Say which in a comment.
9. **A statement timeout on the case list read**: a transaction-local `set_config('statement_timeout', ?, true)` for
   the list query only, following `OwnedStaging`, `deedpoll.list.statement-timeout`, default `5s`. A timeout reaches
   the caller through the existing unavailable path, not a new status.

## Constraints

- **deed-poll-api#108 (additional IDAM issuers) merged just before this plan.** Leave its work alone: `auth/IdamJwtDecoder`,
  `auth/IdamProperties` (now three constructor arguments: issuerUrl, audiences, additionalIssuers) and the `idam.api`
  block of `application.yaml`. Put this plan's tests in new test classes, and its properties under `deedpoll.`.
- `CcdCreationAttemptRepositoryTest` ("an attempt that ran out of tries on refusals may be retried from scratch")
  fails intermittently and is unrelated to this plan. If it alone fails, re-run once. Do not change it.
- Run every Gradle command in the foreground. Never background a build: a session that ends mid-build is restarted,
  and the restart builds over it and wipes its results.
- Never key a limit on IP or `X-Forwarded-For`. Staff share NAT egress.
- Never let a replay become a refusal (decision 8). Do not refuse inside `outbox.drain()` or the linking sweep.
- Never edit a committed Flyway migration (V1–V36). Never edit `src/test/resources/reference/`.
- No new dependency. If one becomes unavoidable, stop.
- The only HTTP contract change is the 429 response on decision 1's six operations. If `openapi.json` changes in any
  other way, stop and explain.
- Do not push, and do not change any environment's configuration.
- Each task is test-first: the new test is seen failing before the change. Controller and filter tests boot the whole
  context (see `CLAUDE.md`: a `@WebMvcTest` slice does not get `SecurityConfiguration`).

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
same source sets, and fails on any warning, as the Gradle task does. Neither file lives in this repo. Do not change
`build.gradle` or the Checkstyle configuration to work around this. Record the actual test counts after each task.

## Implementation Steps

### Task 1: The budget table and counter

**Files:** new `V37__request_budget.sql`; `health/DeedPollSchema.java`; a `RequestBudget` repository; a
container-backed test on `AbstractPostgresContainerTest`.

- [x] Write decision 2's table and upsert; return the count and the window's end, for `Retry-After`.
- [x] Container tests: the count increments within a window and resets in a new one; two subjects and two buckets
  are independent; two concurrent transactions on one row both count (no lost update).
- [x] `DeedPollSchemaHealthTest` and `EntityMappingTest` pass.
- [x] Mark completed (build green: 4312 unit, 20 integration, 0 failed; `RequestBudgetRepository` in `repository/`; `SdkMigrationOrderTest` now expects V1 to V37)

### Task 2: The rate-limit filter, modes and 429

**Files:** a `RateLimitFilter` and a `RateLimitProperties` record; `SecurityConfiguration`; `application.yaml`; the
chart's `values.yaml`; the OpenAPI document.

- [ ] Each of decision 1's six routes counts in its bucket; an unfiltered list read does not; `q` and `where` each
  make a list read count.
- [ ] `enforce`: request N+1 gets 429, `Retry-After` within the window, the fixed `ApiError` wording. `observe`: 200
  and decision 6's log line. `off`: nothing counted.
- [ ] An over-budget upload is refused **without** the multipart being parsed: assert the controller was never
  reached and no part was read.
- [ ] The CCD persistence chain is unaffected. A token with no `sub` uses the shared bucket.
- [ ] A test fails if a bucket's route pattern matches no controller mapping.
- [ ] OpenAPI publishes 429 on the six operations, and nowhere else.
- [ ] Mark completed

### Task 3: The in-flight upload cap

- [ ] With one permit and one upload blocked mid-flight, a second gets 429 with `Retry-After: 5`, in every mode.
- [ ] The permit is released after a success **and** after a thrown error.
- [ ] Mark completed

### Task 4: The per-case, per-template email cap

- [ ] The 4th distinct-key send of one template to one case within 24 hours is a 409 with the fixed sentence.
- [ ] A replay of an earlier key still answers 200 with `enqueued: false`, including when the cap is reached.
- [ ] `PERMANENT` failures do not count; a row 25 hours old does not count; another template does not count.
- [ ] The V15/V20 exclusive-group behaviour is unchanged (existing tests stay green).
- [ ] Mark completed

### Task 5: The statement timeout on the list read

- [ ] Against the Testcontainers Postgres: a forced slow list query (a 1 ms timeout, or a test seam) fails through the
  existing unavailable path, and the next request on the same connection is unaffected (the setting is
  transaction-local).
- [ ] Mark completed

### Task 6: Documentation and handoff

- [ ] `CLAUDE.md`: one rule under "Rules that break things silently", covering the filter's chain placement, the
  replay-before-cap order, the modes, and what enforces from day one.
- [ ] `docs/`: how to read the `rate_limit` lines in App Insights, and how to switch mode per environment.
- [ ] A note that admin's next OpenAPI spec refresh will break its pinned `operation.responses` assertions
  (`contract.test.ts`), and must absorb all six 429s in one refresh.
- [ ] Mark completed

## Post-Completion

Manual, not for this run:

- Merge and deploy to AAT in `observe`; confirm `rate_limit … mode=observe` lines under deliberate load (api tech lead).
- Record the production Notify rate and daily limits in `docs/` (service owner, OWASP plan 06 open decision 6).
- After AAT business testing, tune the limits to roughly 3× the observed per-subject p99 and switch to `enforce`, AAT
  first (OWASP plan 06 Task 11).
- The admin half: the 429 page, admin's Redis limiter and its upload in-flight cap (OWASP plan 06 Tasks 7–10).
