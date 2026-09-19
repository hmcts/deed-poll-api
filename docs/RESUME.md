# Resume state — where the next session picks up

Rewritten **2026-09-19**. **Read this before restarting the loop.**

The rule this file exists to serve: **it must describe today, or it actively misleads.** The 18 September
version was accurate when written and has gone stale in one way that matters a great deal — it says
notify-1 has no implementation, "no `notifications-java-client` in `build.gradle`, no notify package, no
migration". All three now exist, and **none of them are on `master`**. A session that trusted that
sentence would rebuild sixteen classes and a migration that are already written. See the next section
first; it is the only part of this file that can waste a whole session.

## Where the Notify work actually is — read this before anything else

Three places, and only one of them is shared:

| Where | What is there |
|---|---|
| `origin/master` | **Plans only.** Five documents. No notify code, no notify migration, no dependency |
| Local branch `notify-plans` | **Four commits, unpushed.** notify-1 tasks 1–4 built: the client, the outbox, the template, the three operations |
| The working tree, uncommitted | **notify-1 task 5 in flight** — the drain that actually sends. Six modified files plus a new `NotifyEmailSender.java` |

**`origin/notify-plans` is stale** — it holds the plan that merged as PR #25, not the four commits since.
Do not push over the local branch, and do not assume the remote reflects the work.

What task 5 leaves half-done, from the working tree: `NotifyEmailSender` is new and untracked;
`NotificationSender`, `SendOutcome`, `NotificationOutbox`, `EmailTemplate` and `CaseworkerReadController`
are modified. The last of those is the drain being pinned to the caseworker case-list read, which is the
part with the contingency attached — **if that read is `readOnly = true`, drain in its own transaction and
say so in the javadoc rather than widening it.**

Also uncommitted: three container-backed tests in `NotificationRepositoryTest` that prove task 7's defect
against a real Postgres. They were green when last run (11 tests, 0 failures) and are the first coverage
`enqueue` has ever had — every other test of it mocks the repository. **They are the evidence for task 7.
Do not lose them.**

**`docs/plans/*` will collide on the next rebase.** The four new plans were merged to `master` as PR #26
while the same files sit untracked on `notify-plans`. The content is identical, so it is a discard, not a
merge — `git stash -u -- docs/plans && git rebase origin/master && git stash drop`. It looks alarming and
is not.

## What is built, on `master`

| | |
|---|---|
| Flyway migrations | **10** — enums, `DeedPoll`, children, list indexes, saved queries, referrals + backfill, claim/direction, `currentReferralState` + backfill. `V11__notifications.sql` exists **only on `notify-plans`** |
| Caseworker endpoints | **30**, across seven controllers under `api/caseworker/controller/`. `notify-plans` adds an eighth, `CaseworkerEmailController` |
| Judicial endpoints | **4** on `/judicial/**` — the shared pool, one referral, claim, direction. Gated on `Role.JUDICIAL`, **which IDAM still does not issue** (see IDAM below) |
| `/citizen/**` | exists, empty, shares no DTO with the other two namespaces |
| Tests | **2,106** on `master` — `test` 2,098 + `integration` 8. `notify-plans` is at **2,267** after task 4, and task 5's tests are not in that figure |
| `./gradlew build` | green on `master` locally, with `TESTCONTAINERS_RYUK_DISABLED=true` exported |

Thirteen plans are in [`plans/completed/`](plans/completed/). The arc since plan 01: the domain gaps
(notes, witnesses, the enrolment witness-count gate), case query and paging, saved queries and search,
then the whole referrals feature in four plans — create/withdraw, claim/direction/close,
`currentReferralState`, and the judicial surface — plus two CI plans and the ACR migration.

**The referrals backend is finished.** All four api-side plans are merged. The remaining referrals work
is in `deed-poll-admin`, not here.

## The Notify series: five plans, and the order they should run in

All five are on `master` as of PR #26. The numbering is the order they were written, **not** the order to
build them:

```
notify-1  finish task 5, then 6, then 7      the send path                 IN FLIGHT
notify-5  what was sent, and when            case correspondence history   DO THIS NEXT
notify-3  request confirmation to proceed    three exclusive templates
notify-3b track the applicant's reply
notify-4  generate the deed forms            needs a spike first
```

**notify-5 before notify-4 is deliberate**, and it is notify-5's flag 4. notify-4 attaches legal documents
to emails; shipping that while a permanent failure is still invisible means an applicant who never
receives their deed forms and a court that cannot tell. notify-5 also depends on nothing from `notify-0`,
so it can run while template authoring is still outstanding — which no other plan in the series can.

