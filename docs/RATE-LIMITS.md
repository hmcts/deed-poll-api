# Abuse limits: reading them and switching them

The api half of OWASP plan 06 (M6, L13), built by `docs/plans/completed/20261016-abuse-limits.md`. Five limits (the last added with document previews), and each one is
either switchable or not:

| Limit | Where | Answer | Mode |
|---|---|---|---|
| Per-identity budgets: `search`, `email-preview`, `email-send`, `upload` | `config/RateLimitFilter`, V37's `deedpoll_request_budget` | 429, `Retry-After` to the window's end | `RATE_LIMIT_MODE` |
| Uploads in flight on one pod (default 4) | the same filter, a `Semaphore` | 429, `Retry-After: 5` | **always enforced** |
| Emails of one template to one case in 24 hours (default 3) | `CaseEmailService.send` | 409, "This email has already been sent 3 times for this case today" | **always enforced** |
| One statement of the case list read (default `5s`) | `CaseListStatementTimeout` | the existing failed-read 500 | **always enforced** |
| Non-PDF document previews converting at once on one pod (default 3) | `PreviewRenderer`, `ConversionCapacity` | 429 `PREVIEW_BUSY`, `Retry-After` `DOCUMENT_PREVIEW_BUSY_RETRY_AFTER` (default 5 s) | **always enforced**, not part of `RATE_LIMIT_MODE` |
| Document previews of any format holding scratch at once on one pod (default 8), from the store's answer until the response is written | `PreviewRenderer`, `ConversionCapacity` | the same 429 `PREVIEW_BUSY` | **always enforced**, not part of `RATE_LIMIT_MODE` |

The six limited operations and their defaults are in `RateLimitBucket`, and the published OpenAPI document carries
429 on those six and on the two document previews. A preview's 429 is the conversion permit's `PREVIEW_BUSY`,
published by `OpenAPIDocumentPreviews`, not a budget: previews have no V37 per-person budget. An unfiltered `GET /caseworker/cases` is never counted: only one carrying `q` or `where` is
a `search`.

## The modes

`deedpoll.rate-limit.mode`, env var `RATE_LIMIT_MODE`. `application.yaml` and the chart's `values.yaml` both default
to `observe`.

- `off` counts nothing and writes nothing to `deedpoll_request_budget`.
- `observe` counts every limited request and logs a line for each one `enforce` would refuse, but refuses nothing.
- `enforce` answers those same requests with 429.

The mode switches only the budgets. The in-flight upload cap, the daily email cap and the statement timeout ignore it,
because they protect the heap, the applicant and the database rather than anybody's throughput.

### Switching mode in one environment

Do not change the default in `application.yaml` or the chart's `values.yaml`. A chart default reaches every
environment at once. Set it for one environment in `hmcts/cnp-flux-config`, in
`apps/deed-poll/deed-poll-api/<env>.yaml`:

```yaml
spec:
  values:
    java:
      environment:
        RATE_LIMIT_MODE: enforce
```

The agreed order (OWASP plan 06 Task 11) is: deploy in `observe`, run AAT business testing, set each bucket's limit to
about 3× the observed per-subject p99, then switch to `enforce` in AAT before any other environment. To change a limit
or window for one environment, use the same file: `RATE_LIMIT_<BUCKET>_LIMIT` and `RATE_LIMIT_<BUCKET>_WINDOW`, where
`<BUCKET>` is `SEARCH`, `EMAIL_PREVIEW`, `EMAIL_SEND` or `UPLOAD` (for example `RATE_LIMIT_SEARCH_WINDOW: 1m`). The
other three settings are `RATE_LIMIT_UPLOAD_IN_FLIGHT`, `EMAIL_MAX_PER_CASE_PER_TEMPLATE_PER_DAY` and
`LIST_STATEMENT_TIMEOUT`. A value the properties refuse stops the pod at start-up, so a bad override shows as a failed
rollout and never as a silently unlimited service.

A mode change applies when the pod restarts. Counts survive the restart, because they live in Postgres, so a caller
already over budget in `observe` is refused on their next request after the switch if their window is still open.

## Reading the `rate_limit` lines in App Insights

The filter logs at WARN, and the Application Insights agent collects these lines as traces under the role
`deed-poll-api` (`lib/applicationinsights.json`). Each line has one of these two shapes:

```
rate_limit bucket=<bucket> mode=<observe|enforce> subject=<IDAM sub> count=<n> limit=<l>
rate_limit unavailable bucket=<bucket> reason=<exception class>
```

- **The first line is written once for each request over its limit.** In `observe` the request went through, and in
  `enforce` it got a 429. `count` is that request's place in the window, so a caller three over a limit of 20 logs
  `count=21`, `22` and `23`. Nothing is logged within the limit, and nothing at all in `off`.
- **`bucket=upload-in-flight` always says `mode=enforce`**, because it always refuses. Its `count` is the pod's
  permits plus one and its `limit` is the permits. Many of these lines, from many subjects, mean the pod's upload
  concurrency is too low for real use rather than that someone is abusing it.
- **`subject` is the IDAM `sub`**, a user id and never a name or email address. `no-subject` is the one bucket shared
  by every verified token that has no `sub`. No case data, query text, template slot or filename is ever logged.
- **`unavailable` means the counter could not answer, so the request was admitted uncounted.** A burst of these
  matches a database problem. Nothing was limited while it lasted.

Where each line count is (classic resource; in a workspace-based query use `AppTraces`, `Message` and
`AppRoleName`):

```kusto
traces
| where cloud_RoleName == "deed-poll-api" and message startswith "rate_limit bucket="
| parse message with "rate_limit bucket=" bucket " mode=" mode " subject=" subject " count=" count:long " limit=" limit:long
| summarize lines = count(), peak = max(count) by bucket, mode, subject, bin(timestamp, 1h)
| order by lines desc
```

To tune a limit you need the per-subject peak of ordinary use, including the requests the filter does not log. Get it
from requests, not traces:

```kusto
requests
| where cloud_RoleName == "deed-poll-api" and name == "POST /caseworker/cases/{id}/emails/{template}"
| summarize n = count() by user_AuthenticatedId, bin(timestamp, 1h)
| summarize p99 = percentile(n, 99)
```

If `user_AuthenticatedId` is empty in that environment, count rows in `deedpoll_request_budget` during testing
instead. The table holds one row per (subject, bucket) with the current window's count (`select "bucket",
max("count") from deedpoll_request_budget group by 1`). Requests to `GET /caseworker/cases` arrive with their query
string removed (the agent masks it), so traces cannot tell a search from an unfiltered list read.

The daily email cap logs `Case <id> has reached the daily cap for <template>, so nothing was enqueued` at INFO. A
statement timeout appears as a failed `GET /caseworker/cases` request with a `QueryTimeoutException` or
`canceling statement due to statement timeout` exception.

## Admin's next spec refresh

Admin vendors this api's `openapi.json`, and `libs/deed-poll-api-client/src/api/contract.test.ts` holds its client to
that copy, so the refresh has to land all at once. The six budget 429 responses (and their `Retry-After` header) go into
one refresh with every other api change since the last one. Do not try to spread them over several. The plan
expected that refresh to break pinned `operation.responses` assertions. As of 2026-10-16, admin's test pins one
`operation.responses`, on `GET /caseworker/gazette-notices`. That operation is not limited, so it is unaffected. Admin's
own half of plan 06 (its 429 page, its Redis limiter and its upload cap) is a separate admin plan.
