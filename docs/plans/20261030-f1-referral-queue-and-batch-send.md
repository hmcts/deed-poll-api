# Plan F1: referrals wait in a queue, and a Team leader or Delivery Manager sends them to Masters in batches

## Overview

User feedback after item D (Steve, 2026-10-29/30). D2 had the caseworker raising a referral choose its Master. Users
want a check in between: **anyone raises a referral, it waits in a queue, and a Team leader or Delivery Manager checks
it and sends it to a Master**, in batches, only during a referral window, with at most a set number sent per window
**across all Masters**. This lands **before the 2026-11-02 go-live**, so prod starts on it and no prod data is ever
migrated (Steve, 2026-10-30). There is therefore **no expand-then-contract step**: the admin plan Fa1 follows straight
after, and AAT is allowed to be briefly inconsistent between the two deploys.

1. **Raising takes no Master.** Every new referral is `Open`, which now reads "waiting to be sent".
2. **A new capability, `ALLOCATE_REFERRALS`,** held by Team leaders and Delivery Managers, gates the queue, the batch
   send and reassignment.
3. **The queue read**: `Open` referrals, urgent first then oldest raised, with what the checker needs, and the current
   window's state and counts. The same read lists `Claimed` referrals for reassigning.
4. **The batch send**: one request of referral-to-Master pairs, **refused outside a window**, refused **as a whole** if
   it would take the window past the cap, all-or-nothing, and naturally idempotent.
5. **The cap is the window's total across every Master** (default 10, a Delivery Manager setting), not per Master.
6. **Reassignment** is `ALLOCATE_REFERRALS` only, of a `Claimed` referral, **at any time**, and **uses no place** in
   any window. It restarts the 7 days and the reminders, and emails the new Master.
7. **Emails**: each Master in a send gets **one** email for that send, however many referrals, linking to My
   referrals. The window-close digest goes. The 48-hour reminder stays, also linking to My referrals.
8. **My referrals is ordered by the day a referral was sent** to the Master, not the day it was raised.

It builds on D1–D3 (`docs/plans/completed/20261028-d1-*.md`, `-d2-*.md`, `-d3-*.md`). Read their Overviews, D1's
decision table and D2's Development Approach first: this plan changes D-1, D-5, D-6 and D-8 and keeps the rest.

## Context

Read before changing anything:

- `CLAUDE.md`: "Staff permissions", "`GET /me`" (a new `Capability` reaches admin through `/me` with no change there,
  and `CallerAccessOpenApiTest` pins the enum), "The referral process settings are stored, and read on every
  decision", "A referral is assigned to a Master by the office, and the processing window gives it back", "The Master
  emails have their own ledger", "The judicial surface is scoped by visibility", the audit rules and "A case column
  that mirrors the referral table is recomputed from truth".
