# Resume state — where the next session picks up

Rewritten **2026-09-18**. **Read this before restarting the loop.**

The previous version was written 7 September and had gone eleven days stale in a way that mattered: it
said "plan 01 is complete, there is no plan 02 or 03" and stopped there, with no mention of the thirteen
plans completed since; and its first open item told the reader to raise a `cnp-jenkins-config` entry
that was merged on 14 September. A resumed session following it would have re-raised a closed request.
The rule this file exists to serve: **it must describe today, or it actively misleads.**

## What is built, on `master`

| | |
|---|---|
| Flyway migrations | **10** — enums, `DeedPoll`, children, list indexes, saved queries, referrals + backfill, claim/direction, `currentReferralState` + backfill |
| Caseworker endpoints | **30**, across seven controllers under `api/caseworker/controller/` |
| Judicial endpoints | **4** on `/judicial/**` — the shared pool, one referral, claim, direction. Gated on `Role.JUDICIAL`, **which IDAM still does not issue** (see IDAM below) |
| `/citizen/**` | exists, empty, shares no DTO with the other two namespaces |
| Tests | **2,106** — `test` 2,098 + `integration` 8. Measured 2026-09-18 after `referrals-api-3` |
| `./gradlew build` | green locally, with `TESTCONTAINERS_RYUK_DISABLED=true` exported |

Thirteen plans are in [`plans/completed/`](plans/completed/). The arc since plan 01: the domain gaps
(notes, witnesses, the enrolment witness-count gate), case query and paging, saved queries and search,
then the whole referrals feature in four plans — create/withdraw, claim/direction/close,
`currentReferralState`, and the judicial surface — plus two CI plans and the ACR migration.

**The referrals backend is finished.** All four api-side plans are merged. The remaining referrals work
is in `deed-poll-admin`, not here.

## What the next session does: `notify-1`

Branch **`notify-plans`**, one commit ahead of `master`, pushed, no PR. It carries one file:
[`plans/notify-1-email-plumbing.md`](plans/notify-1-email-plumbing.md) — 408 lines, six tasks, reviewed
and merged as a plan on 18 September. **No implementation exists**: no `notifications-java-client` in
`build.gradle`, no notify package, no migration.

It gives the service a GOV.UK Notify send path and proves it with exactly one template — the
*Application Rejected* email — behind a WireMock double. Four things to hold before starting:

1. **It cannot send a real email, and preview cannot render, until `notify-0`.** No Notify service exists
   for deed poll: no API key, no templates, no template IDs. That is expected, not a defect, and Task 1
   establishes the placeholder convention. `notify-0` is Steve's.
2. **Measure the baseline on the branch before Task 1.** The plan says 2,106 and says to confirm rather
   than assume. Sum `tests="…"` across `build/test-results/{test,integration}/*.xml`.
3. **The outbox is drained by a mutating read, not a scheduler** — deliberately, matching
   `ReferralService`'s inactivity sweep and the judicial pool's `reclaimStaleClaims()`. Decision 4 in the
   plan explains why, and decision 5 says to re-read `ReferralRepository.releaseStaleClaim` before Task 2.
   This is the one place the plan diverges from `pcs-api`, knowingly.
4. **The plan's seven flags for Steve are unresolved.** Flag 2 is the one that could bite a session: the
   400/403-permanent, 429/500-temporary error taxonomy is read off `pcs-api`, not off Notify's docs, which
   are blocked from this devcontainer. The method signatures *were* verified with `javap`. The taxonomy
   was not.

## Deployment — what is actually true

Plan [`plans/jenkins-pipeline-cutover.md`](plans/jenkins-pipeline-cutover.md) is complete and its
settled decisions still hold: **Jenkins CNP is the only pipeline** (`.github/workflows/` is gone,
CodeQL has no equivalent and that was accepted); **this repo owns no Terraform** — the resource group,
Key Vault, App Insights, Redis and Postgres all live in `hmcts/deed-poll-infra`; **`Jenkinsfile_nightly`
is deleted deliberately**, because the template's named `rpe` / `spring-boot-template`, another team's
product. `OpenAPIPublisherTest` still writes `/tmp/openapi-specs.json` and **nothing consumes it** — the
spec no longer reaches `hmcts/cnp-api-docs`, an accepted gap worth revisiting.

What has moved since 7 September:

- **`cnp-jenkins-config` is done** — merged 14 September, as one PR covering all four repos, not four
  requests. Jenkins will build this repo.
- **`deed-poll-infra` has applied, for AAT only** — 15 September. The `deed-poll-aat` vault, Postgres and
  Redis exist. `demo`, `ithc`, `perftest` and `prod` do **not**, which is why several config files carry
  environment exclusion lists.
