# CCD, CDAM and document store onboarding

Status: open, recorded 2026-10-06. This checklist tracks the six onboarding workstreams across sessions.
Implementation of the local CCD/CDAM journeys does not establish deployed onboarding. Complete each
workstream with environment-specific evidence before marking it done.

## Resume here

**Workstream 1 is in progress.** Steve agreed the following identifiers on 2026-10-06 after reviewing PCS:

| API setting | Agreed value |
|---|---|
| `CCD_JURISDICTION_ID` | `DEEDPOLL` |
| `CCD_CASE_TYPE_ID` | `DEEDPOLL` |
| `CCD_SYSTEM_ROLE` | `deed-poll-system-update` |

These names are settled for onboarding; registration and deployed configuration are still outstanding.
The internal CCD access profile remains `DEED_POLL_SYSTEM`. Do not grant it to real staff to make downloads work.
Steve explicitly requested AAT registration on 2026-10-06. Infra #25 is merged and its four API secrets
were verified present/enabled on 2026-10-07. A separate CCD importer is now prepared in infra #26 and
IDAM #1016. Apply #26 before the IDAM rerun; see the dedicated importer entry below. Then complete
IDAM/S2S deployment and the CCD role/definition import. No live IDAM or CCD registration is claimed.

Identity investigation found an existing, still-open S2S registration PR and no deed-poll system account
or password-grant client in current IDAM configuration. See the identity inventory and session log below.

The API checkout inspected was `b412e08` on `docs/cdam-03b-callers-plan`, with migrations through V30.
Plan 03b is implemented locally. Existing-file migration remains 03c; the citizen upload adapter is absent.
CCD linking, CDAM and owned staging remain disabled by default.

The workspace deployment handover records user-confirmed AAT application/API persistence on 2 October.
That does not prove CCD linking, document durability or Notify delivery. Older deployment statements in
`RESUME.md` are historical; check deployed configuration when execution starts.

## Progress

- [ ] 1. Identity and agreed identifiers
- [ ] 2. CCD routing, registration and definition
- [ ] 3. Staff document access
- [ ] 4. CDAM service permissions
- [ ] 5. Document store verification
- [ ] 6. Configuration, smoke tests and activation

### 1. Identity and agreed identifiers

- [x] Agree CCD jurisdiction `DEEDPOLL`, case type `DEEDPOLL` and system-user role `deed-poll-system-update`
  (Steve, 2026-10-06).
- [x] Confirm the target environment: AAT (Steve, 2026-10-06).
- [x] Prepare concrete system-account/client/redirect/vault configuration for review in the linked AAT PRs.
  The synthetic account name was proposed and used as the draft default; no account has been provisioned.
- [ ] Provision the account and role through durable IDAM configuration and enable the client's password grant.
- [ ] Complete/deploy the existing `deed_poll_api` S2S registration and verify its source secret.
- [ ] Arrange the application vault secret and access, then verify system-user and S2S authentication.
- [ ] Record secret **names and locations only**, plus provisioning/verification evidence. Never record values,
  passwords or tokens here.

Done when the agreed identifiers are recorded, the system user authenticates with the expected role, and
the service can obtain its own S2S token in the target environment.

#### Identity inventory as of 2026-10-06

| Input | Evidence and remaining action |
|---|---|
| Environment | AAT confirmed by Steve. Draft PRs prepared; no live environment change applied. |
| Jurisdiction, case type and system role | Agreed by Steve: `DEEDPOLL`, `DEEDPOLL`, `deed-poll-system-update`. Registration/deployment pending; API defaults remain `DEED_POLL_PLACEHOLDER`. |
| IDAM roles | Upstream registers `caseworker-deedpoll-admin` and `caseworker-deedpoll-judicial`; neither is the agreed system role. Both exclude demo, ithc, perftest and prod. |
| OAuth client | Existing `deed-poll-admin` has no password grant. IDAM PR #1016 adds dedicated `deed-poll-api` with password grant and openid/profile/roles. |
| System account | PR #1016 adds a durable account with only `deed-poll-system-update`. Infra PR #25 stores the proposed synthetic username and generated password in the product vault. Not provisioned. |
| S2S identity | `deed_poll_api` is the API's configured name and is included in existing [registration PR #1140](https://github.com/hmcts/service-auth-provider-app/pull/1140), still open/draft. |
| Source S2S secret | Live metadata verified: `s2s-aat` / `microservicekey-deed-poll-api` exists and is enabled. No secret value was printed. Registration/token leasing remain unverified. |
| Application S2S secret | Infra PR #25 copies the existing source to `deed-poll-aat/api-s2s-secret`; Flux PR #48052 mounts it as `DEED_POLL_API_S2S_SECRET`. Apply/deployment pending. |

Recommended IDAM route: a dedicated API password-grant client, following the existing PCS pattern,
plus a durable system account in `idam-access-config`'s `accounts.yaml` with vault-backed credentials.
This is now prepared in IDAM PR #1016, not a deployed registration. The current browser client should not be assumed
to support system-user login. The API's `SystemUserTokens` uses a user/password grant; client_credentials
does not replace that user identity. Verify the resulting roles through IDAM userinfo, not by assuming
the access token itself contains roles.

The test-user helper is unsuitable for this durable account: AAT testing-support users expire after
roughly three hours. Provision through the established IDAM account pipeline instead.

PR #1140's stated merge dependency is provisioning both deed-poll secrets in `s2s-prod` by an operator
with access, because its shared chart affects production too. Confirm that dependency is resolved with
the S2S owner before progressing the PR. Secret existence, registration merge, deployment and successful
token leasing are separate checks. No message to platform owners has been sent in this session.

#### PCS precedent and deed-poll decisions

Checked PCS API upstream `e733cfe16ea7d73dab118b9ed6c09bf969165ef1` and IDAM configuration
`9f95709fcb87557a7721236c5a878cfd7a2410c0` on 2026-10-06. These are source/configuration findings,
not a fresh runtime authentication check.