- `domain/auth/Capability`, `RoleCapabilities`.
- `domain/referrals/ReferralService` (`create`, `assign`, `assignLocked`, `currentWindowLoad`, `unassignOverdue`,
  `windowAt`, `inWords`), `ReferralWindows`, `ReferralWindow`, `ReferralWindowLoad`, `ReferralNotes`,
  `ReferralSchemas` (`parseCreateReferral`, `MASTER_ID`, `SELECT_A_MASTER`), `repository/ReferralRepository`
  (`countByMasterInWindow`, `countByStateAndAssignedMasterIdAndWindowDateAndWindowSlot`, `findOwnedBy` and its
  `ORDER BY`), `JudicialReferralService` (`MINE_ORDER`'s javadoc).
- `domain/settings/ProcessSettings` (`MASTER_WINDOW_CAP`), `ProcessSettingsService.masterWindowCap()`,
  `ProcessSettingsRepository`, `api/caseworker/dto/ProcessSettingsResponse`, `UpdateProcessSettingsRequest`.
- `domain/masters/*`: `MasterDigests`, `MasterDigestTimer`, `MasterReminders`, `MasterEmailKind`,
  `MasterEmailPersonalisation`, `MasterEmailTransactions`, `MasterEmailLinks`, `MasterRepository.digestTook`, and
  `docs/MASTER-EMAILS.md`.
- `api/caseworker/controller/CaseworkerReferralController` (create, `PUT …/assignment`),
  `CaseworkerMastersController`, the Gazette controllers (the shape of a capability-gated caseworker area),
  `config/OpenAPIWriteBodies`, `OpenAPIForbiddenResponses`, `StaffAccessRulesTest`, `WriteEndpointAuditCoverageTest`.
- Migrations V42–V45: the referral columns (`"assignedMasterId"`, `"assignedAt"`, `"windowDate"`, `"windowSlot"`,
  `"remindersSent"`) and V43's all-or-nothing check on the four assignment columns; the process settings columns;
  `deedpoll_master_email`'s `kind` (check whether V44 constrains its values).

Decisions (Steve, 2026-10-29/30), recorded so a later reader does not reopen them. They replace D-1, D-5, D-6 and D-8:

| # | Decision |
|---|---|
| F-1 | Anyone raises a referral, with no Master. It waits in a queue until a Team leader or Delivery Manager sends it. |
| F-2 | Only Team leaders and Delivery Managers see the queue, send, and reassign (`ALLOCATE_REFERRALS`). Caseworkers lose assigning entirely. |
| F-3 | The cap is the **window's total across all Masters**, 10 by default, shared out however the sender likes (5 × 2, 1 × 10). It stays a Delivery Manager setting. There is no per-Master limit. |
| F-4 | Sending is possible **only during a window** (07:00–10:30 and 14:00–14:30 by default, working days). Outside a window nothing can be sent. |
| F-5 | Sending is a **batch**: the sender selects referrals, chooses a Master for each, and sends them together. |
| F-6 | Each Master in a send gets **one** email for that send, however many referrals it held, with a link to My referrals. Two sends in one window mean two emails. |
| F-7 | "Checking" is reviewing only. A referral that needs changing is withdrawn by its caseworker and raised again. |
| F-8 | Urgent referrals go to the top of the queue and **count** towards the cap. |
| F-9 | My referrals groups by the **day a referral was sent** to the Master; urgent ones stay at the top whatever the day. |
| F-10 | Reassigning moves a `Claimed` referral to another Master, **at any time**, inside or outside a window, and **takes no place** in any window. The new Master gets the same single email; the old one gets nothing. The 7 days and the 48-hour reminders restart. |
| F-11 | A referral not directed within the processing window is unassigned, as today, and goes back into the queue. |
| F-12 | AAT's existing referrals are withdrawn before this goes live there (an operational step, not this plan's). |

Choices this plan makes that Steve has **not** yet confirmed; flag each in the execution notes so the review sees them:

- **A-1:** the 48-hour reminder also links to My referrals instead of listing each referral (one link in both emails).
- **A-2:** a window's count is every referral **sent** in it, whatever happens to it later (withdrawn, directed or
  reassigned). A withdrawal does not give a place back. Unassignment after 7 days clears the referral's window, but
  only ever for a window long closed.

How the states are used, so no `ReferralState` member is added (it is a Postgres enum):

| State | D2 | F1 |
|---|---|---|
| `Open` | waiting for assignment | **waiting to be sent**: in the queue. Visible to no judge |
| `Claimed` | assigned to a Master | **sent to a Master** (`assignedMasterId`, `assignedAt` = when sent or last reassigned, `windowDate`/`windowSlot` = the window it was sent in) |
| others | unchanged | unchanged |

## Development Approach

1. **Migration `V46__referral_queue.sql`** (or the next free number; never edit V42–V45). Rename V42's
   `master_window_cap` to `window_cap`, keeping its value, its default (10) and its check (1–100), renaming the check
   constraint too. Keep the history: `deedpoll_process_settings_change` rows already written keep their old field name;
   new rows use the new one. Do not drop `deedpoll_window_digest_run` (it is harmless and holds history). V44 constrains
   `deedpoll_master_email.kind` to `('DIGEST', 'REMINDER')`: replace that check with one admitting `'SENT'` as well,
   keeping `'DIGEST'` so the rows already written stay valid. Map everything; `ddl-auto: validate` must pass.
