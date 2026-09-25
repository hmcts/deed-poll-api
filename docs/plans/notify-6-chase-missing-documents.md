# notify-6 — chase missing documents

## Overview

Adds a fifth GOV.UK Notify email: a caseworker-written chase when an application is waiting for further
documents or information. The caseworker chooses it from the Email tab, writes what is still needed, previews
the real Notify rendering and sends it. Every attempt then appears in the correspondence history built by
notify-5.

**Planning assumption agreed by Steve, 2026-09-20:** treat the approved Notify template and its provisioning
as available. The engineering contract is:

- template key `chase-missing-documents`;
- one required caseworker slot, `documents_required`;
- the surrounding wording, branding and instructions live in Notify;
- the email invites a reply and therefore uses the reply-to configuration introduced by notify-3.

Task 0 verifies those four facts against the provisioned template before code is written. A mismatch changes
this plan; it is not handled by silently sending unused or missing personalisation.

This is an api plan. notify-2's Email tab is deliberately data-driven: a template, its slots and defaults come
from `GET /caseworker/cases/{id}/emails`. It should display this template without a feature-specific page.
The one cross-cutting contract addition is an idempotency key for every send, explained below and consumed by
notify-2.

**The Gazette notice remains deferred.** It has no agreed engineering contract in this plan and must not be
folded into the chase merely because both were previously missing templates.

---

## Decisions taken — implement these, do not revisit

1. **Applicability is the derived case state `AwaitingFurtherDocs`.** This email is case-driven, not
   referral-driven. It is offered when `DeedPoll.state == DeedPollState.AwaitingFurtherDocs`, which is derived
   from `awaitingFurtherDocsInfo` by the existing state cascade. Do not inspect referrals, correspondence
   history or individual document rows to infer it.

2. **The caseworker writes one required slot: `documents_required`.** It names the missing documents or
   information in applicant-facing prose. Add `EmailSlot.DOCUMENTS_REQUIRED`, required and capped at 4,000
   characters—the same long-prose ceiling as a judicial direction, but declared as an email-content constant
   rather than coupled to `ReferralSchemas`. Blank is missing, unknown keys are refused, and resolved case
   values remain impossible for a caller to override.

3. **The template resolves only the common applicant/case values.**
   `MissingDocumentsPersonalisation` resolves `reference_number`, `online_application_reference`,
   `old_forename` and `old_surname`, then the required slot. Every key is present in deterministic order and
   nullable case values become empty strings, as the existing personalisation does. There is no referral,
   judge's direction, refund branch, attachment or document id.

4. **A case-driven template makes referral context optional, not fake.** Generalise the internal composed-email
   model so a template can have either a case-only cause or a referral cause. Do not manufacture a referral,
   select an unrelated historical one or overload a zero UUID. Existing referral templates continue storing
   their real `referralId`; this template stores null, as V11 designed.

5. **Multiple chases are legitimate.** A case can remain in `AwaitingFurtherDocs` after one reminder and the
   caseworker may need to chase again. The referral exclusion-group index must not be widened to make one chase
   per case, and correspondence history must preserve every attempt newest first.

6. **A deliberate repeat and a double-click are separated by an explicit request id.** Add a required
   `Idempotency-Key` UUID header to the send operation. One compose journey creates one UUID and reuses it for
   every retry of that final POST; a later compose journey gets another. Persist it as a non-null
   `requestId` on every notification, backfilling existing rows from their own `id`, with a unique index on
   `("deedPollId", "requestId")`. Thus the same request for the same case answers with its existing receipt
   while a later chase remains a new row; a caller cannot create a cross-case collision with a chosen UUID.

   This is necessary because neither payload equality nor a time window can express intent: the same text may
   be a valid reminder next week, and two clicks milliseconds apart may have different timestamps. The
   current referral uniqueness remains a separate business constraint.

7. **One native enqueue handles both unique rules without hiding the reason.** After notify-3, an insert can
   conflict on `requestId` or on the referral exclusion group. Use `ON CONFLICT DO NOTHING` without a target,
   then resolve zero rows in this order:

   1. the case-scoped row with the same `requestId` — idempotent success only when its template, recipient and
      frozen personalisation match; otherwise 409 because one key was reused for a different request;
   2. the blocking row for the referral exclusion group — same-template repeat or contradictory-template 409;
   3. otherwise an explicit internal error, because a database constraint refused a row the service cannot
      explain.

   Keep the index predicate that lets a permanent Notify refusal be replaced. A retry of the *same request
   id* still returns that failed row; the caseworker starts a new compose journey to make a corrected send,
   preserving the failed attempt as history.

