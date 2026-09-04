# Plan: deed-poll-api foundation — schema, domain, caseworker API

## Overview

Plan 1 of 3. This one builds the Spring Boot service, its schema, its domain and the caseworker REST
surface. **It contains no CCD and no CDAM** — those are plans 02 and 03, deliberately separated because
they depend on external onboarding that has not happened.

The service replaces `apps/api` in the sibling repo `deed-poll-admin`, which is a TypeScript Express app
whose domain layer is the specification for this port. It will later also serve `deed-poll-web`, the
citizen-facing service, which is why the API has two endpoint namespaces from the start.

### Source material — read these, do not re-derive them

- `../deed-poll-admin/.business_context/DEED_POLL_API_PLAN.md` — every architectural decision and why.
  Read it for context; do not relitigate it. This plan is its executable form.
- `../deed-poll-admin/libs/deed-poll/src/` — the domain being ported. **This is the specification.**
- `../deed-poll-admin/libs/postgres-prisma/prisma/schema/deed-poll.prisma` — the database shape.
- `../apps/pcs/pcs-api/` — the reference Spring Boot implementation in this estate. Copy its shape.

## Validation Commands

- `./gradlew build`

> Every task, without exception. `build` runs compilation, checkstyle and unit tests via the
> `uk.gov.hmcts.java` plugin. **A task is not complete with a red build, even if its own new test
> passes** — a sister repo once shipped a handover claiming green tests on a tree with six type errors.
> Each task below also carries its own narrower `Verify` command; run both.

## Guardrails — read these every session

- **`../deed-poll-admin/` is read-only.** It is the specification. Never edit, never commit into it.
- **Never edit `src/test/resources/reference/`.** Those are vendored copies of the specification. If one
  looks wrong, stop and report — do not "fix" it.
- **One Flyway migration is never edited once committed.** Add `V{n+1}__…sql` instead. Editing a
  committed migration breaks every environment that has run it.
- **Never invent the CCD case type or jurisdiction ID.** They are unconfirmed. Use the single constant in
  `application.yaml` (`deedpoll.ccd.case-type-id: DEED_POLL_PLACEHOLDER`) and nothing else. If a task
  seems to need the real value, it belongs in plan 02.
- **No live calls to CCD, CDAM, IDAM or payments in any test.** WireMock only. There are no credentials
  and there will not be during this plan.
- **No secrets in the repo.** Everything by environment variable, with a non-working default.
- **Do not touch `apps/api`, `apps/web` or `libs/` in `deed-poll-admin`.** Retiring `apps/api` and cutting
  `apps/web` over to HTTP are later work, in a different plan and a different repo.
- **If a validation command fails for a reason this task did not cause, stop and report.** Do not widen
  scope to make it green.
- **One commit per task.** Never amend or force-push a previous task's commit.

---

### Task 0: Populate the repo from the template

This repo was **not** created from `hmcts/spring-boot-template`, and GitHub cannot apply a template to a
repo that already exists, so the contents have to be copied in by hand. Its only commit adds this plan.

- [x] **Stop if this has already run.** If `build.gradle` exists at the repo root, task 0 is done; report
      and move on rather than copying over it.
- [x] Shallow-clone the template somewhere outside this repo:
      `git clone --depth 1 https://github.com/hmcts/spring-boot-template /tmp/sbt`
- [x] Copy its contents in, excluding its git history:
      `rsync -a --exclude '.git' /tmp/sbt/ .`
      The template has no `docs/` directory, so this plan is not at risk — but verify it survived before
      committing.
- [x] `chmod +x gradlew`, then delete `/tmp/sbt`.
- [x] Commit the scaffold. Do not amend the existing commit that added this plan.

- [x] Verify: `test -f build.gradle && test -f docs/plans/deed-poll-api-01-foundation.md && ./gradlew build`
      — the unmodified template ships a working application and passing tests, so a red build here means the
      copy went wrong, not that the template is broken.
- [x] Mark completed

> **One thing for Steve, not for the agent.** `deed-poll-api`'s default branch is **`main`**, while both
> `spring-boot-template` and `deed-poll-admin` use **`master`**. HMCTS Jenkins conventions assume `master`
> in places — `pcs-api`'s Jenkinsfile has a `syncBranchesWithMaster` step, for instance. Worth confirming
> which `cnp-jenkins-config` expects while doing the Jenkins onboarding, because renaming the default
> branch is much cheaper now than after the pipeline exists. **Do not let the agent decide this.**

### Task 1: Rename off the template

