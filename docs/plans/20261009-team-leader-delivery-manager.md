# Team leader and Delivery Manager: API permissions

## Execution instructions — 9 October 2026

The user authorised this API plan in an isolated worktree with Ralphex `-t` (tasks only).
Independent internal/external review stages are deferred until the user requests them.

- Own only this worktree, `.worktrees/deed-poll-api-roles`, on `feat/team-leader-delivery-manager`.
  Two other agents are working on API changes. Do not edit their checkouts, reset their changes,
  change shared branch refs, stop their services/builds, or merge/cherry-pick their in-progress branches.
- Start from fetched `origin/master` at `938795d417cbca4ba202bc14603de9042cc9a460`.
  Reconcile overlapping work by read-only inspection and document the eventual integration points.
  If the staff-gate implementation is not on this baseline, do not implement that other agent's
  feature here; add new-role support to gates present here and record the companion integration needed.
- The workspace root is two directories above this worktree. Read its canonical CLAUDE.md and this
  repository's CLAUDE.md. Companion plans remain under the workspace's `deed-poll-admin/docs/plans`
  and `deed-poll-infra/docs/plans`; read them there without editing those repositories.
- Verified local toolchain: `JAVA_HOME=/tmp/deed-poll-jdk21`,
  `JAVA_TOOL_OPTIONS=-XX:UseSVE=0`, `TESTCONTAINERS_RYUK_DISABLED=true`.
  Add `-Porg.gradle.java.installations.paths=/tmp/deed-poll-jdk21`
  and `-Porg.gradle.java.installations.auto-detect=false` to Gradle invocations if needed.
  Docker is available with the required execution permissions. Use bounded worker counts and
  isolated build outputs; do not run `gradle --stop` or prune Docker resources shared by other agents.
- Keep contract artifacts under this worktree's build/evidence directory; avoid shared temporary
  output paths used by other builds. Do not claim a failed or skipped required test passed.
- Complete task implementation and its tests, with local commits. Do not launch review mode,
  push, merge, deploy or change real accounts. Leave progress and evidence for the later review run.

## Overview

Replace caseworker-admin with Team leader. Delivery Manager inherits every Team leader permission.
The entire Gazette facility belongs exclusively to Delivery Managers: ready worklist/count, history,
preview, submission, settings, request status and members, and any human recovery operations.
Further Delivery Manager powers will be specified separately; do not invent them in this change.

These requirements were confirmed on 9 October 2026. This is an implementation plan, not evidence
that code, role registrations or deployed access have changed.

Companions:

- [Admin plan](../../../deed-poll-admin/docs/plans/20261009-team-leader-delivery-manager.md).
- [Infra and identity rollout plan](../../../deed-poll-infra/docs/plans/20261009-team-leader-delivery-manager.md).

## Context

Planning inspected the current checkout at `b412e08` on `docs/cdam-03b-callers-plan` and the newer
locally available `origin/master` at `938795d`. The checkout has unrelated documentation edits;
leave them intact. Local `master` is older still. Neither local ref proves what is currently deployed.
Use an isolated implementation branch/worktree from the reconciled release baseline, not this
CDAM branch. Bring this plan into that worktree without bringing unrelated work with it.

The newer ref contains `CaseworkerGazetteController`, `CaseworkerGazetteRequestsController`,
`CaseworkerGazetteSettingsController`, `GazetteNoticeHistory`, `GazettePreviews`, `GazetteRequestLedger`,
`GazetteStatuses`, and `GazetteSettingsService`. Their human access gates currently name
`Role.DEEDPOLL_ADMIN`. Do not implement against the older checkout and miss these operations.

Other relevant code, relative to `src/main/java/uk/gov/hmcts/reform/deedpoll/`:

- `domain/auth/{Role,IdamClaims,CurrentUser}.java`, `auth/IdamAuthentication.java`.
- `domain/deedpoll/{FormPresentation,PresentationContext,WriteContext,EditView,DeedPollService}.java`.
- `domain/notes/NoteService.java`, `domain/witnesses/WitnessService.java`.
- `domain/savedqueries/{SavedQueryPermissions,SavedQueryService}.java`.
- `ccd/{CcdAttemptRepairs,DeedPollRoleMapping,DeedPollAccessProfile,DeedPollCaseType}.java`.
- `api/caseworker/controller/CaseworkerReadController.java` and all Gazette controllers.
- `config/SecurityConfiguration.java`; also reconcile the separate `feat/staff-gate-report-mode`
  work before changing namespace access. Its `StaffArea` currently enumerates caseworker/admin roles.

Tests live under corresponding `src/test/java` paths. CCD definition snapshots are under
`src/test/resources/ccd-definition/`; the vendored `src/test/resources/reference/` must not be edited.

## Permission contract