8. **Reply-to provisioning is per-template and mandatory here.** `CHASE_MISSING_DOCUMENTS` has
   `invitesReply = true`, so availability is unprovisioned without both its template UUID and
   `emailReplyToId`. The sender uses the five-argument Notify overload introduced by notify-3.

9. **No new endpoint and no operation-count change.** The existing availability, preview and send operations
   serve this template. OpenAPI changes are the required `Idempotency-Key` header and the template/slot values
   returned at runtime. Preserve the branch baseline count rather than hard-coding 33: it is 33 before
   notify-3b and 34 after notify-3b adds its confirmation operation.

10. **The admin remains generic.** notify-2 must render the template and required slot from the overview
    response rather than switch on `chase-missing-documents`. Its compose journey generates and preserves the
    idempotency UUID, and its adapter sends the header for every template. No special chase route, form or
    wire type is added.

---

## Dependencies

- notify-1 and notify-5 are merged.
- notify-3 must be merged first because this plan consumes its exclusion-group and reply-to model.
- notify-0 supplies the real template UUID, content and reply-to. For planning they are assumed available;
  Task 0 still verifies the contract rather than treating an assumption as evidence.
- notify-2 can be implemented afterwards and consumes the final OpenAPI contract, including the
  `Idempotency-Key` header.

notify-3b is not a dependency. Tracking an applicant's response to a non-publication decision and chasing
missing application documents are separate processes.

---

## Constraints

All repository constraints apply: Java 21, `-Werror`, five checkstyle source sets, immutable Flyway
migrations, mixed-case quoted columns, DTOs rather than entities, no live Notify calls in tests, and
container-free coverage for rules CI must enforce.

Additional constraints:

- **Take the next free migration number.** notify-3 plans V14, but notify-3b may land before this plan. Inspect
  the migration directory and record the chosen number; never reserve or rewrite one from this document.
- **The migration must tolerate rolling deployment.** Add nullable `requestId`, backfill it from `id`, set
  `DEFAULT gen_random_uuid()` (already used by V7) for old application instances during overlap, then make it
  non-null and unique with `("deedPollId", "requestId")`. The implementation task must prove that exact
  sequence against PostgreSQL rather than relying on the conceptual sketch here.
- **Do not alter `CaseState` or `DeedPollState`.** This feature consumes `AwaitingFurtherDocs`; it does not
  create or derive a state.
- **Do not expose `requestId` in correspondence history.** It is transport idempotency, not caseworker-facing
  correspondence.
- **Do not log slot text, personalisation, applicant details, API keys or reply-to configuration.**
- **Do not attach documents.** This email asks for incoming material; it sends none.
- **Do not implement the Gazette notice.**

---

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

Measure `test`, `integration` and `INTEGRATION_TESTS_ENABLED=false` baselines before Task 1, then record final
totals in Task 4. **A worker or container startup timeout is an environment failure, not a code failure** —
re-run before investigating.

**Baseline measured 2026-09-24 during Task 0, on `notify-6-chase-missing-documents` at `30bac2e`** — before any
code in this plan, from `build --rerun-tasks` so nothing was `UP-TO-DATE`, counted off
`build/test-results/*/TEST-*.xml` immediately after each run:

| Run | `test` | `integration` | Wall clock |
|---|---|---|---|
| `./gradlew build` (Docker) | **2610**, 0 failures / 0 errors / 0 skipped, 331 result files | **12**, all green | 4m 10s, 24 of 24 tasks executed |
| `INTEGRATION_TESTS_ENABLED=false ./gradlew build` (what CI runs) | **1657**, all green | **not run at all** | 1m 7s, 23 tasks |

**The 953-test gap is the number to hold on to, and `build.gradle:136` is why:** Docker-free, `test` does
`excludeTags 'requires-docker'`, and `tasks.check.dependsOn(integration)` is only wired up when the flag is on
(`build.gradle:182`). So CI runs neither the 953 container-backed unit tests nor the 12 integration ones — which
is the concrete reason Task 2's container-backed migration proofs need container-free companions, and the reason
the two runs differ by a task count as well as a test count.

**A trap the "measure, do not read the exit code" note does not quite cover.** After the Docker-free run,
`build/test-results/integration/` still held the previous run's 12 result files even though the task never ran.
Stale files are not marked stale — so a count taken there proves nothing about the run that just finished.
Delete the directory or compare the task list, rather than trusting the XML's presence.

