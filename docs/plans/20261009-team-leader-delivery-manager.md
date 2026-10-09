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

- [ ] Record implementation SHA, branch, clean/dirty baseline, relevant dependency SHAs and validation results. Reconcile newer Gazette/CCD work and the staff-gate branch; do not silently drop either.
- [ ] Inventory every current admin check, actual Gazette HTTP operation, ready-filter execution path, generated role mapping and local fixture. Record file locations and ownership/visibility rules to preserve.
- [ ] Add baseline regression coverage where an existing privileged behaviour lacks it. Distinguish current behaviour from the new matrix; do not preserve legacy Gazette access in the final expectations.
- [ ] Record the exact permission matrix and new claim strings above in test data. Run focused baseline tests and account for failures before implementation.

### Task 2: Add the two roles and explicit inherited capabilities

**Files:** `domain/auth/{Role,IdamClaims,CurrentUser}.java`; new `domain/auth/Capability.java` and
`domain/auth/RoleCapabilities.java`; corresponding auth tests.

- [ ] Add Team leader and Delivery Manager claims. Keep the legacy claim explicitly deprecated for migration, granting only Team leader capabilities. Do not normalise legacy admin into Delivery Manager.
- [ ] Implement the immutable mapping and `can`/`require` capability helpers. A missing capability fails closed; do not derive a required-role list that could invoke the existing open-on-empty `requireAnyRole()` behaviour.
- [ ] Derive any retained compatibility flag from this mapping. Keep identity/subject and audit attribution unchanged; do not permit separately asserted capability sets to override verified roles.
- [ ] Test each role alone, legacy only, empty/unknown claims, duplicate/mixed roles and judicial combinations. Assert Delivery Manager's inherited set includes every Team leader capability and Gazette is the only new one.
- [ ] Run auth/claims tests and compile affected callers; record results.

### Task 3: Preserve casework privileges and CCD document access

**Files:** note/witness services, `SavedQueryPermissions`, `CcdAttemptRepairs`, privacy adapters,
`DeedPollRoleMapping`, applicable staff namespace gates; associated tests and CCD definition snapshots.

- [ ] Replace non-Gazette admin role checks with the corresponding capability. Feed existing privacy adapters from `OVERRIDE_PRIVACY`, preserving both create defaults and edit behaviour.
- [ ] Preserve saved-query rules: shared-query management does not grant access to another user's private query, ownership remains subject-based, and an owner can still unpublish under existing rules.
- [ ] Admit both new roles to caseworker namespace membership wherever membership is enforced or reported. Preserve the staff gate's configured mode and judicial boundaries; Gazette refusals must enforce even if the general staff gate is report-only.
- [ ] Map both new roles to the existing CCD staff reader profile. Preserve the legacy reader mapping temporarily and ordinary/judicial mappings. Grant neither new role the system profile, system events or importer roles.
- [ ] Parameterise privileged domain/HTTP tests over Team leader, Delivery Manager and legacy admin. Add denials for ordinary/judicial callers and document-reader tests for each new role alone.
- [ ] Regenerate and review the CCD definition/snapshots; prove only intended role rows change. Run focused tests and the local cftlib document journey; do not hand-edit vendored reference fixtures.

### Task 4: Make every Gazette entry point Delivery Manager-only

**Files:** Gazette controllers and services; case-list read/filter execution; related HTTP/domain tests.

- [ ] Gate history, settings GET/PUT, preview, submission (including retries), request listing/detail/member reads, and every existing human recovery/download action with `ACCESS_GAZETTE`. Replace controller pre-validation helpers as well as domain gates. Inventory actual routes rather than assuming one `/gazette/**` prefix covers them.
- [ ] Gate the resolved `readyForPublication` worklist before querying cases, draining outbox or invoking the CCD sweep. Carry the caller to the appropriate domain seam so direct service use cannot bypass the rule. Follow saved-query execution into that same gate; keep unrelated filters unchanged.
- [ ] Cover authenticated legacy admin, Team leader, caseworker, judicial, unknown/no service roles and Team leader+judicial: all receive 403 on valid Gazette requests. Unauthenticated calls retain 401. Delivery Manager alone and Delivery Manager+judicial pass the role gate.
- [ ] Assert denials do not reveal batch/settings/member data or mutate settings, requests, reservations, notes, documents or send state; Notify is never invoked. Include a legacy/Team leader retry of an already known request key and a bookmarked ready-filter count.
- [ ] Preserve Delivery Manager happy-path outcomes, concurrency/conflict handling, idempotency and recovery evidence. Background completion of a previously admitted request is not a fresh human authorisation.
- [ ] Run Gazette, case-list, saved-query and security tests with database-backed coverage and record results.

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
