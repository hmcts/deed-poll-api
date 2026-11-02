# The Master emails: the sent email and the reminder

Plan D3 (Master Sullivan's feedback, item D), reshaped by plan F1 (Steve, 2026-10-29/30). These are the only emails
this service sends to anybody but an applicant. Both go to a Master on the Delivery Manager's Masters list, at the
address the list holds **at send time**, and both link to the Master's own list in the admin app, My referrals.
Neither names an application, an applicant, an address or an email address.

**Nothing is sent yet.** Neither Notify template exists, so both ship as `DEED_POLL_PLACEHOLDER` in every
environment. An unprovisioned template is a visible skip, not a backlog (see below).

## The two emails

### The sent email (`master-referral-sent`)

A Team leader or Delivery Manager sends queued referrals to Masters in a batch, during a referral window
(`POST /caseworker/referral-queue/sends`). Each Master with **at least one new referral** in that send gets **one**
email, however many referrals the send gave them. Two sends in one window are two emails.

- The row is written in the send's own transaction (`MasterEmailTransactions.enqueueSent`), so a refused send writes
  none. Its period key is `send:{uuid}`, one UUID per request, so `unique (master_id, kind, period_key)` holds per send
  and Master. A replay (the same body sent again after a lost answer) applies nothing new and writes no row.
- A **reassignment** (`PUT /caseworker/cases/{id}/referrals/{referralId}/assignment`) sends the same email to the new
  Master only; the old one gets nothing. Its period key is `{referralId}@{assignedAt}` to the microsecond
  (`enqueueReassigned`). The same Master again is a no-op and writes nothing.
- Both keys fit V44's `varchar(64)` `period_key` (`MasterEmailPeriodKeysTest` pins them).
- The rows are sent by the drain timer (below), never inside the request, so a send's answer never waits on Notify.

### The reminder (`master-referral-reminder`)

Every reminder interval (the Delivery Manager's setting, default 48 hours) from a referral's send or reassignment
(`assignedAt`), its Master is due a reminder. The reminders due in one run go as **one email per Master**, counting
**every** referral still waiting for that Master's direction, not only the ones that fell due.

- `MasterReminders` runs every `MASTER_REMINDER_INTERVAL_CHECK` (default `PT15M`), and **only on working days from
  07:00 to before 18:00 `Europe/London`**. A reminder that falls due outside those hours waits for the next run inside
  them. (This is plan D3's choice, not the business's; raise it if it is wrong.)
- Reminder `k` is due when `floor((now - assignedAt) / interval) = k` and `k > remindersSent`. One conditional
  `UPDATE` raises `"remindersSent"` to `k`, so two pods remind once. A referral owed several reminders (after an
  outage or a weekend) gets one email, not one per missed interval.
- Reminders stop when the Master directs the referral or it is unassigned. A send, a reassignment and a withdrawn
  direction reset `remindersSent` to 0, because each restarts the clock.
- A claim from before V43 that linking attached to a Master has no `assignedAt`, so it never falls due itself. It is
  still counted when another of that Master's referrals falls due.

### Either email at send time

The referrals a row froze are read again when it is sent, and only those still `Claimed` by that Master with no
direction are counted: a row that waited out a backoff never counts one since directed, unassigned or reassigned. If
none is left, the row is `Skipped`.

### The window digest is gone

D3's digest at each window's close was removed by F1, because the sent email replaces it. `MasterEmailKind.DIGEST`
remains only so the `DIGEST` rows AAT's ledger already holds still map: it has no template key, never reads as
provisioned, and a `DIGEST` row somehow left `Pending` is settled `Skipped` by the drain, never sent.
`MASTER_DIGEST_INTERVAL` and `NOTIFY_TEMPLATE_MASTER_REFERRAL_DIGEST` are gone; `deedpoll_window_digest_run` is kept
as history and nothing writes it.

## Personalisation

Every value is a plain string: Notify links a raw URL and renders no HTML. Both emails use the same three keys.

| Key | Value |
|---|---|
| `master_name` | the Masters-list name |
| `referral_count` | how many referrals the email counts (see "Either email at send time") |
| `my_referrals_link` | `{ADMIN_BASE_URL}/judicial/referrals/mine`, for example `https://deed-poll-admin.aat.platform.hmcts.net/judicial/referrals/mine` |

The reminder linking to My referrals rather than listing each referral is plan F1's choice (A-1), not yet confirmed
by the business.

## Suggested Notify template text

The wording is the business's to agree; this is a starting point that uses every key.

**Sent.** Subject: `Deed poll referrals sent to you`

```
Dear ((master_name)),

((referral_count)) deed poll referral(s) have been sent to you for your direction.

Open My referrals to read each application and give your direction. You will need to sign in:

((my_referrals_link))

This is an automated email. Please do not reply to it.

HMCTS Deed Poll Office
```

**Reminder.** Subject: `Reminder: deed poll referrals waiting for your direction`

```
Dear ((master_name)),

You have ((referral_count)) deed poll referral(s) waiting for your direction.

Open My referrals to read each application and give your direction. You will need to sign in:

((my_referrals_link))

This is an automated email. Please do not reply to it.

HMCTS Deed Poll Office
```

Neither invites a reply, so neither needs `NOTIFY_EMAIL_REPLY_TO_ID`; neither carries a document, so neither needs
`NOTIFY_DOCUMENT_RETENTION_WEEKS`.

## Provisioning

1. Create both templates in the deed poll Notify service for the environment (non-prod first), and read each back
   through `GET /v2/template/{id}` to check the three placeholders match exactly.
2. In `charts/deed-poll-api/values.yaml`, replace `DEED_POLL_PLACEHOLDER` in `NOTIFY_TEMPLATE_MASTER_REFERRAL_SENT`
   and `NOTIFY_TEMPLATE_MASTER_REFERRAL_REMINDER` with the ids, in the conditional form the case templates use, so
   prod keeps rendering the placeholder until its own ids exist (`DeploymentChartConformanceTest` holds this).
3. Bump `Chart.yaml`'s version, or Flux never deploys the change.
4. `ADMIN_BASE_URL` is already set by the chart (`https://deed-poll-admin.{env}.platform.hmcts.net`, and
   `https://deed-poll-admin.platform.hmcts.net` in prod). **Start-up fails if either template is provisioned without
   it**, and whenever it is set but is not an absolute http(s) URL with a host and no query or fragment.

Provisioning starts with the next send, reassignment or due reminder. The rows written while the template was
unprovisioned stay `Skipped` and are never sent, so nobody receives a backlog.

The timers can be switched off with `MASTER_EMAILS_ENABLED=false` (default true); the drain then stops too.

## The ledger

`deedpoll_master_email` (V44, V45; V46 admits `SENT`) holds one row per email: the Master's id (never the address),
the kind, the period key (a send's `send:{uuid}`, a reassignment's `{referralId}@{assignedAt}`, a reminder's run
minute plus a per-pod run id; a retired digest's window, `2026-10-28:AM`), the referral ids the email counts, and the
delivery state. `unique (master_id, kind, period_key)` is what makes two pods enqueue
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
  Masters list. Fix the cause; the next send or reminder will email.

Never change a row's state to unstick it. A row back in `Pending` with `send_started_at` set would be sent twice.
A missed email is not re-sent: My referrals lists every referral sent to the Master.
