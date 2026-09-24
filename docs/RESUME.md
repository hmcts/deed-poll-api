# Resume state — where the next session picks up

Rewritten **2026-09-20**; figures and plan states refreshed **2026-09-24** during the email-case-notes review.
The narrative below still needs a proper rewrite — it was written before notify-3 merged. **Read this before
restarting the loop.**

The rule this file exists to serve: **it must describe today, or it actively misleads.** notify-1, notify-5,
notify-3 and notify-3b are merged on `master`. notify-5's four tasks capture the exact subject and plain-text body, provide
the case-scoped correspondence read, return availability, history and slot defaults from the existing Email-tab
GET, and pin the public wording and cross-case security boundary. notify-3 sends the confirmation email itself,
with one referral-outcome email per referral enforced by the database.

## Where the Notify work actually is — read this before anything else

| Where | What is there |
|---|---|
| `master` / `origin/master` | notify-1, notify-5, notify-3 and **notify-3b** complete and merged in PRs #29, #30, #38 and #42, plus three small Notify fixes in #43, #44 and #45 — `applied_on_paper`, the measured JSON boolean, and never printing the API key |
| `email-case-notes` | complete, filed in `plans/completed/`, **unmerged** — one case note when an email is enqueued and one when it terminally fails. **No contract delta**: no migration, no DTO field and no endpoint, so every count below except the test figures is the same as `master`'s |

V11 creates the outbox, V12 persists a machine-readable failure verdict, and V13 adds the
nullable rendered subject and plain-text body. Only a definite permanent refusal permits a replacement
row; its failed history remains unchanged when the replacement is sent.

## What is built

| | |
|---|---|
| Flyway migrations | **18 on `master`**, notify-3b having merged — V14 adds the nullable referral owner subjects, V15 the notification exclusion group, V16 the referral's four confirmation columns, V17 the case's `awaitingConfirmationSince` read-model and V18 the correction to V16's partial index. `email-case-notes` adds none |
| Caseworker endpoints | **34 across 21 paths** on `master`, over eight controllers under `api/caseworker/controller/`. `OpenAPIPublisherTest` asserts both figures |
| Judicial endpoints | **5**, `referrals-api-6` having merged — the shared pool, this judge's own `Claimed`/`Decided` work, one referral, claim, direction. Gated on `Role.JUDICIAL`, **which IDAM still does not issue** (see IDAM below) |
| `/citizen/**` | exists, empty, shares no DTO with the other two namespaces |
| Tests | **2,622** on `email-case-notes` — `test` 2,610 across 331 classes + `integration` 12 across 3, 0 failures and 0 skipped (measured 2026-09-24 from `build/test-results/` after `./gradlew build --rerun-tasks`). `master` read 2,580 + 12 when this branch started. The branches are not stacked, so figures from one are never cumulative with another's |
| Docker-free CI | **1,657 unit tests** on `email-case-notes`, measured with `INTEGRATION_TESTS_ENABLED=false`; the other 953 are in 32 container-backed classes, which this branch added none to. `build.gradle` carries the history and instructs the next plan to re-measure |
| `./gradlew build` | green with `TESTCONTAINERS_RYUK_DISABLED=true` exported — on `email-case-notes` after its review (2026-09-24), and on `master` after notify-3b |

**Twenty-two** plans are in [`plans/completed/`](plans/completed/) — the four added since this section was
last rewritten are the saved-query publishing gate, notify-3, notify-3b and the email case notes. The arc since plan 01: the domain gaps
(notes, witnesses, the enrolment witness-count gate), case query and paging, saved queries and search,
then the original referrals feature in four completed plans — create/withdraw, claim/direction/close,
`currentReferralState`, and the judicial surface — plus two CI plans and the ACR migration.

Five more landed on 2026-09-21: the three referral follow-ups business testing asked for —
caseworker completion, lifecycle case notes and the judge's own worklist (PRs #33, #35, #36) — and
**notify-1 and notify-5, which were merged in PRs #29 and #30 but never filed**, so `plans/` overstated
what was outstanding until they were moved here.