Canonical new claim names for this plan set are `caseworker-deedpoll-team-leader` and
`caseworker-deedpoll-delivery-manager`; the temporary legacy claim is `caseworker-deedpoll-admin`.
IDAM registration must use these exact values. If the owning
team requires different spelling, reconcile all three plans and implementations before assignment.

| Behaviour | Team leader | Delivery Manager | Legacy admin during migration |
|---|---|---|---|
| Normal casework | Existing access | Inherited | Existing access |
| Existing privacy override/default behaviour | Yes | Yes | Yes |
| Delete notes and witnesses | Yes | Yes | Yes |
| Publish/manage shared saved queries under existing ownership rules | Yes | Yes | Yes |
| Inspect/reconcile/retry CCD creation attempts | Yes | Yes | Yes |
| CCD/CDAM document reads under the existing staff reader profile | Yes | Yes | Yes |
| Entire Gazette facility | No | Yes | No |
| Judicial operations without the judicial role | No | No | No |

The caseworker and judicial roles keep their other existing permissions. A Delivery Manager-only
token must work without either Team leader or legacy admin also being assigned. Mixed roles take
the union of explicitly granted permissions: Team leader plus judicial still has no Gazette access;
Delivery Manager plus judicial can use both areas. Unknown/empty roles grant no new capabilities.

Use a small explicit capability mapping, not role ordering or IDAM role inheritance:
`OVERRIDE_PRIVACY`, `DELETE_NOTE`, `DELETE_WITNESS`, `MANAGE_SHARED_QUERIES`, `REPAIR_CCD`,
`ACCESS_GAZETTE`. Team leader grants the first five; Delivery Manager adds the last. The temporary
legacy role maps to the first five only. Keep the mapping immutable and resolve it from verified roles.
No database permission catalogue, role-management screen, RAS onboarding or generic policy framework.

The existing presentation/write context booleans can remain narrow adapters for privacy, derived
from `OVERRIDE_PRIVACY`. Do not require a wholesale rewrite of the ported form model. Gazette must
never consult an inherited `isAdmin` boolean. Retained compatibility booleans must not become a second
source of authority. This plan supersedes the conflicting scope of `role-model-generalisation.md`.

Restrict the named `readyForPublication` worklist at the API as well as the dedicated Gazette paths,
including counts and execution through saved-query links. Keep normal case visibility, ordinary case
fields, handling markers and case notes unchanged: this is a Gazette workflow boundary, not a new
confidentiality rule hiding all publication-related facts from caseworkers. Ordinary field conditions
may still find the same cases; they must never grant Gazette actions or batch/settings information.

## Development Approach

- Read the implementation baseline's CLAUDE.md before editing. Work only in this repository.
  Preserve other developers' edits and coordinate overlapping Gazette, CCD and staff-gate work.
- Testing approach: add failing permission tests with each behavioural change, then implement.
  Every code task includes updated tests; finish its focused checks before the next task.
- Authentication remains IDAM userinfo plus validated tokens. No caller-supplied roles or admin flags.
- Refuse unauthorised business calls before reads/side effects. Keep domain enforcement, including
  direct service callers, and preserve existing ownership, idempotency and state-transition checks.
- Background Gazette delivery/reconciliation continues using already-admitted durable authority.
  Do not require a human role on a worker, resend ambiguous requests, or rewrite historical attribution.
- No historical migration edits or audit rewrites. No database migration is expected for role changes.
- Use the workspace Ralphex launcher and a selected review profile. Do not rewrite `.ralphex/config`.
  Local task commits/reviews belong here; pushing, merging and live rollout are separate handoffs.

## Validation Commands