- **The ACR migration is done** — `hmctspublic` → `hmctsprod` in `Dockerfile`, `Chart.yaml` and
  `values.yaml`. That nagger was what failed master build #3.
- **CI runs without Docker.** `INTEGRATION_TESTS_ENABLED=false` (set by `Jenkinsfile_CNP`) drops
  `integration` from `check` and excludes the `requires-docker` tag from `test`. **1,277 of the 2,098
  unit tests run in CI**; the other 821 sit in 27 container-backed classes. Re-measure rather than trust
  that split — it moves whenever such a class is added, and `build.gradle:87-98` carries the history.
- **The chart is wired for workload identity** — `useWorkloadIdentity: true`, `saEnabled: true`, reading
  the shared `deed-poll-{env}` vault for `app-insights-connection-string` and the four `postgres-*`
  secrets. The matching identity, federated credential and service-account annotation are
  `cnp-flux-config`'s, not this repo's.

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
   configured and unused.
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

1. **The backend's `.internal` address does not exist.** `charts/deed-poll-api/values.yaml` sets
   `ingressHost` to `deed-poll-api-{env}.service.core-compute-{env}.internal`, which `deed-poll-admin`'s
   chart already expects. That name is created by a two-line entry in `backend_lb_config.yaml` in
   **`hmcts/azure-platform-terraform`** — not by Jenkins, not by Flux, not by this chart. An earlier plan
   that proposed creating it through the deployment tooling was set aside on 17 September for exactly that
   reason. **Not yet raised.**
2. **No probe paths.** `charts/deed-poll-api/` has no `readiness`/`liveness` configuration at all, so
   nothing points at `/health/readiness` or `/health/liveness`. The `cnp-flux-config` namespace and
   HelmRelease are also outstanding, and Jenkins folder configuration is unconfirmed.
3. **`notify-0`** — create the GOV.UK Notify service, API key and templates; register and verify a
   monitored reply-to inbox (flag 5: three of four templates invite a reply); record the real rate limit
   (flag 3: preview calls Notify).
4. **`DOCUMENT_STORE_ROOT` must not reach a deployed environment.** Local disk does not survive AKS and
   replicas do not share a volume. Real storage is plan 03.
5. **A Docker-capable CNP agent** is the real fix for the CI gating, and is an external dependency —
   tracked in `cft-workspace/TRACKER.local.md` §4.

## There is still no plan 02 or 03

Both remain deliberately unwritten, and for unchanged reasons:

- **Plan 02 — CCD.** `decentralised = true`, `CCDConfig`, `CaseView`, `/ccd-persistence/*`, the case-type
  definition. The case type and jurisdiction ID are **unconfirmed**; `deedpoll.ccd.case-type-id:
  DEED_POLL_PLACEHOLDER` in `application.yaml` is the single constant any reference must go through.
- **Plan 03 — CDAM.** Real document storage, hash tokens, the attach cycle, replacing
  `LocalFilesystemDocumentStore` behind the existing `DocumentStore` seam.

Retiring `apps/api` in the sibling repo and cutting `apps/web` over to HTTP is later work in a different
repo, and must not happen until this service is deployed and serving.

## Live plans and findings not yet executed

Everything in [`plans/`](plans/) that is not in `completed/`, and why it is sitting there:

| File | State |
|---|---|
| `notify-1-email-plumbing.md` | **Next.** Ready to run; see above |
| `role-model-generalisation.md` | **Gated, and may not be needed.** A behaviour-preserving refactor of `boolean isAdmin` into capabilities. Gated on scope question Q0.1 and, preferably, on a first successful deployment — so that a failed deploy is unambiguously a deployment problem and not a 33-site refactor |
| `role-based-access-findings.md` | Findings, not a plan. The measured inventory of what could be role-gated, so the business question is asked against a concrete list |
| `role-based-access-questions-for-the-business.md` | **Resolved 2026-09-11**, never sent; the business's `.docx` answered it. Kept for the reasoning |
| `scope-gap-questions.md` | **Q0.1 answered 2026-09-12** — functional parity with the Power App is the minimum. Now the evidenced gap analysis, with verification commands per row |

## Do not let a session drift into

Writing plan 02 or 03 from guesses. Editing `src/test/resources/reference/` — it is a verbatim vendored
copy of the specification and the conformance tests read it as ground truth, so if one looks wrong, stop
and report. Editing a committed Flyway migration. Inventing an IDAM role name, a case type or a
jurisdiction ID. Inferring the state of a Jenkins build from the code, rather than asking for the log.

Full context, if a session needs the *why*:
`../deed-poll-admin/.business_context/DEED_POLL_API_PLAN.md`. Per-clone conventions that break silently:
`../CLAUDE.md` in this repo's root.