- [x] Set `group = 'uk.gov.hmcts.reform'`, base package `uk.gov.hmcts.reform.deedpoll`, artifact and
      application name `deed-poll-api`. The template ships `uk.gov.hmcts.reform.demo` — rename the package
      directories to match and leave nothing behind.
- [x] Update `catalog-info.yaml`: `name: deed-poll-api`, `owner: dcd_deed-poll`, `lifecycle: experimental`.
- [x] Keep the template's `Jenkinsfile_template` and rename to `Jenkinsfile_CNP`. Leave its contents alone.
- [x] Add the plugin versions this service will need, pinned exactly as `pcs-api` pins them, but **do not
      configure them yet**: `id 'hmcts.ccd.sdk' version '6.36.0'` and
      `id 'com.github.hmcts.rse-cft-lib' version '0.19.2360'`. Commented out is fine; plan 02 enables them.

- [x] Verify: `./gradlew build` and `grep -rn "uk.gov.hmcts.reform.demo" src/ || echo clean`
- [x] Mark completed

---

### Task 2: Vendor the specification

The domain and schema are ported from TypeScript. Those sources are the spec, and a session runs in
isolation, so they must be inside this repo.

- [ ] Copy `../deed-poll-admin/libs/postgres-prisma/prisma/schema/deed-poll.prisma` to
      `src/test/resources/reference/deed-poll.prisma`.
- [ ] Copy the whole of `../deed-poll-admin/libs/deed-poll/src/` to `src/test/resources/reference/ts/`.
- [ ] Add `src/test/resources/reference/README.md` stating: these are verbatim copies used as the port's
      specification and its conformance oracle; never edit them; refresh only by re-copying.

- [ ] Verify: `test -f src/test/resources/reference/deed-poll.prisma && test -d src/test/resources/reference/ts && ./gradlew build`
- [ ] Mark completed

---

### Task 3: Postgres and the Flyway baseline: enums and `DeedPoll`

`deed-poll.prisma` defines 6 models and 11 enums in the `public` schema. This task does the enums and the
parent table only; children follow in task 4, so that neither task is too large to verify.

- [ ] Add `docker-compose.yml` with Postgres 16 for local development, and Testcontainers Postgres for
      tests.
- [ ] Write `src/main/resources/db/migration/V1__enums.sql` — all 11 enums, values **in the order the
      Prisma file declares them**, because the integer ordinals are load-bearing and are documented in its
      comments.
- [ ] Write `V2__deed_poll.sql` for `deedpoll_deedpoll`. Reproduce exactly: every `@map` column name,
      `numeric(12,2)` for `refundAmount`, `integer[]` for the three multiselects, and
      **`signaturerecieveddate` with the source's misspelling** — `deed-poll.prisma` documents it as
      deliberate.

- [ ] Verify: `./gradlew test --tests "*SchemaConformance*"` — a new test that parses
      `src/test/resources/reference/deed-poll.prisma`, runs Flyway against Testcontainers, and asserts every
      model field present in the reference has a matching column. Forward coverage only, matching the
      TypeScript harness's own stated limitation.
- [ ] Mark completed

---

### Task 4: Flyway baseline: the child tables

- [ ] `V3__children.sql` for `deedpoll_notes`, `deedpoll_witness`, `deedpoll_supportingdocumenttype`,
      `deedpoll_supportingdocument` and `deedpoll_referencecounter`, with the foreign keys and the two
      indexes the reference declares. `DeedPollSupportingDocument.deedPollId` is `onDelete: Cascade` — the
      cascade is intentional and must be reproduced.

- [ ] Verify: `./gradlew test --tests "*SchemaConformance*"` — now green for all 6 models.
- [ ] Mark completed

---

### Task 5: JPA entities

- [ ] Entities under `uk.gov.hmcts.reform.deedpoll.entity` for all 6 models, and Java enums for all 11.
- [ ] Map, do not redesign: `@Column(name = …)` for every mapped name, `@Enumerated(EnumType.STRING)`,
      `BigDecimal` for the numeric, arrays for the `integer[]` columns.

- [ ] Verify: `./gradlew test --tests "*EntityMapping*"` — a Testcontainers test booting the context with
      `spring.jpa.hibernate.ddl-auto=validate` against the Flyway schema. Hibernate then fails the test on any
      mismatch, which is the strongest automatic check available here.
- [ ] Mark completed

---

### Task 6: Reference-number allocation

- [ ] Port `reference/ts/deed-poll/reference-number.ts` and its allocator. Format is
      `{prefix}{year}-{serial}`, prefix `KBDR` for type 5 (Deed of Relinquishment) else `KBDP`.
