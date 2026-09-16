# Plan: Keep 1,166 unit tests running in CI without Docker

## Overview

`gate-integration-tests-in-ci.md` gated `:integration` out of `check`, and its own final task then found that
**this is not enough** — a finding that contradicted the plan's stated premise and was right to:

- `AbstractPostgresContainerTest` lives in **`src/test`**, not `src/integrationTest`
- **21 `src/test` classes extend it**, and `SchemaConformanceTest` is separately `@Testcontainers` with its
  own container in `@BeforeAll` — **22 container-dependent classes**
- **322 of the 1,488 "unit" tests need Docker.** The other 1,166 do not

The CI log proves it rather than inferring it: it names `AbstractPostgresContainerTest.<clinit>:27` throwing
*"Could not find a valid Docker environment"*, and that is a **`src/test`** class. Gradle simply ordered
`:integration` before `:test`, the build stopped, and `:test` never ran. With `:integration` gated out, `:test`
reaches the same static initialiser — and `failFast` is deliberately **off** for `test`, so all 322 report.

**This plan tags the container-backed tests and excludes them in CI, so 1,166 keep running.**

> **Corrected by measurement (Task 3).** The 322/1,166 split above is the *estimate* this plan was written
> from, and it is wrong. Measured: **609** tests need Docker and **879** do not. The 22 classes are exactly the
> intended ones — they simply hold more tests than was thought, because `@Nested` inner classes are reported
> as separate result files and were counted as separate classes. The title's "1,166" should read **879**. The
> rest of the premise stands, and the fix is unchanged. Full figures in Task 3.

### Why this option, and what was rejected

| Option | Why not |
|---|---|
| Gate `:test` behind the same env var | One line, but disables **all 1,488** in CI. A green build that runs no tests is worse than a red one that tells the truth. |
| Docker-capable agent from PlatOps | **The real fix**, and it would delete both gates. An external wait, and the build has already blocked everything for days. Still worth asking — see the tracker. |
| Reintroduce `.github/workflows/` | Contradicts a settled decision recorded with evidence in `../deed-poll-admin/.business_context/DEED_POLL_INFRA_PLAN.md`. |

This is explicitly a **stopgap**. When a Docker-capable agent exists, the right change is to delete the
exclusion and the gate, not to refine either.

### Source material — read these, do not re-derive them

| What | Where |
|---|---|
| **The estate's precedent for excluding a tag** | `../apps/pcs/pcs-api/build.gradle` — its `test { useJUnitPlatform { excludeTags '…' } }` block |
| The base class every one of the 21 extends | `src/test/java/uk/gov/hmcts/reform/deedpoll/config/AbstractPostgresContainerTest.java` |
| The 22nd, which does not extend it | `src/test/java/uk/gov/hmcts/reform/deedpoll/db/SchemaConformanceTest.java` |
| What the previous plan changed, and its own correction | `docs/plans/completed/gate-integration-tests-in-ci.md`, Task 3 |
| Why `failFast` differs between `test` and `integration` | the task definitions in `build.gradle` |

### Decisions already taken — implement these, do not revisit

1. **Tag the base class, not the 21 subclasses.** JUnit 5 `@Tag` is **inherited**, so one annotation on
   `AbstractPostgresContainerTest` covers all 21. Only `SchemaConformanceTest` needs its own. **That is 2 files
   edited, not 22** — the count in the tracker was written before this was checked.
2. **Tag name: `requires-docker`.** It says why the test is excluded, not where it runs. Do not call it
   `integration` (it is not the integration source set) or `ci-exclude` (it describes a mechanism, not a fact).
3. **Exclude by tag only in CI, never locally.** Reuse the existing `INTEGRATION_TESTS_ENABLED` variable that
   `Jenkinsfile_CNP` already sets — do **not** introduce a second flag for the same condition. Unset means run
   everything, so `./gradlew build` locally is unchanged.
4. **Do not delete, skip or `@Disabled` anything, and do not move tests between source sets.** The tests are
   correct. This changes where they run.
