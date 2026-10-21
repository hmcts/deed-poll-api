# CCD definition import through Jenkins "High Level Data Setup"

Status: implemented on `feat/ccd-high-level-data-setup`, 2026-10-21. Enabled for the `ithc` branch only.

## Why

The DEEDPOLL definition was imported into AAT by hand on 2026-10-16 (`docs/CCD-SMOKE.md` step 1). That route doesn't
carry over to ITHC or prod. Definition store is `*.service.core-compute-<env>.internal`, which isn't reachable from a
developer machine. The team's non-prod AKS RBAC also refuses creating pods, `exec`, `port-forward` and
`services/proxy` in the `deed-poll` namespace, so an in-cluster run isn't possible either. Nobody recorded how the
AAT import reached `.internal`.

Service teams import through cnp-jenkins-library's **High Level Data Setup** stage. They include pcs-api (the
closest exemplar), nfdiv, sptribs, adoption, civil, fpl, prl and probate. It runs on the CNP Jenkins agent, which
can reach `.internal`.

## How it works

- `enableHighLevelDataSetup()` adds the stage `High Level Data Setup - <env>`. It runs `gradle highLevelDataSetup
  --args=<env>` (library 2.12.2, `vars/highLevelDataSetup.groovy`).
- **Which builds run it:** builds of the `ithc`, `demo` and `perftest` branches run it for that environment, before
  their (for us empty) deploy section. Master builds would run it for AAT and then prod.
- The stage reads from three vaults (`withDefinitionImportSecretsAndEnvVars`):
  - `ccd-api-gateway-oauth2-client-secret` from `ccd-<env>`;
  - `microservicekey-ccd-gw` from `s2s-<env>`;
  - `definition-importer-username` and `-password` from **`deed-poll-<env>`**.

  So the import authenticates as CCD's `ccd_gw` service with the `ccd_gateway` client, plus our own importer user. The
  platform supplies that service identity, and we don't hold its key ourselves. CCD-SMOKE's "never borrow
  `ccd_admin`'s key" is about handling another service's key by hand, which this doesn't do. It also means
  definition store's allowlist entry for `deed_poll_api` isn't needed for this route.
- `before('highleveldatasetup')` in `Jenkinsfile_CNP`:
  - generates the definition with the three confirmed identifiers (`DEEDPOLL`, `DEEDPOLL`,
    `deed-poll-system-update`), through the library's `GradleBuilder`, as pcs does;
  - converts it with `bin/create-xlsx.sh <env>` (CCD's `definition-processor` image; the agent has Docker);
  - archives the spreadsheet.
- `HighLevelDataSetupApp` (`src/ccdImport/java`) extends BEFTA's `DataLoaderToDefinitionStore`:
  - it registers CCD-SMOKE row 4's eight roles at PUBLIC;
  - it imports `build/definitions/CCD_Definition_DEEDPOLL_<env>.xlsx`;
  - it creates no role assignments.

  BEFTA 9.2.11 submits with its own import job id and polls the job to COMPLETED, FAILED or EXPIRED. It doesn't
  tolerate failures by default, so a failed import fails the build.

## Decisions

1. **ITHC only, at first.** Enabling it on `master` would re-import into AAT on every merge (AAT's sweep is live) and then
   into prod. AAT keeps its hand-run definition until the ITHC run has proved the stage. Prod comes at go-live
   (2026-11-02). Prod's IDAM entries are still excluded, so the prod importer couldn't authenticate before then
   anyway. Later steps: `onMaster { enableHighLevelDataSetup("", true) }` for AAT without prod, then drop the `true`.
2. **BEFTA is isolated.** pcs puts `befta-fw` in `implementation`. Here it has its own `ccdImport` source set and
   configurations, and shares nothing with `main` or `test`.
   - `bootJar` contains no BEFTA or importer classes (checked).
   - `verifyNoJackson3` covers the new configurations and passes.
3. **POI matches the application.** befta-fw brings POI 4.1.2, whose xmlbeans 3.1.0 has seven CVEs, the worst at CVSS
   7.5. The importer excludes it and takes POI 5.5.1 / xmlbeans 5.3.0, the versions the application already ships.
   - An OWASP scan of `ccdImportRuntimeClasspath` against the local database (dated 2026-09-16) found 0 CVEs.
   - BEFTA uses POI only in its JSON/Excel converter, which isn't on the xlsx import path.
4. **Roles use PUT.** BEFTA registers roles with `PUT /api/user-role`, where CCD-SMOKE uses POST so that a differing
   classification is never overwritten. All eight are deed-poll's own and PUBLIC, so a PUT only restates them.

## Checked locally (2026-10-21)

- `generateCCDConfig` with the confirmed identifiers equals the pinned `src/test/resources/ccd-definition`, once the
  placeholders are substituted. The only difference is ordering in `RoleToAccessProfiles.json`; it has the same six
  entries.
- `bin/create-xlsx.sh ithc` produced a spreadsheet **byte-identical** to the one imported into AAT on 2026-10-16 (sha256
  `100c9024…`).
- `highLevelDataSetup --args=<invalid>` loads and is refused by BEFTA's argument check, so the classpath is complete.
- `./gradlew build`: 4,553 unit and 23 integration tests pass. Checkstyle is clean on `src/ccdImport`.

Not checkable locally: the stage itself. The first `ithc` branch build is the proof. It needs IDAM
(idam-access-config#1046) for the ITHC importer account, and the environment agent's identity must be able to read the
three vaults.

## Rollout

1. Merge this PR.
2. Create the `ithc` branch from master. Its build runs the stage. Read the log: eight roles, then the job COMPLETED.
3. Confirm through definition store, or a CCD read, that DEEDPOLL exists in ITHC. Then CCD-SMOKE steps 2 and 3 for
   ITHC, with Steve's go-ahead before the sweep is switched on.
4. Later: AAT through master (`skipProd`), then prod at go-live.
