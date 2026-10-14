# Plan: Gazette external release review

## Overview

Task 5 of [the Gazette plan](gazette-1-ready-list-and-batch-send.md) needs a separate external
release review of the merged api Gazette work. Every Gazette PR is already on `master`, so this
review diffs against `d5a0523`, the commit before PR #80, not against `master`.

## Scope

`git diff d5a0523...HEAD` also carries unrelated work merged in the same window: AAT
configuration fixes (#81–#86), OWASP suppressions (#96), the staff gate report mode (#98) and
telemetry masking (#99). Review those only where they touch Gazette.

In scope:

- `src/main/java/**/domain/gazette/**`, `src/main/java/**/*Gazette*`, and their tests.
- `src/main/resources/db/migration/V31`–`V35`, `src/main/resources/gazette-templates/`.
- The Gazette OpenAPI bodies and parameters in `config/OpenAPIWriteBodies.java` and
  `config/OpenAPIQueryParameters.java`.
- PR #100's Gazette gating: `Capability.ACCESS_GAZETTE`, `RoleCapabilities`, and the
  `readyForPublication` filter's gate.
- `docs/GAZETTE-RECOVERY.md`, checked against the code it describes.

## Intentional design (do not "fix")

These are recorded decisions. Report a finding against one only if the code fails to do what
the decision says.

- One delivery attempt per request. An uncertain outcome stops further sends for those cases
  pending investigation and is never resent automatically
  ([contract](gazette-1b-send-and-recovery-contract.md)).
- The local transaction does not cover Notify's acceptance; a durable send-start marker
  prevents recovery from sending again.
- Sending is disabled by default (`GAZETTE_ENABLED=false`).
- Only Delivery Manager holds `ACCESS_GAZETTE`. Team leader and legacy admin do not.
- Relinquishment and Unknown/missing classification refuse the whole spreadsheet selection.
- Recipient settings writes send no email; confirmation requires `recipientRevision`.

## Constraints

- Never edit an applied migration (V1–V35). A schema fix takes the next unused number.
- Run every Gradle command in the foreground. Never background a build: a session that ends
  while a build runs is restarted, and the restart builds over it and wipes its results.
- Do not regenerate the published OpenAPI spec unless a fix changes the HTTP contract, and say
  so in the commit if it does.
- Do not push, and do not change any environment's configuration.

## Validation Commands

**Everything here must be green after every fix.** Both were green on `master` (`be3291a`)
before the review started; see Outcome for the baseline counts.

```bash
export JAVA_TOOL_OPTIONS=-XX:UseSVE=0 TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build -I /tmp/gazette-review/no-checkstyle.gradle
/tmp/gazette-review/checkstyle.sh .
```

This devcontainer's JVM needs `-XX:UseSVE=0`, and Gradle's Checkstyle worker daemon starts without
it and dies with exit 134. The init script disables only the Checkstyle tasks; `checkstyle.sh` runs
the same jar and `build/config/checkstyle/checkstyle.xml` over the same source sets, and fails on
any warning, as the Gradle task does. Neither file lives in this repo. Do not change `build.gradle`
or the Checkstyle configuration to work around this.

## Outcome

Baseline, 14 October 2026, on `be3291a` with the commands above: 4,086 test / 19 integration,
no failures, errors or skips, and Checkstyle clean. Both test tasks executed rather than reporting
UP-TO-DATE.

To be recorded after the review: findings, what was fixed, what was dismissed and why, and the
final test counts.
