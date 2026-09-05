# Resume state — where Ralphex picks up

Written 2026-09-05 at the end of the first Ralphex run. **Read this before restarting the loop.**

## One command

```
cd <this repo>
ralphex --serve --port 8081 docs/plans/deed-poll-api-01-foundation.md
```

Ralphex resumes by finding **the first task with unchecked boxes**, which is **Task 12**. It needs no
`--resume` flag and no argument beyond the plan.

`--port 8081` because a second Ralphex session for `deed-poll-web` uses 8080. Drop the flag if that run
is finished. **Neither dashboard is reachable from the host browser** — this devcontainer sets
`"forwardPorts": []` and `onAutoForward: "ignore"`, so forward the port manually in VS Code's Ports panel,
or read `.ralphex/progress/progress-deed-poll-api-01-foundation.txt` instead.

## Where the work is

**Branch `deed-poll-api-01-foundation`, pushed, tip `9db5fe3`.** `main` is only at `5ee506c` (this plan)
— all the real work is on the branch. It was unpushed for the whole first run; it is pushed now, and
**pushing after each session is worth doing deliberately**, because six hours of work existed on one
machine.

| | |
|---|---|
| Tasks complete and committed | **0–11** — one commit each, 12 commits |
| Tasks remaining | **12–16** |
| Java source files | 112 |
| Test files / `@Test` methods | 44 / 511 |
| Flyway migrations | 3 — enums, `DeedPoll`, children |

Committed: the scaffold, the rename off `uk.gov.hmcts.reform.demo`, the vendored specification under
`src/test/resources/reference/`, the full Flyway baseline, JPA entities for 6 models and 11 enums,
reference-number allocation with a locked per-year counter, and the whole domain port — core, notes,
witnesses, documents and the conformance harness.

## Task 12 is half-finished, and its work is uncommitted

The first run was **interrupted**, not failed: `interrupting… (force exit in 5s)` → `context canceled`,
after roughly six hours (19:57 → 02:01).

It was debugging a **Spring `ApplicationContext` startup failure in `SecurityConfigurationTest`** — the
last commands were narrowing an ambiguous-bean or context-wiring error. Uncommitted in the working tree:

```
 M build.gradle                    (+33 lines — new deps: archunit, wiremock,
                                    service-auth-provider, jose, feign)
 M src/main/resources/application.yaml
 M src/main/java/.../domain/deedpoll/WriteContext.java
?? src/main/java/.../api/            }
?? src/main/java/.../auth/           }  14 new files
?? src/main/java/.../config/SecurityConfiguration.java
?? src/main/java/.../config/ServiceAuthConfiguration.java
?? src/main/java/.../domain/{ForbiddenError,UnauthorizedError}.java
?? src/test/java/.../auth/, .../config/*SecurityTest.java
```

**This was left uncommitted on purpose.** Committing a mid-debug state with failing tests would break the
plan's one-commit-per-task rule and put a red build in the history.

### Two ways to restart, and a recommendation

- **Let it continue from the half-finished tree** *(recommended)*. The failure looked like test wiring
  rather than a design problem, and the work is probably mostly right.
- **Or `git checkout . && git clean -fd` the untracked task-12 files first**, so Task 12 starts clean.
  Choose this if the resumed session seems confused by files it did not write.

**Worth considering instead:** a Spring context ambiguity is often a five-minute fix for a human and a
long loop for an agent. Fixing `SecurityConfigurationTest` by hand, committing it, and then letting
Ralphex resume at Task 13 may be faster than another loop on it.

## Do not let a session drift into

Plan 01 stops at Task 16. **CCD and CDAM are plans 02 and 03, and neither is written**, deliberately —
both wait on external onboarding, and writing them now would bake in a case-type ID nobody has confirmed.
The `deedpoll.ccd.case-type-id: DEED_POLL_PLACEHOLDER` constant exists precisely so no task has to invent
one.

Full context, if a session needs the *why*:
`../deed-poll-admin/.business_context/DEED_POLL_API_PLAN.md`.