**One unreproduced red, recorded rather than quietly dropped.** The first Docker-free `build --rerun-tasks`
reported `BUILD FAILED` after 4m; an immediately-following identical invocation passed in 1m 7s. The failure
detail was lost to a `tail -8` on the captured output, so the cause is genuinely unknown — noted here because
"it passed on the retry" is the correct action under the environment-failure rule above but not evidence that
nothing is wrong. If a Docker-free run fails again, capture the whole log before re-running.

Notes so no task rediscovers them:

- **Measure, do not read the exit code.** A run with zero tests selected also exits 0, and a second
  `gradle test` **wipes `build/test-results/`** — so count from the XML immediately after each run, and use
  `--rerun-tasks` when the count is the point. A `BUILD SUCCESSFUL` with no result file means the task was
  `UP-TO-DATE` and ran nothing.
- A `--tests` filter matches **method** names as well as class names, so filter result files by class name for
  an apples-to-apples figure.
- **`@Nested` inner classes each get their own result file**, which is why a class count and a file count differ.
- **`checkstyleTest` fails the build on `severity="warning"`**, so a "violations by severity: [warning: n]" line
  is a failure rather than advice. The rule that bites is the method-name pattern `^[a-z][a-z0-9][a-zA-Z0-9_]*$`:
  a single-letter first word followed by a capital is rejected, so `aCaseworkerMaySend…` fails where
  `anApplicantIsChased…` passes.
- `python3` is **not** installed in this devcontainer; `node` is.
- The `integration` source set needs Docker and is **excluded in CI** (`Jenkinsfile_CNP` sets
  `INTEGRATION_TESTS_ENABLED=false`), so a test that only ever runs there is not protecting the pipeline.

**The heading above was `## Validation commands`, lowercase, until 2026-09-24.** Every plan this repo has
actually executed uses `## Validation Commands`, and a gate that is not recognised is no gate at all — a task can
then commit red and the run walks forward with nothing warning anybody. Corrected before the first run rather
than discovered during it.

---

## Tasks

### Task 0: Verify the provisioned template contract

> **The template now exists and has already been read back out of Notify — 2026-09-24. This task is therefore a
> recording task, not an investigation, and it must not send anything.**
>
> | | |
> |---|---|
> | Template UUID | **`a67e7dd1-0f8c-4366-a1de-ca5fbc24ec98`**, version 1, non-prod service. The value `NOTIFY_TEMPLATE_CHASE_MISSING_DOCUMENTS` takes — configuration, not a secret, and per-service |
> | Reply-to id | **`aabceef1-63e4-4414-9554-a24b0be967d7`** for `KBDEEDSPOLL@justice.gov.uk`, registered and verified. `NOTIFY_EMAIL_REPLY_TO_ID`, **one value for the whole service** — so it serves ④ as well |
> | Placeholders | **Exactly the five this task expects**, confirmed character-for-character |
> | `documents_required` | Required — it sits unconditionally in the body with no `((…??…))` around it, so Notify refuses a send that omits it |
> | Wording | Unchanged from the drafted content, `King's Bench Division` sign-off included |
>
> **So nothing in this plan needs amending** — the bullet about amending before Task 1 has been discharged rather
> than skipped.
>
> **The evidence, and where the prose lives:** read via `GET /v2/template/{id}` against
> `api.notifications.service.gov.uk` with the non-prod key. Subject, body, the five-placeholder contract and the
> sign-off decisions are in the sibling repo at
> `../deed-poll-admin/.business_context/notify-templates/chase-missing-documents.md`, which is the authored
> source — ⑤ was the only one of the six templates with **no supplied `.docx`**. The two ids are also in
> `../local-notes/deed-poll-api/notify-nonprod.env` for a local run.
>
> **Two cautions.** The **reply-to id is recorded on Steve's word, not verified** — Notify publishes no read-only
> API for reply-to addresses — so do not claim it as read-back evidence. And **`charts/deed-poll-api` sets neither
> id**, so AAT will still report ⑤ unprovisioned after this plan: that is a separate chart change and explicitly
> not this plan's job.

- Record the real template UUID configuration key and confirm the template placeholder list is exactly:
  `reference_number`, `online_application_reference`, `old_forename`, `old_surname`,
  `documents_required`.
- Confirm `documents_required` is required plain text and sits in wording that remains usable with up to
  4,000 characters. The 4,000-character validation cap is the api's, not a Notify template constraint.
