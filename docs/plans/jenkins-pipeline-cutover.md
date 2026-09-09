# Plan: Jenkins CNP pipeline cutover

## Overview

Make this repo deployable. Everything here is pipeline and chart configuration — **no application code,
no domain logic, no tests of behaviour**. It is deliberately not numbered `02`, because plans 02 and 03
are CCD and CDAM (`.business_context/DEED_POLL_API_PLAN.md`) and this is orthogonal to both: it can run
before, after or between them.

The repo currently carries the `spring-boot-template`'s identity throughout. `Jenkinsfile_CNP` declares
product `rpe` and component `demo`, `Jenkinsfile_nightly` declares `rpe`/`spring-boot-template`, and the
Helm chart is still `charts/rpe-spring-boot-template` with its `keyVaults` block commented out. None of it
has ever been run, so none of it is known-good — it is untouched scaffolding, and `rpe` is the template
author's product code, not ours.

### Source material — read these, do not re-derive them

| What | Where |
|---|---|
| Target state, five decisions, and which repo owns what | `../deed-poll-admin/.business_context/DEED_POLL_INFRA_PLAN.md` |
| The shared root this chart's vault comes from | `../deed-poll-infra/` and its `docs/plans/01-jenkins-and-shared-split.md` |
| A configured `withPipeline` for a Java service | `../apps/pcs/pcs-api/Jenkinsfile_CNP` |
| A configured chart with a real `keyVaults` block | `../deed-poll-admin/apps/*/helm/values.yaml` |
| Which variables the pipeline supplies | the table in `DEED_POLL_INFRA_PLAN.md` under "The one thing that is not a move" — read from `hmcts/cnp-jenkins-library`, not inferred |

### Architecture decisions already taken — implement these, do not revisit

1. **This repo owns no Terraform.** The resource group, Key Vault, App Insights, Redis and Postgres all
   live in `hmcts/deed-poll-infra`. Do not keep or recreate `infrastructure/`.
2. **Jenkins CNP only.** `.github/workflows/` goes entirely — not just the deploy-side files. CodeQL has
   no Jenkins equivalent and losing it is accepted.
3. **Product `deed-poll`, component `api`.** So the chart is `charts/deed-poll-api`, per the CNP
   `{product}-{component}` convention, and the vault is `deed-poll-{env}`.
4. **One shared database named `deed-poll`.** This service owns the `public` and `ccd` schemas via Flyway;
   `deed-poll-admin` owns a `session` schema in the same database. Do not provision a second one.
5. **Secret names are fixed by the shared root** — `app-insights-connection-string`, `postgres-host`,
   `postgres-user`, `postgres-password`, `postgres-port`, `postgres-url`. Read them; do not rename them
   here. Renaming happens in `deed-poll-infra` or not at all.

### Constraints

- **This repo cannot deploy until `deed-poll-infra` has applied once**, because the vault it reads is
  created there and **nothing enforces that ordering mechanically**. Do not treat a missing secret as a
  chart bug.
- **Do not add a federated-credential or Azure-secret request.** Jenkins authenticates as its own managed
  identity; that is the whole reason the pipeline decision removed four missing secrets.
- **`./gradlew build` must stay green throughout.** None of this work touches code that could break it, so
  a red build means something was deleted that was load-bearing.
- Export `TESTCONTAINERS_RYUK_DISABLED=true` — see this repo's `CLAUDE.md`.

## Validation Commands
- `./gradlew build`

> Every task. It runs compilation, checkstyle, unit tests and the integration source set. Nothing in this
> plan should change its result: if it goes from green to red, the last task removed something the build
> needed rather than something the template left behind.

### Task 1: Give `Jenkinsfile_CNP` this service's identity

- [x] Change `product` from `"rpe"` to `"deed-poll"` and `component` from `"demo"` to `"api"`. Leave `type = "java"`
- [x] Delete the template's comment pointing at the "new microservice" onboarding page — it is advice for someone starting from the template, and it is the reason this file was never edited
- [x] Leave the `withPipeline` body empty. Extra stages are a separate decision and an empty body is the working default
- [x] Mark completed

### Task 2: Decide `Jenkinsfile_nightly`, and act on the decision

It declares `rpe` / `spring-boot-template` and has never run. A nightly pipeline carries its own security
and cross-browser stages, so configuring one that has never run means two untested things landing together.

- [x] **Delete it.** A nightly pipeline can be added deliberately when someone wants one, and a file naming another team's product is worse than no file: it is the kind of thing that runs once by accident and reports failures against `rpe`
- [x] Record the deletion in `docs/RESUME.md` so it reads as a decision rather than an oversight
- [x] Mark completed