Four things a session should hold before touching any of them:

1. **Nothing can send a real email, and preview cannot render, until `notify-0`.** No Notify service
   exists for deed poll: no API key, no templates, no template IDs. That is expected, not a defect;
   notify-1 task 1 establishes the placeholder convention. `notify-0` is Steve's.
2. **notify-3 re-keys notify-1 task 7's index, and task 7 is not written yet.** Whichever runs second must
   read the other's migration rather than reconstruct the predicate. Both plans now carry the reciprocal
   note. Getting this wrong silently changes which rows the partial index covers.
3. **notify-4's task 1 is a spike that is allowed to fail.** The deed templates the business supplied are
   Dynamics 365 Word templates — 30 content controls bound by XPath to a custom XML part, no merge fields.
   Whether docx4j can bind them is unproven. The fallback is Docmosis used for **conversion only**,
   modelled on finrem's `DocumentConversionService`, which needs no CCD, CDAM, S2S or IDAM — none of
   which this service has.
4. **notify-5 rests on one verified fact.** `SendEmailResponse` carries `getSubject()` and `getBody()`, so
   the rendered email comes back inside the send acceptance: history is exact at no extra Notify call and
   nothing against the rate limit. Confirmed with `javap` against `6.2.0-RELEASE`. If that turns out to be
   wrong, the plan's cost changes completely.

**There is no download tracking and there never can be.** No download concept exists anywhere in the
Notify Java client, and `getStatus()` is delivery status only. notify-5 owns the wording as much as the
data, because a screen saying "Sent" gets read as "they have it".

## Deployment — what is actually true

Plan [`plans/jenkins-pipeline-cutover.md`](plans/jenkins-pipeline-cutover.md) is complete and its
settled decisions still hold: **Jenkins CNP is the only pipeline** (`.github/workflows/` is gone,
CodeQL has no equivalent and that was accepted); **this repo owns no Terraform** — the resource group,
Key Vault, App Insights, Redis and Postgres all live in `hmcts/deed-poll-infra`; **`Jenkinsfile_nightly`
is deleted deliberately**, because the template's named `rpe` / `spring-boot-template`, another team's
product. `OpenAPIPublisherTest` still writes `/tmp/openapi-specs.json` and **nothing consumes it** — the
spec no longer reaches `hmcts/cnp-api-docs`, an accepted gap worth revisiting.

Everything in this section was last verified **2026-09-18**; nothing since has touched deployment, and
nothing in this repo can confirm the external half. Re-check rather than quote.

- **`cnp-jenkins-config` is done** — merged 14 September, as one PR covering all four repos, not four
  requests. Jenkins will build this repo.
- **`deed-poll-infra` has applied, for AAT only** — 15 September. The `deed-poll-aat` vault, Postgres and
  Redis exist. `demo`, `ithc`, `perftest` and `prod` do **not**, which is why several config files carry
  environment exclusion lists.
- **The ACR migration is done** — `hmctspublic` → `hmctsprod` in `Dockerfile`, `Chart.yaml` and
  `values.yaml`. That nagger was what failed master build #3.
- **CI runs without Docker.** `INTEGRATION_TESTS_ENABLED=false` (set by `Jenkinsfile_CNP`) drops
  `integration` from `check` and excludes the `requires-docker` tag from `test`. On `master` that is
  **1,277 of 2,098** unit tests running in CI, with 821 in 27 container-backed classes. On `notify-plans`
  after task 4 it is **1,407 of 2,267**, with 860 in 29 classes — task 2 added `NotificationRepositoryTest`
  and task 4 added `CaseworkerEmailControllerTest`. `build.gradle:87-99` carries the full history and
  **instructs you to re-measure and update it whenever a container-backed class is added**, which task 5
  and every later Notify plan will do.
- **The chart is wired for workload identity** — `useWorkloadIdentity: true`, `saEnabled: true`, reading
  the shared `deed-poll-{env}` vault for `app-insights-connection-string` and the four `postgres-*`
  secrets. The matching identity, federated credential and service-account annotation are
  `cnp-flux-config`'s, not this repo's. **A Notify API key will need adding to that vault and to this
  list** — `notify-0`'s other half, and easy to forget because it is not in this repo.

**This repo's first master build has still never been proven green.** Both CI plans say so explicitly:
`build.hmcts.net` is behind Azure AD App Proxy and unreachable from the devcontainer, the Docker-less
agent could not be faithfully imitated locally (`~/.testcontainers.properties` pins
`UnixSocketClientProviderStrategy` and finds the socket regardless of `DOCKER_HOST`), and so whether the
surviving 1,277 pass without a daemon is **the untested step**. Do not infer the build's state from the
code. Ask for the log.