- [ ] The serial comes from `deedpoll_referencecounter` incremented **inside a transaction with a row
      lock** (`SELECT … FOR UPDATE`). The reference file's comment explains this replaced a racy
      read-max-and-add-one; do not reintroduce that.

- [ ] Verify: `./gradlew test --tests "*ReferenceNumber*"` — must include a concurrency test proving two
      parallel allocations never return the same serial.
- [ ] Mark completed

---

### Notes for tasks 7–11 — how to port the domain

Applies to each of the five tasks below. Read the TypeScript under
`src/test/resources/reference/ts/`, port the logic to `uk.gov.hmcts.reform.deedpoll.domain`, and port
**its tests too** — they are the specification, and a passing port with fewer tests is not a port. Zod
schemas become Bean Validation or explicit validators. Keep the nullable-versus-optional distinction the
reference documents: a PATCH clearing a value and a PATCH omitting it must stay different, and the
reference tests assert both directions.

### Task 7: Domain: deed-poll core

- [ ] Port `reference/ts/deed-poll/`. The largest by far — roughly 267 test blocks — including the state
      cascade and the field rules.
- [ ] Port its tests. The reference exports entity-specific names (`updateDeedPoll`, `getCaseFieldRules`)
      rather than `update`/`find`, because those collided across five modules once barrel-exported. Java
      packages remove that constraint, but keep the names: they are what every test and caller says.

- [ ] Verify: `./gradlew test --tests "*DeedPoll*" --tests "*FieldRules*" --tests "*StateCascade*"`
- [ ] Mark completed

### Task 8: Domain: notes

- [ ] Port `reference/ts/notes/`, roughly 44 test blocks, and its tests.
- [ ] `createNoteSchema` trims **and** delegates to `validateNoteText`. Both are load-bearing: without the
      trim, `"   "` is accepted as a note and whitespace is stored.

- [ ] Verify: `./gradlew test --tests "*Note*"`
- [ ] Mark completed

### Task 9: Domain: witnesses

- [ ] Port `reference/ts/witnesses/`, roughly 15 test blocks, and its tests.

- [ ] Verify: `./gradlew test --tests "*Witness*"`
- [ ] Mark completed

### Task 10: Domain: documents, metadata only

- [ ] Port `reference/ts/documents/`, roughly 60 test blocks, and its tests — **metadata and the upload
      policy only.**
- [ ] **Do not port `file-storage.ts`.** Real storage is CDAM, in plan 03. Define a `DocumentStore`
      interface with one local-filesystem implementation for development, and nothing else behind it.
- [ ] Keep the upload policy's own framing in a comment: the accepted-type list is the client's declared
      MIME type, so it is hygiene rather than security, and 25 MB is an unresearched figure.

- [ ] Verify: `./gradlew test --tests "*Document*"`
- [ ] Mark completed

### Task 11: Domain: the conformance harness

- [ ] Port `reference/ts/conformance/`, roughly 32 test blocks, against the vendored Dataverse exports.
      This is the proof the migration did not silently drop a column, so it is the last thing that should
      be allowed to lapse.
- [ ] **Carry its caveat into a comment on the test class:** the Dataverse exports are alphabetically
      truncated at A–R despite declaring 70 columns, so this asserts forward coverage only — every column
      present must map — and must never be read as a complete column set.

- [ ] Verify: `./gradlew test --tests "*Conformance*"`
- [ ] Mark completed

---

### Task 12: Authentication and the two namespaces

- [ ] Accept a forwarded IDAM bearer token on `/caseworker/**` — Spring Security as an OAuth2 resource
      server, issuer from configuration. `../deed-poll-admin/apps/api/src/auth/` is the reference for what
      it validates.
- [ ] Authenticate by default: every route requires a token unless explicitly permitted, so a route added
      later is protected by omission rather than by memory. `apps/api/src/auth/authenticate.ts` documents
      this reasoning.
- [ ] Create an **empty** `/citizen/**` namespace with its own controller package and its own DTO package.
      Nothing in it yet. It exists so plan 04 cannot accidentally share a caseworker DTO.
- [ ] Add `AuthTokenGenerator` from `service-auth-provider-java-client` for outbound S2S, configured but
      unused, with a non-working default secret.

- [ ] Verify: `./gradlew test --tests "*Security*"` — must include a test that an unauthenticated request
      to a `/caseworker/**` route is rejected, and an architecture test asserting no class in the citizen
      controller package imports from the caseworker DTO package.
- [ ] Mark completed

---

### Notes for tasks 13–15 — how to build the caseworker surface