5. **`SchemaConformanceTest` keeps its own `@Testcontainers` container.** It is the largest class in the suite
   (139 tests) and reads the vendored specification. Tag it; do not restructure it.

### Constraints

- **Never edit `src/test/resources/reference/`.** It is a verbatim vendored copy of the specification and the
  conformance tests read it as ground truth (`CLAUDE.md`).
- **Three files at most:** `build.gradle`, `AbstractPostgresContainerTest.java`, `SchemaConformanceTest.java`.
- **`import org.junit.jupiter.api.Tag`** — the JUnit 5 annotation. Not TestNG, not JUnit 4's `@Category`.
- `-Werror` is on and checkstyle runs in `check`, so an unused import fails the build.
- This repo needs `TESTCONTAINERS_RYUK_DISABLED=true` locally (`CLAUDE.md`).

## Validation Commands

- `./gradlew build`

> Every task, and it must stay green — **which is also the proof of decision 3**: with the variable unset,
> nothing is excluded, so the full suite still runs locally. A `build` that goes green while reporting fewer
> tests means the exclusion has leaked into the default path.
>
> Baseline, verified today: **BUILD SUCCESSFUL**, **1,488 unit tests** across 185 classes, **6 integration**
> across 3, zero failures. Docker must be running.

## Tasks

### Task 1: Tag the container-backed tests

- [x] Add `@Tag("requires-docker")` to the class declaration of
      `src/test/java/uk/gov/hmcts/reform/deedpoll/config/AbstractPostgresContainerTest.java`, with
      `import org.junit.jupiter.api.Tag;`
- [x] Extend that class's javadoc by one sentence: the tag exists so CI can exclude these where no Docker
      daemon is available, and **it is inherited by all 21 subclasses** — so a new subclass needs no annotation
- [x] Add the same `@Tag("requires-docker")` to
      `src/test/java/uk/gov/hmcts/reform/deedpoll/db/SchemaConformanceTest.java`, which does **not** extend the
      base class and starts its own container in `@BeforeAll`. Note in a short comment why it carries the tag
      directly
- [x] Confirm the tag reaches every intended class and no others:

      ```bash
      grep -rl "extends AbstractPostgresContainerTest" src/test/ | wc -l   # expect 21
      grep -rl "@Testcontainers" src/test/                                  # expect only SchemaConformanceTest
      ```

      Both held: **21** subclasses, and `SchemaConformanceTest` is the only separately-`@Testcontainers` class.
- [x] Run the validation command. **All 1,488 must still run** — the tag alone excludes nothing

      `./gradlew build` → **BUILD SUCCESSFUL**. Measured from `build/test-results/`: **1,488 tests across 185
      classes** in `test`, **6 across 3** in `integration`, zero failures, zero errors, zero skipped —
      identical to the baseline, so the tag is inert until Task 2 acts on it.
- [x] Mark completed

### Task 2: Exclude the tag in CI only

- [x] In `build.gradle`, inside the existing `tasks.withType(Test).configureEach { useJUnitPlatform() … }`
      block — or a dedicated `test { }` block if that is cleaner, following `pcs-api`'s shape — exclude the tag
      when `integrationTestsEnabled` is false. Reuse the flag the previous plan added; **do not add a second
      variable**:

      ```groovy
      useJUnitPlatform {
        // The CNP VM agent has no Docker daemon. 22 src/test classes need Testcontainers -- 21 inherit the
        // tag from AbstractPostgresContainerTest, SchemaConformanceTest carries it directly -- and they
        // account for 322 of the 1,488 tests. Excluding them keeps the other 1,166 running in CI instead of
        // losing the whole suite to a static initialiser. Unset means exclude nothing, so a local run is
        // unchanged. STOPGAP: delete this when a Docker-capable agent exists.
        if (!integrationTestsEnabled) {
          excludeTags 'requires-docker'
        }
      }
      ```

      Done as a dedicated `test { useJUnitPlatform { … } }` block, following `pcs-api`'s shape, so the
      exclusion applies to the unit task only and not to `integration`, `functional` or `smoke`. The comment
      carries the **measured** counts (below), not the plan's estimate.
