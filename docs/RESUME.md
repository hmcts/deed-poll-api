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
| Caseworker endpoints | 13 |
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

## Open items that are Steve's, not a session's

1. **Deployment is not onboarded.** `charts/rpe-spring-boot-template` is still the template's — chart
   name, image and ingress host are all `spring-boot-template`'s, and no probe path points at
   `/health/readiness` or `/health/liveness`. Jenkins folder configuration is unconfirmed.
2. **`DOCUMENT_STORE_ROOT` must not reach a deployed environment.** Local disk does not survive AKS
   and replicas do not share a volume. Real storage is plan 03.
3. **IDAM roles.** The only role name confirmed is `caseworker-deedpoll-admin`, and it gates one
   field. So `/caseworker/**` is authenticated but not role-gated: naming a non-admin caseworker role
   needs IDAM onboarding, and inventing one would be the same mistake as inventing a case type.
4. **IDAM audiences and issuer.** Both default to empty, which refuses every token. An environment
   with neither answers `/health` and `/v3/api-docs` and 401s everything else.

## Do not let a session drift into

Writing plan 02 or 03 from guesses, editing `src/test/resources/reference/` (it is a vendored copy of
the specification — if one looks wrong, stop and report), or editing a committed Flyway migration.

Full context, if a session needs the *why*:
`../deed-poll-admin/.business_context/DEED_POLL_API_PLAN.md`.