2. **The setting.** `ProcessSettings.MASTER_WINDOW_CAP`/`masterWindowCap` become `WINDOW_CAP`/`windowCap` on the
   wire, the record and the service (`windowCap()`), with the same bounds (1–100) and default (10). The OpenAPI
   description says it is the most referrals that can be sent to Masters in one window, across all Masters. **No
   alias**: today's admin's settings save will be refused until admin Fa1 deploys, which is accepted (Overview).
3. **The capability.** Add `Capability.ALLOCATE_REFERRALS`, declared after `MANAGE_PROCESS_CONFIG`, granted to
   `TEAM_LEADER` (and so to the deprecated `DEEDPOLL_ADMIN`, which shares that set, and to `DELIVERY_MANAGER`, which
   inherits it). Update `RoleCapabilities`' tests, `CallerAccessOpenApiTest` and the capability table in `CLAUDE.md`.
4. **Raising.** `CreateReferralRequest` loses `masterId`, and the create path loses its assignment branch: every new
   referral is `Open`. A body carrying `masterId` is refused as any unknown field is (check how `OpenAPIWriteBodies`
   publishes `additionalProperties: false`). The case note says it was raised and is waiting to be sent to a Master.
5. **The queue read.** `GET /caseworker/referral-queue`, `ALLOCATE_REFERRALS`, 403 before any read (copy the Gazette
   area's gate and its `StaffAccessRulesTest`/`OpenAPIForbiddenResponses` handling). Query `state=Open` (default) or
   `state=Claimed`, and `page`/`size` with the case list's published bounds. **The database filters, orders, pages and
   counts** (as `findOwnedBy` does), across all cases:
   - `Open`: urgent first, then oldest `createdOn` first, then `id`.
   - `Claimed`: oldest `assignedAt` first, then `id`.
   Each row (`ReferralQueueItem`, a new DTO; never reuse an entity or a judicial DTO): the referral's id and its case's
   id and `displayReference`; the applicant's new and old names, `type` and `nationality` (what the checker compares;
   no contact details); the reasons; urgent and its reason; who raised it and when; and for `Claimed` the
   `assignedMaster` (`{id, name}`), `assignedAt`, the window it was sent in and `directionDueBy`. Never a subject or an
   email address. The envelope (`ReferralQueueResponse`) carries the page, the total and a `window` object:
   - `current`: `{date, slot, closesAt}` when now is inside a window, else null;
   - `next`: `{date, slot, opensAt}`, the next window to open (the current one's successor when inside one);
   - `cap` (the setting) and `sent` (referrals sent in `current`, or 0 when null), from one count;
   - `masters`: every **available** Master's `{id, name, sentInWindow}` for `current` (0 when null), one grouped count.
   `ReferralWindows` needs a pure "is this instant inside a window, and which" alongside `windowFor`; add it there with
   exhaustive tests (each boundary minute, a weekend, a bank holiday, both DST changes), keeping `windowFor` for the
   reminder and unassignment code that still uses it.
6. **The batch send.** `POST /caseworker/referral-queue/sends`, `ALLOCATE_REFERRALS`, `@AuditedAs(REFERRAL_SENT)`
   (a new `AuditAction`; names are permanent), body `{allocations: [{referralId, masterId}, …]}` (1 to the cap's
   published maximum, 100; no `referralId` twice). One transaction; **any refusal writes nothing**. In order:
   1. Shape (400, the field path naming the entry, e.g. `allocations[2].masterId`).
   2. **The window.** If now is inside no window, 409: "Referrals can only be sent during a referral window. The next
      one opens at 14:00 today." (the next window in words, as `inWords` does for the morning/afternoon and day).
   3. **The window lock.** Take a transaction-scoped exclusive advisory lock keyed on the current window, so two
      senders serialise and the second counts what the first committed. Then lock the referrals in `id` order, then the
      Masters in `id` order (`findAssigneeForUpdate`), everywhere, so two sends cannot deadlock.
   4. Each referral must exist (404 naming it is not useful across cases: answer 409 on `allocations[i].referralId`,
      "This referral is no longer waiting to be sent") and be `Open`. **A replay is a no-op:** a referral already
      `Claimed` by the same Master in the same current window is skipped, not refused, so a sender whose response was
      lost can press Send again. Anything else (another Master, another window, `Decided`, `Withdrawn`, `Closed`) is
      the 409 above.
   5. Each Master must exist and be available (400 on `allocations[i].masterId`, `SELECT_A_MASTER`'s wording).
   6. **The cap.** `sent` in the current window (A-2: every referral whose `windowDate`/`windowSlot` is this window,
      any state) plus the new ones must not exceed `windowCap()`; otherwise 409: "Only 3 more referrals can be sent this
      morning. Remove 2 and try again." (the room left and the excess, in words, singular and plural). No field.
   7. Apply to each new one, as `assignLocked` does today: `Claimed`, `claimedBy`/`claimedById` from the Master
      (null subject until linked), `claimedAt` = `assignedAt` = `lastTouchedAt` = now, `assignedMasterId`, the
      current window, `remindersSent` = 0, `modifiedBy`/`modifiedOn`. One `ReferralNotes` note per referral ("…
      sent to {Master} by the office."; never an email). One `REFERRAL_SENT` event per referral with old and new
      `state`, `claimedBy`, `assignedAt` (never a subject). Recompute both read-models once per affected case.
   8. Enqueue one `SENT` email row per Master with at least one new referral (step 8), in this transaction. A replay
      enqueues nothing.
   The 200 answer: the window (as in the queue envelope, after the send) and the sent referrals as queue items.
   Retire `assignLocked`'s `digestTook` loop with the digest (step 8): the window lock replaces it.
7. **Reassignment.** `PUT /caseworker/cases/{id}/referrals/{referralId}/assignment` keeps its path and body and
   becomes `ALLOCATE_REFERRALS` only (403 before reads). It takes a `Claimed` referral only (an `Open` one is 409,
   "Send this referral from the queue"; others keep the existing 409), works **at any time**, checks **no cap**, and
   **keeps the referral's `windowDate`/`windowSlot`**. It restarts the clock (`assignedAt` = now, `remindersSent` = 0),
   writes the existing "reassigned from … to …" note and `REFERRAL_ASSIGNED` event, and enqueues one `SENT` email to the
   new Master. The same Master is a no-op, as today. Lock order: the referral, then the Master; it does not take the
   window lock.
8. **Emails.**
   - **Remove the window digest**: `MasterDigests`, `MasterDigestTimer`, their properties (`MASTER_DIGEST_INTERVAL`
     etc.), `recordDigestRun` and `MasterRepository.digestTook`. Keep `MasterEmailKind.DIGEST`, because AAT's ledger
     already holds `DIGEST` rows the entity must still map; nothing writes it any more, its template key and chart
     variable go, and a leftover `DIGEST` row that is somehow `Pending` is settled `Skipped` by the drain, never sent.
   - **A new kind, `SENT`** (`master-referral-sent`, `NOTIFY_TEMPLATE_MASTER_REFERRAL_SENT`, `DEED_POLL_PLACEHOLDER`
     in `application.yaml` and the chart). Its period key is unique per send and Master (for example the send's own
     UUID, generated once per request), so `unique (master_id, kind, period_key)` holds and a replay writes nothing.
     Reassignment's key is the referral id plus its new `assignedAt`. Unprovisioned is the existing visible skip.
   - **Personalisation for both `SENT` and `REMINDER`** (A-1): `master_name`, `referral_count` and
     `my_referrals_link` (`{ADMIN_BASE_URL}/judicial/referrals/mine`). Drop `period` and `referral_list` and the
     per-referral line builder if nothing else uses them. The send-time re-read keeps its rule: count only referrals
     still `Claimed` by that Master with no decision; if none is left, the row is `Skipped`.
   - The reminder's timing is unchanged (`MasterReminders`, working days 07:00–18:00, every reminder interval from
     `assignedAt`).
   - Rewrite `docs/MASTER-EMAILS.md`: the two emails, their keys, the suggested Notify wording (a starting point, the
     business's to agree), provisioning steps (including the chart version bump).
9. **My referrals order.** `findOwnedBy`'s `ORDER BY`: `Claimed` before `Decided`; within `Claimed`, urgent first,
   then oldest **`assignedAt`** first; within `Decided`, oldest `assignedAt` first, urgency ignored; then `id`. Use
   `coalesce("assignedAt", "claimedAt", "createdOn")` so a pre-V43 row still orders. Update `MINE_ORDER`'s javadoc and
   the combination fixture test.
10. **Unassignment.** `unassignOverdue` is unchanged in behaviour (it already returns a referral to `Open` and clears
    the four assignment columns); its note now says "returned to the queue to be sent again: no direction from {name}
    within 7 days." (the window in words from the setting).
11. **What goes.** `ReferralService.currentWindowLoad`/`ReferralWindowLoad` if only the Masters list used them: `GET
    /caseworker/masters` drops `window`, `cap` and each entry's `assignedInWindow` (the queue envelope replaces them).
    The deprecated judicial pool and claim operations stay deprecated and empty, untouched.
12. **Tests first.** Full-context MockMvc against real Postgres for HTTP; pure unit tests for `ReferralWindows`.
    - Capability: Team leader, Delivery Manager and legacy admin hold `ALLOCATE_REFERRALS`; a caseworker gets 403 from
      the queue, the send and the reassignment, before any read or write.
    - Create: no `masterId` accepted; always `Open`; the note.
    - Queue: both orders with an every-combination fixture; paging stable; the envelope inside a window, between
      windows, on a weekend and a bank holiday; `sent` and per-Master counts; unavailable Masters left out; no subject
      or email in the JSON.
    - Send: success (states, columns, notes, audit, read-models, one email row per Master); outside a window 409 with
      nothing written; over the cap 409 with nothing written, at exactly the cap succeeds; urgent counts; a withdrawn
      referral still counts (A-2); an unavailable Master 400; a referral not `Open` 409; a duplicate `referralId` 400;
      **the replay** (same body twice: second is 200, nothing new written, no email row); **concurrency**: with the cap
      at 2 and two sends of 2 released together, exactly one succeeds (copy `NotificationOutboxConcurrencyTest`'s way of
      running two transactions); no deadlock when two sends share referrals or Masters in different orders.
    - Reassign: outside a window succeeds; no cap check; the window columns kept; clock and reminders restart; email
      row for the new Master only; `Open` 409; same Master no-op.
    - Emails: the `SENT` row's personalisation; the reminder's new personalisation; unprovisioned skip; the digest
      gone (no timer bean, no property).
    - `MINE_ORDER` by `assignedAt`; unassignment's note; the settings rename end to end (GET, PUT, history row's field
      name, OpenAPI).
    - OpenAPI: the new operations, the capability's 403s, the 409s, the removed fields, `CallerAccessOpenApiTest`.
13. **Docs.** `CLAUDE.md`: the layout's operation counts, "Staff permissions" (the new capability), the settings
    paragraph (window cap), the assignment paragraph (rewritten around the queue, the send and reassignment), the
    emails paragraph (no digest; `SENT`), and the judicial paragraph's `MINE_ORDER`. `docs/MASTER-EMAILS.md` as in
    step 8.
14. **Out of scope:** admin (plan Fa1); Helm/Flux beyond the chart's new template variable and version bump; clearing
    AAT's referrals (F-12, done by hand); provisioning Notify templates. No new dependency.
15. **Publishing:** local task commits only, no push and no PR.

## Validation Commands

Run the full build from the repository root **twice only**: before implementation (the baseline) and in the final
task, forced. **After each other task**, run only the task's focused tests (`./gradlew test --tests '…'` and, where
the task touched them, `./gradlew integration --tests '…'`), the compile of every source set
(`./gradlew compileJava compileTestJava compileIntegrationTestJava -I <no-checkstyle.gradle>`) and `checkstyle.sh`. If
a focused run shows something outside the task failing, run the full build then rather than carry it forward. The
full build:

```bash
export JAVA_TOOL_OPTIONS=-XX:UseSVE=0 TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build -I /Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/no-checkstyle.gradle --console=plain
/Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/checkstyle.sh .
```

This devcontainer's JVM needs `-XX:UseSVE=0`. Gradle's Checkstyle worker ignores it and crashes with SIGILL, so the
init script disables the Checkstyle tasks, and `checkstyle.sh` runs the same jar and config directly, failing on any
`[WARN]` or `[ERROR]`. Do not edit either helper script or commit them. Record the actual `test` and `integration`
counts from `build/test-results/`, and distinguish baseline failures from regressions.

**Baseline:** api master `dec42d1` (#157), recorded at execution time.

**Known intermittent tests:** `CcdCreationAttemptRepositoryTest` "an attempt that ran out of tries on refusals may be
retried from scratch" and `CorrespondenceReadTest` fail intermittently in a full build. Re-run either alone before
treating it as a regression, and never "fix" them here.

Run every build and test command in the **foreground** and wait for it to finish. A backgrounded build ends the
session, and the next one builds over it, wiping the results.

## Implementation Steps

### Task 1: Migration, the setting and the capability
- [ ] Record the execution baseline (commit, highest migration, test counts) in the execution notes.
- [ ] Add failing tests for V46's mapping, the window cap's rename end to end, and `ALLOCATE_REFERRALS`'s grants and its place in `/me` and the OpenAPI enum.
- [ ] Implement V46, the setting rename and the capability, run the focused tests, the compile and `checkstyle.sh`, and record the results.

### Task 2: Raising without a Master, and the queue read
- [ ] Add failing tests for create (no `masterId`, always `Open`), the window-state function in `ReferralWindows`, and `GET /caseworker/referral-queue` (gate, both orders, paging, envelope, no subject or email).
- [ ] Implement, run the focused tests, the compile and `checkstyle.sh`, and record the results.

### Task 3: The batch send
- [ ] Add failing tests for every rule of the send in step 6, including the replay, the cap boundary, A-2 and the concurrency and deadlock cases.
- [ ] Implement, run the focused tests, the compile and `checkstyle.sh`, and record the results.

### Task 4: Reassignment, My referrals and unassignment
- [ ] Add failing tests for the reassignment rules in step 7, the new `MINE_ORDER`, unassignment's note and the Masters list's removed fields.
- [ ] Implement, run the focused tests, the compile and `checkstyle.sh`, and record the results.

### Task 5: Emails
- [ ] Add failing tests for the `SENT` email (one row per Master per send, replay writes none, reassignment's row, personalisation), the reminder's new personalisation, and the digest's removal.
- [ ] Implement, add the template key to `application.yaml` and the chart (with a `Chart.yaml` version bump), run the focused tests, the compile and `checkstyle.sh`, and record the results.

### Task 6: Documentation and final verification
- [ ] Update `CLAUDE.md` and rewrite `docs/MASTER-EMAILS.md` as steps 8 and 13 say; list A-1 and A-2 in the execution notes for review.
- [ ] Run the final validation **forced**: `./gradlew cleanTest cleanIntegration build -I <no-checkstyle.gradle> --console=plain`, then `checkstyle.sh`. Record the counts against the baseline. Confirm `git status` is clean apart from the task commits.

## Execution notes
