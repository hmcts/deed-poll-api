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

### Review runs, 14 October 2026

1. **ralphex `--mode external --base-ref d5a0523`, first attempt: crashed.** Copilot's Bearer
   redaction left an unescaped quote in a `tool.shell_output` event (it was reading `GazetteNotify`),
   and the launcher's adapter refused the line. Fixed in the workspace adapter; nothing in this repo.
2. **ralphex, second attempt: "NO ISSUES FOUND" in 2m58s over 179 files (+11,592/-727).** The
   reviewer ran under a throwaway sandbox HOME, so no session record survives to show what it read.
   That is too short to be a review of that diff, and it is **not counted** as the release review.
3. **Direct Copilot CLI run: the review of record.** `gpt-6-astra` (confirmed from the event
   stream, not by asking it), read-only (`--deny-tool write`, `shell(git push)` denied), given the 64
   main-code files in scope (3,879 lines), their tests, and this plan's intentional-design list.
   It ran 17:51:51 to 17:56:31 UTC with 98 tool calls. Two findings.

### Findings

**F1, valid, not fixed here: attribution can be an IDAM subject.**
`GazetteRequestLedger` (admission) stores `caller.name()` as `submittedBy`, and it is published, as are
`sentBy`, the case notes and the settings' `updatedBy`. When the token carries no `name`,
`given_name` or `family_name`, `IdamClaims.displayNameFrom` falls back to `sub`. That fallback is
service-wide and deliberate (9cec400: traceable rather than misattributed to the service account),
so every audit column in the service shares it. But it contradicts this contract's "IDAM subjects
… are never published" in exactly that edge case, and no Gazette test covers a caller without a
name. Fixing it is a choice between service-wide and Gazette-only, so it waits on a decision.

**F2, by design, temporary: the staff gate's report mode logs the subject.**
`StaffGate` logs `subject=` for a caller outside the staff roles, before the Gazette capability check
refuses them with 403. This is OWASP plan 01 Task 4's deliberate one-release report mode
(`STAFF_GATE_MODE`, default `report`). Task 5 switches to `enforce` and Task 12 removes it. It is
not Gazette-specific, but **Gazette must not be enabled anywhere the gate is still in report mode.**

No code changed on this branch, so the baseline above stands as the final count.
