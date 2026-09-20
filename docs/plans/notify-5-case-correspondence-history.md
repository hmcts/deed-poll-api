# notify-5 — Case correspondence history

## Overview

Makes the record readable. This service already keeps a careful record of every email it sends — and **no
caseworker can see any of it.** The outbox row is written, frozen, drained, settled, and then never shown to
the one person who might need to act on it.

The question this plan answers, asked by Steve on 2026-09-19:

> after the email content has been generated, will the case history reflect what, including content, has been
> sent? So a caseworker can see what documents an applicant has received/downloaded and what any curated text
> said.

Today: **no**. This plan makes it **yes** for three of those four things, and is deliberately explicit that
the fourth — whether an applicant *downloaded* anything — is not obtainable from GOV.UK Notify at all, and
must not be implied by the screen.

Read first: `notify-1-email-plumbing.md` **flag 8**, which called this a scope call rather than a defect and
asked for it to be decided *"before task 5 completes, not after"*; and `deed-poll-admin`'s
`notify-2-caseworker-email-tab.md` **decision 8** (marked `[PROVISIONAL]`), **decision 11** and **flag 1**,
which is where the requirement was first written down and deferred.

**Depends on notify-1 in full.** It is otherwise independent of notify-3 and notify-4: those add templates
and documents, and this plan reads whatever is there. It is worth running **before** notify-4, not after —
notify-4 makes emails carry legal documents, and shipping that while failures are still invisible is the
exact situation notify-1's decision 7 refuses 422s to prevent.

**Scope is the api.** The tab that renders this is `deed-poll-admin`'s and needs a follow-on plan there —
flag 5.

---

## What already exists, checked rather than assumed

**More than I first reported.** In summarising this to Steve I said `NotificationRepository` had no
case-scoped finder. **That was wrong** — `findByDeedPollIdOrderByCreatedOnDesc` is at
`NotificationRepository:38`, documented in as many words as *"One case's notifications, newest first — the
Email tab's history"*, and notify-1's flag 8 already notes it *"has no caller in `src/main`"*. The query was
written for this plan before this plan existed. That makes task 2 smaller than it looks.

What a row already freezes at enqueue, and why freezing matters: a later edit to the case cannot rewrite what
an applicant was told.

| Column | What it gives history |
|---|---|
| `template`, `templateVersion` | which email, and which revision of its wording |
| `personalisation` (`jsonb`) | the resolved values **and the caseworker's curated slots** |
| `recipient` | where it went, frozen — a later address change cannot rewrite it |
| `createdBy`, `createdOn` | who sent it and when |
| `state`, `attempts`, `failureReason` | what became of it |
| `providerNotificationId` | the Notify-side id, for support |

**What is missing is not the record — it is a route to it, and the words themselves.**

---

## Decisions taken — implement these, do not revisit

1. **Store the rendered subject and body at send time, taken from the response the service already has.**
   `SendEmailResponse` carries `getSubject()` and `getBody()` — verified with `javap` against
   `notifications-java-client:6.2.0-RELEASE`. They come back **in the acceptance of the send itself**, so
   capturing them costs **no extra Notify call and nothing against the rate limit**, and gives the exact text
   Notify rendered rather than a reconstruction this service assembles from values and a template it does not
   own. This is the decision the whole plan rests on.

2. **Do not fetch the body later with `getNotificationById`.** The method exists and would work. It is the
   wrong choice three times over: it costs a Notify call per row read, it makes a court record depend on an
   external service being reachable, and the text would be subject to **Notify's** retention rather than the
   court's. History must be self-contained.

   **Correction, found checking the client rather than assuming — the stored body is plain text, and no HTML
   version of a sent email exists anywhere.** `SendEmailResponse.getBody()` returns a `String` and the class
   has no HTML accessor at all; neither does `Notification`. **Only `TemplatePreview` has `getHtml()`, and it
   returns `Optional<String>`.** So the *preview* can be shown as the applicant will see it, and the
   *history* cannot — what is stored is Notify's plain-text rendering of the template, with the
   personalisation substituted. That is still the real content and still the right thing to store, but three
   things follow and each of them would otherwise be discovered late:

   - **This is not notify-2's decision 3 problem.** That decision wraps preview HTML in a sandboxed `iframe`
     because it is HTML. A stored body is text, so it renders through Nunjucks' autoescaping in the ordinary
     way — **do not reach for `| safe`, and do not reuse the iframe**. Preserve newlines with CSS, not markup.
   - **Notify's body is Markdown-flavoured**, because that is what the template author writes. Bullets and
     headings will appear as `*` and `#`. Rendering it as Markdown would mean trusting caseworker free text
     through a Markdown renderer, which is the escaping problem decision 3 exists to avoid — so **show it as
     text**, and accept that a caseworker sees the source of a list rather than a list.
   - **The screen must not claim to be the email.** Label it as the wording that was sent, not as a copy of
     what the applicant received, because the applicant received branded HTML. Task 4 owns that sentence.

   **Storing the preview's HTML instead was considered and rejected.** It would need a `preview` call at send
   time — a second Notify call per send, against the same rate limit, for a rendering that may legitimately
   differ from the one that went out. The text is what the service can know for free and for certain.

