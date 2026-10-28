# Plan D3: the Master's referral digest and reminder emails

## Overview

Master Sullivan's feedback, item D (Steve, 2026-10-27/28). Two emails to Masters, which this service has never sent
(every existing template goes to an applicant):

1. **The digest** (decision D-6, D-7): when a referral window closes (10:30 and 14:30 by default, on working days
   only), each Master who has referrals counted towards that window gets **one** email listing them, each with a link
   to the referral in the admin app. No email when there is nothing.
2. **The reminder** (decision D-4): every `reminderInterval` (48 hours) from a referral's assignment, its Master is due
   a reminder. The reminders due in a run go as **one email per Master**, listing **every** referral still waiting for
   their direction, each with a link. Reminders stop when the Master directs the referral or it is unassigned.

It builds on **D1** (Masters list with emails, `reminderInterval()`, `BankHolidays`) and **D2** (assignment,
`ReferralWindows.windowFor`/`closesAt`, `assignedAt`, the window columns). Read both plans' Overviews first.

**Nothing is sent until the two Notify templates exist.** Nobody has created them yet. They ship unprovisioned
(`DEED_POLL_PLACEHOLDER`), and an unprovisioned template is skipped visibly (step 6), so this plan is safe to deploy
before the templates exist. `docs/MASTER-EMAILS.md` (step 9) carries the suggested template text for whoever
creates them.

## Context

Read before changing anything:

- `CLAUDE.md`: "The outbox must run outside any caller transaction", ADR-0007 (unknown sends are not retried
  automatically; `sendStartedAt`), "A new email template is declared in a handful of places, and two of them fail
  silently" (`notify.templates.<key>`, the chart env var, `EmailTemplateTest.Configuration`), "Never put a token,
  secret, session id, cookie or email address in an event, its `detail` or a log line", and the scheduler examples
  (`GazetteRecovery`, `StagingConfiguration`).
- `notify/`: `NotificationRepository`, `NotificationDeliveryTransactions`, `NotifyProperties.isProvisioned`, how the
  drain claims rows with an owner and a lease, and how a Notify call is classified (`TEMPORARY`, `TECHNICAL`,
  `PERMANENT`). **The Master emails do not go through the case outbox**: `deedpoll_notification` is case-scoped
  (`deedPollId` NOT NULL, per-case indexes, case notes), and a digest spans cases. They get their own small ledger
  (step 1) that copies the outbox's delivery rules, not its tables.
- `charts/deed-poll-api/values.yaml` (the `NOTIFY_TEMPLATE_*` lines and how per-environment URLs are written) and
  `Chart.yaml`. **A values change needs a chart version bump** or Flux never deploys it.

## Development Approach