Run from the API implementation worktree with Java 21 and Docker available. Record actual test counts
and skips; a run excluding `requires-docker` is not evidence of HTTP/database enforcement.

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew test --tests '*CurrentUser*' --tests '*IdamClaims*' --tests '*RoleCapabilities*'
./gradlew test --tests '*Note*' --tests '*Witness*' --tests '*SavedQuery*' --tests '*CcdAttempt*' --tests '*FormPresentation*'
./gradlew test --tests '*Gazette*' --tests '*Security*' --tests '*Staff*'
./gradlew generateCCDConfig
./gradlew cftlibTest
./gradlew build
git diff --check
```

Adapt test selectors to the reconciled baseline and measure each run before another overwrites its
reports. Regenerate OpenAPI through the baseline's `OpenAPIPublisherTest`; capture a uniquely named
artifact and its source SHA/checksum for admin rather than reusing a shared temporary file.

## Implementation Steps

### Task 1: Establish the release baseline and role contract

**Files:** this plan; relevant role/security/Gazette tests in `src/test/java/uk/gov/hmcts/reform/deedpoll/`.

- [x] Record implementation SHA, branch, clean/dirty baseline, relevant dependency SHAs and validation results. Reconcile newer Gazette/CCD work and the staff-gate branch; do not silently drop either.
- [x] Inventory every current admin check, actual Gazette HTTP operation, ready-filter execution path, generated role mapping and local fixture. Record file locations and ownership/visibility rules to preserve.
- [x] Add baseline regression coverage where an existing privileged behaviour lacks it. Distinguish current behaviour from the new matrix; do not preserve legacy Gazette access in the final expectations.
- [x] Record the exact permission matrix and new claim strings above in test data. Run focused baseline tests and account for failures before implementation.

### Task 2: Add the two roles and explicit inherited capabilities

**Files:** `domain/auth/{Role,IdamClaims,CurrentUser}.java`; new `domain/auth/Capability.java` and
`domain/auth/RoleCapabilities.java`; corresponding auth tests.

- [x] Add Team leader and Delivery Manager claims. Keep the legacy claim explicitly deprecated for migration, granting only Team leader capabilities. Do not normalise legacy admin into Delivery Manager.
- [x] Implement the immutable mapping and `can`/`require` capability helpers. A missing capability fails closed; do not derive a required-role list that could invoke the existing open-on-empty `requireAnyRole()` behaviour.
- [x] Derive any retained compatibility flag from this mapping. Keep identity/subject and audit attribution unchanged; do not permit separately asserted capability sets to override verified roles.
- [x] Test each role alone, legacy only, empty/unknown claims, duplicate/mixed roles and judicial combinations. Assert Delivery Manager's inherited set includes every Team leader capability and Gazette is the only new one.
- [x] Run auth/claims tests and compile affected callers; record results.

### Task 3: Preserve casework privileges and CCD document access

**Files:** note/witness services, `SavedQueryPermissions`, `CcdAttemptRepairs`, privacy adapters,
`DeedPollRoleMapping`, applicable staff namespace gates; associated tests and CCD definition snapshots.

- [x] Replace non-Gazette admin role checks with the corresponding capability. Feed existing privacy adapters from `OVERRIDE_PRIVACY`, preserving both create defaults and edit behaviour.
- [x] Preserve saved-query rules: shared-query management does not grant access to another user's private query, ownership remains subject-based, and an owner can still unpublish under existing rules.
- [x] Admit both new roles to caseworker namespace membership wherever membership is enforced or reported. Preserve the staff gate's configured mode and judicial boundaries; Gazette refusals must enforce even if the general staff gate is report-only.
- [x] Map both new roles to the existing CCD staff reader profile. Preserve the legacy reader mapping temporarily and ordinary/judicial mappings. Grant neither new role the system profile, system events or importer roles.
- [x] Parameterise privileged domain/HTTP tests over Team leader, Delivery Manager and legacy admin. Add denials for ordinary/judicial callers and document-reader tests for each new role alone.
- [x] Regenerate and review the CCD definition/snapshots; prove only intended role rows change. Run focused tests and the local cftlib document journey; do not hand-edit vendored reference fixtures.

### Task 4: Make every Gazette entry point Delivery Manager-only

**Files:** Gazette controllers and services; case-list read/filter execution; related HTTP/domain tests.

- [x] Gate history, settings GET/PUT, preview, submission (including retries), request listing/detail/member reads, and every existing human recovery/download action with `ACCESS_GAZETTE`. Replace controller pre-validation helpers as well as domain gates. Inventory actual routes rather than assuming one `/gazette/**` prefix covers them.
- [x] Gate the resolved `readyForPublication` worklist before querying cases, draining outbox or invoking the CCD sweep. Carry the caller to the appropriate domain seam so direct service use cannot bypass the rule. Follow saved-query execution into that same gate; keep unrelated filters unchanged.
- [x] Cover authenticated legacy admin, Team leader, caseworker, judicial, unknown/no service roles and Team leader+judicial: all receive 403 on valid Gazette requests. Unauthenticated calls retain 401. Delivery Manager alone and Delivery Manager+judicial pass the role gate.
- [x] Assert denials do not reveal batch/settings/member data or mutate settings, requests, reservations, notes, documents or send state; Notify is never invoked. Include a legacy/Team leader retry of an already known request key and a bookmarked ready-filter count.
- [x] Preserve Delivery Manager happy-path outcomes, concurrency/conflict handling, idempotency and recovery evidence. Background completion of a previously admitted request is not a fresh human authorisation.
- [x] Run Gazette, case-list, saved-query and security tests with database-backed coverage and record results.

### Task 5: Reconcile fixtures, API descriptions and contract provenance

**Files:** cftlib/test fixtures; Gazette controller OpenAPI descriptions; integration publisher tests;
`README.md`, `CLAUDE.md`, active role/Gazette plans.

- [ ] Make Gazette fixtures use Delivery Manager-only identities. Keep separate Team leader and legacy negative fixtures; avoid fixing tests by assigning all roles to every caller.
- [ ] Update operation descriptions and privileged-role documentation, including named-ready-filter refusal. Preserve paths, payloads, idempotency semantics and audit attribution.
- [ ] Mark the active older Gazette plans' admin-access decisions and the generalisation plan as superseded by this permission contract. Keep completed plans and vendored reference source as historical evidence.
- [ ] Generate the complete OpenAPI artifact and record source SHA, checksum and full delta for the admin plan. Do not filter unrelated contract drift away; describe it for reconciliation.
- [ ] Run publisher/contract tests and review regenerated artifacts for unintended schema/path changes.

### Task 6: Verify acceptance criteria and prepare the release handoff

**Files:** this plan's execution evidence; API release notes/role documentation.

- [ ] Run full build, required security/domain/container tests, generated-definition comparison and local cftlib checks. Record commands, counts, failures/skips and changed permission coverage honestly.
- [ ] Re-scan runtime source for legacy-only authorisation and Gazette `isAdmin` checks. Remaining legacy references must be limited to explicit compatibility mapping, its fixtures and migration documentation.
- [ ] Review all capability consumers against the matrix: Team leader retains every non-Gazette admin privilege; Delivery Manager inherits them without a second role; neither acquires judicial/system privileges.
- [ ] Record release artifact/source identities, CCD definition provenance, admin contract handoff, compatibility-removal criteria and outstanding external rollout steps. Do not mark deployment as complete from test results.

## Post-Completion

Role registration, CCD import, account assignment, live smoke checks and legacy retirement follow the
infra companion's sequence. They are not autonomous Ralphex checkboxes. Deploy the API restriction to
every instance before exposing the new admin UI. Mixed old API instances still grant legacy Gazette
access; do not declare cutover complete while any serve traffic.

Once the new roles and reader mappings are deployed, existing admin users migrate to Team leader by
default. Delivery Manager assignment is explicit. Retain subject IDs and historical attribution.
Fresh login is required after assignment changes. Legacy compatibility must be removed in a coordinated
follow-up only after assignments, sessions and rollback versions have been accounted for. Rolling back
to a binary granting Gazette access to old admins would reverse the agreed boundary; use a forward fix
or suspend Gazette access instead. In-flight Gazette evidence must survive either approach.


## Execution evidence — Task 1 (9 October 2026)

### Baseline and integration inputs

- Started clean on `feat/team-leader-delivery-manager` at
  `9e2ac0979235e5b3bfc61c6afda4011fd90351c6`. Its only delta from fetched
  `origin/master` `938795d417cbca4ba202bc14603de9042cc9a460` was this plan.
- Read-only companion HEADs: admin `3ac876cfc0864350a2895892254eacc1105a5117`,
  infra `8d8bc501eb423f8e3addf3631f15be79a105e926`. Both companion plans agree
  on the exact claims and API-before-UI rollout. These are source identities, not deployed versions.
- CCD branch `feat/aat-ccd-staff-read` at `e17d0454e9b3a2fb65296ce5e8242f968e56f34f`
  is already an ancestor of the release baseline (merge-base ancestry check passed).
  Retain its three existing reader mappings and add the two new staff mappings in Task 3.
- Staff-gate branch inspected at `54c6f5fbeec4ab72b5a2852eaefb8195a5b7b60b`.
  Its `config/StaffArea.java` admits CASEWORKER/DEEDPOLL_ADMIN to `/caseworker`
  and only JUDICIAL to `/judicial`. It introduces StaffGate, StaffAccessDeniedHandler,
  SecurityConfiguration wiring, application configuration and three staff test classes.
  None is on this baseline: its general chain uses `anyRequest().authenticated()`.
  Companion integration must add both new roles to StaffArea.CASEWORKER and its
  membership/report-mode tests, preserve the configured mode, and retain the judicial boundary.
  Do not import the other agent's unfinished feature here. Gazette domain gates must enforce
  regardless of whether that companion gate reports or enforces.
- Build pins CCD SDK `7.1.0` (BOM-managed decentralised-runtime), cftlib `0.19.2377`,
  CDAM client `1.59.2`, IDAM client `3.0.5`, service-auth client `5.3.5`, Notify
  `6.2.0-RELEASE`, Spring Boot `3.5.16`, Java 21. These are published dependencies;
  this build does not consume sibling source checkouts and does not establish their source SHAs.

### Authorisation and execution inventory

Paths below are relative to `src/main/java/uk/gov/hmcts/reform/deedpoll/`.
Full line-numbered snapshots are retained locally under `build/evidence/task1/`
(`admin-checks.txt`, `role-fixtures.txt`, `dependency-versions.txt`).

- `domain/auth/Role.java`, `IdamClaims.java`, `CurrentUser.java`: exact claim recognition,
  admin derivation and immutable role list. CurrentUser's public constructor currently accepts
  an independent isAdmin boolean; Task 2 must remove its authority. `requireAnyRole()` is
  open on an empty required-role list; capability refusal must not delegate an empty list.
- `domain/notes/NoteService.java:160`, `domain/witnesses/WitnessService.java:157`:
  deletion requires DEEDPOLL_ADMIN. Preserve parent scoping, not-found behaviour and attribution.
- `ccd/CcdAttemptRepairs.java:88,105,127`: list, reconcile, retry require admin.
  Repairs also require subject identity. Keep no-resend evidence, state checks and transactions.
- `domain/savedqueries/SavedQueryPermissions.java:69,95`: shared edits and publishing use
  isAdmin. Visibility remains owner-or-shared; an admin cannot see another person's private query.
  Ownership uses subject, never display name. The owner may unpublish after losing privilege.
- Privacy adapters: `CurrentUser.writeContext`, `domain/deedpoll/WriteContext`,
  `PresentationContext`, `FormPresentation` (create defaults and editability),
  `DeedPollService` (create and privacy patch), `EditView.EditContext`.
  Preserve ordinary case data and privacy field rules; derive the flag from OVERRIDE_PRIVACY.
- `ccd/DeedPollRoleMapping.java:32-37` supplies both persistence authentication and
  `DeedPollCaseType` generated RoleToAccessProfiles rows. Existing CASEWORKER, DEEDPOLL_ADMIN
  and JUDICIAL map only to DEED_POLL_READER; the configured system role maps separately.
  Reader grants are read-only case type/state/supportingDocuments; no system events or identifiers.
  Snapshot: `src/test/resources/ccd-definition/DEED_POLL_PLACEHOLDER/RoleToAccessProfiles.json`.
- `src/cftlib/.../CftlibConfig.java`: ADMIN_USER has only the legacy claim; role catalogue
  bootstrap and reader fixtures need the two new claims, without adding system/importer authority.
  The complete test-fixture inventory includes auth/claims, security, CCD mapping/definition,
  saved queries, referrals, note/witness/case controllers, Gazette ledger/preview/delivery/settings/history.
  Vendored `src/test/resources/reference/` remains untouched historical specification.

Actual Gazette HTTP operations (all under `/caseworker`):

| Method | Path | Current domain gate |
|---|---|---|
| GET | `/gazette-notices` | GazetteNoticeHistory.history |
| GET, PUT | `/gazette-settings` | GazetteSettingsService.get/update |
| POST | `/gazette-notice-previews` | GazettePreviews.preview |
| POST | `/gazette-notices` | GazetteRequestLedger admission/repeated-key resolution |
| GET | `/gazette-notice-requests` | GazetteStatuses.list |
| GET | `/gazette-notices/{batchId}` | GazetteStatuses.detail |
| GET | `/gazette-notices/{batchId}/cases` | GazetteStatuses.members via detail |

These eight operations live in CaseworkerGazetteController, CaseworkerGazetteSettingsController
and CaseworkerGazetteRequestsController. The latter's `admin()` helper also checks before body/key
validation on preview/submit. Ledger gates occur in admit, repeat and read paths;
all must change, including repeat keys. No human recovery or download endpoint exists on this
baseline. GazetteRecovery and GazetteDelivery use durable admitted authority; preserve reservations,
provider/send evidence, immutable attribution and no-replay semantics. Existing legacy-admin happy
paths describe the baseline only and must be converted to Delivery Manager in later tasks.

The ninth Gazette entry is `GET /caseworker/cases?filter=readyForPublication`, including its
pagination total/count. CaseworkerReadController.cases resolves ListFilters.filterFor, calls
DeedPollService.findDeedPolls, then drains the notification outbox and runs the CCD sweep.
All public findDeedPolls overloads currently lack caller-based authorisation, including unpaged
and query/sort/paging variants. Task 4 must gate the resolved named filter at the domain seam,
before database reads and before those controller side effects. SavedQueryService stores and
returns filter/query definitions through SavedQueryView; execution is a case-list link, not a
separate server-side saved-query execution endpoint. The same named-filter gate must apply there.
Ordinary field conditions may select identical cases and remain permitted.

### Regression coverage and target contract

Existing CurrentUser/IdamClaims, FormPresentation, Note/Witness, SavedQueryPermissions/Service,
CcdAttemptRepairs/controller, GazettePreviewApi, GazetteDeliveryApi and history/settings controller
tests cover current role behaviour, ownership, state transitions and HTTP gates.
Added GazetteStatusesTest covers direct domain list/detail/member denials and denial before parsing,
with no repository interactions for empty, caseworker, judicial and caseworker+judicial callers.
It asserts no legacy Gazette allowance and therefore remains valid after the new contract lands.

`src/test/resources/auth/staff-permission-contract.json` records the target claim/capability matrix
for single, empty, unknown, duplicate and mixed roles, plus separate judicial, CCD reader and system
expectations. It is target test data for Tasks 2–4, not an assertion that new roles work today.
The first five capabilities belong to Team leader and temporary legacy admin; Delivery Manager
adds only ACCESS_GAZETTE. Neither new role confers judicial or system authority. Private-query
visibility and subject ownership remain independent of that capability matrix.


### Task 1 validation result

Executed from this worktree with `JAVA_HOME=/tmp/deed-poll-jdk21`,
`JAVA_TOOL_OPTIONS=-XX:UseSVE=0`, `TESTCONTAINERS_RYUK_DISABLED=true`:

```bash
./gradlew test --tests '*CurrentUser*' --tests '*IdamClaims*' \
  --tests '*Note*' --tests '*Witness*' --tests '*SavedQuery*' \
  --tests '*CcdAttempt*' --tests '*FormPresentation*' --tests '*Gazette*' \
  --tests '*Security*' --tests '*DeedPollRoleMapping*' --tests '*DeedPollDefinition*' \
  checkstyleMain checkstyleTest --max-workers=2 \
  -Porg.gradle.java.installations.paths=/tmp/deed-poll-jdk21 \
  -Porg.gradle.java.installations.auto-detect=false
git diff --check
```

Result: BUILD SUCCESSFUL; 919 tests across 135 XML suites, zero failures, errors or skips.
Docker-backed HTTP/database tests were included; no requires-docker exclusion was used.
GazetteStatusesTest contributed eight passing cases. Main/test Checkstyle and diff check passed.
Existing compiler/Gradle deprecation warnings remain; there were no validation failures to resolve.
Results and the exact Gradle log were copied before any subsequent run into
`build/evidence/task1/test-results`, `counts.json`, `validation.log` and `checkstyle/`.
No RoleCapabilities or Staff test classes exist on this baseline, so those selectors were omitted.
Standalone generation, cftlib, OpenAPI publication and full build belong to later implementation
and acceptance tasks; this focused baseline run does not claim those checks or deployment passed.
Only Task 1 is complete in this iteration; runtime roles and authorisation remain at the baseline.


## Execution evidence — Task 2 (9 October 2026)

Started clean at `4a119c8e2014a37eb10a44b6fbbfeeea7c5a72d4` on
`feat/team-leader-delivery-manager`. Read the workspace/repository instructions and both companion
plans; their claim names and inheritance contract agree. No sibling repository was changed.

Added the exact Team leader and Delivery Manager claims to `Role`; marked `DEEDPOLL_ADMIN`
deprecated for migration. `IdamClaims` already resolves through `Role.fromClaimValue`, so both
array and comma-separated claims recognise the new roles without a second parsing implementation.
`Capability` and `RoleCapabilities` define an immutable explicit union: Team leader and legacy
admin receive the five casework capabilities; Delivery Manager inherits those and only adds
`ACCESS_GAZETTE`. Caseworker/judicial roles grant none of these privileged capabilities.
`CurrentUser.can`/`require` fail closed, including a null requirement, independently of the existing
open-on-empty role helper. The retained constructor flag is ignored and derived from
`OVERRIDE_PRIVACY`; roles are defensively copied. Identity, subject and audit attribution stay intact.
No capability set or extra claims can override recognised roles.

Added `RoleCapabilitiesTest`: all 12 contract fixtures, each exercised with both supported claim
formats, plus exact inheritance/legacy checks, immutable grants and role snapshots, independent
flag/claim injection refusals, null requirement refusal and identity assertions. Existing auth and
claims tests still pass. Tests were added first: compilation failed on the missing capability API
(`build/evidence/task2/red.log`), then passed after implementation.

Executed with `JAVA_HOME=/tmp/deed-poll-jdk21`, `JAVA_TOOL_OPTIONS=-XX:UseSVE=0`,
`TESTCONTAINERS_RYUK_DISABLED=true`:

```bash
./gradlew test --tests '*CurrentUser*' --tests '*IdamClaims*' --tests '*RoleCapabilities*' \
  checkstyleMain checkstyleTest --max-workers=2 \
  -Porg.gradle.java.installations.paths=/tmp/deed-poll-jdk21 \
  -Porg.gradle.java.installations.auto-detect=false
git diff --check
```

BUILD SUCCESSFUL: 54 tests across 7 XML suites; zero failures, errors or skips. Main/test compilation
and both Checkstyle tasks passed. Evidence is saved under `build/evidence/task2/` as validation.log,
counts.json, test-results/ and checkstyle/. Deprecation notices include the deliberately deprecated
legacy role; no warnings were suppressed. This is focused domain/auth coverage, not HTTP/database
or rollout proof. Consumer gate migration, CCD generation/cftlib, OpenAPI and full-build acceptance
remain Tasks 3–6. Only Task 2 was completed in this iteration.


## Execution evidence — Task 3 (9 October 2026)

Started clean at `fa92c555db850ca051836f8616d3541306f858c4` on
`feat/team-leader-delivery-manager`. Read workspace/repository guidance and both companion plans.
Only this API worktree was edited; no review stage, push, merge or deployment was performed.

Migrated note deletion, witness deletion and all three CCD repair operations to their explicit
capabilities. Saved-query editing/publishing now consults `MANAGE_SHARED_QUERIES`; visibility,
subject-based ownership and the owner's ability to unpublish remain unchanged. `CurrentUser.writeContext`
feeds the existing privacy adapters directly from `OVERRIDE_PRIVACY`. Tests cover create/edit privacy
writes, create defaults, existing-value preservation and attribution for each privileged role alone.

Parameterized existing domain and HTTP regressions over Team leader, Delivery Manager and legacy
admin, including parent scoping, private-query refusals, shared-query edits/deletes/publishing,
CCD repair outcomes, conflict/no-resend evidence and concurrent repairs. Ordinary/judicial deletion
and shared-query denials remain explicit; all five judicial operations refuse each privileged role
without JUDICIAL. The new CCD mappings are exercised by the persistence security chain and local
CDAM downloads with each role alone; both new roles are refused CCD creation/document event submission.

This baseline still has no `StaffArea`/`StaffGate` membership or report-mode implementation. The general
chain remains `anyRequest().authenticated()` and the new-role HTTP tests pass through it. No separate
staff-gate feature was imported or its mode changed. Companion integration must admit both new roles
in `StaffArea.CASEWORKER` and cover report/enforce modes while retaining JUDICIAL-only judicial access,
as identified in Task 1. Gazette domain gates remain enforcing independently of that general chain;
their migration to Delivery Manager-only capability checks is Task 4, not completed here.

`generateCCDConfig` compared 23 JSON files against the prior pinned definition. Exactly one file
changed: `RoleToAccessProfiles.json`, with exactly two added `DEED_POLL_READER` rows for the new claims.
All existing mappings and every other generated file are unchanged. System/event/importer privileges
were not added. The snapshot was copied from generated output; vendored reference fixtures were untouched.
The complete delta is recorded in `build/evidence/task3/definition-delta.json`.

Executed from this worktree with `JAVA_HOME=/tmp/deed-poll-jdk21`,
`JAVA_TOOL_OPTIONS=-XX:UseSVE=0`, `TESTCONTAINERS_RYUK_DISABLED=true`; each Gradle invocation used
`--max-workers=2 -Porg.gradle.java.installations.paths=/tmp/deed-poll-jdk21
-Porg.gradle.java.installations.auto-detect=false`:

```bash
./gradlew test --tests '*CcdAttemptRepairsTest' --tests '*SavedQueryPermissionsTest'
./gradlew generateCCDConfig
./gradlew test --tests '*CurrentUser*' --tests '*IdamClaims*' --tests '*RoleCapabilities*' \
  --tests '*Note*' --tests '*Witness*' --tests '*SavedQuery*' --tests '*CcdAttempt*' \
  --tests '*FormPresentation*' --tests '*CaseworkerCaseController*' --tests '*Security*' \
  --tests '*DeedPollRoleMapping*' --tests '*DeedPollDefinition*' checkstyleMain checkstyleTest
./gradlew cftlibTest checkstyleCftlib checkstyleCftlibTest checkstyleMain
git diff --check
```

The first command was the red test run before implementation: 87 tests, 24 expected failures because
new-role CCD repairs were still legacy-only. Focused validation after implementation passed all
832 tests across 124 XML suites, zero failures/errors/skips, including Docker-backed HTTP/database
coverage (no `requires-docker` exclusion). Its initial Checkstyle run found one variable-declaration
distance warning in a new parameterized test; making the local caller final resolved it. Repeating
the validation command succeeded; Gradle retained the passing test result as UP-TO-DATE because that
lint-only correction did not change test bytecode. Main/test Checkstyle both passed.

The complete local cftlib suite passed all 14 tests, zero failures/errors/skips, including the document
journey with all five staff roles, event refusals, restart/recovery and existing linking/caller journeys.
Both cftlib Checkstyle tasks passed. Evidence is retained under `build/evidence/task3/`: red.log,
generation.log, validation-initial.log, validation.log, cftlib.log, counts.json, cftlib-counts.json,
test-results/, cftlib-test-results/, checkstyle/ and definition-delta.json. Existing compiler/Gradle/JVM
warnings remain. These results establish local behaviour only; full-build acceptance, Gazette gates,
contract publication and external rollout remain Tasks 4–6 and Post-Completion. Only Task 3 is complete.


## Execution evidence — Task 4 (9 October 2026)

Started clean at `9f2193cf9e08acf3551040cf81a2d06474506530` on
`feat/team-leader-delivery-manager`. Read workspace/repository guidance and companion plans.
Only this API worktree was edited. No review stage, push, merge or deployment was performed.

Replaced all human Gazette domain gates with `ACCESS_GAZETTE`, including ledger admission,
known-key repeat and direct reads. The controller's preview/submission helper checks the capability
before business body/key validation. The route inventory remains eight operations: history GET,
settings GET/PUT, preview POST, submission POST, request-list GET, detail GET and members GET.
There is no human recovery/download operation on this baseline. Background delivery/recovery keeps
using the durable admission claim; its send, reservation, attribution and recovery logic is unchanged.

The ninth entry is the named `readyForPublication` case-list filter. All four filter-taking
`DeedPollService.findDeedPolls` overloads now require an explicit caller and enforce before any
finder/specification, total-count or reference-match read. The HTTP controller passes the verified
caller and only drains outbox/runs CCD sweep after the authorised result. The no-argument overload
continues to select the ordinary all-cases filter. Saved queries store definitions and execute through
case-list URLs, so they reach the same gate; owning or publishing a saved query grants no bypass.
Ordinary filters, field conditions and case visibility remain unchanged.

Added direct-domain denial tests with zero collaborator interactions for history, settings, preview,
ledger admission/repeat/read and every list overload; extended status/member tests for legacy admin,
Team leader and Team leader+judicial. PostgreSQL/full-security-context HTTP tests cover all nine
entries for empty/unknown roles, caseworker, judicial, legacy admin, Team leader and Team leader+judicial,
plus unauthenticated 401s and Delivery Manager alone/with judicial allowances. Valid fresh requests and
same-subject known-key retries are refused. Full row snapshots prove denied calls leave settings,
requests/files/members, reservations, successful history, notes, documents, notifications and cases
unchanged; mocks prove no Notify, outbox or sweep calls. Saved-query read-to-execution tests prove
403 without a count, while ordinary conditions still return the same case.

Existing Gazette outcome fixtures now use Delivery Manager alone, preserving the concurrency,
conflict, idempotency, attribution, send and recovery regressions. Other list fixtures carry explicit
callers; ordinary domain query tests use caseworker, while all-filter/ready-list fixtures use Delivery
Manager. Broader fixture naming, cftlib identity reconciliation and operation documentation remain Task 5.

Validation used Java 21 and Docker, with no `requires-docker` exclusion:

```bash
export JAVA_HOME=/tmp/deed-poll-jdk21
export JAVA_TOOL_OPTIONS=-XX:UseSVE=0
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew test --tests '*GazetteStatusesTest' --max-workers=2 \
  -Porg.gradle.java.installations.paths=/tmp/deed-poll-jdk21 \
  -Porg.gradle.java.installations.auto-detect=false
./gradlew checkstyleMain checkstyleTest test --tests '*Gazette*' \
  --tests '*CaseworkerRead*' --tests '*DeedPollService*' --tests '*CcdCaseReferenceColumn*' \
  --tests '*SavedQuery*' --tests '*Security*' --max-workers=2 \
  -Porg.gradle.java.installations.paths=/tmp/deed-poll-jdk21 \
  -Porg.gradle.java.installations.auto-detect=false
git diff --check
```

The first command was the red run before gate migration: 14 tests, two expected failures for legacy
admin status reads. Initial broad validation ran 745 tests with seven new snapshot-comparison failures:
JDBC SQL-array wrapper equality was comparing object identity. JSON row-value snapshots resolved them.
After adding saved-query coverage, seven cleanup failures exposed a string bind against its UUID key;
binding `UUID.fromString` resolved them. Initial lint found two long lines and two variable-declaration
distance warnings; line wrapping/final locals resolved them. No production rule was relaxed.

Final result: BUILD SUCCESSFUL; 752 tests across 80 XML suites, zero failures/errors/skips.
Both main/test Checkstyle tasks and diff check passed. Existing JVM/compiler/Gradle warnings remain.
Evidence is retained under `build/evidence/task4/`: red.log, validation-initial.log, validation-second.log,
lint-initial.log, validation.log, initial-counts.json, counts.json, initial-test-results/, test-results/,
checkstyle/ and gates.txt. The runtime scan found no legacy role checks or `isAdmin` in Gazette gates.
No Staff test classes exist on this baseline; the existing Security tests were included. Full build,
OpenAPI publication and release acceptance remain Tasks 5–6; these local tests do not prove deployment.
Only Task 4 is complete in this iteration.