- [x] **Check the declaration order.** `integrationTestsEnabled` is currently defined near the bottom of
      `build.gradle`, next to `tasks.check.dependsOn(integration)`, but Groovy evaluates top to bottom — a
      `Test` task configured *above* the definition will not see it. Move the `def` above the first use and
      leave a comment saying why, rather than duplicating it

      Moved above the `Test`-task configuration, with the reason stated in the comment; the original site now
      carries a one-line pointer instead of a second `def`.
- [x] Prove the default path is unchanged: `./gradlew test --dry-run` and the full validation command both
      still run the whole suite with the variable unset

      `./gradlew test --dry-run` → **BUILD SUCCESSFUL**. `./gradlew build` → **BUILD SUCCESSFUL**, and
      `build/test-results/test/` reports **1,488 tests across 185 result files**, zero failures, zero errors,
      zero skipped — identical to the baseline, so the exclusion has not leaked into the default path.
- [x] Prove the exclusion works, which is the point of the task:

      ```bash
      INTEGRATION_TESTS_ENABLED=false ./gradlew test --rerun-tasks
      ```

      It must pass **and report ~1,166 tests rather than 1,488**. Record the exact number. If it still reports
      1,488, the tag is not being excluded; if it fails, something outside the 22 classes needs Docker too —
      report that rather than widening the exclusion to make it pass

      **BUILD SUCCESSFUL**, and **879 tests across 125 result files**, zero failures, zero errors, zero
      skipped. So **609 tests are excluded, not the 322 the plan predicted** — the estimate carried over from
      the previous plan's analysis is wrong, and Task 3 should record 609. It is not over-exclusion: diffing
      the result files between the two runs gives 60 files, which reduce to **exactly 22 top-level classes**,
      and that set is byte-identical to the 21 direct subclasses of `AbstractPostgresContainerTest` plus
      `SchemaConformanceTest`. The extra 38 files are `@Nested` inner classes, which Gradle reports
      separately — the 185-vs-22 accounting confusion is what produced the 322 estimate. The tag reaches
      exactly the intended classes; those classes simply hold more tests than was thought.
- [x] Mark completed

### Task 3: Confirm the numbers and record the residue

- [x] State the measured counts in this plan: total with the variable unset, total with it `false`, and the
      difference. **The 322 figure came from the previous run's analysis — verify it rather than repeat it**

      Re-measured independently of Task 2, both runs on this machine with Docker up, counted by summing
      `tests="…"` across `build/test-results/*/TEST-*.xml`:

      | Run | Unit tests | Result files | Failures / errors / skipped |
      |---|---|---|---|
      | `./gradlew build` (variable unset) | **1,488** | 185 | 0 / 0 / 0 |
      | `INTEGRATION_TESTS_ENABLED=false ./gradlew test --rerun-tasks` | **879** | 125 | 0 / 0 / 0 |
      | **Difference — excluded by the tag** | **609** | 60 | — |

      **The 322 figure is wrong; the real number is 609.** It was not verified before being carried into this
      plan, and the discrepancy is an accounting error, not over-exclusion: the 60 additional result files
      normalise to **exactly 22 top-level classes**, and that set `diff`s clean against the 21 direct
      subclasses of `AbstractPostgresContainerTest` plus `SchemaConformanceTest`. The 38 extra files are
      `@Nested` inner classes, which Gradle reports separately; the raw filename diff appears to show 34
      classes only because Gradle mangles long result filenames with an inserted hash
      (`uk.-2VO146322BMH6.hmcts.…`), splitting one class across several apparent names. So the tag reaches
      precisely the intended classes — they just hold 609 tests, not 322. `build.gradle`'s comment carries the
      measured figures.
