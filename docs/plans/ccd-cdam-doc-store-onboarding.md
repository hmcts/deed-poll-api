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
Steve explicitly requested AAT registration on 2026-10-06. Four linked draft PRs now contain the
registration configuration; see the AAT execution log below. Next review/apply infrastructure PR #25,
then complete IDAM/S2S deployment and the CCD role/definition import. No live registration is claimed.

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

- Agree which real staff roles may read documents, including any case-visibility/privacy restrictions.
- Implement the role-to-access-profile mapping and READ grants on the case type, state and
  `supportingDocuments` field. The current definition grants only the system profile.
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
  final check. Passing local validation is not a passing platform pipeline or approval to deploy.
- `CCD-SMOKE.md` now includes the supporting-document event/field, internal profile registration and
  API-client audience. No deployed smoke was run.

Remaining execution gates, in order:

1. Review/apply infra #25 and verify all four enabled destination secrets without exposing their values.
2. Complete IDAM #1016 synchronization and S2S #1140 deployment; verify password-grant userinfo role and
   own-service token leasing. Do not substitute the short-lived test-user helper or another product's identity.
3. Deploy CDAM #813 and Flux #48052 with registration flags still off; confirm effective runtime settings.
4. Arrange an authorized deed-poll CCD importer. The user was asked for its vault/secret names; no account
   was supplied in this session. Register `deed-poll-system-update` and `DEED_POLL_SYSTEM` as `PUBLIC`, then
   import the generated definition. Do not give definition-import privilege to the runtime account.
5. Run the controlled CCD/CDAM smoke after staff access and rollout prerequisites are settled. There is no
   separate direct API-to-doc-store registration in this design; CDAM already appears in AAT's store allowlist.

## Related procedures and implementation plans

- [Session handover](../RESUME.md)
- [CCD smoke procedure](../CCD-SMOKE.md)
- [CDAM smoke procedure](../CDAM-SMOKE.md)
- [Recovery and rollout boundaries](../CDAM-RECOVERY.md)
- [CDAM protocol and permissions](../CDAM-SEQUENCE.md)
- [CDAM implementation and migration scope](deed-poll-api-03-cdam-documents.md)
- [03b callers and staging](deed-poll-api-03b-cdam-callers-and-staging.md)