**Eleven operations, and only eleven.** They are derived from what `deed-poll-admin`'s 8 data-access files
actually call. **Do not add others** — in particular no deletes, no witness writes, no document-type
writes and no `validate` endpoint. They have no consumer. `apps/api` built all of them anyway from an
imagined consumer, and not repeating that is the point.

Separate request and response DTOs per operation under `…deedpoll.api.caseworker.dto`. **Never return an
entity from a controller.**

### Task 13: Caseworker reads

- [ ] `GET /caseworker/cases` — the filtered list.
- [ ] `GET /caseworker/cases/{id}` — one case **with its nested notes, witnesses and documents**. The
      reference does this in one query and the UI depends on it; do not split it into four calls.
- [ ] `GET /caseworker/cases/field-rules`
- [ ] `GET /caseworker/document-types`

- [ ] Verify: `./gradlew test --tests "*CaseworkerReadController*"`
- [ ] Mark completed

### Task 14: Caseworker case writes

- [ ] `POST /caseworker/cases` — allocates a reference number via task 6. A create without `dateReceived`
      cannot allocate one and must fail with a 400 rather than a 500; the reference records this as a known
      sharp edge.
- [ ] `PATCH /caseworker/cases/{id}`
- [ ] Field-level validation errors must come back attributed to the field, not as one message. The
      reference carries `FieldError[]` for exactly this reason and the UI renders them against individual
      inputs.

- [ ] Verify: `./gradlew test --tests "*CaseworkerCaseController*"`
- [ ] Mark completed

### Task 15: Caseworker notes and documents

- [ ] `GET` and `POST /caseworker/cases/{id}/notes`, and `GET`/`PATCH
      /caseworker/cases/{id}/notes/{noteId}`.
- [ ] `POST /caseworker/cases/{id}/documents` — multipart upload, writing through the `DocumentStore`
      interface.
- [ ] `GET /caseworker/cases/{id}/documents/{documentId}/file` — streams from `DocumentStore`. Set
      `Content-Disposition: inline` for `application/pdf`, `image/jpeg` and `image/png`, `attachment`
      otherwise, plus `X-Content-Type-Options: nosniff` and `Cache-Control: no-store`. The inline decision
      and its reasoning are in the API plan; the allowlist is what makes it safe.
- [ ] A child list under a non-existent parent case must 404, not return an empty list with 200.

- [ ] Verify: `./gradlew test --tests "*CaseworkerNote*" --tests "*CaseworkerDocument*"`
- [ ] Mark completed

---

### Task 16: Health, readiness and the OpenAPI spec

- [ ] Readiness must check the database **and name the missing tables** if any are absent — being paged for
      "readiness DOWN" with no further detail wastes the diagnosis.
      `../deed-poll-admin/libs/deed-poll/src/health.ts` is the reference, including why the table list
      cannot be derived.
- [ ] Liveness stays empty on purpose: the process answering is the signal. A database blip must not make
      Kubernetes restart every pod, because restarting cannot fix someone else's database.
- [ ] Publish an OpenAPI spec via springdoc, and wire the `SwaggerPublisherTest` pattern the template
      ships so it can later publish to `hmcts/cnp-api-docs`.

- [ ] Verify: `./gradlew build && ./gradlew test --tests "*Health*"` — including a test that readiness
      returns 503, not 200, when the database is unreachable. Returning 200 on a failed probe is a real defect
      this codebase has already had once.
- [ ] Mark completed

---

## Success criteria

- `./gradlew build` green, and every task's own `Verify` command green.
- The Flyway schema and the JPA entities both agree with the vendored `deed-poll.prisma`, proved by the
  schema-conformance test and by Hibernate's `ddl-auto=validate` rather than by inspection.
- The domain port carries **all** of the reference's test blocks, not a subset. A port with fewer tests is
  not a port.
- Eleven caseworker endpoints exist and no twelfth.
- `/citizen/**` exists, is empty, and shares no DTO with `/caseworker/**`.
- Nothing in the repo makes a live call to CCD, CDAM, IDAM or payments, and no secret is committed.

## Out of scope for this plan

Stated so no session tries to be helpful:

- **CCD** — `decentralised = true`, `CCDConfig`, `CaseView`, `/ccd-persistence/*`, the case-type
  definition. Plan 02.
- **CDAM** — real document storage, hash tokens, the attach cycle. Plan 03.
- **`deed-poll-web`** — citizen endpoints, payments, anonymous submission. Its own plan, later.
- **Retiring `apps/api` and cutting `apps/web` over to HTTP.** Different repo, and it must not happen
  until this service is deployed and serving.
- **Deployment.** The chart and Jenkins onboarding are Steve's; nothing here should try to deploy.