- [x] Confirm what now runs nowhere in CI: the **6** `integrationTest` tests plus the **322** tagged ones.
      Say so plainly, with the class count, so the cost is legible without reading two plans

      Stated plainly, with the corrected figure: **615 tests across 25 classes now run nowhere in CI.** That is
      the **6** `integrationTest` tests across **3** classes, gated out of `check` by the previous plan, plus
      the **609** `src/test` tests across **22** classes excluded by `requires-docker` here. **879 of the 1,488
      unit tests still run**, which is the point of the change — before it, the static initialiser in
      `AbstractPostgresContainerTest` took all 1,488 down with it. All 1,494 still run locally on any machine
      with a Docker daemon; nothing is deleted, skipped or `@Disabled`.
- [x] Confirm `:integration` is still gated and still runs locally — this plan must not have quietly undone
      the previous one

      Intact. `build.gradle:139-141` still reads `if (integrationTestsEnabled) { tasks.check.dependsOn(integration) }`,
      unchanged in substance — this plan only moved the `def` above its first use and left a pointer at the old
      site. It still runs locally: the validation `./gradlew build` produced 3 `integration` result files with
      **6** tests, zero failures. Scoping the exclusion to a `test { }` block rather than
      `tasks.withType(Test)` is what keeps the two mechanisms independent.
- [x] Note that `functional` and `smoke` remain unwired from `check`, so neither is affected

      Both are still bare `tasks.register(…, Test)` declarations (`build.gradle:99` and `:120`) with no
      `dependsOn` from `check` and no reference in `Jenkinsfile_CNP`. Neither carries the tag exclusion either,
      since that is on `test` alone. Unaffected in both directions.
- [x] **Do not attempt to verify the pipeline.** `build.hmcts.net` is behind Azure AD App Proxy and
      unreachable from the devcontainer. Record that both this and the previous plan are unverified against a
      real build, and that triggering it is Steve's

      Not attempted. **Both this plan and `gate-integration-tests-in-ci.md` are unverified against a real CNP
      build.** What is proven is local and by test count: the flag changes which tests run, in both directions.
      What is *not* proven is that the CNP agent then goes green — that depends on nothing outside the 22
      classes needing Docker, which only a real build can settle. Triggering it is Steve's.
- [x] If the local Docker-less simulation cannot be staged — `~/.testcontainers.properties` caches
      `docker.client.strategy=UnixSocketClientProviderStrategy` and reaches the socket regardless of
      `DOCKER_HOST` or the override variables, as the previous run found — **say so** rather than implying the
      exclusion was proven against a Docker-less environment. Excluding by tag is verifiable from the test
      count alone, which is why Task 2 asks for the number

      **Saying so: it could not be staged, and the exclusion was never run against a Docker-less environment.**
      `~/.testcontainers.properties` still pins
      `docker.client.strategy=org.testcontainers.dockerclient.UnixSocketClientProviderStrategy`, which finds
      the socket regardless of `DOCKER_HOST`, so a Docker-less CNP agent cannot be faithfully imitated here.
      The claim this plan actually supports is narrower and sufficient: **the tag excludes 609 tests from
      `:test` when `INTEGRATION_TESTS_ENABLED=false` and excludes nothing when it is unset** — verifiable from
      the counts above without any Docker-less environment. Whether the surviving 879 pass without a daemon is
      the untested step, and it is what the first real build will answer.
- [x] Mark completed

## Deliberately not in this plan

- **Asking PlatOps for a Docker-capable agent.** The real fix, an external dependency, tracked in
  `cft-workspace/TRACKER.local.md` §4.
- **Gating `:test` wholesale.** Rejected above — it disables all 1,488.
- **Reintroducing `.github/workflows/`.** Contradicts a settled decision.
- **Moving the 22 classes into `src/integrationTest`.** Arguably where they belong, since they boot a context
  and hit Postgres — but that is a much larger change to 22 files plus source-set wiring, and the base class's
  javadoc explains why it sits in `test` today. Worth its own plan if anyone wants it.
- **Triggering or verifying the master build.** Not possible from here.