### Task 3: Rename and configure the Helm chart

`charts/rpe-spring-boot-template/` must become `charts/deed-poll-api/`. The CNP chart resolves the vault
from the chart's own metadata, so this is not cosmetic.

- [x] `git mv charts/rpe-spring-boot-template charts/deed-poll-api`
- [x] In `Chart.yaml`: `name: deed-poll-api`, a description naming this service, `home` pointing at `https://github.com/hmcts/deed-poll-api`, and drop the `HMCTS rpe team` maintainer. Add `annotations: team: deed-poll` — `deed-poll-admin`'s and `deed-poll-web`'s charts both carry it, and the CNP tooling reads `annotations.team` as the product
- [x] Leave the `java` chart dependency and its version alone
- [x] Grep the whole repo for `rpe-spring-boot-template` and `rpe` afterwards. A path left behind in a values file or a CI reference fails at deploy time, not at build time — `values.yaml`'s `image`, `ingressHost` and `aadIdentityName` were all `rpe`/`spring-boot-template` and are now this service's; `README.md`, `docs/RESUME.md` and `docs/DEPLOYMENT.md` all named the old chart path and now name the new one. The remaining `rpe` hits are the commented-out `keyVaults` block, `lib/applicationinsights.json` and `application.yaml`'s `/mnt/secrets/rpe/` configtree — all of which are the vault mount, i.e. task 4
- [x] Mark completed

### Task 4: Wire the vault secrets this service actually reads

Uncomment and replace the template's `keyVaults` block. **The mapping is not the same shape as the Node
repos':** `application.yaml` reads the database as five separate variables, not one URL.

- [x] `keyVaults: deed-poll:` — the vault is `deed-poll-{env}`, shared with the other two services
- [x] Map `postgres-host` → `DB_HOST`, `postgres-port` → `DB_PORT`, `postgres-user` → `DB_USER_NAME`, `postgres-password` → `DB_PASSWORD`, matching `application.yaml` lines 83–85. **Not `postgres-url`** — that secret exists for the Node services, which want a composed URL. Both forms are provisioned by the shared root precisely because the two runtimes want different shapes
- [x] Set `DB_NAME` as a plain environment value, not a secret, to `deed-poll` — the shared database. Note `application.yaml` defaults it to `deedpoll` for local development, which is a different name; the deployed value must be explicit or Hibernate validates against a database that does not exist
- [x] Map `app-insights-connection-string`. **`application.yaml:152-154` currently reads `${rpe.AppInsightsInstrumentationKey:...}`** — an instrumentation key under an `rpe`-prefixed property, which is the template's. Change it to read the connection string, which is what the shared root writes and what Application Insights has wanted since instrumentation keys were deprecated
- [x] Do **not** add `idam-client-secret` or any S2S secret. Neither exists in the vault: IDAM has not replied, and S2S is deliberately out of the first apply. A chart that names a missing secret fails to deploy, which is the right way round but not yet
- [x] Mark completed

Two things this task had to settle that the plan did not name:

- **The vault mount path, in two more places.** `keyVaults` is keyed on `deed-poll`, and the java chart
  mounts at `/mnt/secrets/{that key}`. So `application.yaml`'s configtree import and
  `lib/applicationinsights.json`'s `${file:...}` both had to move off `/mnt/secrets/rpe/`. They were
  the last two `rpe` hits task 3 deferred here. `app-insights-connection-string` therefore carries **no
  alias**: it has to stay a file for the agent to read, and the configtree turns the same file into the
  property `application.yaml` binds.
- **`DB_OPTIONS: "?sslmode=require&gssEncMode=disable"`**, a sixth plain environment value.
  `application.yaml` already appends `${DB_OPTIONS:}` to the JDBC URL, and Azure Postgres flexible
  refuses a cleartext connection that the driver will not negotiate by default — so without it every
  connection fails at startup. `postgres-url`'s own value in `deed-poll-infra` ends `?sslmode=require`
  for the same reason; `pcs-api` sets the same pair.

### Task 5: Delete `infrastructure/` and `.github/workflows/`