- Confirm the wording asks the applicant to reply and the configured reply-to is the monitored inbox.
- Send no real email. Use Notify's template metadata/UI and record the evidence in the plan and provisioning
  checklist.
- If any placeholder or reply behavior differs, amend the plan before Task 1 rather than translating between
  two contracts in code.

#### Recorded 2026-09-24 — the evidence, and what each line of it rests on

No email was sent and no source file was changed. Each row below was read out of a file, not recalled, and the
last column says whether it is Notify's own answer or somebody's statement — because the two are not the same
strength of evidence and Task 4 has to be able to tell them apart.

| Fact | Value | Read from | Strength |
|---|---|---|---|
| Template UUID | `a67e7dd1-0f8c-4366-a1de-ca5fbc24ec98`, version 1, type `email`, non-prod service | the read-back table in `../deed-poll-admin/.business_context/notify-templates/chase-missing-documents.md` §"Authored 2026-09-24", and the same value in `../local-notes/deed-poll-api/notify-nonprod.env` | **Notify**, via `GET /v2/template/{id}` |
| Placeholders | exactly `documents_required`, `old_forename`, `old_surname`, `online_application_reference`, `reference_number` — no sixth, none missing | same read-back table; the authoring contract table in §"exactly five placeholders" lists the identical five | **Notify** |
| `documents_required` requiredness | required — it sits unconditionally in the body with no `((…??…))` wrapper, so Notify refuses a send that omits it | the template body quoted in that file, line-for-line with the read-back | **Notify** |
| Reply invitation | "If anything above is unclear, or you have already sent these to us, **reply to this email** and we will help." — and the body also names `KBDEEDSPOLL@justice.gov.uk` in prose, so documents can arrive from another account or a forward | the quoted body | **Notify** |
| Reply-to id | `aabceef1-63e4-4414-9554-a24b0be967d7` for `KBDEEDSPOLL@justice.gov.uk` | `NOTIFY_PROVISIONING_REQUEST.md` "A reply-to address" §, and `notify-nonprod.env` | **Steve's word** — Notify publishes no read-only API for reply-to addresses |

**The configuration key, confirmed from this repo's code rather than from the naming convention.**
`NotifyProperties.isProvisioned` resolves `templateId(template.key())` and `templateId` is a lookup into the
`notify.templates` map, so the property key *is* the `EmailTemplate` key: Task 1 adds
`chase-missing-documents: ${NOTIFY_TEMPLATE_CHASE_MISSING_DOCUMENTS:DEED_POLL_PLACEHOLDER}` beside the two
already in `application.yaml`. That environment-variable name is the one `notify-nonprod.env` already exports,
so a spelling of it that merely looks reasonable would leave a local run silently unprovisioned.

**The 4,000-character cap is ours and the wording survives it.** Notify imposes no length on a placeholder;
`((documents_required))` stands alone on its own line under a colon-introduced sentence, so a single sentence
and a Markdown bullet list both read correctly — which is the property the cap needs, not a Notify constraint
to be discovered.

**So the amendment bullet above is discharged, not skipped: nothing in this plan changes.** Every assumption
Task 0 existed to test — the template key, the one required slot, the surrounding wording living in Notify, and
the reply-to dependency — matched.

Two things deliberately *not* claimed, so Task 4 finds them stated rather than inferred:

- **The reply-to id is unverified.** A wrong id is a loud 400; a *blank* one is silent, which is why
  `NotifyProperties.isProvisioned` gates on it. It will be proven by the first real send.
- **`charts/deed-poll-api` sets neither id** — `values.yaml` names only
  `NOTIFY_TEMPLATE_APPLICATION_REJECTED` — so AAT will still report ⑤ unprovisioned after this plan. That is a
  chart change and explicitly not this plan's job.

One cosmetic mismatch, recorded because it will confuse whoever next opens the Notify UI: the template is
**named `case_documents`** there, where ③ is named `application-rejected`. The api resolves by UUID, so nothing
breaks.

- [x] the provisioned template, placeholder names, required slot and reply-to behavior are recorded from
      evidence rather than assumption, with no implementation change

### Task 1: Add the template, personalisation and applicability

- Add `EmailSlot.DOCUMENTS_REQUIRED` with its requiredness and cap.
- Add `MissingDocumentsPersonalisation` and
  `EmailTemplate.CHASE_MISSING_DOCUMENTS("chase-missing-documents", "Chase missing documents", ...)`, with
  no document keys and `invitesReply = true`.