## IDAM — what landed, and what it does not give this service

`hmcts/idam-access-config#993` **merged 2026-09-16**. `apps/deed-poll/base/` now holds `services.yaml`
and `roles.yaml`. What that registered:

- **One OAuth client, `deed-poll-admin`** — authorization_code + refresh_token, MFA off, MOJ SSO,
  redirect `https://deed-poll-admin.<<ENV>>.platform.hmcts.net/oauth2/callback`. Excluded in `demo`,
  `ithc`, `perftest` and `prod`, so in practice **AAT only**.
- **One role, `caseworker-deedpoll-admin`**, same exclusions, `managed_by: ccd-admin`.

What it does **not** give, and each of these is a real constraint on what a session may build:

1. **No `deed-poll-api` client and no S2S registration.** This service is a resource server; it validates
   tokens minted for `deed-poll-admin`. Nothing here calls another CFT service yet, so `idam.s2s-auth` is
   configured and unused. **Notify does not change this** — it is a direct HTTPS call with its own API
   key, not a CFT service behind S2S, which is precisely why the whole Notify series can proceed without
   the registration.
2. **No judicial role.** `Role.JUDICIAL` gates the four `/judicial/**` operations and IDAM issues no such
   role, so that surface cannot be exercised by a real token in any environment. Its tests are the only
   proof it works.
3. **No non-admin caseworker role.** `caseworker-deedpoll-admin` is still the only confirmed name. It
   gates one field plus the two deletes the business restricts — a note's and a witness's, both enforced
   in the domain. **Naming a second role is still inventing one**, and is the same mistake as inventing a
   case type.
4. **`IDAM_API_ISSUER_URL` and `IDAM_API_AUDIENCES` are still unset by the chart**, and both default to
   empty, which refuses every token (deliberately — an unset value must not be the permissive case). The
   *audience* is now knowable, because the client id is confirmed as `deed-poll-admin`; the issuer URL is
   not confirmed and endpoints are discovered from it, never configured. An environment with neither
   answers `/health` and `/v3/api-docs` and 401s everything else. The chart's `values.yaml` says why it
   names no IDAM secret: the vault does not hold one, and a chart naming an absent secret fails to deploy.

## Open items that are Steve's, not a session's

1. **`notify-0`** — create the GOV.UK Notify service, API key and templates; turn on **send a file by
   email** (notify-4 needs it, and it is off by default); register and verify a monitored reply-to inbox
   (three of four templates invite a reply); record the real rate limit and the real retention default.
   The checklist is `../deed-poll-admin/.business_context/NOTIFY_PROVISIONING_REQUEST.md`, corrected and
   merged 19 September. **All five Notify plans depend on this and none of them can be demonstrated
   without it.**
2. **The five blank forms are not in `doc_templates/`.** LOC021, LOC023, LOC024, LOC027 and LOC028 are
   named by the taxonomy and attached by notify-4, and nobody has supplied them. Two of the seven
   documents (LOC020 adult, LOC022 minor) are generated and present; the other five are static and
   missing. **notify-4 cannot finish without them.**
3. **What `signatureRequestedDate` means.** `CaseState.deriveState` derives `AwaitingSignature` from it
   being non-null, so sending the forms-for-completion email *is* the signature request — but it is also
   patchable through `DeedPollPatch`, so a caseworker can set it by hand. notify-3b flag 1 and notify-4
   flag 2 ask the same question and should be answered together.
4. **`DOCUMENT_STORE_ROOT` must not reach a deployed environment — and this is now urgent.** Local disk
   does not survive AKS and replicas do not share a volume. It was tolerable while nothing wrote
   documents; notify-4 generates deeds and stores them, so the gap becomes a data-loss gap. Real storage
   is plan 03.
5. **The backend's `.internal` address does not exist.** `charts/deed-poll-api/values.yaml` sets
   `ingressHost` to `deed-poll-api-{env}.service.core-compute-{env}.internal`, which `deed-poll-admin`'s
   chart already expects. That name is created by a two-line entry in `backend_lb_config.yaml` in
   **`hmcts/azure-platform-terraform`** — not by Jenkins, not by Flux, not by this chart. An earlier plan
   that proposed creating it through the deployment tooling was set aside on 17 September for exactly that
   reason. **Not yet raised.**
6. **No probe paths.** `charts/deed-poll-api/` has no `readiness`/`liveness` configuration at all, so
   nothing points at `/health/readiness` or `/health/liveness`. The `cnp-flux-config` namespace and
   HelmRelease are also outstanding, and Jenkins folder configuration is unconfirmed.