- [x] Delete `infrastructure/` in full — `main.tf` is a bare provider block, the three tfvars are empty, and nothing in it has ever been applied. The shared resources live in `hmcts/deed-poll-infra`
- [x] Delete `.github/workflows/` in full: `ci.yml`, `codeql.yml`, `publish-openapi.yaml`. The CNP pipeline covers build, test and scanning. It does **not** cover spec publishing — see below, that one is a real loss rather than a replacement
- [x] Keep everything else under `.github/` that is not a workflow — `CODEOWNERS`, `CONTRIBUTING.md`, `ISSUE_TEMPLATE.md`, `PULL_REQUEST_TEMPLATE.md`, `renovate.json`, `stale.yml` all stay
- [x] Update `CLAUDE.md`'s "Build and test" section if it references any deleted workflow — it does not; it only documents `./gradlew` invocations, so no change was needed
- [x] Mark completed

Three things this task had to settle that the plan did not name:

- **`OpenAPIPublisherTest` stays, but nothing consumes what it writes any more — this is an open gap.**
  The test writes `/tmp/openapi-specs.json`. The only thing that has ever read that file is the reusable
  workflow `hmcts/workflow-publish-openapi-spec`, which copies it to `docs/specs/<api>.json` in
  `hmcts/cnp-api-docs` — and that workflow is exactly what the deleted `publish-openapi.yaml` invoked.
  `hmcts/cnp-jenkins-library` contains **no** reference to `cnp-api-docs`, `swagger` or
  `openapi-specs.json`: there is no Jenkins-side equivalent, so deleting the workflow removed the
  publishing mechanism and not merely its trigger. The two are complementary rather than alternatives,
  which is why 19 CNP-pipeline Java services in this workspace (`pcs-api`, `em-stitching-api`,
  `civil-service`, the `wa-*` and `bulk-scan-*` sets) keep the workflow *alongside* `Jenkinsfile_CNP`.
  **Consequence:** `deed-poll-api` will not appear in the API catalogue until the workflow is restored.
  Restoring it needs `SWAGGER_PUBLISHER_API_TOKEN`, an org-level secret this repo must be in the
  selected-repositories list for — unverifiable without `admin:org`, so it is a step-5 question, not a
  silent assumption.
- **`.github/CODEOWNERS` had a `.github/workflows/*.yaml` line** in its Renovate-ignore list. Removed:
  it now names a path that does not exist.
- **`docs/PIPELINES.md` and `docs/ARCHITECTURE.md` asserted this repo had three workflows** in the
  corrective banners at the top of each. Both banners now say it has none. The inherited bodies of those
  files still describe `deed-poll-admin`'s GitHub Actions hierarchy — that is what the banners are for,
  and rewriting them is not this plan's job.

### Task 6: Say so in the docs

- [x] Update `docs/RESUME.md`: this repo deploys via Jenkins, owns no Terraform, and reads the vault created by `hmcts/deed-poll-infra` — which must apply before this service can deploy
- [x] Note the one external dependency this plan leaves: a `cnp-jenkins-config` entry for `hmcts/deed-poll-api`. It cannot be raised before `Jenkinsfile_CNP` exists and is correct, which is task 1 — that ordering is why it has not been raised yet
- [x] Mark completed

The `Jenkinsfile_nightly` decision was already recorded in `RESUME.md` by task 2, so this task folded it
into a single **How this repo deploys** section rather than leaving two accounts of the cutover in one
file. The `cnp-jenkins-config` entry became open item 1 rather than a note: it is the one thing blocking
a first build, as distinct from `deed-poll-infra` applying, which blocks a first *deploy*. Both are now
stated as separate prerequisites, because conflating them is how one gets waited on for the other.

## Success criteria

- `./gradlew build` passes.
- `Jenkinsfile_CNP` declares `java` / `deed-poll` / `api`. No `Jenkinsfile_nightly`.
- The chart is `charts/deed-poll-api`, its `Chart.yaml` carries `annotations.team: deed-poll`, and grepping
  the repo for `rpe` returns nothing outside vendored reference material.
- The `keyVaults` block reads `app-insights-connection-string` and the four `postgres-*` secrets, and names
  no secret the shared root does not create.
- No `infrastructure/` and no `.github/workflows/`.
- Nothing in this diff touches `src/main/java` except `application.yaml`'s App Insights property.

## Out of scope for this plan

- **CCD (plan 02) and CDAM (plan 03).**
- **Applying anything.** `deed-poll-infra` must apply first, and Jenkins onboarding must happen first
  again. This plan makes the repo deployable; it does not deploy it.
- The `cnp-jenkins-config` entry, the `cnp-flux-config` namespace and HelmRelease, the IDAM client, and S2S
  registration — all external.
- `deed-poll-web`'s equivalent cutover, which has its own plan of the same name in that repo.
