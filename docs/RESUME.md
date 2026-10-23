# Resume state — where the next session picks up

**Document previews, 2026-10-23:** on `feat/document-preview-rendering`, not pushed and not live-verified.
`GET /caseworker/cases/{id}/documents/{documentId}/preview` and `GET /judicial/referrals/{referralId}/documents/{documentId}/preview`
serve a transient, complete PDF rendition of PDF, JPEG, PNG, every frame of a TIFF that is not JPEG-compressed, and DOCX (Docmosis Tornado
`/rs/convert`), audited as `DOCUMENT_PREVIEWED`. DOCX answers `503 PREVIEW_UNAVAILABLE` until the converter endpoint,
`docmosis-access-key` and fonts are provisioned, none of which is done. The chart has no Docmosis entry yet. Next:
Ralphex review; open the PR; admin regenerates `openapi.json` from the artifact recorded in
[DOCUMENT-PREVIEW-SMOKE.md](DOCUMENT-PREVIEW-SMOKE.md), which also holds the AAT acceptance steps (prepared, not run).
See [the plan](plans/completed/20261023-document-preview-rendering.md). Revised by
[the review fixes](plans/completed/20261023-document-preview-review-fixes.md): the original is read before any busy
or unavailable refusal; a PDF or Docmosis output is served unparsed; PNG text chunks are never inflated; and
`DOCUMENT_PREVIEW_MAX_PAGES` is 100, enforced by the API for JPEG, PNG and TIFF only, so admin's viewer must refuse
a PDF or DOCX over `maxPages`. The handoff artifact was then `c1fc4438…`; it is now `351b6047…` (see the third review below). `./gradlew cftlibTest` passed on 2026-10-23 (21 tests,
including all seven `DocumentPreviewCftlibTest` journeys). [The second review's fixes](plans/completed/20261023-document-preview-review-fixes-2.md) hold a tiled TIFF
frame's declared tile to `max-frame-pixels` before decoding and make the Docmosis request one-shot, so a
`503 Retry-After: 0` is never resent. [The third review's fixes](plans/completed/20261023-document-preview-review-fixes-3.md)
check a TIFF's whole structure with bounded reads before any ImageIO call (`TiffStructure`: directory tables, field
values and strip/tile byte counts together no larger than the file), and deliberately do not preview JPEG-compressed
TIFFs (Compression 6/7, `422 PREVIEW_INVALID_DOCUMENT`; `/file` still downloads them).

**Narrative cap and blank witnesses, 2026-10-22 (admin R15):** on `fix/textarea-limit-and-blank-witnesses`, not
pushed. `returnReason`, `referralNotes` and `masterDirections` are capped at 2,000 characters with line breaks counted
once, matching admin's edit form; witness name, address and occupation are trimmed and a blank is refused. Only
`reference/ts/witnesses/schemas{,.test}.ts` were re-vendored, from admin `7567b60`; the reference README still records
`8b6b8fc` for the whole tree, and the re-vendored `schemas.ts` comment saying api accepts `""` is now stale and must be
fixed in admin. Next: open the PR; admin refreshes its `openapi.json`. See
[the plan](plans/completed/20261022-textarea-limit-and-blank-witnesses.md).

**Judicial application view and revisable directions, 2026-10-19:** on `plan/judicial-application-view`, not
pushed and not live-verified. By the business's decisions of 2026-10-19, judges see the whole application
(`JudicialApplicationView`: details, witnesses, readable documents; no case id, no payment or refund data, no
office processing record) and download its documents through the referral. The directing judge may revise
(`PUT …/direction`) or withdraw (`POST …/direction/withdraw`) their direction until the office closes the
referral; both set V38's never-cleared `directionChangedAt`, write a case note and a new audit event. A withdrawn
direction is a live claim, so the inactivity sweep can return it to the pool. Judicial operations: **5 → 8**
(seven paths); caseworker 34 unchanged. Open business question: address lines, declaration multiselects,
`publishToLondonGazette`, `migratedRecord` and `retainPermanently` are on neither list and are left out. Next: open
the PR; then the admin plan refreshes its api spec from the whole document. CDAM: confirm a judge's download is
authorised in AAT before CDAM is switched on. See [the plan](plans/20261019-judicial-application-view-and-revised-directions.md).

**CCD in AAT, 2026-10-16:** `docs/CCD-SMOKE.md` steps 1–3 ran in AAT and answered as written. `DEEDPOLL` v1 is
imported, eight roles are registered at PUBLIC, and the linking sweep is on at one record per list request
(cnp-flux-config#48332). KBDP2026-00001 and -00002 are linked to CCD cases. It took #108 (additional IDAM issuers),
cnp-flux-config#48329 and #111 (chart bump) to get there; see the smoke doc's "AAT run" section. CDAM stays off.
Production needs a linking backfill plan before CDAM is enabled.

**Download invariants, 2026-10-16 (OWASP Plan 05 Task 5):** tests now assert `nosniff`, the inert sandbox
CSP and `no-store` for all six allowed upload types through both the real filesystem store and the CDAM
WireMock upload/attach/download journey. Disposition follows the recorded MIME type: PDF/JPEG/PNG types
are inline; Word/TIFF types are attachments. This does not verify file contents. Conflicting CDAM upload
metadata and download headers do not override the recorded client metadata or the API's response policy.
A separate invariant test checks that the inline allowlist is a subset of upload types; the request-based
tests pin Word/TIFF attachment behaviour. Production code is unchanged.
Baseline at `474e409`: 4,305 main-suite + 20 integration tests. Local `cleanTest build`:
4,312 + 20, zero failures/errors/skips, Checkstyle clear (local ARM launcher workaround only).
**Abuse limits, 2026-10-16:** per-subject request budgets (V37 `deedpoll_request_budget`, `RateLimitFilter`, modes `off`/`observe`/`enforce`, default `observe`), the per-pod in-flight upload cap, the daily per-case per-template email cap and the case list statement timeout are implemented on `feat/abuse-limits` and not live-verified. The last three enforce whatever the mode. Next: open the PR; deploy to AAT in `observe`; tune each limit from the logged p99; switch AAT to `enforce`; record the production Notify limits; admin refreshes its api spec for the six 429s. See [the plan](plans/completed/20261016-abuse-limits.md) and [RATE-LIMITS.md](RATE-LIMITS.md).

**Upload content checks, 2026-10-16 (OWASP Plan 05 Tasks 1–3):** `feat/document-content-policy` adds one
content policy before document storage and owned-staging persistence. Steve approved dropping legacy `.doc`,
refusing encrypted PDF/DOCX and refusing the specified active-content indicators. Signature checks, bounded
DOCX package inspection and escaped PDF-name checks are covered by adversarial fixtures and admission tests.
These are interim controls, not antivirus/full PDF parsing; limitations and ZIP budgets are in README.
The coordinated admin branch `fix/document-content-types` removes `.doc` and updates the upload hint.
No CDAM activation, live deployment, existing-file migration or metadata-integrity fix is included.
Task 9 mock fidelity and metadata integrity remain separate follow-ups. #110 is merged; API #112 now targets
master, with admin #188 as its companion. The CDAM fixtures use typed bytes; historical `.doc` coverage seeds
legacy metadata without permitting new `.doc` uploads. No download-header assertion is removed.
Gale's review corrections distinguish format mismatches from known unsafe Word features and give OLE
containers neutral format guidance. POI limits now apply during startup before reader beans, not on the
first DOCX upload. Tests cover startup ordering and the exact 50 MiB expanded-total boundary while proving
all other archive/upload budgets pass. No acceptance rule or limit has been relaxed.

**Download invariants, 2026-10-16 (OWASP Plan 05 Task 5):** tests assert `nosniff`, the inert sandbox
CSP and `no-store` for the five accepted types plus historical `.doc` metadata through both the real
filesystem store and CDAM WireMock journey. Disposition follows the recorded MIME type: PDF/JPEG/PNG types
are inline; Word/TIFF types are attachments. Conflicting CDAM upload metadata and download headers do not
override the recorded client metadata or the API's response policy. A separate invariant test checks that
the inline allowlist is a subset of upload types; the request-based tests pin Word/TIFF attachment behaviour.
No live CDAM calls or separate `cftlibTest` run; live download verification remains the onboarding gate.

**Filesystem storage guard, 2026-10-16 (OWASP Plan 05 Task 4):** startup now refuses filesystem storage unless
`ENVIRONMENT_NAME` is exactly `aat`, `preview`, `demo`, `ithc` or `perftest`, or is empty with `CLUSTER_NAME`
absent for local use. There is no profile bypass or override switch. The chart supplies the name, with an
explicit preview override. CDAM onboarding checks remain unchanged. Before rollout replaces any pod, record
aggregate document-row/file inventories and resolve preservation needs. Live preview startup, AAT readiness
and upload/download checks remain deployment verification; no replica changes or CDAM activation are included.
Local verification: `cleanTest build` passed with 4,272 main-suite tests and 20 integration tests, no skips,
and no Checkstyle violations. Helm lint and chart-java 6.1.1 rendering passed for AAT, preview and production.

**Audit trail, 2026-10-15:** the append-only audit trail (V36 `deedpoll_audit_event` and `deedpoll_audit_event_value`, `AuditTrail`, `ReadAudit`, `SecurityEvents`) is implemented on `feat/audit-trail` and not live-verified. Every caseworker and judicial write records an event in its own transaction, so a failed audit insert rolls the change back; case views fail open and document downloads fail closed. Next: open the PR; retain-and-dispose schedules the two disposal functions; a later plan adds a read endpoint. See [the plan](plans/20261015-audit-trail.md).

**GET /me, 2026-10-14:** `GET /me` (`getCallerAccess`) is implemented on `feat/me-capabilities` and not live-verified. It is read-only, outside both staff areas, carries no identity and answers `Cache-Control: no-store`. Next: open the PR, then admin refreshes its api spec and reads `/me` at login and token refresh in place of `capabilitiesFor(roles)`. Operation counts are unchanged. See [the plan](plans/20261014-me-caller-access.md).

**Gazette delivery/recovery, 2026-10-09:** PR #92 merged the ledger and preview/status
contract into master (`801e949`). The isolated `feat/gazette-delivery-recovery` worktree
implements parent 4c/4d: all-file preparation, one-shot delivery, committed outcomes
and independent bounded recovery. Sending stays disabled by default. See
[execution scope and validation](plans/completed/gazette-1e-delivery-and-recovery.md) and the
[recovery/release procedure](GAZETTE-RECOVERY.md).
Next: refresh the admin OpenAPI client, then implement preview/confirmation, stable
request keys, typed outcomes and request detail/full membership. Provisioning,
external review and controlled test-recipient delivery remain release work.
This supersedes the historical Gazette handoff further below.

**Staff document READ, 2026-10-08:** Steve agreed read access for all three user types. The definition now
maps ordinary caseworkers (`caseworker-deedpoll`), admins (`caseworker-deedpoll-admin`) and judiciary
(`caseworker-deedpoll-judicial`) to `DEED_POLL_READER`. It grants R only on the case type, Registered
state and document collection; system grants are unchanged. The ordinary caseworker role is new and
is in [IDAM #1016](https://github.com/hmcts/idam-access-config/pull/1016) at `947b956`; the test-persona
update is [admin #161](https://github.com/hmcts/deed-poll-admin/pull/161). Registration/assignment are pending. Register all six external roles
and internal profiles at PUBLIC, then regenerate/import the definition using `docs/CCD-SMOKE.md`.
Local proof: 170 API permission/security tests and all 14 CCD/CDAM journeys passed.
No activation or live staff smoke has run. Linking/CDAM/staging remain off. Production is deferred.

AAT infrastructure #26 is merged. IDAM #1016 and CDAM #813 have since passed CI. The AAT-only S2S
registration is [Flux #48113](https://github.com/hmcts/cnp-flux-config/pull/48113), with auto-merge requested
and review outstanding at the last check; shared S2S #1140 is deferred. Definition-store also needs
[Flux #48117](https://github.com/hmcts/cnp-flux-config/pull/48117), and runtime onboarding still needs
[Flux #48052](https://github.com/hmcts/cnp-flux-config/pull/48052). These updates supersede the older statuses below.

**AAT onboarding, updated 2026-10-07:** Runtime identifiers remain `DEEDPOLL` / `DEEDPOLL` /
`deed-poll-system-update`, with internal profile `DEED_POLL_SYSTEM`. Infrastructure #25 and documentation
#78 are merged. All four API identity secrets were verified present/enabled in `deed-poll-aat`.

Steve requested a separate CCD importer following PCS. [Infra #26](https://github.com/hmcts/deed-poll-infra/pull/26)
prepares `definition-importer-username/password`; updated [IDAM #1016](https://github.com/hmcts/idam-access-config/pull/1016)
registers that account with `ccd-admin` and `ccd-import`, keeping runtime privileges separate.
**Apply #26 before rerunning IDAM #1016:** the two importer secrets are new prerequisites. The account has
not been created or authenticated by this session, and no definition import has run.

IDAM #1016 and [CDAM #813](https://github.com/hmcts/ccd-case-document-am-api/pull/813) build 1 reported ERROR.
Jenkins console access requires Microsoft sign-in; the underlying causes remain unknown. Local IDAM
41 tests and CDAM 75 focused tests passed. Steve will rerun the pipelines during office hours.
[Flux #48052](https://github.com/hmcts/cnp-flux-config/pull/48052) passed its 18 reported checks.
[S2S #1140](https://github.com/hmcts/service-auth-provider-app/pull/1140) remains a separate prerequisite.

Next verify importer credentials and IDAM synchronization, then PUBLIC CCD role registration and the
checked `DEEDPOLL` definition import. Linking/CDAM/staging remain disabled. The
[six-workstream checklist](plans/ccd-cdam-doc-store-onboarding.md) records identities, PRs, ordering,
validation and the outstanding smoke, staff-access and existing-file migration gates.

**Gazette foundation handoff, 2026-10-06:** The named `readyForPublication` filter,
V31 audit schema, V32 membership/read indexes and admin-only `GET /caseworker/gazette-notices` are implemented.
The full admin plan 16 contract remains unavailable: no batch-send operation,
spreadsheet or Gazette Notify provisioning is implemented. The parent
[Gazette plan](plans/gazette-1-ready-list-and-batch-send.md) remains active with
business inputs and send/full-validation tasks unchecked. Its transaction proposal
cannot guarantee all-or-nothing email delivery: rollback cannot undo Notify acceptance.
Spreadsheet layout, Notify wording and uncertain-send reconciliation/retry decisions
are still required. Final counts and local OpenAPI provenance are recorded in
[foundation validation](plans/gazette-1a-task4-validation.md).

**03b implementation handoff, 2026-10-02:** Tasks 1–7 implement and prove durable CDAM deeds for both templates,
service-authorised Notify retrieval across restart, per-notification claim/send-start/outcome commits and
owned RECORD/DRAFT staging with bounded independent cleanup. Task 8 consolidates the operational runbooks;
its final fresh validation evidence is in [plan 03b](plans/deed-poll-api-03b-cdam-callers-and-staging.md#task-8-record-operational-limits-and-the-handoff-to-03c).
This update supersedes historical “not implemented” and open-design notes below; their dates preserve context.

CDAM and staging remain off by default. The citizen adapter and browser byte transfer are absent. Production
quotas/durations/schedule, database/backup capacity, aggregate backlog/expiry monitoring and named operational
owners remain activation gates under ADRs 0007–0009. Only document-state gauges are currently exported.
Nothing is onboarded by these local tests; the expanded [operator smoke](CDAM-SMOKE.md) is prepared, not run.

Read [CDAM-RECOVERY.md](CDAM-RECOVERY.md) before upgrade/rollback: stop all old traffic-triggered drain workers
before V28/new workers; a possibly-sent email is parked for investigation, never automatically resent. Disabling
staging stops cleanup and requires retained-backlog ownership. Changing the backend cannot serve the other
store's documents. 03c must inventory Local rows, original files/checksums, previously generated deeds and frozen
queued email references, preserve sources/identities, and verify attachment/read-back before cutover. Citizen/web
work must implement authenticated durable draft ownership, actual byte transfer and verified promotion mapping.
The recovery guide gives concrete input/check lists for both handoffs. No sibling repository was changed.

## Historical decision and delivery notes

**03b design decisions complete, 2026-10-02:** Steve accepted the final cleanup approach: opt-in bounded
scheduled cleanup, independent of caseworker traffic, safe across replicas and preserving recovery payloads.
[ADR-0009](../../deed-poll-documentation/decisions/0009-staged-upload-cleanup-runs-on-a-bounded-schedule.md)
records it. All three design flags are now resolved; 03b's execution handoff and appended decision record
supersede the earlier open-status notes. Production quotas, expiry durations, cleanup schedule/limits and
operational ownership remain release/activation prerequisites. Citizen activation still needs real draft
authority and the web upload handoff. No implementation or fresh baseline build has run.

**03b expiry decision and release reminder, 2026-10-02:** Steve accepted fixed expiry from first staging,
separate durations by owner type, no retry extension and protection of generated-deed recovery/unfinished
or uncertain promotion. [ADR-0008](../../deed-poll-documentation/decisions/0008-staged-upload-expiry-requires-release-agreement.md)
records the rule. Production durations must be agreed and configured before a release enables staging;
the product [release checklist](../../deed-poll-documentation/operations/deployment-readiness-checklist.md#staged-upload-expiry-release-gate--2026-10-02)
tracks this explicitly. Only the cleanup decision remains open in 03b. No implementation was started.

**03b staging limits update, 2026-10-02:** Steve accepted citizen limits of 7 MB/file and 10 files/draft,
the existing 25 MB caseworker file limit with an explicit configurable staged-file count per record, plus
mandatory per-owner byte limits and an overall storage cap. Missing settings prevent staging activation;
full storage refuses new uploads without deleting recovery evidence. Production capacity values remain
activation prerequisites; local tests use explicit finite values. Flags 1/2 and flag 3's ownership/limits
policy are accepted. Expiry and cleanup remain open. See 03b's appended decision record; no implementation.

**03b staging ownership update, 2026-10-02:** Steve accepted both owner types under flag 3: existing local
deed-poll records awaiting CCD linking, and future citizen drafts. Citizen uploads stay disabled until
actual draft ownership/authentication exists. Flags 1 and 2 are accepted; flag 3's limits, expiry and
cleanup remain open. The acceptance is appended to 03b; no implementation was started.

**03b business-rule update, 2026-10-02:** Steve accepted flag 2: per-notification committed claims/outcomes,
with no automatic retry of a possibly-sent email. All email templates follow this rule; uncertain outcomes
need investigation before a resend decision. The product decision is
[ADR-0007](../../deed-poll-documentation/decisions/0007-uncertain-email-delivery-requires-investigation.md),
linked from 03b's appended decision record. Flags 1 and 2 are now accepted; only flag 3 remains open.
No implementation or validation build was run.

**03b decision update, 2026-10-02:** Steve accepted flag 1: bounded Postgres payload staging, with capacity
checks and explicit quotas required before activation. The decision is appended to
[03b](plans/deed-poll-api-03b-cdam-callers-and-staging.md). Flags 2 (possibly-sent email recovery) and 3
(pre-case ownership, limits, expiry and cleanup) remain open. No implementation or validation build was run.

**CDAM planning update, 2026-10-01:** 03a is now merged to `master` through PR #73 (`f4eb7db`), confirmed by
fetching the remote. The next implementation plan is
[03b — generated deeds, Notify retrieval and pre-case staging](plans/deed-poll-api-03b-cdam-callers-and-staging.md).
It is a draft with eight tasks, not implemented. Three proposals need a decision before dependent tasks:
temporary durable payload storage, outbox recovery after a possibly-sent email, and pre-case ownership/expiry
and cleanup. Citizen/web activation still depends on the actual draft authority and upload adapter. 03c owns
existing-file migration. This planning update ran no builds, live smoke or onboarding checks; the older 03a
local validation below remains historical evidence. CDAM remains off by default.

**CDAM core round trip, 2026-09-29: plan 03a is complete locally** on branch
`deed-poll-api-03a-cdam-core-round-trip`; all eight tasks are implemented. Read
[plan 03a](plans/deed-poll-api-03a-cdam-core-round-trip.md) and the notes here before the older plan-03-next
statements below. **Nothing is onboarded. CDAM remains off by default.**

The supporting-document round trip uses real CCD/CDAM in cftlib with a source-derived WireMock dm-store. Six new
journeys prove upload/attachment, restart/download, CCD READ refusal, the separately recorded unmapped-user
failure, cross-case/pre-link refusal, and domain deletion including CCD's event snapshot and CDAM's subsequent
404. There is still no document-delete HTTP endpoint. The stub's pinned OpenAPI, version/header/multipart guards
and manual `checkDmStoreSpecDrift` task are in place; the spec comparison reports no differences.

`CdamOnboarding` refuses local/default CDAM endpoints and placeholder CCD identifiers when CDAM is enabled,
except local `test`/`cftlib` profiles against loopback. The chart remains on `filesystem`. The bounded
`deedpoll.documents.storage` metric counts rows by state without IDs or tokens. Recovery, rollout and deletion
limits are in [CDAM-RECOVERY.md](CDAM-RECOVERY.md); [CDAM-SMOKE.md](CDAM-SMOKE.md) is prepared, **not run**.

Final validation, measured from fresh XML on 2026-09-29, with **zero failures/errors/skips**:

| Run | Result | Wall clock |
|---|---|---|
| Docker full build and spec drift | **3457** `test` (414 files), **14** `integration` (3 files); no spec differences | **5m 5s** |
| Docker-free full build | **2339** `test` (294 files); integration not run and old XML cleared | **1m 31s** |
| `checkstyleCftlibTest cftlibTest` | **9** tests; upload plus attachment **1852 ms** | **2m 17s** |

Against plan 03a Task 1: +153 tests, +131 Docker-free, unchanged 14 integration; cftlib has six new journeys
beside the existing three. The final cftlib test JVM routes non-loopback HTTP(S) to a closed loopback proxy to
prevent the bundled WA service's unused LaunchDarkly client from contacting the live service. Its expected
connection warnings name `127.0.0.1:1`; local CCD/CDAM and auth simulators remain directly reachable.

Next work and limits:

- **03b:** generated deeds, Notify retrieval and pre-case staging. **03c:** existing-file migration. 03a alone
  is not a production storage cutover; enabling it now leaves those callers/migration unfinished.
- Agree/onboard the real identifiers, S2S permissions and staff CCD access profile, then run the operator smoke
  and replace source-derived dm-store bodies with sanitised AAT captures. The local reader uses the existing
  system profile only as a fixture; do not grant that role to real staff to make downloads work.
- An entirely unmapped staff user reaches CDAM but the persistence 403 is wrapped by data-store as 500 and CDAM
  as **502**. No binary is read. This needs a staff-access/upstream-error follow-up; do not relax the persistence
  gate or map every 502 to forbidden. The genuine CCD READ-denial journey is independently proved.

**CCD linking lifecycle, 2026-09-27: plan 02b is complete** and merged to `master` on 2026-09-28 (`24c8d97`,
PR #65). Read [its section](#ccd-linking-lifecycle--plan-02b-complete-2026-09-27) first. Plan 02 is merged
on `master` (`3430346`, PR #63), so its section below is history, updated by 02b where the two disagree.

**Master's red build had two causes: the OWASP gate and the SonarCloud quality gate.** Jenkins showed only
"This commit cannot be built". The OWASP gate was fixed by PR #68 (`da79005`, 2026-09-28), but builds #44 and #45
still failed, on "Pipeline aborted due to quality gate failure: ERROR". That line was in the first log too. Sonar
rated new-code security D and reliability C against a limit of A. The `sonar-quality-gate` branch fixes the code
issues. The rest (two CSRF-disabled chains, both stateless bearer-token APIs, and the deliberate 25 MB upload
limit) must be marked safe in SonarCloud. The Sonar gate is measured by
`api/qualitygates/project_status?projectKey=uk.gov.hmcts.reform:deed-poll-api`, which the devcontainer can
reach, so check it before blaming anything else.

**The OWASP half:** the console log showed `dependencyCheckAggregate` failing on 56 CVEs, because plan 02's
Boot 3.5.16 upgrade moved every dependency off the exact versions the temporary suppressions in
`config/owasp/suppressions.xml` were pinned to. The CCD runtime also added Jackson 3, Kotlin and OpenTelemetry,
which had no suppressions. #68 suppresses exactly those 56 CVEs at the versions now in use. **Every temporary
suppression expires on 2026-10-16**, and the gate fails again that day unless the date is extended. Upgrading
individual versions instead of suppressing is deferred to the end of development. Any future Boot upgrade will need
the suppressions re-pinned. **Plan 03 (CDAM)** is next.

**CCD foundation, 2026-09-27: plan 02 is complete** and merged (`3430346`).
Read [its section](#ccd-foundation--plan-02-complete-2026-09-27) before anything about CCD further down, all of
which predates it.

**CCD planning update, 2026-09-26:** read the
[new integration-plan baseline](#ccd-planning-update--2026-09-26) below before using
the older statements that plans 02/03 are unwritten.

Rewritten **2026-09-20**; figures and plan states refreshed **2026-09-24** during the email-case-notes review, and
again **2026-09-25** when notify-6 completed. **The Notify sections were refreshed 2026-09-26**, after every
Notify plan except notify-4a had merged. The older narrative further down ("What is built" onwards) still needs a
proper rewrite, and **its test figures are per-branch measurements from before those merges**, so re-measure
rather than quote them. **Read this before restarting the loop.**

The rule this file exists to serve: **it must describe today, or it actively misleads.** notify-1, notify-5,
notify-3, notify-3b, notify-6, notify-4b and notify-4c are all merged on `master`; notify-4a is filed too, and ②'s LOC024 was attached on 2026-09-28 (template v9). notify-5's four tasks capture the exact subject and plain-text body, provide
the case-scoped correspondence read, return availability, history and slot defaults from the existing Email-tab
GET, and pin the public wording and cross-case security boundary. notify-3 sends the confirmation email itself,
with one referral-outcome email per referral enforced by the database.

## CCD linking lifecycle — plan 02b complete, 2026-09-27

[Plan 02b](plans/completed/deed-poll-api-02b-ccd-linking-lifecycle.md), all six tasks, on branch
`deed-poll-api-02b-ccd-linking-lifecycle`, merged to `master` as `24c8d97` (PR #65). **Still proved on placeholders, and still not onboarded.**
The sweep ships switched off, so in every deployed environment nothing calls the linking operation yet.

What it adds, in `ccd/` unless named:

- **V23** on `deedpoll_ccdcreationattempt`: `submissionMayHaveReached` (existing rows backfilled `true`, new rows
  `false`), `notBefore`, `repairedById` and `repairedOn`. `CcdCreationAttemptStatus` gains the conditional edge
  `NeedsRepair -> Pending`. Its condition is `CcdLinkingRule.mayRetryFromScratch`.
- **V24** adds `provenNothingCreated`, so an attempt that ran out of tries on refusals can still be retried from
  scratch (open item 8).
- `CcdLinkingService` commits `submissionMayHaveReached = true` on its own `Claimed` row before
  `submitCaseCreation`, and submits nothing if that write matches nothing. A released attempt waits
  `CCD_LINKING_RETRY_AFTER` × tries (`notBefore`), and `claim` refuses a row that is not due.
- `CcdLinkingSweep`, called from `GET /caseworker/cases` after `outbox.drain()`, outside any transaction:
  `CCD_LINKING_SWEEP_ENABLED` (default `false`), at most `CCD_LINKING_SWEEP_MAX_RECORDS` (2) links and
  `CCD_LINKING_SWEEP_MAX_RECONCILIATIONS` (5) reconciliations, and it stops at the first `RETRY_LATER`,
  `CCD_UNANSWERED` or exception, or once `CCD_LINKING_SWEEP_MAX_DURATION` (`PT15S`) has passed. It never throws into the list. `POST /caseworker/cases` does not link. Feign
  timeouts are now 5 s connect and 10 s read, for every Feign client.
- `CcdAttemptRepairs` and `/caseworker/ccd-attempts`, for `caseworker-deedpoll-admin` only:
  - `GET` returns the counts and a page of unlinked attempts;
  - `POST …/reconciliation` moves `NeedsRepair -> AwaitingConfirmation`;
  - `POST …/retry` moves `NeedsRepair -> Pending`, and only while `canRetryFromScratch` is true.

  Each move is one conditional statement, and neither calls CCD or writes a binding. Each is attributed and logged
  without the admin's identity. The OpenAPI document gains exactly those three paths and three schemas. **The admin
  screen is a follow-up plan in deed-poll-admin**, whose spec refresh must carry them.
- `cftlibTest` has two new journeys: a record created and then linked by the next list request, and a
  `NeedsRepair` attempt retried from scratch and then linked by the next sweep, one case each. A warm list request
  that links one record costs about 150–170 ms more than one with nothing to link. The first list request in a
  fresh JVM takes about 3 s, which is cold start and not the sweep's steady cost.

Final counts, measured 2026-09-27 from `build/test-results/` after `cleanTest cleanIntegration`, on the branch's
last code:

| Run | `test` | `integration` | Wall clock |
|---|---|---|---|
| `./gradlew cleanTest cleanIntegration build` (Docker) | **3270**, 0 failures / 0 errors / 0 skipped, 395 files | **14**, green, 3 files | 5m 53s |
| `INTEGRATION_TESTS_ENABLED=false ./gradlew cleanTest cleanIntegration build` | **2187**, 0 / 0 / 0, 278 files | not run | 1m 17s |
| `./gradlew cftlibTest` | **3** tests, green | — | 1m 26s |

Against Task 1's baseline on this branch before any change (Docker 3150 / 383 and 13 integration; Docker-free
2103 / 271; `cftlibTest` 1), that is **120** new `test` tests in 12 result files, one new `integration` test and
two new `cftlibTest` journeys. **84** of the new tests are in 7 Docker-free files, and run in CI. V23's backfill,
the keyset selection, the concurrent repairs and the WireMock linking scenarios need real Postgres. Each has a
container-free companion.

**What stands between this and AAT** is unchanged: plan 02's Dependencies (Steve's), the system role registered as
a definition-store user role, and then `CCD_LINKING_SWEEP_ENABLED=true`, which is the switch. [`CCD-SMOKE.md`](CCD-SMOKE.md)
step 3 now has its trigger, a case list request with the sweep switched on, and a repair procedure. It has not been
run. The chart lists no CCD or linking variables, so none was added there. Whoever onboards adds all of them
together.

## CCD foundation — plan 02 complete, 2026-09-27

[Plan 02](plans/completed/deed-poll-api-02-ccd-foundation.md), all nine tasks, merged to `master` as `3430346` from
`deed-poll-api-02-ccd-foundation`. **Proved on placeholders, not onboarded**: every identifier is still `DEED_POLL_PLACEHOLDER`, nothing
was imported, registered or deployed, and the linking operation is called by no real flow.

What is built, in `ccd/` unless named:

- Spring Boot **3.5.16**, the `hmcts.ccd.sdk` plugin **7.1.0** and `decentralised-runtime` from its BOM. No
  indexing, Service Bus, NoC or Work Allocation. The SDK's `ccd`-schema migrations run before ours, under its own
  history table; this application defines no `FlywayMigrationStrategy`, and `ConfigGenProfileTest` keeps it so.
- The minimal definition: `DeedPollCaseType` — state `Registered`, event `linkDeedPoll`, fields `deedPollId` and
  `deedPollReference`, `DEED_POLL_SYSTEM` granted create and read. `CcdProperties` reads the case type,
  jurisdiction and system role. `SPRING_PROFILES_ACTIVE=config-gen ./gradlew generateCCDConfig` writes it with no
  database, and `DeedPollDefinitionTest` pins it against `src/test/resources/ccd-definition/`.
- **V21**: `deedpoll_deedpoll."ccdCaseReference"`, nullable, unique, 16 digits. It is not readable, patchable,
  queryable or published. **V22**: `deedpoll_ccdcreationattempt`, with one live attempt per record, the ledger
  the migration plan reuses (`CcdCreationAttempts`).
- `/ccd-persistence/**` has its own chain. It admits S2S `ccd_data` only, plus an IDAM user holding the configured
  system role. The caseworker and judicial namespaces still need no S2S.
- `LinkDeedPollHandler` binds a reference atomically in the SDK's transaction, once, and never overwrites.
  `DeedPollCaseView` composes the view from `CcdCaseProjection`.
- `CcdLinkingService.link` claims, commits, then starts and submits through `CoreCaseDataApi` as the system user.
  Recovery reads rather than retries. The start-event token is not persisted; the plan's Task 7 has the evidence.
- **No repair operation exists yet** (superseded: plan 02b adds `/caseworker/ccd-attempts`). `reconcileOutstanding` reads expired claims and `AwaitingConfirmation` only;
  a `NeedsRepair` attempt — a contradiction, a binding never seen within the settle window, or every try refused —
  stays live and blocks a fresh attempt until a person reads the record and CCD. The plan defines
  `NeedsRepair → AwaitingConfirmation` and nothing yet performs it. V22 also says `operationId` proves ownership
  of a submission; nothing carries it to CCD or checks it, so for now it is an identifier and no more.
- `cftlibTest` (**local only**, not on `build`, `check` or the Jenkinsfile): `CcdLinkingCftlibTest` creates, links
  and reads one case through the real in-process CCD stack. The stack needs about 5.1 GB and fits this
  devcontainer.

Final counts, measured 2026-09-27 from `build/test-results/` after deleting it, on the branch's last code:

| Run | `test` | `integration` | Wall clock |
|---|---|---|---|
| `./gradlew build` (Docker) | **3138**, 0 failures / 0 errors / 0 skipped, 383 files | **13**, green, 3 files | 4m 42s |
| `INTEGRATION_TESTS_ENABLED=false ./gradlew build` | **2093**, 0 / 0 / 0, 271 files | not run | 1m 17s |
| `./gradlew cftlibTest` | 1 test, green | — | 1m 32s |

Against the `master` baseline (`09bab4c`: Docker 2956 / 363, Docker-free 1956 / 256), that is **182** new tests
in 20 result files. **137** of them are in 15 Docker-free files, and run in CI. The migration histories, the
concurrent claims, the persistence submissions and the linking scenarios need real Postgres; each has a
container-free companion.

**What stands between this and AAT** is the plan's Dependencies section (Steve's), plus one item cftlib added: the
confirmed system role must be registered as a **user role** in each environment's definition store.
[`CCD-SMOKE.md`](CCD-SMOKE.md) is the operator-run procedure: the definition import, the provisioned identities,
and create/read. It has not been run. **Its create/read step had no trigger**, because nothing called `link`. Plan 02b supplied one: a case list
request with `CCD_LINKING_SWEEP_ENABLED=true`.

## Where the Notify work actually is — read this before anything else

| Where | What is there |
|---|---|
| `master` / `origin/master` | **Everything.** notify-1, -5, -3 and -3b (PRs #29, #30, #38, #42), the three small Notify fixes (#43–#45: `applied_on_paper`, the measured JSON boolean, never printing the API key), the email case notes (#46, #48), notify-6 (#49, #50), the 4a/4b split (#51), notify-4b (#52) and notify-4c (#54). No Notify work sits on an unmerged branch |
| [`plans/completed/notify-4a-attach-files-to-an-email.md`](plans/completed/notify-4a-attach-files-to-an-email.md) | **filed as complete 2026-09-26**, the last of the series. **All five tasks are ticked** (#56–#58): ① was sent for real, local admin → local api → real Notify, on 2026-09-26. ②'s LOC024, its last open item, was attached on 2026-09-28 and read back from v9 as `((LOC024.pdf))`. Task 5 is the handover table of what stands between ① / ② and AAT |
| `charts/deed-poll-api/values.yaml` | sets the ③ `b2cb5754-…`, ④ `a58c5a8c-…` (#59) and ⑤ `a67e7dd1-…` template ids and `NOTIFY_EMAIL_REPLY_TO_ID`. ① and ② are **deliberately not set** (4a's decision 7) until the deed has a durable store |

V11 creates the outbox, V12 persists a machine-readable failure verdict, and V13 adds the
nullable rendered subject and plain-text body. Only a definite permanent refusal permits a replacement
row; its failed history remains unchanged when the replacement is sent.

## What is built

| | |
|---|---|
| Flyway migrations | **24 on `master`** (V1–V24): plan 02 added V21 (`ccdCaseReference`) and V22 (the creation-attempt ledger), and plan 02b added V23 and V24 (see its section above). Earlier wording, kept for the per-migration detail — V14 adds the nullable referral owner subjects, V15 the notification exclusion group, V16 the referral's four confirmation columns, V17 the case's `awaitingConfirmationSince` read-model and V18 the correction to V16's partial index. `email-case-notes` adds none; **`notify-6` adds V19**, the notification's non-null `requestId` with a per-row backfill and a `("deedPollId", "requestId")` unique index; **`notify-4b` adds V20**, the partial `("deedPollId", "exclusionGroup")` key over the groups `EmailExclusionGroup.exclusivePerCase()` names — which is what stops one applicant being sent both deeds |
| Caseworker endpoints | **34 across 21 paths** on `master`, over eight controllers under `api/caseworker/controller/`. `OpenAPIPublisherTest` asserts both figures, and on `notify-6` re-asserts the 34 **inside the test that proves the new send header is published**, because a header is the kind of change that looks like it should have added an operation |
| Judicial endpoints | **5** on `master`, `referrals-api-6` having merged — the shared pool, this judge's own `Claimed`/`Decided` work, one referral, claim, direction; **8** on `plan/judicial-application-view` (2026-10-19), adding the document download and revising or withdrawing a direction. Gated on `Role.JUDICIAL`, **which IDAM still does not issue** (see IDAM below) |
| `/citizen/**` | exists, empty, shares no DTO with the other two namespaces |
| Tests | **2,935 on `notify-4b`** — `test` 2,922 across 361 result files + `integration` 13 across 3, 0 failures / 0 errors / 0 skipped (measured 2026-09-25 from `build/test-results/` after `./gradlew build`, following two review passes: the first added the XML-illegal-character rules, the storeDeed refusal, the sender's document short-circuit and the `application.yaml` template-key conformance; the second added the deed-type mis-filing refusal, the patched-direction refusal and Notify's 1–78-week retention range). `notify-6` reads **2,729** (2,716 + 13), `email-case-notes` **2,622** and `master` 2,580 + 12. The branches are not stacked, so figures from one are never cumulative with another's |
| Docker-free CI | **1,923 unit tests on `notify-4b`** across 254 classes, measured with `INTEGRATION_TESTS_ENABLED=false`; the other **999** are in the container-backed classes, notify-4b having added two (`DeedDocumentServiceTest` and the forms nest of `CaseworkerEmailControllerTest` — the latter pre-existing as a class). `notify-6` reads 1,739 of 2,716 in 33 classes. Everything about the deed generator, the routing, the escaping and the exclusion-group flag runs in CI: the container-backed rules are the enqueue statement's conflict paths and the stored-once-per-case claim, and each has a container-free companion. `build.gradle` carries the history and instructs the next plan to re-measure |
| `./gradlew build` | green with `TESTCONTAINERS_RYUK_DISABLED=true` exported — on `notify-4b` after its review, both with Docker and Docker-free (2026-09-25), on `notify-6` from cold (2026-09-25), on `email-case-notes` after its review (2026-09-24), and on `master` after notify-3b. **Wall clock is not a regression signal**: the same Docker build measured 35m 32s and 3m 58s on identical code, because container startup under this Docker configuration varies by an order of magnitude |

**Twenty-eight** plans are in [`plans/completed/`](plans/completed/). The ten added since this section was
last rewritten are the saved-query publishing gate, notify-3, notify-3b, the email case notes, notify-6,
notify-4b, notify-4c, notify-4a, and CCD plans 02 and 02b. The arc since plan 01: the domain gaps
(notes, witnesses, the enrolment witness-count gate), case query and paging, saved queries and search,
then the original referrals feature in four completed plans — create/withdraw, claim/direction/close,
`currentReferralState`, and the judicial surface — plus two CI plans and the ACR migration.

Five more landed on 2026-09-21: the three referral follow-ups business testing asked for —
caseworker completion, lifecycle case notes and the judge's own worklist (PRs #33, #35, #36) — and
**notify-1 and notify-5, which were merged in PRs #29 and #30 but never filed**, so `plans/` overstated
what was outstanding until they were moved here.

**The original referrals backend is merged, and business testing reopened one rule, which is now closed.**
[`plans/referrals-api-4-caseworker-completion.md`](plans/completed/referrals-api-4-caseworker-completion.md) is
**complete and merged** (PR #33): a `Decided` referral can no longer be withdrawn,
and the existing `Decided → Closed` caseworker action is untouched. The authority is a second enum predicate,
`ReferralState.canBeWithdrawn()` (`Open || Claimed`), added *beside* `isTerminal()` rather than narrowing it —
narrowing `isTerminal()` is the plausible-looking mistake, and would also have moved the one-live-referral
guard and `currentReferralState`, letting a case raise a second referral beside a decided one. **No contract
delta**: no migration, no enum member, no DTO, 33 caseworker and 4 judicial operations unchanged, which
`OpenAPIPublisherTest` passing untouched is the evidence for. Its companion admin plan adds the missing
closure journey — the API half is already there.
[`plans/referrals-api-5-lifecycle-case-notes.md`](plans/completed/referrals-api-5-lifecycle-case-notes.md) is
**complete and merged** (PR #35), and records claim, direction and closure in the
existing case Notes timeline: every successful transition now writes exactly one note, composed in
`ReferralNotes` and authored by whoever caused it — the judge on a claim or a direction, the caseworker on a
raise, a withdrawal or a closure, and the service account only on an inactivity lapse, which no person did. A
refused transition, a judicial `touch` and the backfill write none. The direction note summarises the decision
and the Gazette answer and never quotes `directionsText`, which may be twice a note's length. **No contract
delta**: no migration, no DTO, no endpoint, 33 caseworker and 4 judicial operations unchanged — proven by
regenerating the spec from `master` into a throwaway worktree and byte-diffing it, identical at 56,771 bytes.
That 56,771 is now the baseline `referrals-api-6`'s delta was measured against: **57,495 bytes**, the difference
being one path with one GET and one schema, with the rest of the document identical key for key.
[`plans/referrals-api-6-judicial-my-referrals.md`](plans/completed/referrals-api-6-judicial-my-referrals.md) is
**complete and merged** (PR #36), and adds the missing judge-owned `Claimed`/`Decided`
worklist — `GET /judicial/referrals/mine` — after migrating ownership from display names to stable IDAM subjects,
which is what makes a personal list safe to publish at all: keyed on a name it would have enumerated a namesake's
referrals instead of requiring one of their ids. `V14` adds nullable `claimedById`/`decidedById`, nothing is
backfilled, and a pre-migration row with a null owner belongs to nobody — invisible and undirectable, which is the
fail-closed rollout consequence rather than a gap to patch. **This is the one referral follow-up with a contract
delta**: the judicial operation count is **4 → 5** with one new response schema, `JudicialMyReferralsResponse`,
while `JudicialReferralSummary` is reused unchanged and the caseworker 33 does not move. Neither owner id is
published anywhere.

## The Notify series: seven api plans, all complete

The numbering is the order they were written, **not** the order they were built:

```
notify-1  the send path                     COMPLETE (PR #29)
notify-5  what was sent, and when            case correspondence history   COMPLETE (PR #30)
notify-3  request confirmation to proceed    three exclusive templates     COMPLETE (PR #38)
notify-3b track the applicant's reply                                      COMPLETE (PR #42)
notify-6  chase missing documents            case-driven, repeatable       COMPLETE (PR #50)
notify-4b generate the deed                  LOC020 and LOC022 from a case COMPLETE (PR #52)
notify-4c preview attachments, forms notes   split out of 4a               COMPLETE (PR #54)
notify-4a attach files to an email           the Notify upload seam        COMPLETE (#56–#58); ②'s LOC024 attached 2026-09-28
```

**Every template is authored on the non-prod Notify service**, and each was read back through
`GET /v2/template/{id}` rather than taken on trust:

```
③ application-rejected             b2cb5754-591d-40c0-b961-b2823a5792eb   in the chart
④ request-confirmation-to-proceed  a58c5a8c-7727-4d1f-af08-4953d9f3d33e   in the chart (2026-09-26, two previews)
⑤ chase-missing-documents          a67e7dd1-0f8c-4366-a1de-ca5fbc24ec98   in the chart
① forms-for-completion-adult       91a89945-bfa6-4423-86f5-be6f1c782aa5   sent for real locally; not in the chart
② forms-for-completion-minor       a2500c11-b282-48c3-a196-9f5700d13c91   LOC024 attached 2026-09-28 (v9); not in the chart
```

The drafts behind them are in deed-poll-admin's `.business_context/notify-templates/`.

`notify-4` was split into `notify-4a` (attaching a file to an email) and `notify-4b` (generating the deed); the
single file no longer exists.

**notify-5 before notify-4 is deliberate**, and it is notify-5's flag 4. notify-4 attaches legal documents
to emails; shipping that while a permanent failure is still invisible means an applicant who never
receives their deed forms and a court that cannot tell. notify-5 also depends on nothing from `notify-0`,
so it can run while template authoring is still outstanding — which no other plan in the series can.

Five things a session should hold before touching any of them:

1. **`notify-0` is done locally but not in AAT.** The non-prod Notify service, its templates and a
   team-and-guest-list key exist, and the key lives in `cft-workspace/local-notes/deed-poll-api/notify-nonprod.env`.
   **AAT has no key**: the `deed-poll-aat` vault grants write only to the `DTS Deed Poll` group, which has no
   members until azure-access#8111 merges. Until then ③, ④ and ⑤ are offered in AAT and every send fails
   terminally with `Empty key`, visibly (a case note and a `Failed` row).
2. **notify-3 re-keys notify-1 task 7's index, which is now V12.** It must carry V12's null-safe
   permanent-failure predicate through unchanged rather than reconstructing it. Both plans carry the
   reciprocal note. Getting this wrong silently changes which rows the partial index covers.
3. **notify-4's spike ran, and the answer is no new library.** The deed templates the business supplied are
   Dynamics 365 Word templates — 30 and 13 content controls bound by XPath to a custom XML part, no merge
   fields. docx4j and Apache POI were both measured and rejected; `domain/deeds` writes the two changed zip
   entries with the JDK's own `java.util.zip` and `javax.xml`, and the Docmosis fallback was not needed.
   The output format is settled as `.docx`. See `plans/completed/notify-4b-generate-the-deed.md`.
4. **notify-5 rests on one verified fact.** `SendEmailResponse` carries `getSubject()` and `getBody()`, so
   the rendered email comes back inside the send acceptance: history is exact at no extra Notify call and
   nothing against the rate limit. Confirmed with `javap` against `6.2.0-RELEASE`. If that turns out to be
   wrong, the plan's cost changes completely.
5. **notify-6's template assumption is discharged, and so is its provisioning gap** (the chart now sets the
   chase id and the reply-to; the rest of this point is the history). The chase template was
   read back out of Notify on 2026-09-24 — UUID `a67e7dd1-0f8c-4366-a1de-ca5fbc24ec98`, exactly five
   placeholders, `documents_required` unconditionally required — so the plan needed no amendment. But the
   **reply-to id is Steve's word, not read-back evidence** (Notify publishes no read-only API for reply-to
   addresses), and **`charts/deed-poll-api/values.yaml` sets neither the chase template id nor
   `NOTIFY_EMAIL_REPLY_TO_ID`** — it names only `NOTIFY_TEMPLATE_APPLICATION_REJECTED`. So ⑤ **and ④** will
   report unprovisioned in AAT after notify-6 merges, and that is a chart change nobody has made rather than a
   defect in the code. The Gazette notice remains deferred with no engineering contract.

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
  `integration` from `check` and excludes the `requires-docker` tag from `test`. On `notify-6` that is
  **1,739 of 2,716** unit tests running in CI, with 977 in 33 container-backed classes; on `email-case-notes`,
  1,657 of 2,610 in 32. `build.gradle` carries the full history and instructs the next plan to re-measure it.
  **A count taken from `build/test-results/integration/` after a Docker-free run proves nothing** — the
  directory keeps the previous run's files and nothing marks them stale, so delete it first or read the task
  list.
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
   email** (the two forms emails need it, and it is off by default); register and verify a monitored reply-to
   inbox (four of the five declared templates invite a reply); record the real rate limit and the real retention
   default. notify-4b hands over three more settings: `NOTIFY_TEMPLATE_FORMS_FOR_COMPLETION_ADULT`,
   `NOTIFY_TEMPLATE_FORMS_FOR_COMPLETION_MINOR` and **`NOTIFY_DOCUMENT_RETENTION_WEEKS`**, which has no working
   default because how long an applicant needs is a business decision — and which must be **1 to 78 weeks**, the
   range Notify accepts and `NotifyProperties.retention` enforces, because anything else reads as unconfigured and
   the Email tab greys the two forms emails out. `NOTIFY_EMAIL_REPLY_TO_ID` is already in the chart and serves
   both. notify-4c hands over one Notify edit: place `((additional_notes))` in ① (and in ② when it is authored),
   or a caseworker's note is dropped with nothing failing.
   The checklist is `../deed-poll-admin/.business_context/NOTIFY_PROVISIONING_REQUEST.md`, corrected and
   merged 19 September. **All five Notify plans depend on this and none of them can be demonstrated
   without it.**
2. **The five blank forms are not in `doc_templates/`, and no longer need to be.** LOC021, LOC023, LOC024,
   LOC027 and LOC028 are named by the taxonomy, and notify-4b measured the answer: they are attached to the
   **Notify template**, so this service never stores or sends them (notify-4b Task 4 note 1, quoted into
   notify-4a Task 1's amendment). LOC020 and LOC022 are the two it generates, and both are vendored under
   `src/main/resources/deed-templates/`. What remains outstanding for the forms emails is open item 1's
   provisioning, not these files.
3. **What `signatureRequestedDate` means.** `CaseState.deriveState` derives `AwaitingSignature` from it
   being non-null, so sending the forms-for-completion email *is* the signature request — but it is also
   patchable through `DeedPollPatch`, so a caseworker can set it by hand. notify-3b flag 1 and notify-4
   flag 2 ask the same question and should be answered together.
4. **`DOCUMENT_STORE_ROOT` must not reach a deployed environment — and this is now actual, not prospective.**
   Local disk does not survive AKS and replicas do not share a volume. It was tolerable while nothing wrote
   documents; **notify-4b now generates deeds and stores them**, so on more than one replica the drain resolves
   a deed from a pod that never wrote it and **either forms email fails to send at all**. Real storage is
   plan 03.
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
8. **Resolved 2026-09-28: Steve chose the V24 fact.** `V24__ccd_attempt_proven_nothing_created.sql` adds
   `provenNothingCreated`. It is set only by `CcdCreationAttempts.exhaust`, kept by reconciliation, and cleared by
   the retry. `mayRetryFromScratch` accepts it as well as a false flag. `CcdLinkingScenariosTest` covers both
   repair moves on such an attempt, and a retry that ends in an unknown outcome. The original finding follows.
   **An attempt whose tries ran out on refused submissions had no way out of `NeedsRepair`** (found in review,
   2026-09-28). `submissionMayHaveReached` is set before every `submitCaseCreation` and never reset (plan 02b,
   decision 9). A 4xx that CCD refuses with nothing bound releases the attempt to `Pending` automatically, but once
   `CCD_LINKING_MAX_ATTEMPTS` is used up it lands in `NeedsRepair` with the flag true. `retry` is then a 409, and
   `reconciliation` finds nothing bound and returns it to `NeedsRepair` after the settle window. The live attempt
   also blocks a fresh one, so the record can be linked only by hand-written SQL. Likely causes are a 403 from a
   stale token, a 422 from the handler, or a record with no display reference. **Deciding this is Steve's call**,
   because either fix changes decision 9. One option is a V24 fact, written by the same statement that moves an
   exhausted, refused-and-unbound attempt to `NeedsRepair`, which `mayRetryFromScratch` also accepts. The automatic
   path already trusts the same evidence when it re-sends after a refusal. Either way, add a
   `CcdLinkingScenariosTest` scenario that runs out of tries on refusals and then tries both repair moves.

## There is still no plan 02 or 03 — historical

**Superseded twice**: both plans were written on 2026-09-26, and plan 02 was built on 2026-09-27 (see the CCD
foundation section above). Plan 03 is written and not built. Kept for its reasoning about `doc-assembly`:

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

**notify-6's handoff to admin, in three lines, because a session that never opens that plan still needs them**
(its decision 16 is reinstated in `../deed-poll-admin/docs/plans/completed/notify-2-caseworker-email-tab.md`,
2026-09-25):

1. **`Idempotency-Key` is required on `POST /caseworker/cases/{id}/emails/{template}` for every template**, not
   only the chase. It is `string`/`uuid`. The preview and the availability `GET` declare no header, because
   neither writes a row.
2. **One compose journey mints one UUID and preserves it** through preview, a validation failure and every
   retry; a later journey mints another. A fresh UUID per request satisfies the schema and destroys the
   protection — which is why the parameter's *description* says so and a test pins the description, not just the
   declaration.
3. **Three answers, three renderings**: 200 with `enqueued: false` is "already sent, here is the receipt"; 409 is
   "this key was used for different content"; 400 names the missing or unparseable header. And the Email tab
   renders the chase's template and its one required slot from the overview response — no chase-specific route,
   form or wire type.

## Do not let a session drift into

Writing plan 02 or 03 from guesses. Editing `src/test/resources/reference/` — it is a verbatim vendored
copy of the specification and the conformance tests read it as ground truth, so if one looks wrong, stop
and report. Editing a committed Flyway migration — **including `V11__notifications.sql`, which is
committed as V11 and V12**; **`V18` is the highest on `master`** (`notify-3b` took V16, V17 and V18) and
**`V20` is the highest committed anywhere** — notify-6 took V19 and notify-4b took V20, the case-scoped
notification exclusion-group key — so the next plan takes **V21**, confirmed
by listing `src/main/resources/db/migration` rather than by a number a plan named in advance. notify-6's
Constraints section named V14 and the real answer was V19, which is exactly why the rule is "inspect, never
reserve". **Do not reserve a number and jump ahead of it**: Flyway runs with
out-of-order disabled, so a V20 arriving after V21 has been applied fails every environment that applied V21. Inventing
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

## CCD planning update — 2026-09-26

Steve has chosen decentralised CCD, a minimal case definition and a nullable CCD
reference on the deed-poll record, followed by CDAM and Retain and Dispose. Draft plans
now exist; the earlier statements that plans 02/03 are unwritten are superseded.
No CCD/CDAM/payment integration was implemented or onboarded in this planning session.

- [Plan 02: CCD foundation](plans/completed/deed-poll-api-02-ccd-foundation.md)
- [Resumable CCD migration](plans/ccd-migration.md)
- [Payment plan amendment](plans/fees-and-pay-1-ways-to-pay-card.md#amendment-2026-09-26--ccd-and-queue-admission)
- [Plan 03: CDAM documents](plans/deed-poll-api-03-cdam-documents.md)
- [Unpaid online applications](plans/unpaid-online-applications.md)
- [Retain and Dispose](plans/retain-and-dispose.md)
- [Cross-repository overview](../../deed-poll-documentation/plans/active/ccd-and-shared-services.md)

New online submissions enter the normal caseworker queue only after verified payment.
Existing/migrated/manual cases using the established payment route retain their queue
eligibility. Online submissions get a seven-day payment deadline and one day-three
reminder with secure payment recovery. Resolve uncertain payments before coordinated
deletion. `feeReference` is free text, not evidence of receipt; record payment route and
verified status separately. Historical retention periods remain unconfirmed.

The identifiers, roles/system accounts and platform onboarding are still open inputs.
Read the new plans' dependencies rather than inventing values. The September 22
drop-CCD/synthetic-reference direction is historical and is not the implementation
baseline for this work. ~~Next step: review plan 02 and record confirmed onboarding inputs.~~

**2026-09-27: plan 02 is reviewed and restructured for ralphex — nine `### Task N:` tasks, ready to run.**
Onboarding moved out of the task list into a Dependencies section (Steve's; only Task 9's AAT smoke procedure
waits on it), so Tasks 1–8 build on `DEED_POLL_PLACEHOLDER` against WireMock and a local cftlib. Settled with
Steve: SDK **7.1.0** (what `pcs-api` runs), Spring Boot 3.5.16 first and alone, cftlib **local only**, the CCD
reference **stored but not published** (the payment plan's amendment now owns publishing it), and the Task 3
names. Baseline on `master` `09bab4c`: **2956** `test` + **13** `integration`, green — measured, and the first
attempt died on a Gradle worker timeout, which the plan records. The onboarding inputs are still unconfirmed.