7. **A Docker-capable CNP agent** is the real fix for the CI gating, and is an external dependency —
   tracked in `cft-workspace/TRACKER.local.md` §4.

## There is still no plan 02 or 03

Both remain deliberately unwritten, and for unchanged reasons:

- **Plan 02 — CCD.** `decentralised = true`, `CCDConfig`, `CaseView`, `/ccd-persistence/*`, the case-type
  definition. The case type and jurisdiction ID are **unconfirmed**; `deedpoll.ccd.case-type-id:
  DEED_POLL_PLACEHOLDER` in `application.yaml` is the single constant any reference must go through.
- **Plan 03 — CDAM.** Real document storage, hash tokens, the attach cycle, replacing
  `LocalFilesystemDocumentStore` behind the existing `DocumentStore` seam. **notify-4 now pushes on
  this** — see open item 4.

Their absence is also why notify-4 does **not** use `doc-assembly` the way `pcs-api` does:
`DocAssemblyRequest` needs a `caseTypeId` and `jurisdictionId`, and `DocAssemblyResponse` returns a
`renditionOutputLocation` and a `hashToken` rather than bytes — so that route would couple the Notify
series to both unwritten plans. Verified against the client's source, not assumed.

Retiring `apps/api` in the sibling repo and cutting `apps/web` over to HTTP is later work in a different
repo, and must not happen until this service is deployed and serving.

## Live plans and findings not yet executed

Everything in [`plans/`](plans/) that is not in `completed/`, and why it is sitting there:

| File | State |
|---|---|
| `notify-1-email-plumbing.md` | **In flight.** Tasks 1–4 committed on `notify-plans`, unpushed; task 5 uncommitted in the working tree; tasks 6 and 7 not started |
| `notify-5-case-correspondence-history.md` | **Next after notify-1.** Needs nothing from `notify-0`. Flag 4 argues the ordering |
| `notify-3-request-confirmation-to-proceed.md` | Ready. Re-keys notify-1 task 7's index — read whichever migration exists first |
| `notify-3b-confirmation-tracking.md` | Ready, but flag 1 wants a business answer on `signatureRequestedDate` |
| `notify-4-forms-for-completion.md` | **Blocked twice** — task 1 is an unproven spike, and five of the seven documents do not exist |
| `role-model-generalisation.md` | **Gated, and may not be needed.** A behaviour-preserving refactor of `boolean isAdmin` into capabilities. Gated on scope question Q0.1 and, preferably, on a first successful deployment — so that a failed deploy is unambiguously a deployment problem and not a 33-site refactor |
| `role-based-access-findings.md` | Findings, not a plan. The measured inventory of what could be role-gated, so the business question is asked against a concrete list |
| `role-based-access-questions-for-the-business.md` | **Resolved 2026-09-11**, never sent; the business's `.docx` answered it. Kept for the reasoning |
| `scope-gap-questions.md` | **Q0.1 answered 2026-09-12** — functional parity with the Power App is the minimum. Now the evidenced gap analysis, with verification commands per row. Six parity gaps still have no plan |

There is also a follow-on owed to **`deed-poll-admin`**: notify-2's decision 8 is `[PROVISIONAL]` and its
decision 11 defers case correspondence history to "a later plan… it is api's data to serve". notify-5 is
that data; once it lands, notify-2 can be amended and both resolved. notify-5's flag 5 records it.

## Do not let a session drift into

Writing plan 02 or 03 from guesses. Editing `src/test/resources/reference/` — it is a verbatim vendored
copy of the specification and the conformance tests read it as ground truth, so if one looks wrong, stop
and report. Editing a committed Flyway migration — **including `V11__notifications.sql`, which is
committed on `notify-plans`**; notify-3 and notify-4 each take the next free number and say so. Inventing
an IDAM role name, a case type or a jurisdiction ID. Inferring the state of a Jenkins build from the code,
rather than asking for the log. Assuming `origin/notify-plans` reflects the local branch — it does not.

Two habits this repo's plans expect, because breaking them has cost time before: **append corrections
rather than rewriting** a plan, so the reasoning survives; and **verify before asserting** — three claims
in the session that produced notify-4 and notify-5 were wrong and were caught only by checking, one of
them because a `head`-truncated grep was reported as a complete result.

Full context, if a session needs the *why*:
`../deed-poll-admin/.business_context/DEED_POLL_API_PLAN.md`, and for the email work
`NOTIFY_TEMPLATE_TAXONOMY.md` and `NOTIFY_PROVISIONING_REQUEST.md` beside it. Per-clone conventions that
break silently: `../CLAUDE.md` in this repo's root.
