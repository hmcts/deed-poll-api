# The Master emails: the window digest and the reminder

Plan D3 (Master Sullivan's feedback, item D). These are the only emails this service sends to anybody but an
applicant. Both go to a Master on the Delivery Manager's Masters list, at the address the list holds **at send
time**, and both list referrals by their application's display reference with a link into the admin app. Neither
carries an applicant's name, address or email address.

**Nothing is sent yet.** Neither Notify template exists, so both ship as `DEED_POLL_PLACEHOLDER` in every
environment. An unprovisioned template is a visible skip, not a backlog (see below).

## The two emails

### The digest (`master-referral-digest`)

When a referral window closes (by default the morning window at 10:30 and the afternoon one at 14:30
`Europe/London`, on working days only; both are the Delivery Manager's process settings), each Master with
`Claimed` referrals counted towards that window gets **one** email listing them. A Master with none gets nothing.

- `MasterDigests` runs every `MASTER_DIGEST_INTERVAL` (default `PT5M`) and looks at every window that closed in the
  last 24 hours. A window older than that is never sent, so a long outage does not flood Masters with stale digests.
  A window is first taken one minute after its end (`MasterDigests.SETTLE`), so an assignment that read the window
  just before it closed has committed by then.
- One `deedpoll_window_digest_run` row per window, written in the same transaction as that window's email rows:
  whichever pod commits first enqueues the window; the others write nothing.
- A referral reassigned before the window closed goes in its new Master's digest. A referral with no
  `assignedMasterId` (claimed before V43) is left out and counted in the run's INFO line.
- Either email's list is read again at send time, and keeps only referrals still `Claimed` by that Master with no
  direction: a row that waited out a backoff never names one since directed, unassigned or reassigned. If nothing
  is left, the row is `Skipped`.
- A window's end time changed after its digest ran does not run it again.

### The reminder (`master-referral-reminder`)

Every reminder interval (the Delivery Manager's setting, default 48 hours) from a referral's assignment, its Master
is due a reminder. The reminders due in one run go as **one email per Master**, listing **every** referral still
waiting for that Master's direction, not only the ones that fell due.

- `MasterReminders` runs every `MASTER_REMINDER_INTERVAL_CHECK` (default `PT15M`), and **only on working days from
  07:00 to before 18:00 `Europe/London`**. A reminder that falls due outside those hours waits for the next run inside
  them. (This is plan D3's choice, not the business's; raise it if it is wrong.)
- Reminder `k` is due when `floor((now - assignedAt) / interval) = k` and `k > remindersSent`. One conditional
  `UPDATE` raises `"remindersSent"` to `k`, so two pods remind once. A referral owed several reminders (after an
  outage or a weekend) gets one email, not one per missed interval.
- Reminders stop when the Master directs the referral or it is unassigned. An assignment, a reassignment and a
  withdrawn direction reset `remindersSent` to 0, because each restarts the clock.
- A claim from before V43 that linking attached to a Master has no `assignedAt`, so it never falls due itself (the
  plan counts reminders from assignment). It is still listed when another of that Master's referrals falls due.

## Personalisation

Every value is a plain string: Notify links a raw URL and renders no HTML.

| Key | Digest | Reminder |
|---|---|---|
| `master_name` | the Masters-list name | the same |
| `referral_count` | how many referrals the list holds | the same |
| `period` | `the morning of 28-10-26` / `the afternoon of 28-10-26` | `waiting for your direction` |
| `referral_list` | one line per referral, urgent first, then oldest first | the same |

A `referral_list` line is
`{display reference} – {reasons} – {Urgent|Standard} – referred {dd-mm-yy} – {ADMIN_BASE_URL}/judicial/referrals/{id}`,
with the reasons worded as the referral's case notes word them. For example:

```
DP2026-000123 – Applicant is a minor: Have parental consent – Urgent – referred 27-10-26 – https://deed-poll-admin.aat.platform.hmcts.net/judicial/referrals/0b6c…
DP2026-000140 – Other – Standard – referred 28-10-26 – https://deed-poll-admin.aat.platform.hmcts.net/judicial/referrals/7e21…
```

## Suggested Notify template text

The wording is the business's to agree; this is a starting point that uses every key.

**Digest.** Subject: `Deed poll referrals for ((period))`

```
Dear ((master_name)),

((referral_count)) deed poll referral(s) were assigned to you for ((period)):

((referral_list))

Open each link to read the application and give your direction. You will need to sign in.

This is an automated email. Please do not reply to it.

HMCTS Deed Poll Office
```

**Reminder.** Subject: `Reminder: deed poll referrals waiting for your direction`

```
Dear ((master_name)),

You have ((referral_count)) deed poll referral(s) ((period)):

((referral_list))

Open each link to read the application and give your direction. You will need to sign in.

This is an automated email. Please do not reply to it.

HMCTS Deed Poll Office
```

Neither invites a reply, so neither needs `NOTIFY_EMAIL_REPLY_TO_ID`; neither carries a document, so neither needs
`NOTIFY_DOCUMENT_RETENTION_WEEKS`.

## Provisioning

1. Create both templates in the deed poll Notify service for the environment (non-prod first), and read each back
   through `GET /v2/template/{id}` to check the four placeholders match exactly.
2. In `charts/deed-poll-api/values.yaml`, replace `DEED_POLL_PLACEHOLDER` in `NOTIFY_TEMPLATE_MASTER_REFERRAL_DIGEST`
   and `NOTIFY_TEMPLATE_MASTER_REFERRAL_REMINDER` with the ids, in the conditional form the case templates use, so
   prod keeps rendering the placeholder until its own ids exist (`DeploymentChartConformanceTest` holds this).
3. Bump `Chart.yaml`'s version, or Flux never deploys the change.
4. `ADMIN_BASE_URL` is already set by the chart (`https://deed-poll-admin.{env}.platform.hmcts.net`, and
   `https://deed-poll-admin.platform.hmcts.net` in prod). **Start-up fails if either template is provisioned without
   it**, and whenever it is set but is not an absolute http(s) URL with a host and no query or fragment.

Provisioning starts with the next window and the next due reminder. The rows written while the template was
unprovisioned stay `Skipped` and are never sent, so nobody receives yesterday's digest.

The timers can be switched off with `MASTER_EMAILS_ENABLED=false` (default true); the drain then stops too.

## The ledger

`deedpoll_master_email` (V44, V45) holds one row per email: the Master's id (never the address), the kind, the period
key (a digest's window, `2026-10-28:AM`; a reminder's run minute plus a per-pod run id), the referral ids in the order
the email lists them, and the delivery state. `unique (master_id, kind, period_key)` is what makes two pods enqueue
once. It is not the case outbox and writes no case notes and no audit events.

| State | Meaning |
|---|---|
| `Pending` | Waiting to be sent, or to be tried again after a known temporary refusal (`next_attempt_at`). |
| `Sending` | `send_started_at` committed; the one Notify call may have been made. |
| `Sent` | Notify accepted it; `notify_id` is Notify's id. |
| `Failed` | Refused, out of attempts, or of unknown outcome. `failure` is `PERMANENT`, `TECHNICAL` or `TEMPORARY`. |
| `Skipped` | Never sent: the template was unprovisioned, or none of its referrals was left. |

`MasterEmailDrain` claims at most 25 `Pending` rows a minute (`MASTER_EMAIL_DRAIN_INTERVAL`) with an owner and a
lease, commits `Sending` **before** the Notify call, then settles. A known temporary refusal (Notify's 429, classified
`TEMPORARY`) goes back to `Pending` with the outbox's backoff, up to eight attempts, then `Failed`/
`TEMPORARY`. Anything else (a 400 or 403 is `PERMANENT`; a 500, any other status or a client exception
is `TECHNICAL`) is `Failed`. A `Sending` row whose lease has ended (the pod
died mid-send) is `Failed`/`TECHNICAL` at the start of the next drain. **No `Failed` row is ever resent
automatically** (ADR-0007): the Master may already have it.

## Investigating a `Failed` row

Logs name the row id, the verdict and Notify's status, never an address. Start from the row:

```sql
SELECT id, master_id, kind, period_key, state, attempts, failure, send_started_at, notify_id, created_at, settled_at
FROM deedpoll_master_email
WHERE state = 'Failed'
ORDER BY created_at DESC;
```

- **`send_started_at` set:** the call may have reached Notify. Search Notify's dashboard by **reference**, which is
  the row's `id`. If Notify has it, the Master was emailed; leave the row as it is. If it does not, tell the Master
  directly (the admin app's referral list shows the same work).
- **`send_started_at` empty, `TEMPORARY`:** Notify rate-limited it eight times, or its content could not be read;
  nothing was sent. Check Notify's status and the database.
- **`PERMANENT`:** usually a template id from another service, a missing placeholder or a bad address on the
  Masters list. Fix the cause; the next window or reminder will send.

Never change a row's state to unstick it. A row back in `Pending` with `send_started_at` set would be sent twice.
A missed digest is not re-sent: the Master's own page lists every referral assigned to them.