| Purpose | PCS | Deed-poll value and status |
|---|---|---|
| CCD jurisdiction ID | `PCS` | `DEEDPOLL` — agreed |
| Base CCD case type ID | `PCS` | `DEEDPOLL` — agreed |
| System-user IDAM role | `pcs-system-update` | `deed-poll-system-update` — agreed |
| Dedicated OAuth client | `pcs-api` | `deed-poll-api` — prepared in AAT draft PR #1016 |
| S2S service identity | `pcs_api` | `deed_poll_api` (already named in registration PR #1140) |

PCS uses a product-specific jurisdiction and a single base case type with the same identifier.
Its case type can append `CASE_TYPE_SUFFIX` for staging/preview, while its jurisdiction stays `PCS`.
For deed-poll, `DEEDPOLL` is readable and avoids underscores in the decentralised routing prefix.
Separate staging/preview case types can be considered when those deployments need isolated definitions;
the agreed case type above is the base identifier only.

PCS registers `pcs-system-update` as an IDAM role, maps `idam:pcs-system-update` to its CCD system
access profile, and registers the role in definition store with classification `PUBLIC`. Deed-poll
already maps the configurable `CCD_SYSTEM_ROLE` to its internal `DEED_POLL_SYSTEM` access profile.
The agreed external role therefore does not require renaming that internal profile or changing its grants.

PCS's durable account is declared in `accounts.yaml`. Its email and password are references to
`idam-system-user-name` and `idam-system-user-password` in `pcs-<<ENV>>`; its roles are `caseworker`,
`caseworker-pcs` and `pcs-system-update`. Deed-poll's account roles must match its own access requirements;
do not copy PCS's generic/staff roles without establishing the need. The real account email is not present
in source, so no deed-poll email has been inferred from PCS's localhost default.

PCS's dedicated API client allows the password grant with `openid`, `profile` and `roles`, reads
`pcs-api-idam-secret` from its product vault, and declares
`https://pcs.<<ENV>>.platform.hmcts.net/receiver` as its redirect URI. The API chart supplies that client
secret and the separate system-user credentials. Deed-poll's client secret names, account identity and
redirect URI are now recorded in the AAT execution log; the PCS URL is not copied into deed-poll configuration.

Sources:

- [PCS case identifiers](https://github.com/hmcts/pcs-api/blob/e733cfe16ea7d73dab118b9ed6c09bf969165ef1/src/main/java/uk/gov/hmcts/reform/pcs/ccd/CaseType.java)
- [PCS IDAM role and access-profile mapping](https://github.com/hmcts/pcs-api/blob/e733cfe16ea7d73dab118b9ed6c09bf969165ef1/src/main/java/uk/gov/hmcts/reform/pcs/ccd/accesscontrol/UserRole.java)
- [PCS definition-store role registration](https://github.com/hmcts/pcs-api/blob/e733cfe16ea7d73dab118b9ed6c09bf969165ef1/src/main/java/uk/gov/hmcts/reform/pcs/ccd/config/HighLevelDataSetupApp.java)
- [PCS IDAM registrations](https://github.com/hmcts/idam-access-config/tree/9f95709fcb87557a7721236c5a878cfd7a2410c0/apps/pcs/base)
- [PCS chart secret wiring](https://github.com/hmcts/pcs-api/blob/e733cfe16ea7d73dab118b9ed6c09bf969165ef1/charts/pcs-api/values.yaml)

#### Where the agreed values must be registered or configured

Apply and verify these in each target environment; choosing the names does not register them.

| Location | Required entry | Workstream |
|---|---|---|
| IDAM `idam-access-config` | Declare `deed-poll-system-update` in deed-poll's `roles.yaml` and assign it to the durable system account in `accounts.yaml`. | 1 |
| CCD definition store user roles | Register `deed-poll-system-update` with `PUBLIC` classification. Retain/register the internal `DEED_POLL_SYSTEM` profile required by the definition; registering only that profile does not supply the IDAM role's classification. | 2 |
| Generated CCD definition, imported into definition store | Jurisdiction `DEEDPOLL`, case type `DEEDPOLL`, and `RoleToAccessProfiles` mapping `idam:deed-poll-system-update` to `DEED_POLL_SYSTEM`, with existing grants. Import creates/updates the jurisdiction and case type. | 2 |
| CCD data-store deployment in Flux | Route the `DEEDPOLL` case-type prefix to this environment's deed-poll API through `ccd.decentralised.case-type-service-urls`. | 2 |
| CDAM `src/main/resources/service_config.json` | Entry for `deed_poll_api`, jurisdiction `DEEDPOLL`, case type list `["DEEDPOLL"]`, permissions CREATE/READ/UPDATE. | 4 |
| Deed-poll API deployment and definition-generation environment | Set `CCD_JURISDICTION_ID=DEEDPOLL`, `CCD_CASE_TYPE_ID=DEEDPOLL`, `CCD_SYSTEM_ROLE=deed-poll-system-update` consistently. The same role mapping controls persistence access. | 2 / 6 |

The two CCD role/profile names serve different purposes: the IDAM role is assigned to the system account;
the internal profile carries CCD permissions. Do not assign `DEED_POLL_SYSTEM` to staff or treat it as a
replacement for the external role's definition-store registration.

The document store receives jurisdiction/case-type/case metadata through CDAM; the current design requires
no separate deed-poll jurisdiction or IDAM-role registration there. S2S registration and the CCD/CDAM service
allowlists use `deed_poll_api`, a separate service identity. Staff access remains workstream 3; additional
XUI/AM/WA registrations are outside this minimal linking/document flow.

### 2. CCD routing, registration and definition

- Add `deed_poll_api` to data-store's `DATA_STORE_S2S_AUTHORISED_SERVICES` in the environment's Flux overlay.
- Add the agreed case-type prefix to `ccd.decentralised.case-type-service-urls`, targeting the deployed API.
  Preserve the intended map key; environment-variable binding can turn underscores into separators.
- Verify data-store can reach the API's `/ccd-persistence/**` endpoints and the API admits `ccd_data` only
  as the calling service, with the required user authority.
- Register the required definition-store user roles and security classifications, including the actual
  system IDAM role as `PUBLIC`, and agree who imports definitions.
- Generate and import the current definition with confirmed identifiers and the staff grants from workstream 3.
  It must include the supporting-document field/event added by 03a. `CCD-SMOKE.md` was corrected on
  2026-10-06 to describe the current definition and API-client audience requirement.

Done when the definition and routing are deployed and a controlled create/read proves one CCD binding per
local record. Coordinate enabling the traffic-triggered linking sweep with workstream 6: it selects existing
unlinked records as well as newly created smoke cases.

### 3. Staff document access

- Agreed 2026-10-08: all three staff types receive case-type-wide CCD document READ. The definition
  maps their dedicated IDAM roles to `DEED_POLL_READER`, with R on case type, state and supportingDocuments.
- Register/assign `caseworker-deedpoll` in AAT; deploy the API/admin changes and register all six role/profile
  names at PUBLIC before importing the regenerated definition. See the latest dated entry below.
- Update the generated-definition fixtures and permission tests together with the implementation.
- Prove authorised staff downloads and denial for a user without the required READ permission, plus
  wrong-case refusal. Service-authorised Notify retrieval is a separate check.

Done when the agreed staff model is implemented and deployed permission checks pass without assigning
system privileges to staff. Record the known unmapped-user upstream 502 separately from a genuine CCD
READ refusal; see `CDAM-SMOKE.md` and `CDAM-RECOVERY.md`.

### 4. CDAM service permissions

- Add `deed_poll_api` to `CASE_DOCUMENT_S2S_AUTHORISED_SERVICES` in the target environment's Flux overlay.
- Add an entry in `ccd-case-document-am-api/src/main/resources/service_config.json`, scoped to the confirmed
  case type and jurisdiction, granting **CREATE, READ and UPDATE**. UPDATE permits the soft-delete path.
- Verify CCD data-store's `ccd_data` identity retains ATTACH and READ. CCD performs attachment; the API
  does not need ATTACH or permanent-delete permission for its current flow.
- Record the PRs, merges and deployed CDAM version containing the service configuration.

Done when the deployed allowlist and service permissions admit the required API operations and CCD attachment.

### 5. Document store verification

- Verify CDAM's access to the real document store and the accepted file types/sizes in the target environment.
  Direct `deed_poll_api` access to doc-store appears unnecessary: application storage calls go through CDAM.
- Prove attachment metadata (`case_id`, `case_type_id`, `jurisdiction`) and removal of the temporary TTL.
- Prove byte-for-byte download after restart or through another replica, and controlled soft deletion.
- Arrange sanitised dm-store response captures with its operator as described in `CDAM-SMOKE.md`; update
  the local contract fixtures/provenance and run their drift and contract checks when captures are available.

Done when the real store round trip and durability checks pass and evidence identifies deployed versions.
A CDAM public response alone is not a dm-store response capture.

### 6. Configuration, smoke tests and activation

- Wire the confirmed endpoints, identifiers and vault secrets into the API deployment. Set a document URL
  base accepted by data-store's environment-specific document URL pattern.
- Agree the controlled smoke scope and existing-record handling before enabling linking. Run
  `CCD-SMOKE.md`, then `CDAM-SMOKE.md`, recording expected and observed outcomes.
- For generated deeds, configure an agreed durable-payload capacity and verify both forms, attachment,
  service-authorised retrieval and a later Notify drain. Follow `CDAM-RECOVERY.md` for worker quiescence
  before V28/new outbox workers and for uncertain delivery; never blindly resend a possibly-sent email.
- Complete 03c's inventory/migration of Local documents, generated deeds and frozen queued-email references
  before switching ordinary traffic. Preserve original bytes and identities and verify remote read-back.
- Record rollout/rollback decisions, operational owners, monitoring and capacity evidence. Switching the
  backend alone does not migrate documents or make the other backend's rows readable.
- Keep owned staging disabled unless its separate settings, operational gates and trusted adapter are ready.
  Citizen readiness additionally requires authenticated draft ownership and actual browser byte transfer.

Done when the agreed deployed journeys pass, existing files remain accessible, and the environment's
activation and operational handoff are recorded. Track other environments separately; one does not prove another.

## Evidence and session log

Append dated decisions and execution evidence here; preserve earlier reasoning when correcting it.
Update the progress checkboxes and “Resume here” pointer after each session. For every external change,
record repository, PR/commit, environment, deployment status and observed result separately.

### 2026-10-06 initial investigation

- Fetched Flux `origin/master` at `47f64908280`. Its AAT data-store and CDAM allowlists omit `deed_poll_api`,
  and its data-store routing has no deed-poll entry.
- Fetched CDAM `origin/master` at `1d320648`. `src/main/resources/service_config.json` has no deed-poll entry;
  `ccd_data` has wildcard ATTACH/READ permissions.
- Flux's AAT dm-store overlay already includes `ccd_case_document_am_api` in `S2S_NAMES_WHITELIST`.
  This is configuration evidence, not a live connectivity or deployment check.
- Prepared a temporary AAT patch adding `deed_poll_api` to the two service allowlists; it passed
  `git apply --check` against the fetched Flux files. No PR was raised and nothing was applied or deployed.
  Recreate it from current upstream when the environment is agreed; do not depend on a `/tmp` artifact.
- Environment and identifiers/access decisions remain unanswered. No onboarding smoke was run.

### 2026-10-06 workstream 1 investigation

- Fetched `idam-access-config` master at `9f95709` and inspected `apps/deed-poll/base/{services,roles}.yaml`
  and `apps/deed-poll/aat/services.yaml`. Confirmed the role/client/account gaps in the inventory above.
  The existing local `deed-poll-judicial-role` branch was preserved.
- Read the IDAM repository's Accounts model: `accounts.yaml` supports environment exclusions, a vault,
  user email, vault-backed password and role names. PCS provides a checked-in example of a dedicated
  API password-grant client and durable system account. No deed-poll account was created.
- Queried GitHub for [S2S PR #1140](https://github.com/hmcts/service-auth-provider-app/pull/1140): open,
  draft, `merged_at: null`. Its patch registers both `deed_poll_web` and `deed_poll_api`. Its body reports
  non-production source secrets exist and production source secrets are missing. These are the PR author's
  reports, not fresh vault verification. Current upstream chart lookup found no deed-poll entries.
- Fetched `deed-poll-infra` master at `ae52e9859d0dd954a33dc6ffbb1d6a5d5ef239db`: `s2s.tf` copies only
  `microservicekey-deed-poll-web` into `web-s2s-secret`. Its comment calling the API registered is not
  sufficient evidence of registration; the still-open PR takes precedence for merge status.
- Azure account metadata returned Enabled. Read-only secret-metadata listings for `s2s-aat` and
  `deed-poll-aat` both crashed with `Illegal instruction`, including outside the filesystem sandbox.
  No secret values were requested or printed. Actual secret presence/access and token leasing remain
  unverified; retry on a working Azure CLI host or resolve the CLI fault before provisioning.
- Next: confirm the environment and identifiers, settle the durable account/client/vault naming, then
  prepare the IDAM and infrastructure changes. Coordinate the existing S2S PR rather than duplicating it.
  No registration, vault, deployment or runtime setting was changed; workstream 1 remains unchecked.

### 2026-10-06 PCS comparison

- Steve confirmed the jurisdiction, case type and system role have not been settled and requested the
  possession service precedent. Traced PCS's definitions, IDAM role/account/client, definition-store role
  registration and chart wiring. Recorded the proposed names above, pending his decision.
- Fetched PCS master without moving its local checkout. No PCS source or deployed identity was changed.

### 2026-10-06 identifier decision

- Steve accepted jurisdiction `DEEDPOLL`, base case type `DEEDPOLL` and IDAM system role
  `deed-poll-system-update`. This settles the three-name proposal recorded in the PCS comparison above.
- Use these values for the corresponding `CCD_*` settings when preparing onboarding configuration.
  Retain the internal `DEED_POLL_SYSTEM` access profile and existing grants.
- This decision does not establish registration, deployment or successful authentication. The environment,
  OAuth client/account details and secret names remain outstanding; workstream 1 remains open.

### 2026-10-06 AAT registration preparation

Steve requested completion of AAT registration. Configuration is prepared, committed and pushed in isolated
worktrees; original shared-service checkouts were preserved. **None of the following PRs has been merged or
deployed by this session.** Workstream completion still requires live evidence.

| Repository / draft PR | Commit | Purpose and ordering |
|---|---|---|
| [deed-poll-infra #25](https://github.com/hmcts/deed-poll-infra/pull/25) | `e1d6d57` | Review the real pipeline plan, then apply AAT credentials first. New resources are AAT-only. |
| [idam-access-config #1016](https://github.com/hmcts/idam-access-config/pull/1016) | `385306f` | Role, password-grant client and durable account. Requires the three IDAM vault secrets before synchronization. |
| [ccd-case-document-am-api #813](https://github.com/hmcts/ccd-case-document-am-api/pull/813) | `24676204` | CREATE/READ/UPDATE for `deed_poll_api`, scoped to `DEEDPOLL` jurisdiction and case type. |
| [cnp-flux-config #48052](https://github.com/hmcts/cnp-flux-config/pull/48052) | `96ff9864ef0` | AAT allowlists/routing, API identity/endpoints and secret mounts. Requires infra apply before reconciliation. |
| [Existing S2S #1140](https://github.com/hmcts/service-auth-provider-app/pull/1140) | Existing PR | Still draft/open; its shared chart's missing production secrets remain a separate prerequisite. |

All four new branches are named `deed-poll-aat-ccd-registration`. Local worktrees are under
`/tmp/deed-poll-aat-registration-{deed-poll-infra,idam-access-config,ccd-case-document-am-api,cnp-flux-config}`;
use the pushed branches/PRs if temporary directories disappear. PR descriptions cross-link the dependencies.

Prepared identity settings:

- Client: `deed-poll-api`, password grant, scopes `openid profile roles`.
- Reserved redirect: `http://deed-poll-api-aat.service.core-compute-aat.internal/receiver`; no browser grant.
- Proposed synthetic account: `deed-poll-system-update-aat@hmcts.net`, used as the draft Terraform default
  after offering an existing-account alternative. No account was created. Its only role is
  `deed-poll-system-update`; no staff or import privilege is granted.
- Product vault: `deed-poll-aat`; names `api-idam-client-secret`, `api-idam-system-username`,
  `api-idam-system-password`, `api-s2s-secret`. Terraform generates the client secret and system password, stores the
  username, and copies the existing platform S2S key without rotation. No values are recorded here.
- API token audiences: `deed-poll-admin,deed-poll-api`. The same JWT decoder protects CCD persistence,
  so the dedicated system client's audience must be accepted there.
- Linking remains `false`, document store remains `filesystem`, staging remains `false`.

Read-only live checks: API HelmRelease reported chart `0.0.19`, successful release revision 17. Vault
metadata showed existing admin/web identities but no API identity secrets. The source API S2S secret
exists and is enabled. Azure CLI worked with `OPENSSL_armcap=0`; the earlier SIGILL was a tooling failure,
not a missing-secret result. No vault values were printed and no vault writes were made.

Validation completed:

- Terraform 1.14.0 formatting and validation passed. Initialization used `-backend=false`; **no plan or
  apply** was run. The real pipeline plan, permissions and source-secret access still need review.
- IDAM's own YAML schemas passed. Its effective-config loader confirmed deed-poll entries only in AAT
  across aat/demo/ithc/perftest/prod. No secret resolution, IDAM sync or live authentication occurred.
- Both AAT Flux overlays rendered with `kubectl kustomize --load-restrictor LoadRestrictionsNone`.
  Assertions verified IDs, audiences, routing, allowlists, all five retained API-chart secrets and off flags.
- CDAM JSON scope was checked and every pre-existing service entry compared unchanged.
- `generateCCDConfig` passed using the agreed values. All 22 JSON files match the pinned definition
  with only identity substitutions. The official definition processor produced
  `build/definitions/CCD_Definition_DEEDPOLL_aat.xlsx`; read-back verified jurisdiction, case type,
  role mapping and both events. SHA-256: `0c9170e04fab0ef88906c08607b2a838257beca85c03b02a675345fcf83d956a`.
  This local build artifact is not committed and must be regenerated if cleaned. API source was
  `b412e08523289946c27c6b77aa795f207f815d5f`; processor source was
  `7a30a86181d9f72fdfb3c9b9cd40c2bb1181845d`. From the API repository, generate with
  `CCD_JURISDICTION_ID=DEEDPOLL CCD_CASE_TYPE_ID=DEEDPOLL CCD_SYSTEM_ROLE=deed-poll-system-update ./gradlew generateCCDConfig`.
  From the processor repository, run `yarn json2xlsx -D <api>/build/definitions/DEEDPOLL -o <api>/build/definitions/CCD_Definition_DEEDPOLL_aat.xlsx`.
  On this ARM host Java additionally needed `JAVA_TOOL_OPTIONS=-XX:UseSVE=0`.
- GitHub showed the four new PRs open/draft; no CI checks were reported for infra, IDAM or CDAM at the
  final check. Flux #48052 passed all 18 reported static, secrets and environment-schema CI checks,
  including both AAT clusters. Local validation is not a substitute for the other platform pipelines
  or infrastructure plan review.
- `CCD-SMOKE.md` now includes the supporting-document event/field, internal profile registration and
  API-client audience. No deployed smoke was run.

Remaining execution gates, in order:

1. Infra #25's four API secrets are verified present/enabled. Review/apply importer infra #26 and verify its
   two separate importer secrets before the next IDAM run.
2. Complete IDAM #1016 synchronization and S2S #1140 deployment; verify password-grant userinfo role and
   own-service token leasing. Do not substitute the short-lived test-user helper or another product's identity.
3. Deploy CDAM #813 and Flux #48052 with registration flags still off; confirm effective runtime settings.
4. Verify the new importer's IDAM synchronization and authentication using the #26 credentials. Register `deed-poll-system-update` and `DEED_POLL_SYSTEM` as `PUBLIC`, then
   import the generated definition. Do not give definition-import privilege to the runtime account.
5. Run the controlled CCD/CDAM smoke after staff access and rollout prerequisites are settled. There is no
   separate direct API-to-doc-store registration in this design; CDAM already appears in AAT's store allowlist.

### 2026-10-06 infrastructure PR merged

- Steve explicitly authorized merging infra #25. It was marked ready and squash-merged at
  `2026-10-06T22:21:51Z`, merge commit `8cf4c51c6e7dc1f867a71d04bc064d28aa4ce65b`.
- GitHub confirmed `MERGED`. The merge was pinned to the validated head `e1d6d57`; no admin override
  was used. GitHub reported no PR checks or pipeline-plan comments at merge time.
- Merge is not apply evidence. The infrastructure pipeline's AAT result and all four destination secrets
  still need verification before IDAM registration or Flux secret mounts proceed. No other registration
  PR was merged in this turn. Workstream 1 remains open.

### 2026-10-07 Jenkins failure investigation

- Live metadata confirmed all four API secrets exist and are enabled in `deed-poll-aat`, updated at
  `2026-10-07T05:40:51Z`. This establishes the destination-secret prerequisite, not IDAM registration.
- IDAM #1016 build 1 reported ERROR at `05:39:25Z`, 86 seconds before those secrets appeared.
  Missing secrets are a hypothesis until the console error is read. GitHub's description is only
  `This commit cannot be built`.
- CDAM #813 build 1 reported ERROR at `05:35:58Z` with the same generic description. Its actual
  failing stage/cause is unknown.
- Both Jenkins console requests redirected to Microsoft sign-in. The available browser tool could not
  start because its Chrome executable is absent. No authenticated Jenkins session was available.
  Steve was asked for each build's failing stage and first error block, with credentials omitted.
- IDAM PR head `385306f`: 41 local unit tests passed. `writeSecretFile` with `TEAMS=deed-poll` and all
  five pipeline environments produced exactly the three expected API IDAM secret references, all
  pointing to `deed-poll-aat`. No live IDAM calls or secret-value reads were made.
- CDAM PR head `24676204`: 75 local tests passed across `DocumentManagementServiceImplTest` and
  `CaseDocumentAmControllerTest`; compilation succeeded. This does not cover the full Jenkins pipeline.
- No reproducible defect was found in the PR changes, so neither PR was modified or merged and no
  Jenkins retry was triggered. Next obtain the two console error blocks; retry IDAM once its failure
  is confirmed to be the now-resolved credential prerequisite, or fix the actual reported cause.
- Local diagnostic logs: `/tmp/deed-poll-idam-1016-local-tests.log` and
  `/tmp/deed-poll-cdam-813-local-tests.log`. This investigation was recorded after documentation PR #78 merged. Steve will rerun the pipelines
  during office hours; account preparation continues separately.

### 2026-10-07 dedicated CCD importer prepared

Steve confirmed no known deed-poll CCD importer exists and requested the PCS pattern. Prepared a separate
AAT-only durable IDAM account; no live account was created and no definition-store call was made.

| Setting | Prepared value |
|---|---|
| Account | `deed-poll-definition-importer-aat@hmcts.net` |
| Roles | `ccd-admin`, `ccd-import`, matching PCS's `PCS CCD Definition Import User` |
| Vault | `deed-poll-aat` |
| Username secret | `definition-importer-username` |
| Password secret | `definition-importer-password` (Terraform-generated; no value committed) |
| Excluded environments | demo, ithc, perftest, prod |
| Infrastructure | [deed-poll-infra #26](https://github.com/hmcts/deed-poll-infra/pull/26), commit `216ff5c` |
| IDAM registration | Updated [idam-access-config #1016](https://github.com/hmcts/idam-access-config/pull/1016), importer commit `74ec2f7` |

PCS precedent: `idam-access-config/apps/pcs/base/accounts.yaml` declares the same two roles and vault-reference
names. `pcs-api/Jenkinsfile_CNP` loads them into `DEFINITION_IMPORTER_USERNAME/PASSWORD` for data setup;
its `HighLevelDataSetupApp` registers CCD roles then loads the generated workbook. Deed-poll's account
preparation follows the identity pattern; automatic pipeline imports are not enabled in this change.

The importer is distinct from the runtime user, which retains only `deed-poll-system-update`. Its credentials
are not added to API/chart/Flux secret mounts. The existing CCD-owned roles are reused, not redefined.
A registered user alone does not establish a usable import route: the eventual operator/pipeline must use
an accepted OAuth client and authorized service caller where required by definition store.

Validation: Terraform formatting and validation passed without a state backend, plan or apply. IDAM's
schemas and effective-environment loader passed; the secret manifest contains exactly two importer references,
both in AAT. The account's role list and vault-reference names match PCS exactly. No secrets were read.

Next, review and apply infra #26, verify the two enabled importer secrets, then rerun/synchronize IDAM #1016.
**The new importer references mean an IDAM rerun now requires #26 as well as the already-applied #25 secrets.**
After synchronization, authenticate the importer and verify its two roles. Then register `deed-poll-system-update`
and `DEED_POLL_SYSTEM` as PUBLIC in definition store and import the checked `DEEDPOLL` workbook through the
authorized route. Keep linking/CDAM/staging disabled until their separate smoke and rollout gates pass.

## Related procedures and implementation plans

- [Session handover](../RESUME.md)
- [CCD smoke procedure](../CCD-SMOKE.md)
- [CDAM smoke procedure](../CDAM-SMOKE.md)
- [Recovery and rollout boundaries](../CDAM-RECOVERY.md)
- [CDAM protocol and permissions](../CDAM-SEQUENCE.md)
- [CDAM implementation and migration scope](deed-poll-api-03-cdam-documents.md)
- [03b callers and staging](deed-poll-api-03b-cdam-callers-and-staging.md)


### 2026-10-08 PR and AAT deployment checkpoint

Verified GitHub status and live `cft-aat-01-aks` configuration. Earlier failing-build and missing-importer
secret notes above are historical and superseded by this checkpoint.

| Change | Verified state |
|---|---|
| Infra #26 | Merged 2026-10-07 07:32 UTC; importer secret metadata updated 07:35 UTC. Both importer secrets and all four API identity secrets are enabled. |
| IDAM #1016 | Open, not draft, review required, behind master. Jenkins build 7 SUCCESS at `b798b8124c5cc8bec35513a754d5038ae60eca5a`. Diff remains only accounts/roles/services YAML. |
| CDAM #813 | Open/draft; five checks SUCCESS at `b0eeb4463a0dd09a759d283ae6d819b04fa7ff98`, including preview functional/smoke and Pact verification. |
| Flux #48052 | Open/draft. Earlier 18 checks passed at `96ff9864ef0`. Pushed `c8f2a7857fd` to retain the Notify secret added to today's API chart; all 18 fresh CI checks passed. |
| S2S #1140 | Open/draft, merge conflict, overall Jenkins ERROR and static/unit check SUCCESS at `799abbe1`. Its recorded production-secret prerequisite still needs owner confirmation; production was not inspected. |

The IDAM pipeline passed without local Gradle patch `a6a81cf`. That patch remains unpushed and is not a
current merge prerequisite. HTTP 429 was the earlier observed failure; no specific infrastructure or
cache change has been proven to explain the later success. The PR description was corrected accordingly.

Live deployment evidence:

- API: 3/3 ready, `prod-577e674-20261008091046`, Helm release Ready at chart 0.0.19.
  `DOCUMENT_STORE=filesystem`, `IDAM_API_AUDIENCES=deed-poll-admin`; no explicit agreed CCD identifiers or
  linking/staging enablement in the deployment environment.
- CCD data-store: 3 ready, `prod-9c7ea73-20261007164257`; no `deed_poll_api` allowlist member or DEEDPOLL route.
- CDAM: 2 ready, `prod-98b0b52-20261007144954`; no `deed_poll_api` allowlist member.
- Definition store: 2 ready, `prod-906c27b-20261008103307`; no role-catalogue or definition-import call made.
- S2S: 3 ready, `prod-8ca9939-20261007061917`; no deed-poll-named environment configuration observed.
  This is not a token-leasing test.
- dm-store: 2 ready, `prod-a56f955-20261006115603`; its S2S allowlist already includes
  `ccd_case_document_am_api`. Connectivity, attachment metadata and byte durability remain untested.

Flux compatibility correction: the current API chart now mounts `notify-api-key` as `NOTIFY_API_KEY`.
The older onboarding overlay replaced the entire list and would have removed it. Added that existing mount;
all six chart mounts are preserved alongside four identity mounts. YAML parsing, no-duplicate/mount-preservation
assertions, `git diff --check` and `kubectl kustomize` for `apps/deed-poll/aat/base` passed. Rendered HelmRelease
assertions also confirm the three activation flags stay off. No runtime rollout or application data changed.

Next gate is review/merge/deployment of IDAM and CDAM, resolution of the S2S merge/production-secret prerequisites,
and corrected Flux merge (all 18 checks passed). Then verify authentication, register CCD PUBLIC roles and import the definition.
Staff access, real document-store smoke and 03c migration remain separate gates. This dated handover is local.

### 2026-10-08 S2S #1140 conflict resolved and prerequisites rechecked

Steve asked to investigate S2S #1140 while the other registration PRs are in flight. Created isolated
checkout `/tmp/deed-poll-s2s-1140` and merged master `2312b777` into the existing PR branch. The sole conflict
was chart version: PR 0.3.140 versus master's 0.3.139. Kept 0.3.140 and master's Java chart dependency 5.3.2.
Pushed merge commit `0126712a4b9b7ff7f8f6320807f8964711f54e21`; GitHub reports MERGEABLE, still draft.
The final diff against master remains four mapping lines and the version bump only.

Helm dependency retrieval and lint passed. Rendered AAT and production with Key Vault support enabled;
assertions prove both secret names/aliases occur exactly once in the proper environment vault, no duplicates,
and all master registrations are retained. Fresh Jenkins status is pending. The older run's generic ERROR
cannot be diagnosed from its successful unit/static check; fetching its detailed console returned HTTP 403.

Fresh metadata checks confirm both `microservicekey-deed-poll-web` and `microservicekey-deed-poll-api` are
present and enabled in sandbox, demo, AAT, perftest and ITHC. **Production metadata access now works**, correcting
the older no-access note: both names are absent in `s2s-prod`, and neither appears among soft-deleted secrets.
No secret values were read and no secrets were created or rotated. Read access does not establish write access.

Next action requiring Steve's authorization beyond the existing AAT scope: create only those two missing
production secrets, each independently generated from ten cryptographically random bytes encoded as 16 BASE32
characters, then verify without logging values. Preserve all non-production secrets. The shared chart references
production as well as AAT, which is why this prerequisite blocks merge even for AAT onboarding.
Keep PR draft until prerequisite verification and CI complete. No merge, live rollout or token lease was performed.

### 2026-10-08 AAT-only S2S route selected and prepared

Steve explicitly requested AAT first instead of production secret creation. No production secrets were
changed. The preceding request for production authorization is superseded for current onboarding.

Raised [Flux #48113](https://github.com/hmcts/cnp-flux-config/pull/48113), branch `deed-poll-s2s-aat-only`,
commit `7fae4eb0faf`, from master `2a79fa3eca8`. It changes only the RPE AAT kustomization and adds an
AAT-only S2S HelmRelease patch. It uses the deployed S2S chart 0.3.139 (Java chart 5.3.2) without a chart
release or application image update:

- Add `java.keyVaults.s2s-aat` with `excludeEnvironmentSuffix: true`, mounting the existing
  `microservicekey-deed-poll-api` and `microservicekey-deed-poll-web` under `/mnt/secrets/s2s-aat/`.
  Aliases remain `microserviceKeys.deed_poll_api` and `microserviceKeys.deed_poll_web`.
- Retain the standard `/mnt/secrets/s2s/` configuration import and append the new directory through
  AAT's `SPRING_CONFIG_IMPORT`. The new import is required, so a missing mount fails startup visibly.
- The additional vault map entry preserves the chart's complete existing S2S secret list and
  SecretProviderClass. Both mounts use the same AAT vault and existing S2S identity access.

Validation: Helm lint passed; AAT Kustomize and Helm rendering verified the exact aliases, both mount paths
and both imports, and that the original SecretProviderClass is unchanged. RPE production, demo, ITHC,
perftest, sandbox and preview Kustomize output is byte-identical to the baseline. `git diff --check` passed.

A full S2S build could not run locally because its Java 25 toolchain is unavailable. A separate Java 21
harness used the deployed source `8ca9939`'s actual `AppProperties` class and exact Spring Boot 3.4.4 version;
both tests passed (zero failures/errors): old keys/JWT configuration survive while both new underscore-named
service keys bind; a missing required import fails startup. All keys were synthetic; no live credentials used.
This is focused binding evidence, not a full application build or deployed token-leasing proof.

All 18 Flux CI checks passed at `7fae4eb0faf`, including both AAT schemas and static tests.
Next: Flux #48113 review/merge, wait for AAT reconciliation and readiness, then lease/validate a token for
`deed_poll_api` using its existing AAT key without logging values/tokens. CCD/CDAM onboarding continues through
its own PRs. S2S #1140 remains draft for a later wider rollout and no longer gates this AAT-only route.
Remove the extra mount and import together when the shared chart eventually supplies the same registrations.

### 2026-10-08 AAT-only S2S merge authorized; awaiting repository review

Steve authorized proceeding with Flux #48113 merge, AAT reconciliation and token-leasing verification.
Rechecked head `7fae4eb0fafa697d15e7931b52fe20de892b07dc`: all 18 CI checks passed. A normal squash merge
was refused by the base-branch policy because review is required. Enabled squash auto-merge at
2026-10-08 14:43 UTC; GitHub reports OPEN / BLOCKED / REVIEW_REQUIRED with auto-merge enabled.
No administrator override was used. Existing review requests include Platform Operations; no messages or
new review requests were sent. No merge, rollout or token lease has occurred yet.

After review/merge, verify the AAT RPE HelmRelease and all S2S replicas are ready with the additional mount,
then POST `/lease` with the service name and freshly generated OTP, followed by GET `/details` with the
leased bearer token. Report only HTTP outcomes and returned service identity; never log secret values,
OTPs or tokens. Production remains deferred. This entry is the current resume point for AAT S2S.

### 2026-10-08 work advanced while reviews are pending: definition import readiness

Steve requested independent work while S2S #48113 awaits review. Rechecked IDAM #1016, CDAM #813 and
Flux #48052/#48113: all remain open awaiting review, so account authentication and deployed token verification
were not attempted against unprovisioned identities.

Found an additional prerequisite: AAT definition store's S2S allowlist contains `pcs_api` but not
`deed_poll_api`. This was confirmed from its live deployment, image `prod-906c27b-20261008103307`, and the
Flux AAT overlay. Its service-authentication filter applies before the user authorization checks; possessing
an importer account alone is insufficient. `/import` also requires the user's `ccd-import` authority.

Raised [Flux #48117](https://github.com/hmcts/cnp-flux-config/pull/48117), branch
`deed-poll-aat-definition-import`, commit `d36982c1a58`: append only `deed_poll_api` to
`apps/ccd/ccd-definition-store-api/aat.yaml`'s `DEFINITION_STORE_S2S_AUTHORISED_SERVICES`. Both AAT cluster
Kustomize renders preserve every existing caller and add the new identity once. Semantic YAML comparison
confirmed no other setting changes, and `git diff --check` passed. No production file changed and no merge
or live registration was performed. All 18 Flux CI checks passed at `d36982c1a58`.

Expanded `docs/CCD-SMOKE.md` with the exact pre-import request sequence: dedicated importer password grant
and userinfo role check, own-service token lease/validation, read-before-create PUBLIC role catalogue entries,
multipart import with a pre-recorded `X-Import-Job-Id`, then job/definition read-back. Existing classifications
are never overwritten implicitly. Unknown import outcomes require status inspection, not blind resubmission.
Confirmed the service filter, import authority, job-ID header and job endpoint against deployed source `906c27b`.

Artifact recheck: all 22 generated JSON files still match the pinned fixtures with only agreed identifier
substitutions. The existing spreadsheet remains at
`build/definitions/CCD_Definition_DEEDPOLL_aat.xlsx`, SHA-256
`0c9170e04fab0ef88906c08607b2a838257beca85c03b02a675345fcf83d956a`.
Compared generator baseline `b412e08` with deployed API `577e674`: no CCD definition classes, fixtures or SDK
dependency/generation configuration changed; build.gradle changes concern CI test gating only. This confirms
artifact readiness, not deployed import acceptance. It still grants only the system profile; staff access is
workstream 3 and remains a separate gate before ordinary document use.

Next: review/merge/deploy #48117 alongside the other prepared prerequisites, authenticate the provisioned
identities, then perform role registration/import. Linking, CDAM storage and staging remain disabled.

### 2026-10-08 staff document access implementation outline (decision pending)

Investigated the independent staff-access workstream while the deployment PRs await review. Current
`DeedPollRoleMapping.from` maps only the configured system role; `DeedPollAccessProfile` grants only
`DEED_POLL_SYSTEM`. `DocumentService.downloadDocument` checks document/case ownership and passes the caller's
authority to CDAM. It does not substitute system authority for a staff download. The judicial HTTP surface
currently exposes referral operations, not a judicial document-download route.

Asked Steve whether initial AAT READ should cover only existing `caseworker-deedpoll-admin` (recommended
initial scope), or also judicial users with referral-scoped visibility. No answer recorded yet and no
permissions changed. Do not grant judicial users blanket case-type READ as a substitute for referral checks.

If admin-only scope is accepted, the implementation should introduce a distinct internal READ profile,
map the existing admin IDAM role to it, and grant R on case type, Registered state and supportingDocuments
without granting either mutation event or C/U/D. Keep system grants unchanged. `DeedPollRoleMapping`,
`DeedPollAccessProfile`, `DeedPollCaseType`, `SupportingDocumentsAccess` and generated-definition fixtures
must move together. Register the chosen staff profile and actual admin IDAM role as PUBLIC in definition
store before importing the expanded definition; re-generate the workbook, since today's checked artifact
still has only system grants.

Verification must prove allowed staff byte download, unmapped-role denial, refusal of creation/update
under staff authority, unchanged system attachment/Notify retrieval and wrong-case refusal. Update
`DeedPollDefinitionTest`, role-mapping tests and persistence-security tests; use cftlib for the downstream
CDAM/CCD permission behavior before deployed AAT smoke. Existing-file migration and activation stay separate.

### 2026-10-08 — staff READ scope agreed

Steve confirmed all three user types may read documents. The implementation maps `caseworker-deedpoll`,
`caseworker-deedpoll-admin` and `caseworker-deedpoll-judicial` to `DEED_POLL_READER`, with R only on the
case type, Registered state and supportingDocuments. The system profile retains its existing grants.
No staff mutation event, history or identifier-field grant is added. This is case-type-wide CCD document
access; the application referral visibility rules and UI routes remain unchanged.

Ordinary caseworkers previously held no dedicated IDAM role. Prepare/register `caseworker-deedpoll`
for AAT with the same environment exclusions as the other staff roles, assign it to caseworker accounts,
and update the admin persona/local seeder. No role is automatically assigned to existing real accounts.
Register all six role/profile names listed in `docs/CCD-SMOKE.md` as PUBLIC in definition store. Regenerate
the workbook: the earlier system-only AAT workbook is superseded and must not be imported for staff reads.
Workstream 3 remains open until deployment and the three-persona AAT smoke pass. Keep CDAM, linking and
staging off until their separate activation prerequisites are met.

Validation for the staff-read implementation: 170 API authentication/permission tests and all 14 real local
CCD/CDAM journeys passed. The journeys cover all three staff readers, denied CCD creation/update, missing
READ, unmapped users, wrong-case refusal, system attachment/deletion and Notify retrieval across restart.
Checkstyle on all changed Java sources passed via its pinned CLI; Gradle's Checkstyle workers crash on this
ARM host, so the direct invocation supplied `-XX:UseSVE=0`. IDAM's 41 tests passed using a temporary local
Maven Central override (Artifactory DNS unavailable; no repository build changes). Admin's 84 persona/gate
and 18 claim-parser tests, build and lint passed. These are local proofs; the deployed AAT smoke is still due.

IDAM #1016 now includes the new role at `947b956`; admin persona PR is
https://github.com/hmcts/deed-poll-admin/pull/161. Wait for registration before deploying the admin persona,
then assign/refresh existing real caseworker identities. The runtime account remains system-only.

Regenerated AAT artifact: `build/definitions/CCD_Definition_DEEDPOLL_aat_staff_read.xlsx`, SHA-256
`2f0450bf49a58b8673573c086d60d62a0009158d57bb363b6e55e0b37e49ef79`.
All 23 generated JSON files match pinned fixtures after identifier substitution (role-map rows sort by the
substituted role name). XLSX conversion used definition-processor `7a30a861`; required identifiers and
reader mappings are present. This artifact supersedes the earlier system-only workbook; it is not imported.
Generate with `--rerun-tasks` when changing identifiers because Gradle does not track those environment inputs.
