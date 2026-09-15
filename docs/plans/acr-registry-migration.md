# Plan: Move off the retired `hmctspublic` container registry

## Overview

This repo's **first master build has never succeeded**, and this is the only thing stopping it. Build #3
(2026-09-14 20:57) failed in `sectionBuildAndTest` on the CNP pipeline's ACR deprecation nagger:

```
Found old ACR references in Dockerfile(s) above
  ❌ ./Dockerfile
  ❌ charts/deed-poll-api/Chart.yaml
  ❌ charts/deed-poll-api/values.yaml

Your code references the old Azure Container Registry domains
(hmctsprivate|hmctspublic|hmctssandbox|sdshmctspublic|sdshmctspublicsbox). Please update Dockerfiles, Helm
charts and templates to use the new registries: *hmctsprod,hmctssbox*
This change is enforced from 15/04/2026
```

**Three lines, one string each: `hmctspublic` → `hmctsprod`.** That is the whole plan. It is written down
rather than done ad hoc because it blocks a deployment and the verification matters more than the edit.

**Why this matters now.** `hmcts/deed-poll-infra` applied successfully for the first time on 2026-09-15, so
the `deed-poll-aat` Key Vault, Postgres and Redis now exist. The remaining gate on a first deployment is
that no component has ever published an image or a Helm chart — and a component's **first master build** is
what publishes them. This plan removes the reason this repo's build fails.

### Source material — read these, do not re-derive them

| What | Where |
|---|---|
| The failing check's own script | `warnAboutOldAcrReferences.groovy` and `check-old-acr-references.sh` in `hmcts/cnp-jenkins-library` |
| Which registries are current, and the deadline | `hmcts/cnp-deprecation-map`, `nagger-versions.yaml`, key `acr.old_registry_migration` |
| **A working Java service already on the new registry** | `../apps/pcs/pcs-api/Dockerfile` and `../apps/pcs/pcs-api/charts/*/Chart.yaml` |
| A sibling repo of ours that is already clean | `../deed-poll-admin/Dockerfile`, `../deed-poll-admin/charts/deed-poll-admin/` |

### Decisions already taken — implement these, do not revisit

1. **`hmctsprod`, not `hmctssbox`.** The nagger accepts either. `hmctsprod` is what every CFT service uses
   for its real images; `hmctssbox` is for sandbox work. `pcs-api` and `deed-poll-admin` both use
   `hmctsprod`.
2. **Registry only — do not change any version or tag.** Not the base image tag, not the chart version.
   The registries hold the same artefacts, so a version bump would turn a proven one-string swap into an
   untested upgrade and make a failure ambiguous.
3. **Do not restructure the chart or the Dockerfile.** Multi-stage layout, distroless base, the
   `keyVaults` block: all out of scope. If something looks wrong, note it and leave it.

### This has been verified against a working service, not assumed

Both targets are **byte-identical** to what `pcs-api` already runs in production on the new registry:

| Ours (`deed-poll-api`) | `pcs-api` (works) |
|---|---|
| `FROM hmctspublic.azurecr.io/base/java:21-distroless` | `FROM hmctsprod.azurecr.io/base/java:21-distroless` |
| `java` chart `5.3.0`, `oci://hmctspublic.azurecr.io/helm` | `java` chart **`5.3.0`**, `oci://hmctsprod.azurecr.io/helm` |

Same image tag, same chart version — so `hmctsprod` demonstrably hosts both. **This is the single most
useful fact in this plan: if a task fails, the cause is not "the artefact is missing from the new
registry".**

### Constraints

- **No application code, no domain logic, no test changes.** If `./gradlew build` behaviour changes, the
  task has done something beyond its scope.
- **`charts/deed-poll-api/values.yaml:3`'s image is `deed-poll/api:latest`** — a placeholder the pipeline
  overrides with `${IMAGE_NAME}` at deploy time. Change its registry; leave the rest of the string alone.
- The nagger scans `*.tpl.yaml`, Dockerfiles and Helm charts. This repo has no `.tpl.yaml`, so the three
  known files are the whole surface — but Task 3 re-checks rather than trusting that.

## Validation Commands

- `./gradlew build`

> Every task. Compilation, checkstyle, unit tests and the integration source set. **Nothing in this plan
> should change its result** — it does not read the Dockerfile or the chart. If it goes red, the task
> edited something it should not have. It was green before this plan started.

## Tasks

### Task 1: Point the Dockerfile's base image at `hmctsprod`

- [ ] In `Dockerfile` line 3, change `hmctspublic.azurecr.io/base/java:21-distroless` to
      `hmctsprod.azurecr.io/base/java:21-distroless`. **The tag `21-distroless` does not change.**
- [ ] Confirm no other line in `Dockerfile` names any retired registry:
      `grep -nE 'hmctsprivate|hmctspublic|hmctssandbox|sdshmctspublic|sdshmctspublicsbox' Dockerfile`
      must print nothing
- [ ] Run the validation command
- [ ] Mark completed

### Task 2: Point the chart's dependency and image at `hmctsprod`

- [ ] In `charts/deed-poll-api/Chart.yaml` line 14, change the `java` dependency's `repository` from
      `oci://hmctspublic.azurecr.io/helm` to `oci://hmctsprod.azurecr.io/helm`. **`version: 5.3.0` does not
      change** — `pcs-api` pins the same version on the new registry
- [ ] In `charts/deed-poll-api/values.yaml` line 3, change
      `hmctspublic.azurecr.io/deed-poll/api:latest` to `hmctsprod.azurecr.io/deed-poll/api:latest`.
      Keep the `deed-poll/api:latest` path and tag exactly as they are
- [ ] Leave the comment above the `annotations` block alone — it documents which vault the secrets come
      from and is still true
- [ ] Run the validation command
- [ ] Mark completed

### Task 3: Prove the whole repo is clean, the way the pipeline checks it

The nagger scans more than the three files that happened to fail. This task exists so the next master build
is not a guess.

- [ ] Run the same search the pipeline runs, across the whole repo:

      ```bash
      grep -rnE 'hmctsprivate|hmctspublic|hmctssandbox|sdshmctspublic|sdshmctspublicsbox' \
        --include='Dockerfile*' --include='*.yaml' --include='*.yml' --include='*.tpl.yaml' . \
        | grep -v '^./build/' | grep -v '^./.git/'
      ```

      It must print **nothing**. If it finds anything outside the three files above, fix it the same way —
      registry swap only — and record what and where in this plan
- [ ] Confirm the intended state is actually present, rather than only that the old string is absent:
      `grep -rn 'hmctsprod.azurecr.io' Dockerfile charts/` should show **three** matches — the base image,
      the chart repository and the values image
- [ ] Run `./gradlew build` once more from clean (`./gradlew clean build`) and record the result — task
      count and test count — so the next plan has a baseline
- [ ] **Do not attempt to verify the pipeline itself.** `build.hmcts.net` is behind Azure AD App Proxy and
      is unreachable from the devcontainer; the master build is Steve's to trigger and read. Note in this
      plan that the change is unverified against a real build, and that this is expected
- [ ] Mark completed

## Deliberately not in this plan

- **The renovate-config nagger.** It fails `deed-poll-web` and `deed-poll-admin`, not this repo — this
  repo's `renovate.json` already extends `local>hmcts/.github:renovate-config`. Each has its own plan.
- **Any chart or base-image version upgrade.** See decision 2.
- **The `keyVaults` block and secret names.** Fixed by `hmcts/deed-poll-infra`; renaming happens there or
  not at all.
- **Triggering or verifying the master build.** Not possible from here, and it is the point of the work
  rather than part of it.