- Add its template UUID property using the existing unprovisioned-placeholder convention.
- Generalise compose/cause so the case-only template carries no referral while existing referral templates
  retain theirs.
- Offer it exactly in `AwaitingFurtherDocs`; publish a specific reason when the case is in another state.
- Slot defaults contain an empty `documents_required`; preview and send both use the same resolver.
- Container-free tests cover every resolved key, blank/null case values, required/unknown/overlong slot
  failures, applicability in every state, no referral lookup, applicant recipient and no raw case field
  override.

#### Recorded 2026-09-24 — what the generalisation actually cost, and the two decisions it forced

Green from `./gradlew build` with Docker (**2666** `test`, 0 failures, 336 result files; **12** `integration`, all
green) and from `INTEGRATION_TESTS_ENABLED=false ./gradlew build` (**1713** `test`, all green, `integration` not run
— the directory was deleted first, so the count is this run's). **+56 unit tests against Task 0's baseline**, of
which 23 are the new `chasing missing documents` nest; the integration count is unchanged, which is expected — this
task added no schema and no statement.

Four new files, all in `notify/`: `MissingDocumentsPersonalisation`, `EmailCause`, and the two test classes.
`EmailExclusionGroup.DOCUMENT_CHASE` is a new member rather than a reuse of `REFERRAL_OUTCOME` — sharing it would
make a chase and a refusal mutually exclusive on the same referral, which decision 5 forbids — and it is
deliberately a group no index can act on, because a chase's rows carry no `referralId` and the V11 index is partial
on `"referralId" IS NOT NULL`.

**Two things the plan did not anticipate, both of which would have failed silently.**

1. **`overview` composed its slot defaults through `EmailSlots.parse`.** That works only while every required slot
   pre-populates from something — `judges_reason` has the direction. `documents_required` has nothing, so an
   unchanged `overview` would have answered **400 for the whole Email tab on exactly the cases the chase is for**,
   and no existing test would have said so. `EmailSlots.defaults` is now the one resolver for "what a form opens
   with", `parse` remains the only gate a preview or send passes, and the pre-population rule itself is asked once
   (`prePopulated`) so the two cannot disagree about what a caseworker was shown. An over-long migrated direction is
   now *returned* by `defaults` rather than refusing the tab — the refusal says "shorten it", which is only
   actionable if the form shows the text.
2. **The required-slot refusal was hard-coded to the judge.** `judges_reason is required: this email quotes the
   judge…` addressed to `documents_required` would send a caseworker to the referral tab for a field that has no
   referral. Requiredness is now *spelled as* that sentence on `EmailSlot` — `required()` is "there is a sentence",
   `OPTIONAL` is the empty one — so a required slot with no reason to give a caseworker is not expressible, and a
   boolean and a message cannot drift apart. (Enum constants must qualify the constants they pass:
   `EmailSlot.MAX_EMAIL_PROSE_LENGTH`, or javac calls it an illegal forward reference.)

`MAX_EMAIL_PROSE_LENGTH = 4000` is declared on `EmailSlot`, equal to `ReferralSchemas.MAX_DIRECTIONS_LENGTH` and
deliberately not read from it: the equality is two independent decisions agreeing, and a dependency would let a
change to what a judge may record change what a caseworker may write to an applicant.

Three existing tests asserted "every template applies" over a referral fixture and now name the two referral
templates explicitly; `writesOneNotePerEmail` gained a fixture that satisfies all three at once and a
`minimumSlots` switch, so a fourth template with an un-pre-populatable slot fails to compile there rather than
failing at runtime. `CaseworkerEmailControllerTest` counted a template's slots off `EmailSlot.values().length`,
which stopped being the same number. The checkstyle method-name trap the Validation Commands section warns about
bit twice (`aCaseWithNoStateIsNotChased`, `aRefusalIsIrrelevantToIt`).

Not done here, and not hidden: `send` still resolves a zero-row enqueue by reading the referral-keyed blocking row,
so a chase that somehow conflicted would raise the "refused by the unique index, but no such row is readable"
`IllegalStateException`. It is unreachable today — a null `referralId` cannot collide with the only index that
exists — and Task 2 is what generalises that resolution.

- [x] the case-driven template composes without a fake referral, is offered only in
      `AwaitingFurtherDocs`, requires bounded caseworker text, uses the reply-to provisioning rule, and the
      build is green

### Task 2: Make repeatable sends idempotent

- Add the next Flyway migration for non-null `requestId`, existing-row backfill and uniqueness, with the
  rolling-deployment sequence recorded in its comments.
- Map the column on `Notification` without publishing it in history DTOs.
- Add the required `Idempotency-Key` UUID header to the send controller and pass it through the service to
  enqueue.
- Generalise the native insert and zero-row resolution exactly as decision 7 specifies. Keep notify-3's
  exclusion-group behavior and V12's null-safe permanent-failure predicate.
- Container-backed tests prove: existing rows backfill uniquely; the same case/request id creates one row and
  returns it; reusing it with different content is 409; the same UUID on another case does not collide;
  different request ids create multiple chase rows; concurrent equal ids create one row; referral exclusion
  still blocks contradictory templates; and permanent refusal replacement still works.
- Container-free tests prove malformed/missing header responses, receipt reuse and the distinction between
  request-id conflict and exclusion-group conflict.

#### Recorded 2026-09-25 — the migration number, what the two keys cost each other, and three fixtures nobody expected

Green from `./gradlew build` with Docker (**2702** `test`, 0 failures / 0 errors / 0 skipped, 340 result files;
**12** `integration`, all green; 10m 20s, 24 of 24 tasks executed) and from
`INTEGRATION_TESTS_ENABLED=false ./gradlew build` (**1729** `test`, all green, 236 files; `integration` never ran —
`build/test-results/` was deleted first and the directory is *absent* afterwards, which is the stale-file trap the
Validation Commands section warns about, checked rather than assumed). **+36 unit tests against Task 1**, of which
+16 are Docker-free; the gap is now **973**.

**The migration is V19, not the V14 the plan's Constraints section names.** V14 through V18 were already committed,
so the next free number was taken and recorded, as the rule says.

**`ON CONFLICT` lost its target, and that is the one substantive design change.** With two unique indexes able to
refuse the same insert — V19's `("deedPollId", "requestId")` and V15's partial `("referralId", "exclusionGroup")` —
`ON CONFLICT` can infer only one of them, and the other would then raise a `DataIntegrityViolationException` that
marks the caseworker's transaction rollback-only. So the statement is a bare `ON CONFLICT DO NOTHING`. What that
gives up is stated in the javadoc rather than glossed: a target makes Postgres *verify* a matching index exists, so
without one an index quietly dropped would make the statement stop deduplicating rather than fail. That guarantee is
bought back by assertion — `NotificationRepositoryTest` now executes the statement for every conflict either index
can produce, and it is the only place either clause runs.

**Three fixtures the plan did not anticipate, each of which failed loudly rather than silently — the good outcome.**

1. **`requestId` has no `@PrePersist` fallback, deliberately, so every entity-side writer had to be told.** The
   column is `NOT NULL` with a database default that exists *only* for the rolling-deployment window, so a row
   persisted through the entity with no request id fails. That caught `EntityMappingTest` (two fixtures),
   `NotificationRepositoryTest.owed`, `NotificationOutboxConcurrencyTest.owedNotification` and
   `ConfirmationConcurrencyTest`'s two direct `enqueue` calls. The alternative — mirroring `nextAttemptAt`'s
   `@PrePersist` — would have let a future non-send writer invent a request id for a request nobody made.
2. **The idempotent-match comparison cannot be over the serialised JSON.** `jsonb` does not preserve key order, so
   the stored map comes back in whatever order Postgres hands over; a text comparison would refuse a caseworker's
   own retry as a reused key, which is a 409 on the one path that exists to answer 200. The resolved map is
   therefore round-tripped through Jackson and compared as a map, so both sides have been through the same
   deserialiser and neither side's Java types are what is being asserted. `keyOrderIsNotPartOfTheComparison` pins it.
3. **`MissingRequestHeaderException` had no handler, so a client that omitted the header got a `ProblemDetail`.**
   Every refusal this service reasons about is an `ApiError`; one that is not means two shapes to parse for one
   status, which is the argument `unreadableBody()` already makes. `DomainErrorHandler.missingHeader` is narrow on
   purpose — one header is required anywhere in the service, and catching the parent
   `ServletRequestBindingException` would take in signals from paths that answer correctly already.

**The method-name checkstyle trap bit six times** (`aReusedRequest…`, `aSecondJourney…`, `aPermanentlyRefused…`),
exactly as the Validation Commands section predicts: a single-letter first word followed by a capital is rejected.

**How the migration is proved, since no build has rows to migrate.**
`NotificationRequestIdMigrationTest` rebuilds the pre-V19 table in a schema of its own —
`CREATE TABLE … AS SELECT * … WHERE false` off the real table, then `DROP COLUMN "requestId"` — points
`search_path` at it, and executes the committed file verbatim. That is the only way to run an `ADD COLUMN` whose
column Flyway has already added, and it means the backfill, the `NOT NULL`, the `gen_random_uuid()` default and the
index are each asserted as effects of the file rather than of a restatement of it. Two rows on one case, because a
backfill from anything but a per-row value would make the index impossible to build in the first environment that
had chased anybody twice.

**Container-free where CI can reach it, container-backed where only Postgres can answer.**
`CaseEmailServiceTest.ResolvingARefusedInsert` states the *order* the two indexes are read in and the answer for
each — a resolution that asked the exclusion group first would tell a caseworker retrying their own POST that the
rejection email was already requested, and no database test would notice.
`CaseworkerEmailIdempotencyHeaderTest` is a standalone `MockMvc` — the one place in this repo that is not the whole
context, and it claims nothing about security, which is why that is defensible: it asserts argument binding and the
refusal's shape, and the container-backed class still owns authentication. It also asserts the *declaration* by
reflection, because `required = false` or a `String` parameter would keep every request-level assertion passing.

**One thing deliberately not duplicated.** "Referral exclusion still blocks contradictory templates" is proved where
it already was — `NotificationRepositoryTest.enqueuesNothingForTheCounterpartTemplate` against the statement and
`CaseworkerEmailControllerTest` over HTTP — both still green under the retargeted `ON CONFLICT`. A third fixture for
it would have been a third thing to keep in step.

- [x] one compose journey cannot enqueue twice, later chase journeys can, all prior referral uniqueness and
      permanent-failure behavior survive, migration rollout is safe, and the build is green

### Task 3: History, security and admin contract

- Prove multiple chase attempts appear newest first with their own state, author, recipient, subject and body.
- Extend `NotificationSecurityTest`: only declared personalisation keys leave the service; recipient is
  `applicantEmail`; another case's request id or notification cannot be read; slot text and rendered content
  are absent from logs; `requestId` is absent from public DTOs.
- Extend the real HTTP/PostgreSQL/StubNotify controller test through availability → preview → send → drain →
  history for the case-driven template, including the configured reply-to.
- Update OpenAPI tests for the required `Idempotency-Key` header while retaining the branch's caseworker
  operation count.
- Add a dated correction to notify-2: its adapter supplies the header for every send, the compose journey
  creates one UUID and preserves it through preview, validation failure and retry, and a fixture with this
  template renders from metadata without template-specific code.

#### Recorded 2026-09-25 — what the published contract was missing, and the one disclosure the header creates

Green from `./gradlew build` with Docker (**2712** `test`, 0 failures / 0 errors / 0 skipped, 341 result files;
**13** `integration`, all green; 35m 32s, 24 of 24 tasks executed) and from
`INTEGRATION_TESTS_ENABLED=false ./gradlew build` (**1735** `test`, 0 skipped, 236 files; `build/test-results/`
held **only** `test/` afterwards, so the stale-file trap was checked rather than assumed). **+10 unit tests
against Task 2 and +1 integration**, of which 6 are Docker-free; the gap is now **977**.

**The header was published and undescribed, which is the one substantive source change this task made.** springdoc
reflects the `@RequestHeader` faithfully — `required: true`, `string`/`uuid` — and that is exactly what a client
minting a fresh UUID per request satisfies, while sending one email per double-click. The schema cannot express
"one compose journey, one UUID", so `OpenAPIEmailTemplates` now describes the parameter as well as the
`{template}` token: the three answers (200 with `enqueued: false`, 409 for the same key with different content,
400 for a missing one) and the instruction not to generate one per request. `documentsTheIdempotencyKeyHeader`
pins the description, not only the declaration, for that reason — and pins that the preview and the availability
`GET` carry no such header, because neither writes a row.

**One disclosure the header creates that nothing else in this service does, now stated as a test.** A request id
is a UUID the *caller* chooses, so a caller who learned one used on another case could ask for it. V19's index is
on `("deedPollId", "requestId")` and `resolveRefusedInsert` reads `findByDeedPollIdAndRequestId` — both scoped —
but the rule was implicit: nothing failed if the lookup were widened to the request id alone, which would answer a
caller with a stranger's receipt. `anotherCasesRequestIdIsUnreachable` puts a row under the other case with the
same key and asserts the fault rather than the receipt, and that the other case's id is never queried.

**Three things asserted that the container-backed journey could not be trusted alone for.**

1. **`requestId` is absent from the whole API surface, by ArchUnit rather than by naming three DTOs.**
   `noFields()` over `api..` plus the counterpart that the column *does* exist on `Notification`, so the rule
   cannot pass because nothing stores one.
2. **Neither the slot text nor Notify's rendering reaches a log line**, over the notify package's own logger and
   across both halves of the code that handles an email: the service's refusal path, which has both texts in its
   hands at once, and the real sender against `StubNotify`. The key itself is asserted absent too.
3. **Repeated chases are separate history, container-free.** The controller journey proves it over Postgres, and
   CI runs neither that class nor `integration` — so `repeatedChasesAreEachTheirOwnEntry` states the same rule
   where CI reaches it: each attempt carries its own state, author, address and rendered wording, and a `Pending`
   second attempt does not borrow the first's subject.

**The controller journey is one test rather than five, deliberately.** ⑤ is the first template whose entitlement
is the case's derived state, the first whose rows carry a null `referralId`, and the first a caseworker may send
twice — and what needed proving is that availability, preview, send, drain and history agree about *one case as
it moves*, which five independent tests would each have re-established from scratch. `chasedCase()` reaches
`AwaitingFurtherDocs` through `updateDeedPoll` and asserts the cascade derived it, so the fixture is a case this
service could have produced rather than a row with a state written onto it.

**The reply-to is asserted on the wire**, read off the stub's recorded request body as `email_reply_to_id`: a
wrong id is a loud 400 and a blank one is silent, and silence here loses the documents the email exists to collect.

**The method-name checkstyle trap bit once more** (`aFailedChaseStaysInHistory`), which the Validation Commands
section predicts. **Two counts that are not what they look like:** `CHASE_INDEX` is read off
`EmailTemplate.ordinal()` rather than written as `2`, and the caseworker operation count stays **34** — a header
is part of an operation, not one of its own.

**notify-2's correction is in the sibling repo, not this one.**
`../deed-poll-admin/docs/plans/completed/notify-2-caseworker-email-tab.md` decision 16 was *reinstated* rather
than amended: its original wording ("every send carries one idempotency key for its compose journey, sent as
api's required header") was corrected away on 2026-09-21 because no such header existed, and the removal's own
"the gap that leaves" paragraph named this plan as where it would have to be built. The correction records that
the adapter sends the header for every template rather than only for ⑤, that the journey must preserve one UUID
through preview, validation failure and retry, that the three answers are three different renderings, and that
the tests written to assert the token's *absence* now assert the opposite of the contract. Task 5's bullet carries
the same date and points at it.

- [x] repeated chases are complete correspondence history, case scope and disclosure boundaries are explicit,
      the header is published without a new operation, notify-2 records its generic responsibility, and the
      build is green

### Task 4: Full validation and handoff

- Run a cold build and record unit, integration and Docker-free totals against the baseline.
- Confirm checkstyle on all five source sets and the ArchUnit Notify-client boundary.
- Generate `/tmp/openapi-specs.json` and verify the send header and unchanged operation count.
- Update `docs/RESUME.md`, this plan's measured totals and the admin contract handoff.
- Confirm every `[ASSUMED]` or provisional statement introduced during Task 0 has been resolved.

- [ ] the full build is green from cold, counts and contract are recorded, admin has an exact handoff, and
      no assumption remains disguised as implementation evidence

---

## Deliberately not in this plan

- The Gazette notice email.
- Automatically deciding which documents are missing.
- Reading inbound replies or attachments.
- Uploading documents on the applicant's behalf.
- Changing `awaitingFurtherDocsInfo` or moving the case out of `AwaitingFurtherDocs`.
- A reminder schedule, deadline or automatic chase.
- A dedicated admin page; notify-2's generic Email tab owns compose, preview and send.

---

## Flags for Steve

1. **The plan assumes one required placeholder named `documents_required`.** Task 0 verifies it against the
   real template. If content design chose another name or several fields, change the plan before code; do not
   add aliases.

2. **There is still no rule for when to chase or how often.** This plan permits deliberate repeats and makes
   each one idempotent, but the caseworker decides when. An automatic schedule needs a business deadline and a
   separate plan.

3. **The applicant's reply is still a manual process.** A monitored inbox and correspondence history prove
   what was requested, not what came back. Uploading or recording the supplied documents remains the existing
   caseworker workflow.

4. **The Gazette notice stays explicitly deferred.** Nothing in this plan should be read as resolving its
   missing trigger, PDF source, recipient wording or delivery evidence.
