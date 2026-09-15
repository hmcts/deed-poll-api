# Plan: Let `check` pass on an agent with no Docker

## Overview

`acr-registry-migration` fixed the ACR nagger and the build got much further — through `az acr login`,
compilation, and all five checkstyle tasks. It then failed on something new, in `PR-15/1`
(2026-09-15 14:52):

```
> Task :integration
RefusedUploadSwallowTest > ... FAILED
    Caused by:
    java.lang.IllegalStateException: Could not find a valid Docker environment.
        at org.testcontainers.dockerclient.DockerClientProviderStrategy...
        at uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest.<clinit>(AbstractPostgresContainerTest.java:27)

2 tests completed, 2 failed
> Task :integration FAILED
BUILD FAILED in 1m 35s
```

**The CNP pipeline runs `./gradlew check`, and `build.gradle:113` wires `integration` into `check`.** Those
tests use Testcontainers to start `postgres:16-alpine`, because the application runs Flyway on start-up and
so needs a real database. **The Jenkins VM agent has no Docker daemon available to the Gradle process**, so
`POSTGRES.start()` throws inside a `static` initialiser and takes every test in the class hierarchy with it.

Two details in that log that are consequences, not separate faults:

- **Only 2 tests ran** because `integration` sets `failFast = true` — deliberate, and documented in
  `build.gradle` as the right choice for container-backed tests.
- **`No test report files were found`** is what happens when the JVM dies in `<clinit>` before any test
  writes a result. Not a reporting misconfiguration.

**Nothing is wrong with the tests.** They pass locally — `./gradlew clean build` was verified green today at
1,488 unit tests plus 6 integration, zero failures. The problem is purely *where* CI tries to run them.

### Source material — read these, do not re-derive them

| What | Where |
|---|---|
| **The precedent that solves this** | `../apps/pcs/pcs-api/build.gradle` around `integrationTestsEnabled`, and its `Jenkinsfile_CNP` `onPR()` block |
| The class that fails, and why it lives in `test` not `integrationTest` | `src/test/java/uk/gov/hmcts/reform/deedpoll/config/AbstractPostgresContainerTest.java` — read its javadoc |
| Why `failFast` is on `integration` and not `test` | the comment on the `integration` task in `build.gradle` |
| The local Docker workaround this repo already needs | `CLAUDE.md`, the `TESTCONTAINERS_RYUK_DISABLED` note |

### Decisions already taken — implement these, do not revisit

1. **Gate on an environment variable, defaulting to enabled.** Copy `pcs-api`'s shape exactly:
   `System.getenv('INTEGRATION_TESTS_ENABLED') != 'false'`. Unset means *enabled*, so a local
   `./gradlew check` still runs everything and a developer notices a broken integration test.
2. **Set it `false` in `Jenkinsfile_CNP` on BOTH the PR and master paths.** `pcs-api` sets it only inside
   `onPR()` because their master agent runs them — **ours does not**, and master's own build fails the same
   way. Setting it only for PRs would leave master red for exactly this reason.
3. **Do not delete, skip or `@Disabled` any test.** The tests are correct and pass locally. This changes
   *where* they run, not whether they exist.
4. **Do not reintroduce `.github/workflows/`.** `pcs-api` runs these in GitHub Actions, and it is tempting to
   copy that too. All four deed-poll repos deliberately deleted their workflows when moving to the Jenkins
   CNP pipeline; `../deed-poll-admin/.business_context/DEED_POLL_INFRA_PLAN.md` records five decisions and
   their evidence. **Reintroducing GHA is out of scope and contradicts a settled decision.**
5. **`jacocoTestReport` keeps `executionData(test, integration)` unchanged.** Jacoco tolerates a missing exec
   file, which `pcs-api` relies on and states in a comment. Do not make coverage conditional too.

### The consequence, stated plainly because it is a real cost

**With this change, the 6 integration tests run nowhere automatically.** They run locally and they ran green
today, but no CI job executes them until either a Docker-capable agent is available or another home is found.

That is accepted for now because:

- The build has blocked **everything** — no image, no chart, no deployment, and nine changes merged this week
  with no automated checks at all.
- The unit suite is unaffected: **1,488 tests still run in `check`** on every build. The 6 integration tests
  are the boot-the-context-and-hit-Postgres tests, not where most of the risk sits.

**It is not the right long-term answer.** Recorded as an open question in `cft-workspace/TRACKER.local.md`;
the proper fix is asking PlatOps whether a Docker-capable agent is available (`team-config.yml` has an
`agent:` key that `xui` and `civil` set), at which point this gate is deleted rather than refined.

### Constraints

- **No application code, no test changes.** Two files: `build.gradle` and `Jenkinsfile_CNP`.
- **`tasks.check.dependsOn(integration)` at `build.gradle:113` is the only line to wrap.** Leave
  `tasks.sonarqube.dependsOn(jacocoTestReport)` above it alone.
- **`Jenkinsfile_CNP` is currently `withPipeline(type, product, component) {}` — an empty closure.** This is
  the first thing to go inside it. Keep the pinned `@Library("Infrastructure@2.4.4")` and its comment: all
  four repos are pinned together on purpose.
- Groovy truthiness will bite: set the variable to the **string** `"false"`, not the boolean `false`, because
  Gradle compares `System.getenv(...) != 'false'` against a string.

## Validation Commands

- `./gradlew build`