**The original referrals backend is merged, and business testing reopened one rule, which is now closed.**
[`plans/referrals-api-4-caseworker-completion.md`](plans/referrals-api-4-caseworker-completion.md) is
**complete on branch `referrals-api-4`, not yet merged**: a `Decided` referral can no longer be withdrawn,
and the existing `Decided → Closed` caseworker action is untouched. The authority is a second enum predicate,
`ReferralState.canBeWithdrawn()` (`Open || Claimed`), added *beside* `isTerminal()` rather than narrowing it —
narrowing `isTerminal()` is the plausible-looking mistake, and would also have moved the one-live-referral
guard and `currentReferralState`, letting a case raise a second referral beside a decided one. **No contract
delta**: no migration, no enum member, no DTO, 33 caseworker and 4 judicial operations unchanged, which
`OpenAPIPublisherTest` passing untouched is the evidence for. Its companion admin plan adds the missing
closure journey — the API half is already there.
[`plans/referrals-api-5-lifecycle-case-notes.md`](plans/referrals-api-5-lifecycle-case-notes.md) is
**complete on branch `referrals-api-5`, not yet merged**, and records claim, direction and closure in the
existing case Notes timeline: every successful transition now writes exactly one note, composed in
`ReferralNotes` and authored by whoever caused it — the judge on a claim or a direction, the caseworker on a
raise, a withdrawal or a closure, and the service account only on an inactivity lapse, which no person did. A
refused transition, a judicial `touch` and the backfill write none. The direction note summarises the decision
and the Gazette answer and never quotes `directionsText`, which may be twice a note's length. **No contract
delta**: no migration, no DTO, no endpoint, 33 caseworker and 4 judicial operations unchanged — proven by
regenerating the spec from `master` into a throwaway worktree and byte-diffing it, identical at 56,771 bytes.
That 56,771 is now the baseline `referrals-api-6`'s delta was measured against: **57,495 bytes**, the difference
being one path with one GET and one schema, with the rest of the document identical key for key.
[`plans/referrals-api-6-judicial-my-referrals.md`](plans/referrals-api-6-judicial-my-referrals.md) is
**complete on branch `referrals-api-6`, not yet merged**, and adds the missing judge-owned `Claimed`/`Decided`
worklist — `GET /judicial/referrals/mine` — after migrating ownership from display names to stable IDAM subjects,
which is what makes a personal list safe to publish at all: keyed on a name it would have enumerated a namesake's
referrals instead of requiring one of their ids. `V14` adds nullable `claimedById`/`decidedById`, nothing is
backfilled, and a pre-migration row with a null owner belongs to nobody — invisible and undirectable, which is the
fail-closed rollout consequence rather than a gap to patch. **This is the one referral follow-up with a contract
delta**: the judicial operation count is **4 → 5** with one new response schema, `JudicialMyReferralsResponse`,
while `JudicialReferralSummary` is reused unchanged and the caseworker 33 does not move. Neither owner id is
published anywhere.

## The Notify series: six api plans, and the order they should run in

The original five are on `master` as of PR #26; notify-6 was drafted on 2026-09-20. The numbering is the order
they were written, **not** the order to build them:

```
notify-1  the send path                     COMPLETE
notify-5  what was sent, and when            case correspondence history   COMPLETE
notify-3  request confirmation to proceed    three exclusive templates     COMPLETE (PR #38, merged)
notify-3b track the applicant's reply                                      COMPLETE (PR #42, merged)
notify-6  chase missing documents             case-driven, repeatable
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
2. **notify-3 re-keys notify-1 task 7's index, which is now V12.** It must carry V12's null-safe
   permanent-failure predicate through unchanged rather than reconstructing it. Both plans carry the
   reciprocal note. Getting this wrong silently changes which rows the partial index covers.
3. **notify-4's task 1 is a spike that is allowed to fail.** The deed templates the business supplied are
   Dynamics 365 Word templates — 30 content controls bound by XPath to a custom XML part, no merge fields.
   Whether docx4j can bind them is unproven. The fallback is Docmosis used for **conversion only**,
   modelled on finrem's `DocumentConversionService`, which needs no CCD, CDAM, S2S or IDAM — none of
   which this service has.
4. **notify-5 rests on one verified fact.** `SendEmailResponse` carries `getSubject()` and `getBody()`, so
   the rendered email comes back inside the send acceptance: history is exact at no extra Notify call and
   nothing against the rate limit. Confirmed with `javap` against `6.2.0-RELEASE`. If that turns out to be
   wrong, the plan's cost changes completely.
5. **notify-6 assumes the missing-documents template is provisioned.** Steve authorised that assumption for
   planning on 2026-09-20. Its Task 0 still verifies the real placeholder names and reply-to before
   implementation; the Gazette notice remains deferred.

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
  `integration` from `check` and excludes the `requires-docker` tag from `test`. On `email-case-notes` that is
  **1,657 of 2,610** unit tests running in CI, with 953 in 32 container-backed classes.
  `build.gradle` carries the full history and instructs the next plan to re-measure it.
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
2. **No judicial role.** `Role.JUDICIAL` gates every `/judicial/**` operation — four on `master`, five on
   `referrals-api-6` — and IDAM issues no such role, so that surface cannot be exercised by a real token in any
   environment. Its tests are the only proof it works. That is also why `referrals-api-6`'s move to subject-keyed
   ownership needed no backfill: no real judge has ever claimed anything.
3. **No non-admin caseworker role.** `caseworker-deedpoll-admin` is still the only confirmed name. It
   gates one field, the two deletes the business restricts — a note's and a witness's — and publishing or
   amending a shared saved query, all enforced
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
| `notify-6-chase-missing-documents.md` | **Drafted.** Assumes a provisioned template with required caseworker text; Task 0 verifies it |
| `notify-4-forms-for-completion.md` | **Blocked twice** — task 1 is an unproven spike, and five of the seven documents do not exist |
| `role-model-generalisation.md` | **Gated, and may not be needed.** A behaviour-preserving refactor of `boolean isAdmin` into capabilities. Gated on scope question Q0.1 and, preferably, on a first successful deployment — so that a failed deploy is unambiguously a deployment problem and not a 33-site refactor |
| `role-based-access-findings.md` | Findings, not a plan. The measured inventory of what could be role-gated, so the business question is asked against a concrete list |
| `role-based-access-questions-for-the-business.md` | **Resolved 2026-09-11**, never sent; the business's `.docx` answered it. Kept for the reasoning |
| `scope-gap-questions.md` | **Q0.1 answered 2026-09-12** — functional parity with the Power App is the minimum. Now the evidenced gap analysis, with verification commands per row. Six parity gaps still have no plan |

Three files in `plans/` have no row above and need one from whoever knows their state:
`deed-poll-api-01-foundation.md`, `fees-and-pay-1-ways-to-pay-card.md`, and
`jenkins-pipeline-cutover.md` — the last is described as complete in the deployment section above but has
never been moved into `completed/`.

There is also a follow-on owed to **`deed-poll-admin`**: notify-2's decision 8 is `[PROVISIONAL]` and its
decision 11 defers case correspondence history to "a later plan… it is api's data to serve". notify-5 is
that data; once it lands, notify-2 can be amended and both resolved. notify-5's flag 5 records it.
The concrete Task 3 contract is now `CaseEmailsResponse`: `availability` retains the existing
`EmailTypeResponse` entries, with `history` and `slotDefaults` alongside it. Admin should refresh its
generated client from the published spec rather than hand-writing this shape — the caseworker operation
count is now **34**, notify-3b having added the confirmation route.

## Do not let a session drift into

Writing plan 02 or 03 from guesses. Editing `src/test/resources/reference/` — it is a verbatim vendored
copy of the specification and the conformance tests read it as ground truth, so if one looks wrong, stop
and report. Editing a committed Flyway migration — **including `V11__notifications.sql`, which is
committed as V11 and V12**; **`V18` is the highest committed** (`notify-3b` took V16, V17 and V18), so the next
plan takes V19 — confirmed by listing `src/main/resources/db/migration` rather than by a number a plan named in
advance. **Do not reserve a number and jump ahead of it**: Flyway runs with
out-of-order disabled, so a V18 arriving after V19 has been applied fails every environment that applied V19. Inventing
an IDAM role name, a case type or a jurisdiction ID. Inferring the state of a Jenkins build from the code,
rather than asking for the log.

Two habits this repo's plans expect, because breaking them has cost time before: **append corrections
rather than rewriting** a plan, so the reasoning survives; and **verify before asserting** — three claims
in the session that produced notify-4 and notify-5 were wrong and were caught only by checking, one of
them because a `head`-truncated grep was reported as a complete result.

Full context, if a session needs the *why*:
`../deed-poll-admin/.business_context/DEED_POLL_API_PLAN.md`, and for the email work
`NOTIFY_TEMPLATE_TAXONOMY.md` and `NOTIFY_PROVISIONING_REQUEST.md` beside it. Per-clone conventions that
break silently: `../CLAUDE.md` in this repo's root.