3. **The stored body is written once and never updated**, the same argument as notify-4's decision 7. It is
   the record of a thing that happened. A re-send is a new row — notify-1's task 7 already establishes that
   shape.

4. **Content exists only for rows that were actually sent, and the model says so.** A `Pending` row has no
   body yet; a permanently `Failed` row may never have one. Subject and body are **nullable**, and the
   response distinguishes "this email has not been sent" from "this email was sent and here is what it said".
   An interface that returned an empty string for both would let a tab render an empty email as though it had
   gone.

5. **One endpoint, not two: extend the existing `GET /caseworker/cases/{id}/emails`.** It returns
   availability today. It grows a **history** alongside it. This is notify-2's stated performance rule —
   *"the tab must not make one call for history and another for applicability"* — and it resolves the first
   half of that plan's flag 1, which offered to amend notify-1 and was never taken up.

6. **Include the pre-populated slot defaults, resolving the second half of notify-2's flag 1.** Without them
   the compose page has to call `preview` merely to discover the judge's reason, and **preview costs a Notify
   call** that counts against the same rate limit as real sends (notify-2's decision 9). A default is a value
   this service already resolves; returning it is free.

7. **Which documents an email carried is read from the frozen personalisation, not from a join table.**
   notify-4's decision 9 already puts document references there, and they are frozen with everything else. A
   second table recording the same fact would be a second truth, and the two would eventually disagree. The
   read resolves those references to names and ids for display.

8. **Never expose the raw personalisation over HTTP.** It holds internal document references and resolved
   ids, and its shape is an implementation detail that notify-3 and notify-4 both change. Expose the
   **rendered** subject and body, plus a list of documents. This also keeps the response stable while the
   personalisation keeps moving.

9. **The state is reported as what it actually is, and the wording is part of this plan.** The outbox knows
   whether **Notify accepted** the send. It does **not** know whether the email was delivered, and it can
   never know whether the applicant opened or downloaded anything. So the API returns the state it holds and
   **does not use the word "delivered"** for it, because a tab that says "Delivered" will be read as "they
   have it". See decision 10.

10. **Downloads are not observable, and this plan states that where somebody will read it.** There is no
    download concept anywhere in the Notify Java client — checked — and `Notification.getStatus()` is email
    delivery status only. This is not a gap to be closed later by more engineering; it is a property of the
    platform. The javadoc on the response type says so, so that a future reader does not go looking for the
    field they assume was forgotten.

11. **Read-only, and no resend button.** notify-2's decision 10 already refuses one: retry is the outbox's
    job, and a second send is what the unique index exists to prevent. Nothing here changes that, and this
    plan must not quietly introduce a route that does.

12. **Newest first, using the finder that already exists.** `findByDeedPollIdOrderByCreatedOnDesc`. Do not
    write a second query, and do not page it — a case has a handful of emails, not a feed.

13. **Every failure a caseworker could act on is visible, and that is the point of shipping this.**
    notify-1's decision 7 refuses a blank recipient with a 422 *"precisely so a caseworker is never left
    believing an applicant was told something"*, and its flag 8 observes that every asynchronous failure
    silently reintroduces exactly that. A permanently failed email needs a phone call, and nobody can
    currently make it.

---

## Constraints

- **One migration**, for the subject and body columns. Take the next free number and say so; `V11` is
  committed, notify-1's task 7 takes `V12`, notify-3 and notify-4 take the next two. **Do not assume.**
- **A read must never call Notify.** Not for the body, not for the state, not for anything. The moment a case
  screen depends on an outbound HTTP call it becomes slow and it becomes flaky, and the whole point of
  decision 1 is that it does not have to.
- **Existing rows cannot be backfilled** — the text was never captured and cannot be recovered without
  calling Notify, which decision 2 refuses. Anything sent before this ships shows metadata only, and the
  response must represent that honestly rather than as an empty email. Flag 1.
- **Body is `text`, not `varchar`.** It is a rendered email and its length is Notify's business.
- **The body is plain text and there is no HTML alternative to store.** Decision 2's correction. The column
  holds Notify's text rendering; nothing in the client offers the HTML for a *sent* email.
- **The recipient stays frozen.** A history that resolved the recipient from the case at read time would show
  the current address for an email sent to a previous one, which is the exact failure `Notification`'s
  javadoc says freezing exists to prevent.

---

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

**Measure the baseline on this branch before Task 1** by summing `tests="…"` across
`build/test-results/{test,integration}/*.xml`, and record it next to the final count in the last task.

**A worker or container startup timeout is an environment failure, not a code failure** — re-run before
investigating.

---

## Tasks

### Task 1: Capture what was actually sent

Read first: `SendOutcome`, `NotifyEmailSender.send`, `NotificationOutbox.settle`, and decisions 1 to 4.

- `SendOutcome.Sent` grows `subject` and `body`, taken from `SendEmailResponse`. It already carries
  `templateVersion` from the same response for the same reason — notify-1's decision 14 — so this is the
  established shape rather than a new one.
- The migration adds `subject` and `body`, both **nullable**, both `text`.
- `settle` writes them on the success path only. A failure leaves them null, and that is meaningful rather
  than missing.
- **Do not log the body.** It contains an applicant's name, their old name and a caseworker's free text. The
  existing log line records ids and the template key, which is right; adding the body to it would put the
  whole email in Application Insights.
- Tests, container-free with `StubNotify`: a successful send stores the subject and body Notify returned; a
  technical failure stores neither and leaves both null; a re-send after notify-1's task 7 writes a **new**
  row with its own body and leaves the first row's untouched. Container-backed: both columns round-trip,
  including a body containing newlines and non-ASCII characters — an applicant's name may contain either.

- [ ] The rendered subject and body are captured from the send response with no extra Notify call, failures
      leave them null, the columns round-trip, nothing logs the body, and `./gradlew build` green

### Task 2: The case-scoped read

Read first: `NotificationRepository:38` — the finder already exists — and decisions 4, 7, 8 and 12.

- A method on `CaseEmailService` returning one case's correspondence, newest first, built from the existing
  finder. No new query.
- A response type per row: the template key and its human name, when, who sent it, the recipient, the state,
  the failure reason when there is one, and the subject and body **when they exist**.
- **The raw personalisation is not on it** (decision 8). The documents an email carried are resolved from it
  into a list of names and ids (decision 7), and nothing else from it is published.
- The javadoc on the response type carries decision 10's sentence about downloads, because that type is where
  somebody will look for the field.
- Tests, container-free: a case with a `Sent`, a `Pending` and a permanently `Failed` row returns all three,
  newest first, with content on the first and null on the others; a case with no emails returns an empty list
  rather than a 404; and the raw personalisation appears nowhere in the response.

- [ ] One case's emails are readable newest-first with content where it exists, documents are resolved for
      display, the personalisation is not exposed, and `./gradlew build` green

### Task 3: One call for the whole tab

Read first: `CaseworkerEmailController.emails`, `EmailTypeResponse`, notify-2's decision 8 and flag 1, and
decisions 5 and 6.

- `GET /caseworker/cases/{id}/emails` returns availability **and** history **and** slot defaults, in one
  response. The existing availability list keeps its shape so notify-2's existing work is not invalidated —
  this is additive.
- Slot defaults are resolved the same way `preview` resolves them, and **without calling Notify** — a default
  is a value this service computes. If it turns out a default cannot be produced without a Notify call, stop:
  that is a finding, not something to work around by calling Notify from a `GET`.
- **Update the OpenAPI spec and tell `deed-poll-admin`.** `OpenAPIPublisherTest` asserts paths and the
  caseworker operation count; this adds fields rather than operations, so the count should not move — confirm
  that rather than assume it.
- Tests, container-free: the response carries all three sections; a case with no history still returns
  availability; **no Notify call is made** by the `GET`, asserted rather than assumed; and the operation
  count is unchanged.

- [ ] One call returns availability, history and slot defaults, no Notify call happens on a read, the spec is
      updated, and `./gradlew build` green

### Task 4: Wording, security and full validation

Read first: `NotificationSecurityTest`, and decisions 9, 10 and 13.

- **The state's public wording is settled here.** `Sent` means *accepted by GOV.UK Notify*, and the field
  name, its javadoc and the spec description all say that rather than "delivered". A caseworker reading
  "Delivered" concludes the applicant has it; the service does not know that.
- **So is the wording for the body**, for the same reason and following decision 2's correction: what is
  stored is Notify's **plain-text** rendering, and the applicant received branded HTML. The spec description
  for the field says so, so that `deed-poll-admin` labels it as the wording that was sent rather than as a
  copy of the email. A screen that presents text as though it were the email invites "that is not what they
  got".
- `NotificationSecurityTest` extended rather than duplicated: history for one case never contains another
  case's row — the finder is case-scoped, so this asserts the scoping rather than hoping for it — and a
  resolved document list never names a document belonging to another case.
- A row whose `deedPollId` does not match the path is unreachable by construction; add the test that proves
  it stays that way.
- Confirm the whole chain by hand once against a real case: send, drain, read, and check the body shown is
  the body Notify rendered.

- [ ] State wording says what the service actually knows, cross-case leakage is covered by test, the chain is
      confirmed end to end, `./gradlew build` green from cold, checkstyle clean on five source sets, and both
      totals recorded

---

## Deliberately not in this plan

- **Delivery callbacks.** Notify can POST status updates to a callback URL, which is the only way "delivered"
  ever becomes knowable. It needs a **publicly reachable endpoint and a bearer token**, which is an infra
  change in `deed-poll-infra` as much as a code change, and notify-0 already lists it under *not needed yet*.
  It is genuinely additive — `providerNotificationId` is already stored — so it can be a later plan without
  rework. **The reason it is not here** is that the failure a caseworker must act on is a *permanent
  refusal*, and that is already recorded; "delivered" is a refinement on top of a problem this plan solves
  without it. Flag 2.
- **Download tracking.** Not deferred — **impossible**. Decision 10.
- **Resend.** notify-2's decision 10 and notify-1's unique index both refuse it.
- **The Email tab itself.** `deed-poll-admin`'s, and it needs its own plan — flag 5.
- **Backfilling history for emails already sent.** Cannot be done without calling Notify. Flag 1.

---

## Flags for Steve — pick these up on return

1. **Anything sent before this ships has no stored body, and never will.** Decision 2 refuses to fetch it
   from Notify, so those rows show metadata only. In practice this is likely to be nothing at all — notify-1
   is not yet in production — but if any real email has gone out by the time this runs, the tab needs wording
   for "sent before we started keeping the text" rather than showing a blank email.

2. **Delivery callbacks: worth doing, and worth doing separately.** Without them the screen can only ever say
   *accepted by Notify*. With them it could say delivered, or bounced — and a bounced email to a wrong
   address is a real case-handling event that currently vanishes. It needs a public endpoint and a token, so
   it is a joint plan with `deed-poll-infra`. **My view: do it, but after this** — this plan makes failures
   visible, which is the urgent half.

3. **Should a caseworker see the body of an email that failed, or one still pending?** There is no body for
   either (decision 4), but there *is* a preview that could be rendered from the frozen personalisation. That
   would cost a Notify call on a read, which the constraints forbid — so the honest answer is "no, and the
   screen says why". Worth confirming that is acceptable, because "I can see it failed but not what it would
   have said" is a reasonable thing for a caseworker to be annoyed by.

4. **This plan should run before notify-4, not after.** notify-4 attaches legal documents to emails. Shipping
   that while a permanent failure is still invisible means an applicant who never receives their deed forms
   and a court that cannot tell. The ordering is notify-1 → **notify-5** → notify-3 → notify-4, and only the
   last two are blocked on notify-0's template authoring anyway.

5. **`deed-poll-admin` needs a follow-on plan to consume this**, and notify-2 should be amended rather than
   rewritten: its decision 8 is already marked `[PROVISIONAL]` against exactly this, and its decision 11
   defers the content half with *"it is api's data to serve"*. Once this ships, both can be resolved and the
   `[PROVISIONAL]` marker removed. **Carry decision 2's correction across with it** — notify-2's decision 3
   is written for preview HTML, and history is text, so the two must not share a renderer.

6. **The caseworker sign-off question from notify-2's flag 5 is still open**, and history makes it visible in
   a new way: if every email ends `Kind regards,` with nothing after it, that will now be on a screen where
   somebody notices. Worth resolving with the content owner before this ships rather than after.