> Every task. **This must stay green, and it proves decision 1 works**: `build` runs `check`, `check` still
> depends on `integration` when the variable is unset, and locally it is unset — so a green `build` after
> Task 1 means the gate defaults to *enabled* rather than silently disabling the tests everywhere.
>
> Baseline from `acr-registry-migration`, verified today: **BUILD SUCCESSFUL**, 25 actionable tasks,
> **1,488 unit tests** across 185 classes, **6 integration tests** across 3 classes, zero failures. Docker
> must be running locally, and this repo needs `TESTCONTAINERS_RYUK_DISABLED=true` — see `CLAUDE.md`.

## Tasks

### Task 1: Gate `check`'s dependency on `integration`

- [x] In `build.gradle`, immediately above `tasks.check.dependsOn(integration)` (currently line 113), add the
      flag and wrap the dependency, matching `pcs-api`:

      ```groovy
      // Testcontainers needs a Docker daemon, and the CNP Jenkins VM agent has none: `POSTGRES.start()`
      // throws in a static initialiser and every context-booting test fails with it (PR-15/1, 2026-09-15).
      // Unset means enabled, so a local `./gradlew check` still runs them and a developer sees a real
      // failure. `Jenkinsfile_CNP` sets it false. See docs/plans for the cost this accepts.
      def integrationTestsEnabled = System.getenv('INTEGRATION_TESTS_ENABLED') != 'false'

      if (integrationTestsEnabled) {
        tasks.check.dependsOn(integration)
      }
      ```
- [x] Leave `tasks.sonarqube.dependsOn(jacocoTestReport)` and the `jacocoTestReport` block untouched
- [x] Prove the default is *enabled* — this is the point of the task:
      `./gradlew check --dry-run | grep -c ':integration'` must be **non-zero** with the variable unset —
      returned **2** (`:integrationTestClasses`, `:integration`)
- [x] Prove the gate actually gates:
      `INTEGRATION_TESTS_ENABLED=false ./gradlew check --dry-run | grep -c ':integration'` must be **0**

      As written this returns **1**, not 0, because `grep ':integration'` is a substring match and
      `:integrationTestClasses` still appears. The `:integration` *task itself* is correctly gone; matched
      exactly with `grep -cE '^:integration '`, which returns **1** unset and **0** when `false`.

      What remains when gated off is `:integrationTestClasses` and `:checkstyleIntegrationTest` — the
      integration tests are still **compiled and checkstyled** in CI, just not executed. Neither needs
      Docker, and this is a desirable side effect: the source set cannot rot into non-compiling code
      unnoticed while it runs nowhere.
- [x] Run the validation command. It must stay green, including the 6 integration tests — `./gradlew clean
      build` **BUILD SUCCESSFUL** in 6m19s, 25 actionable tasks, all executed. Matches the baseline exactly:
      **1,488 unit tests** across 185 classes, **6 integration tests** across 3 classes, 0 failures, 0
      errors. (A non-clean `./gradlew build` reports everything `UP-TO-DATE` and proves nothing, so the
      count above comes from a clean run that genuinely re-executed both source sets.)
- [x] Mark completed

### Task 2: Set the variable in the pipeline, on both paths

- [ ] In `Jenkinsfile_CNP`, replace the empty closure body so the variable is set for **both** PR and master
      builds. Note `pcs-api` sets it inside `onPR()` only; ours must cover master too — decision 2:

      ```groovy
      withPipeline(type, product, component) {
        // The CNP VM agent has no Docker daemon, so the Testcontainers-backed `integration` source set
        // cannot run here. `build.gradle` leaves it wired into `check` when this is unset, which is what a
        // local run wants. Set on BOTH paths, unlike pcs-api's onPR()-only: this repo's master agent has no
        // Docker either, and master build #3 failed for the same reason.
        env.INTEGRATION_TESTS_ENABLED = "false"
      }
      ```
- [ ] The value is the **string** `"false"` — see the constraint on Groovy truthiness
- [ ] Keep `@Library("Infrastructure@2.4.4")` and its comment exactly as they are
- [ ] Confirm the file is still valid Groovy as far as can be checked here — it cannot be executed without
      Jenkins, so read it against `../apps/pcs/pcs-api/Jenkinsfile_CNP` for shape rather than running it
- [ ] Run the validation command
- [ ] Mark completed

### Task 3: Record what this accepts, and check nothing else needs the same treatment

- [ ] Confirm no *other* Gradle task wired into `check` needs Docker. Check `functional` and `smoke`: both are
      registered but neither is a `check` dependency, so neither should be affected. State what you found
- [ ] Confirm the unit suite genuinely does not need Docker, so the claim "1,488 tests still run" is true and
      not just assumed: `./gradlew test` alone, and record the count
- [ ] Note in this plan, explicitly, that **the 6 integration tests now run in no CI job**, that they passed
      locally at the time of writing, and that the proper fix is a Docker-capable agent from PlatOps rather
      than this gate
- [ ] **Do not attempt to verify the pipeline.** `build.hmcts.net` is behind Azure AD App Proxy and
      unreachable from the devcontainer. Triggering and reading the build is Steve's. Note that the change is
      unverified against a real build and that this is expected
- [ ] Mark completed

## Deliberately not in this plan

- **Reintroducing `.github/workflows/`** to run integration tests, as `pcs-api` does. Decision 4 — it
  contradicts a settled architecture decision recorded with its evidence.
- **Asking PlatOps for a Docker-capable agent.** The right long-term fix, and an external dependency; tracked
  rather than done here.
- **Deleting, disabling or rewriting any test.** Decision 3.
- **Making `jacocoTestReport` conditional.** Decision 5.
- **The `functional` and `smoke` source sets.** Neither is wired into `check`; Task 3 confirms rather than
  changes that.
- **Triggering or verifying the master build.** Not possible from here.
