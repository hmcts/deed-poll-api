# Gazette delivery and recovery

A batch sends one email with the required adult/minor spreadsheet download links.
`Sent` means Notify accepted the email, not that LG received or published it. The
batch UUID is the Notify reference and the identifier to use for investigation.

## Configuration and activation

`GAZETTE_ENABLED` defaults to false. If an environment previously enabled it only
for previews, turn it off before deploying this slice: it now also permits new sends.
Preview and new admission need it plus:

- The recipient saved through the admin Gazette setting (with its current revision).
- `NOTIFY_API_KEY` and the existing Notify API URL configuration.
- `NOTIFY_TEMPLATE_GAZETTE_ADULT`, `NOTIFY_TEMPLATE_GAZETTE_MINOR` and
  `NOTIFY_TEMPLATE_GAZETTE_MIXED`: provision all three for rollout. Each request
  checks and freezes its required variant only.
- The existing `NOTIFY_EMAIL_REPLY_TO_ID` and `NOTIFY_DOCUMENT_RETENTION_WEEKS`
  (1–78 weeks). Download email confirmation is always true.

The agreed template fields are `case_count`, `adult_spreadsheet` and
`minor_spreadsheet`. Adult-only/minor-only variants omit the absent file field;
the mixed variant has both. The initial subject is “Enrolled deed polls for
publication in The London Gazette”; copy remains editable in Notify. The wording
draft is `deed-poll-documentation/plans/active/gazette-email-wording.md`. The court
reply-to mailbox is `kbdeedspoll@justice.gov.uk`; it is not the LG recipient default.

Before enabling delivery, record the provisioned identifiers, retention decision,
monitored support team/escalation contact, evidence-access/retention policy and
external review. Validate all three variants using synthetic cases and a controlled
test recipient, including both download links and recipient email confirmation.
Confirm that deployment egress permits direct HTTPS to Notify and cannot replay
POSTs. No explicit/ambient JVM HTTP proxy is used by the Gazette transport. Local
WireMock evidence does not prove deployed egress behaviour or provision templates.

Disable new sends with `GAZETTE_ENABLED=false`. Existing requests remain readable;
identical submissions still return their status. Recovery continues independently.
Changing recipient or template configuration cannot redirect an admitted request:
recipient, template ID, reply-to ID and retention are frozen at admission.

## Read the state before acting

| State | Meaning | Action |
|---|---|---|
| Preparing | Admitted; no send authority recorded | Wait/check the same request. After lease expiry recovery marks NotSent. |
| Sending | Send authority committed; email may have been accepted | Wait/check status. Never create a replacement request. |
| Sent | Provider acceptance and local case/history/notes committed | No resend. Delivery/publication are not established. |
| NotSent | Preparation failed, pre-send lease expired, or explicit 400/403/429 refusal committed | Correct the cause, then start a fresh preview/confirmation with a new key. The original key remains terminal. |
| Uncertain | Acceptance cannot be safely settled or excluded | Investigate; case reservations remain held. No automatic resend or repair endpoint exists. |

The owner lease is five minutes. Every minute the recovery scanner selects at most
25 expired Preparing/Sending rows, oldest lease then batch UUID, and settles each
in its own transaction. It never generates files or calls Notify. Multiple replicas
recheck under the request row lock, so only one writes the terminal state/failure
notes. A failed transaction leaves its row eligible for a later pass. A sufficiently
large backlog can take multiple passes; these reads/notes are not a monitored alert.

An expired Sending request becomes Uncertain even if the process died before making
its HTTP call. This conservative outcome is deliberate. A late accepted receipt can
be retained while Uncertain; it cannot automatically release reservations or change
the request to Sent. Expiry alone does not stop the original process.

## Investigating uncertainty

1. Use the admin-only request list/detail and complete membership endpoints to
   capture batch ID, state, submission time, frozen recipient and references.
   After a lost HTTP response, repeat the original key **and exact body**, or read
   the known batch ID. A 200 response is terminal, not necessarily successful.
2. An authorised support operator correlates the batch UUID and any provider ID
   with Notify records. Missing provider search results do not prove non-acceptance.
   The API does not expose provider IDs; authorised evidence access is an operational
   prerequisite. If a receipt could not be persisted, the correlation log contains
   only batch UUID, provider UUID and template version, without email content.
3. Preserve the investigation reference and findings. Do not reset state, send-start,
   owner, request key or reservations with SQL. No UI or scheduled resend is supplied.
   Proven acceptance may justify separately reviewed local reconciliation without
   sending; proven non-acceptance may justify separately reviewed reservation release.
   Fence/quiesce the original worker before either action.
4. Until that recovery is approved and performed, keep the request stopped. Never
   include applicant details, spreadsheets, Notify file values or keys in logs/tickets.

The ledger stores file names, counts, byte sizes and SHA-256, not workbook bytes or
full field snapshots. Frozen references support audit, not historical regeneration.
There is no file archive/export or automatic provider-lookup facility in this slice.