1. **Migration `V44__master_emails.sql`** (or the next free number), snake_case like V31/V40:
   - `deedpoll_master_email`: `id uuid primary key`, `master_id uuid not null references deedpoll_master(id)`,
     `kind varchar(16) not null check (kind in ('DIGEST','REMINDER'))`, `period_key varchar(64) not null` (a digest's
     window, e.g. `2026-10-28:AM`; a reminder's run, step 4), `referral_ids uuid[] not null`, `state varchar(16) not
     null` (`Pending`, `Sending`, `Sent`, `Failed`, `Skipped`), `attempts integer not null default 0`, `owner uuid`,
     `lease_until timestamp`, `send_started_at timestamp`, `notify_id uuid`, `failure varchar(32)`, `created_at`,
     `settled_at`, and **`unique (master_id, kind, period_key)`**, which is what makes two pods enqueue once.
   - `deedpoll_window_digest_run`: `window_date date`, `window_slot varchar(2)`, `ran_at timestamp not null`,
     `primary key (window_date, window_slot)`: one row per closed window, inserted by whichever pod gets there first.
   - On `"deedpoll_referral"` (mixed case, quoted): `"remindersSent" integer not null default 0`. D2's `assign` and
     `withdrawDirection` reset it to 0 when they restart the clock; teach both here and test it.
   - Add the tables to `DeedPollSchema.DEED_POLL_TABLES`.
2. **The digest job.** `domain/masters/MasterDigests`, `@Scheduled` with a fixed delay (`deedpoll.masters.digest-interval`,
   default 5 minutes, switchable off for tests), outside any transaction, never throwing out of the scheduler:
   - For each window that has **closed** in the last 24 hours (enumerate them with D2's `ReferralWindows` over working
     days; a window older than 24 hours is never sent, so a long outage does not flood Masters with stale digests),
     insert its `deedpoll_window_digest_run` row with `ON CONFLICT DO NOTHING`. Only the pod whose insert wrote a row
     continues for that window.
   - Collect `Claimed` referrals whose `windowDate`/`windowSlot` is that window, grouped by `assignedMasterId`
     (referrals with no `assignedMasterId`, the go-live case, are skipped and counted in the log line). Insert one
     `deedpoll_master_email` per Master (`DIGEST`, the window's key, the referral ids in D2's `MINE_ORDER`), state
     `Pending`, with `ON CONFLICT DO NOTHING`.
3. **The reminder job.** `domain/masters/MasterReminders`, `@Scheduled` (`deedpoll.masters.reminder-interval-check`,
   default 15 minutes):
   - **Reminders are sent only on working days between 07:00 and 18:00 `Europe/London`.** A reminder that falls due
     outside those hours waits for the next run inside them. That is this plan's own choice, not one of Steve's, and
     it is called out in the PR for review: a Master emailed at 3am on a Sunday would rightly complain.
   - A referral is due reminder `k` when it is `Claimed`, has an `assignedMasterId`, no decision, and
     `floor((now - assignedAt) / reminderInterval()) = k` with `k > remindersSent`. Claim each due referral with one
     conditional `UPDATE ... SET "remindersSent" = k WHERE "remindersSent" < k AND state = 'Claimed' ...`, so two pods
     claim a reminder once.
   - Group the claimed referrals by Master. For each Master with at least one, insert one `REMINDER` email whose
     `referral_ids` is **every** referral currently `Claimed` by that Master with no decision (D-4: the email lists all
     their outstanding work, not only the ones that fell due), and whose `period_key` is the run's start instant
     truncated to the minute plus the pod's run id, so a retry of the same run cannot enqueue twice.
4. **Delivery.** `domain/masters/MasterEmailDelivery`, driven by both jobs after they enqueue, and by its own
   `@Scheduled` drain (1 minute): claim `Pending` rows with an owner and a lease in a short committed transaction;
   build the personalisation; commit `Sending` with `send_started_at` **before** the single Notify call; settle `Sent`
   with the Notify id, or classify the failure as the case outbox does. A known `TEMPORARY` refusal returns the row to
   `Pending` with `attempts + 1`, up to 8; anything unknown, or a crash after `Sending`, is `Failed` and **never resent
   automatically** (ADR-0007). Reuse the existing Notify client bean and its error classification. Do not reuse
   `GazetteNotify`.
5. **The emails.**
   - Recipient: the Masters-list entry's email at send time (a Master whose email changed gets it at the new
     address). An entry marked unavailable still gets it: its referrals are still its own until reassigned.
   - Personalisation, as plain strings (GOV.UK Notify links raw URLs; it does not render HTML):
     `master_name`; `referral_count`; `period` (digest: "the morning of 28-10-26" / "the afternoon of 28-10-26",
     the dd-mm-yy style Steve chose for the Master's page; reminder: "waiting for your direction");
     `referral_list`, one line per referral in `MINE_ORDER`: `{displayReference} – {reasons, folded as ReferralNotes
     folds them} – {Urgent|Standard} – referred {dd-mm-yy} – {link}`. The reference is the application's display
     reference. **No applicant name, no address, no email address.**
   - Link: `{deedpoll.admin.base-url}/judicial/referrals/{referralId}`. A new property `deedpoll.admin.base-url`
     (`ADMIN_BASE_URL`), required when either template is provisioned (start-up fails with a fixed message
     otherwise), with no default in `application.yaml` beyond an obviously local one for tests.
   - Template keys `master-referral-digest` and `master-referral-reminder` under `notify.templates`, env vars
     `NOTIFY_TEMPLATE_MASTER_REFERRAL_DIGEST` and `NOTIFY_TEMPLATE_MASTER_REFERRAL_REMINDER`, defaulting to
     `DEED_POLL_PLACEHOLDER`. Reply-to: none.
6. **Unprovisioned is a visible skip, never a backlog.** If a template is unprovisioned (`NotifyProperties`'
   rule), the job still records its digest run and its reminder claims, but inserts the email rows as `Skipped` and
   sends nothing, and logs one INFO per run naming the template key (never an address). So provisioning the template
   later starts with the next window and never sends yesterday's digests.
7. **Chart.** In `charts/deed-poll-api/values.yaml`, add `NOTIFY_TEMPLATE_MASTER_REFERRAL_DIGEST` and
   `NOTIFY_TEMPLATE_MASTER_REFERRAL_REMINDER` as `DEED_POLL_PLACEHOLDER` in every environment (no ids exist yet), and
   `ADMIN_BASE_URL: '{{ if eq .Values.global.environment `prod` }}https://deed-poll-admin.platform.hmcts.net{{ else }}https://deed-poll-admin.{{ .Values.global.environment }}.platform.hmcts.net{{ end }}'`
   (check the admin chart in `hmcts/deed-poll-admin` `charts/deed-poll-admin/values.yaml` `ingressHost` names the same
   host shape, and say so in the execution notes). **Bump `Chart.yaml`'s version.** Keep any chart test passing.
8. **Tests first**, real Postgres for the ledger and the jobs, WireMock for Notify (no live calls):
   - Digest: a window closing with referrals for two Masters enqueues two emails; one with none enqueues nothing;
     two pods running the job together enqueue once (run the job twice concurrently); a window closed 25 hours ago is
     not sent; a referral reassigned before the window closed goes in the new Master's digest; a Saturday has no
     window; the email lists referrals in `MINE_ORDER` with their links.
   - Reminder: a referral assigned 47h59m ago is not due, 48h is due 1, 96h is due 2; directed or unassigned stops
     them; reassignment resets the count; outside 07:00–18:00 or on a holiday nothing is sent and the next in-hours
     run sends it; two concurrent runs claim once; the email lists every outstanding referral, not only the due ones.
   - Delivery: `Sending` is committed before the Notify call (fail the call after it and assert the row is
     `Failed`, not resent); a temporary refusal retries up to 8 attempts; a permanent one fails.
   - Unprovisioned: rows `Skipped`, WireMock received nothing, the run recorded; provisioning afterwards sends only
     the next window.
   - Privacy: no log line, audit row or exception message contains an email address (capture the logs and grep).
   - Configuration: `EmailTemplateTest.Configuration` (or a sibling) holds the two new keys against
     `application.yaml`; start-up refuses a provisioned template with no `ADMIN_BASE_URL`.
9. **Docs.** `docs/MASTER-EMAILS.md`: what each email is for, when it goes, the personalisation keys with an example,
   **suggested Notify template text** for both (subject and body, using `((referral_list))` etc.), how to provision
   (create the templates in the deed poll Notify service, put the ids in the chart per environment, bump the chart),
   and how to investigate a `Failed` row. Update `CLAUDE.md` with a paragraph on the Master email ledger and its rules.
10. **Out of scope:** admin; creating the Notify templates; any Notify id in the chart. No new dependency.
11. **Publishing:** local task commits only, no push and no PR.

## Validation Commands

Run from the repository root before implementation and after each task:

```bash
export JAVA_TOOL_OPTIONS=-XX:UseSVE=0 TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build -I /Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/no-checkstyle.gradle --console=plain
/Users/steve.newman/Documents/GitHub/deed-poll-admin-workspace/cft-workspace/.context/scripts/checkstyle.sh .
```

This devcontainer's JVM needs `-XX:UseSVE=0`. Gradle's Checkstyle worker ignores it and crashes with SIGILL, so the
init script disables the Checkstyle tasks, and `checkstyle.sh` runs the same jar and config directly, failing on any
`[WARN]` or `[ERROR]`. Do not edit either helper script or commit them. Record the actual `test` and `integration`
counts from `build/test-results/`, and distinguish baseline failures from regressions.

**Baseline:** whatever D2 finished on, recorded at execution time.

**Known intermittent test:** `CcdCreationAttemptRepositoryTest` "an attempt that ran out of tries on refusals may be
retried from scratch" fails intermittently. Re-run it alone before treating it as a regression, and never "fix" it.

Run every build and test command in the **foreground** and wait for it to finish. A backgrounded build ends the
session, and the next one builds over it, wiping the results.

## Implementation Steps

### Task 1: Ledger and configuration
- [ ] Record the execution baseline in the execution notes.
- [ ] Add failing tests for the migration, the configuration keys, the base-URL start-up rule and the `remindersSent` reset in `assign` and `withdrawDirection`.
- [ ] Implement V44, the properties and the resets, run the focused tests and all Validation Commands, and record the results.

### Task 2: Delivery
- [ ] Add failing tests for claiming, `Sending` before the call, settlement, retries, the unknown outcome and the unprovisioned skip.
- [ ] Implement `MasterEmailDelivery` and its drain, run the focused tests and all Validation Commands, and record the results.

### Task 3: The digest
- [ ] Add failing tests for every digest case in the Development Approach.
- [ ] Implement `MasterDigests`, run the focused tests and all Validation Commands, and record the results.

### Task 4: The reminders
- [ ] Add failing tests for every reminder case in the Development Approach.
- [ ] Implement `MasterReminders`, run the focused tests and all Validation Commands, and record the results.

### Task 5: Chart, docs and final verification
- [ ] Chart values and version bump; `docs/MASTER-EMAILS.md`; `CLAUDE.md`.
- [ ] Run the final validation **forced**: `./gradlew cleanTest cleanIntegration build -I <no-checkstyle.gradle> --console=plain`, then `checkstyle.sh`. Record the counts against the baseline. Confirm `git status` is clean apart from the task commits.

## Execution notes
