# Resume state — where the next session picks up

Rewritten 2026-09-07, after plan 01 finished and a code review of it. **Read this before restarting
the loop.** The previous version described a half-finished Task 12 and an uncommitted working tree;
neither exists any more, and it was pointing a resumed session at work that was already done.

## Plan 01 is complete

**Branch `deed-poll-api-01-foundation`.** All sixteen tasks in
[`plans/deed-poll-api-01-foundation.md`](plans/deed-poll-api-01-foundation.md) are checked and
committed, one commit per task, plus a review-fixes commit on top. `master` holds only the docs.

| | |
|---|---|
| Tasks complete and committed | **0–16**, all of them |
| Flyway migrations | 4 — enums, `DeedPoll`, children, list indexes |
| Caseworker endpoints | 17 — 13 from plan 01, plus the four witness routes added afterwards |
| `/citizen/**` | exists, empty, shares no DTO with `/caseworker/**` |

`./gradlew build` is green. Two things about running it here, both recorded in the README as well:
export `TESTCONTAINERS_RYUK_DISABLED=true` on this machine, and Testcontainers reaches its
containers over the Docker bridge network rather than a published port (`ContainerDatabase`).

## There is no plan 02 or 03 yet

Both are deliberately unwritten, because both wait on external onboarding:

- **Plan 02 — CCD.** `decentralised = true`, `CCDConfig`, `CaseView`, `/ccd-persistence/*`, the
  case-type definition. The case type and jurisdiction ID are **unconfirmed**. Nothing may invent
  them: `deedpoll.ccd.case-type-id: DEED_POLL_PLACEHOLDER` in `application.yaml` is the single
  constant any reference must go through.
- **Plan 03 — CDAM.** Real document storage, hash tokens, the attach cycle. It replaces
  `LocalFilesystemDocumentStore` behind the existing `DocumentStore` interface, which is the seam
  plan 01 built for it.
- **`deed-poll-web`** — citizen endpoints, payments, anonymous submission — is its own plan, later,
  and is why `/citizen/**` exists empty rather than not at all.

Retiring `apps/api` in the sibling repo and cutting `apps/web` over to HTTP is later work in a
different repo, and must not happen until this service is deployed and serving.

## How this repo deploys

Plan [`plans/jenkins-pipeline-cutover.md`](plans/jenkins-pipeline-cutover.md) is complete. In short:

- **Jenkins CNP is the only pipeline.** `Jenkinsfile_CNP` declares `java` / `deed-poll` / `api`, with
  an empty `withPipeline` body. `.github/workflows/` is gone entirely — build, test and scanning are
  the pipeline's now, and CodeQL has no Jenkins equivalent, which was accepted rather than replaced.
  `OpenAPIPublisherTest` stays and still writes `/tmp/openapi-specs.json`, but **nothing consumes it
  any more.** The only reader was `hmcts/workflow-publish-openapi-spec`, the reusable workflow the
  deleted `publish-openapi.yaml` invoked; `hmcts/cnp-jenkins-library` has no equivalent. So the spec
  no longer reaches `hmcts/cnp-api-docs` — an accepted-for-now gap, not a replacement, and the one
  `.github/workflows/` deletion worth revisiting during onboarding. See the plan's Task 5 notes.
- **This repo owns no Terraform.** `infrastructure/` is deleted and must not come back. The resource
  group, Key Vault, App Insights, Redis and Postgres all live in `hmcts/deed-poll-infra`.
- **It reads a vault it does not create.** `charts/deed-poll-api` reads `deed-poll-{env}`, created by
  the shared root in `hmcts/deed-poll-infra` and shared with `deed-poll-admin` and `deed-poll-web`:
  `app-insights-connection-string` plus four `postgres-*` secrets mapped to `DB_HOST`, `DB_PORT`,
  `DB_USER_NAME` and `DB_PASSWORD`. **`deed-poll-infra` must have applied before this service can
  deploy, and nothing enforces that ordering mechanically** — so a missing secret is a missing apply
  there, not a chart bug. Secret names are fixed by the shared root; renaming happens there or not at
  all.
- **One shared database, `deed-poll`.** Set as a plain environment value, not a secret, because
  `application.yaml` defaults it to `deedpoll` for docker-compose and inheriting that default would
  have Hibernate validate against a database that does not exist.
- **`Jenkinsfile_nightly` is deleted, deliberately.** It was the template's, declaring
  `rpe` / `spring-boot-template` — another team's product code — and it had never run. A nightly
  pipeline brings its own security and cross-browser stages, so configuring one that has never run
  would land two untested things at once. A file naming someone else's product is worse than no file:
  it is the kind of thing that runs once by accident and reports failures against `rpe`. If a nightly
  build is wanted later, add it deliberately with this service's own identity.

The cutover configures the repo. It deploys nothing, and it deliberately raised no Azure-secret or
federated-credential request: Jenkins authenticates as its own managed identity.

## Open items that are Steve's, not a session's

1. **The `cnp-jenkins-config` entry — the one external dependency the cutover leaves.**
   `hmcts/deed-poll-api` has to be added to the repository list in `hmcts/cnp-jenkins-config` before
   Jenkins will build it at all; until then `Jenkinsfile_CNP` is a correct file no job runs. It has
   not been raised yet, and the ordering is the reason: the entry cannot sensibly be requested before
   `Jenkinsfile_CNP` exists and declares the right product and component, which is what the cutover
   just did. Raise it now. `deed-poll-infra` applying is a *separate* prerequisite, for the vault —
   see above.
2. **Deployment is not otherwise onboarded either.** The chart is `charts/deed-poll-api` now, carrying
   this service's name, image, ingress host and `annotations.team: deed-poll`, but no probe path
   points at `/health/readiness` or `/health/liveness`. Jenkins folder configuration is unconfirmed,
   and the `cnp-flux-config` namespace and HelmRelease are outstanding.
3. **`DOCUMENT_STORE_ROOT` must not reach a deployed environment.** Local disk does not survive AKS
   and replicas do not share a volume. Real storage is plan 03.
4. **IDAM roles.** The only role name confirmed is `caseworker-deedpoll-admin`, and it gates one
   field. So `/caseworker/**` is authenticated but not role-gated: naming a non-admin caseworker role
   needs IDAM onboarding, and inventing one would be the same mistake as inventing a case type.
5. **IDAM audiences and issuer.** Both default to empty, which refuses every token. An environment
   with neither answers `/health` and `/v3/api-docs` and 401s everything else.

## Do not let a session drift into

Writing plan 02 or 03 from guesses, editing `src/test/resources/reference/` (it is a vendored copy of
the specification — if one looks wrong, stop and report), or editing a committed Flyway migration.

Full context, if a session needs the *why*:
`../deed-poll-admin/.business_context/DEED_POLL_API_PLAN.md`.
