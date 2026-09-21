# notify-4 — *Forms for completion*, the two document emails

## Overview

Adds the last two of the original four templates, and they are the ones the taxonomy rates **medium-high volume**:
the email an applicant receives once their application has been checked, carrying the forms they must print,
sign in front of two witnesses, have sworn by a commissioner of oaths, and post back.

**This plan is different in kind from notify-1 and notify-3.** Those were a new enum member and a new
personalisation record on plumbing that already existed. This one introduces three things the service has
never done: it **generates a document**, it **stores a document it produced itself**, and it **sends a file
by email**. It is the largest of the four and the only one with an unresolved technical risk in it, which is
why its first task is a spike whose job is to fail cheaply if the answer is no.

Read first: [`NOTIFY_TEMPLATE_TAXONOMY.md`](../../../deed-poll-admin/.business_context/NOTIFY_TEMPLATE_TAXONOMY.md)
§6, `notify-1-email-plumbing.md` and `notify-3-request-confirmation-to-proceed.md` — their decisions are this
plan's decisions except where said otherwise. Read `notify-3`'s decision 3 in particular: the exclusion group
it introduces is the mechanism this plan needs and **cannot use unmodified**, for the reason in decision 14.

**Depends on notify-1 in full, including task 7**, and on **notify-3's migration**, which is where the
exclusion group is introduced. It also depends on **notify-0** for more than the template UUIDs this time:
*send a file by email* is a per-service Notify setting, and the retention period is a number somebody has to
choose. Both are in decisions 10 and 11.

**Scope is the api.** notify-2's task 4 asserts the Email tab needs no change when a template is added. A
template with no slots and a document attached may break that assertion — if it does, that is a finding worth
reporting to notify-2 rather than a change worth making quietly in `deed-poll-admin`.

---

## What the evidence actually says

Four claims below are load-bearing and all four were read from the supplied files rather than inferred from
the taxonomy's summary of them. The extraction was `unzip -p "<file>" word/document.xml` piped through a tag
strip; `python3` is not available in this devcontainer.

**1. Seven documents, not two.** The adult email says *"we have attached three forms"*; the minor email says
*"four"*.

| Email | Documents |
|---|---|
| ① adult | **LOC020** change of name deed, LOC021 statutory declaration, LOC027 exhibit cover sheets |
| ② minor | **LOC022** change of name deed, LOC023 affidavit of best interest, LOC024 statutory declaration for a child, LOC028 exhibit cover sheets |

**2. Only LOC020 and LOC022 carry case data, and the email says so** — *"This form contains the information
you provided in your online application and will become your final change of name deed once you and your
witnesses have signed it and it has been sealed in the High Court."* The other five are blank forms for a
declarant and a commissioner of oaths to complete. They are **static files to be stored and served, not
documents to be generated**, and that is most of the apparent scope of this plan gone.

**3. The two deed templates are Dynamics 365 Word templates, and are machine-readable as supplied.**
`Deed Poll Adult Deed.docx` contains **30 `w:sdt` content controls**, each with a `w:dataBinding` whose
`w:xpath` reads:

```
/ns0:DocumentTemplate[1]/deedpoll_deedpoll[1]/deedpoll_newforename[1]
```

against `xmlns:ns0='urn:microsoft-crm/document-template/deedpoll_deedpoll/10740/'`. There are **no merge
fields, no Docmosis `<<field>>` syntax and no plain-text tokens**: `10740` is the Dataverse entity type code
and `customXml/item1.xml` is the bound data part. This cuts directly against the Docmosis route — see
decision 3.

**4. The bound fields all exist on `DeedPoll`.** Adult binds 15 distinct fields, minor binds 13, and the two
sets differ by more than the template text does:

| Bound name | Column | Notes |
|---|---|---|
| `deedpoll_newforename` / `_newmiddlename` / `_newsurname` | `newForename`, `newMiddleName`, `newSurname` | both |
| `deedpoll_oldforename` / `_oldmiddlename` / `_oldsurname` | `oldForename`, `oldMiddleName`, `oldSurname` | both |
| `deedpoll_nationality` | `nationality` | both, an enum |
| `deedpoll_section` / `_sectionother` | `nationalitySection`, `nationalitySectionOther` | both |
| `deedpoll_referencenumber` | **ambiguous** — `referencenumber` or `displayReference` | both; flag 4 |
| `deedpoll_date` | **ambiguous** — "Date you completed the form"; flag 4 | both |
| `deedpoll_applicantbuildingnoname`, `_applicantaddress1`, `_applicantaddress2`, `_applicanttowncity`, `_applicantpostcode`, `_applicantemail` | the matching `applicant*` columns | **adult only** |
| `deedpoll_status` | `maritalStatus`, probably; flag 4 | **adult only** |
| `deedpoll_ageofminor` | `ageOfMinor` | **minor only** |
| `deedpoll_parents` | `parents` | **minor only** |

The witness blocks on both deeds are **blank** — witnesses sign by hand on paper, so nothing in
`DeedPollWitness` is merged into the deed. That is worth knowing before somebody tries.

---

## Decisions taken — implement these, do not revisit

1. **Two templates, not one conditionalised pair.** `forms-for-completion-adult` and
   `forms-for-completion-minor`, exactly as taxonomy §6 proposes. They differ in which forms, which exhibits
   and most of the body text, not in a sentence, and a content designer owns the words. One template with
   that much conditional content is harder to proofread than two.

2. **`DeedPollType` picks the template, and it has four members.** `Adult` and `AdultTransgender` take ①;
   `Minor` and `MinorTransgender` take ②. The taxonomy's §6 rows say "`type` = adult" and "`type` = minor" as
   though there were two, and **there are four** — an `AdultTransgender` application falling through to the
   minor branch would send an applicant an email about their child. A `switch` with no `default`, the
   convention `CaseEmailService.cause()` already relies on, so a fifth member fails to compile.

3. **Generated in-process, not through `doc-assembly` / Docmosis.** Two independent reasons, both checked
   against source rather than assumed:

   - **`doc-assembly` returns a document-store location, never bytes.** `DocAssemblyResponse` has exactly
     `renditionOutputLocation` and `hashToken`, and `DocAssemblyRequest` carries `caseTypeId` and
     `jurisdictionId`. This service has no CCD case type — `deedpoll.ccd.case-type-id` is
     `DEED_POLL_PLACEHOLDER` and plan 02 is unwritten — and no CDAM, plan 03 being unwritten too. Taking
     this route makes the highest-volume email in the service depend on **both** missing plans.
   - **The supplied templates are not Docmosis templates and would have to be re-authored by hand.** See
     evidence 3. Re-typing a legal deed into a second format, then keeping that copy honest against the
     original, is a worse risk than the one this plan takes instead.

   Notify's `prepareUpload` takes a `byte[]`. In-process generation gives it one directly.

4. **The five blank forms are stored files, not generated ones.** They are the same bytes for every
   applicant. They are **not in `doc_templates/`** and must be sourced — flag 1 — and once sourced they are
   loaded from the classpath or from the document store and attached unchanged. No template engine touches
   them. If a later plan needs a blank form pre-filled, that is a new decision and not this one.

5. **Bind the supplied `.docx` as it is; do not retype it.** The content controls are standard OOXML with
   XPath data bindings, which is a format a library can drive. **Task 1 is a spike that proves this before
   anything is built on it**, because if it turns out not to hold, decision 3's second reason evaporates and
   the whole plan should be reconsidered rather than worked around.

6. **The output is PDF, and this is the one genuinely unresolved risk in this plan.** The applicant prints
   the deed and signs it in front of witnesses, and a court seals it, so it must render identically
   everywhere — which a `.docx` does not. Producing a faithful PDF from a Word document in-process is the
   hard part of this plan and **task 1 must answer it, not assume it**. The realistic options, in the order
   I would try them, are in task 1. **Do not build tasks 2 onwards until one of them is demonstrated on the
   real template.**

7. **Generate once, store the bytes, and never regenerate.** LOC020 *becomes* the sealed deed. If it were
   regenerated later — after a link expired, or for a re-send — and the case data had changed in between,
   the applicant would hold a deed that differs from the one they signed in front of two witnesses. That is
   a correctness problem in a legal document, not an inconvenience. The generated bytes are written once and
   are immutable thereafter.

8. **A generated deed is a case document, stored as one.** `DeedPollSupportingDocument` already has
   everything it needs: `direction` — whose `Outgoing` member is ported from the reference and has been
   unused until now — plus `storageKey`, `mimeType`, `sizeBytes` and `originalName`. It goes behind the
   existing `DocumentStore` seam, so the CDAM implementation plan 03 adds will pick it up with no change
   here. This also answers *"what exactly did we send this applicant?"*, which the Deed Poll office will
   eventually ask.

9. **The personalisation stores a reference to the document, and the drain resolves it to an upload.**
   `Notification.personalisation` is `jsonb` and frozen at enqueue, deliberately, so that a later edit cannot
   rewrite what was sent. `prepareUpload` embeds the file as **base64**, so putting documents in the
   personalisation at enqueue would write roughly 18 MB per row for the minor email's four files, *and* force
   document generation into the caseworker's synchronous request. So:

   - **at enqueue**, the personalisation holds the supporting-document ids, under keys the template does not
     use — they are resolved values like any other, and `EmailSlots.parse` must refuse a client that sets one;
   - **at drain**, `NotifyEmailSender` swaps each id for `NotificationClient.prepareUpload(...)` immediately
     before `sendEmail`, and the result never touches the database.

   The freeze invariant survives — what was frozen is *which document*, and the document is immutable by
   decision 7 — without the row bloat.

10. **`confirm_email_before_download` is `true`, for every document this service sends.** `prepareUpload`
    writes four keys: `file`, `filename`, `confirm_email_before_download` and `retention_period`. Deed poll
    data is sensitive — `DeedPollType` has `AdultTransgender` and `MinorTransgender` members — and an
    unconfirmed link is retrievable by anyone the email is forwarded to, deliberately or otherwise. Requiring
    the recipient to confirm their address costs one screen and closes that.

11. **The retention period is generous, and is notify-0's number to choose.** `RetentionPeriodDuration(int,
    ChronoUnit)`. The process this email starts is inherently slow — print, find a commissioner of oaths,
    post back — so a short retention guarantees phone calls to the number in the email's own footer. Pick it
    deliberately, record it in notify-0, and do not leave it at the client default by omission.

12. **The email says "download", not "attached", and the templates are authored that way.** Notify's *send a
    file by email* has always been a **link**, not an attachment — this is not a change of mechanism, only of
    wording, and the supplied text *"we have attached three forms"* would be wrong either way. notify-0's
    authoring checklist carries it. The link is time-bound, so the wording should say so rather than leave an
    applicant to discover it.

13. **No caseworker slots on either template, despite what §6 says.** Taxonomy §6 lists "notes" as a slot for
    both, but neither supplied document has an `[Enter …]` marker anywhere in it — unlike ③ and ④, which have
    two each. There is nothing for a caseworker to curate here: the email is the same for every applicant of
    a given type. `slots()` is `List.of()`, and `EmailSlots.parse` then refuses any slot a client sends.
    **If the business wants a notes box, that is a template change in notify-0 first** — adding a slot the
    template has no placeholder for makes Notify refuse the send.

14. **① and ② are mutually exclusive, and notify-3's exclusion group does not cover them.** They share an
    obvious group — an applicant must never receive both the adult and the minor forms — but notify-3's index
    is **partial on `"referralId" IS NOT NULL"`**, and these two emails are not referral-driven: they are sent
    once an application has been checked, with no judge and no referral involved. So the partial index never
    sees their rows. Their exclusivity is per **case**, not per referral. Widen the mechanism rather than
    inventing a second one: a second partial index on `("deedPollId", "exclusionGroup")` for the rows where
    `"referralId" IS NULL`, carrying notify-1 task 7's permanent-failure predicate exactly as notify-3 leaves
    it. Group name `FORMS_FOR_COMPLETION`.

15. **Availability is "the application has been checked and no signature has been requested yet."** Taxonomy
    §6 gives the trigger as *"application checked"* and the state afterwards as `AwaitingSignature`, and
    `CaseState.deriveState` shows what that means mechanically: `AwaitingSignature` is derived from
    `signatureRequestedDate != null`. So the email is offered while that date is null, and **sending it is the
    act that requests the signature**. Whether the send should therefore *set* the date is flag 2 — it is a
    business question, not an engineering one, and the plan must not answer it by accident.

16. **The Gazette sentence is a template conditional, from `publishToLondonGazette`.** The supplied text
    carries it inline — *"You will also receive a PDF copy of The Gazette notice via email, if your name
    change is being published in The Gazette"* — which reads as a hedge because the document could not branch.
    The template can. `publishToLondonGazette` is a non-null `boolean` defaulting to `true`, so the resolved
    key is always present and never empty.

---

## Constraints

- **Two migrations at most, and they sit on top of notify-3's.** One for the `("deedPollId",
  "exclusionGroup")` index of decision 14; one for whatever decision 8 needs if the generated deed's document
  type is not already a row. **Take the next free number and say so** — `V11` is committed, notify-1 task 7
  takes `V12`, notify-3 takes the next. Do not assume.
- **Nothing in `src/main` may generate a document twice for one case.** Decision 7 is the whole reason this
  plan stores bytes rather than re-deriving them, and a convenience method that "just regenerates it" would
  quietly undo it.
- **`LocalFilesystemDocumentStore` must not be the production store.** Its own javadoc says so, and this plan
  makes the consequence worse: local disk does not survive AKS, and a generated deed is now a court record
  rather than a caseworker's upload. This plan does not fix that — plan 03 does — but it raises the stakes,
  and flag 3 says so.
- **The generated PDF must be under 2 MB.** `prepareUpload` enforces it client-side — verified by `javap`,
  `ldc 2097152`, throwing `NotificationClientException` with *"File is larger than 2MB"*. A two-page deed
  will not come close unless a font is embedded carelessly, but the check belongs in a test rather than in
  an assumption.
- **Do not edit `src/test/resources/reference/`.** It is a verbatim vendored copy of the specification and
  the conformance tests read it as ground truth.

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

### Task 1: The spike — prove a deed can be produced, before building anything on it

**This task is throwaway and its output is a decision, not code.** Timebox it. If none of the options below
works, **stop and report**: decision 3 rests on this, and the honest outcome is to reconsider the route
rather than to force one.

Read first: evidence 3 and 4 above, and decisions 5 and 6.

Two questions, in order, both answered against `Deed Poll Adult Deed.docx` itself and not a cut-down copy:

1. **Can the bound content controls be driven?** `docx4j`'s binding support is the obvious first attempt —
   the format is standard OOXML data binding and `customXml/item1.xml` is the part to populate. Apache POI's
   `XWPF` is the fallback, walking the `w:sdt` elements directly. `et-ccd-callbacks` already depends on
   `docx4j` in its test scope, so the dependency is not novel in HMCTS.
2. **Can the result become a faithful PDF in-process?** This is the harder one. In the order I would try
   them: `docx4j-export-fo`; **posting the bound `.docx` to Docmosis's convert endpoint** — which is what
   `finrem-case-orchestration-service` does in `DocumentConversionService`, and it is worth noting that this
   is *conversion only*, a multipart POST with an access key returning bytes, with **no CCD, CDAM, S2S or
   IDAM involved**, so it does not carry decision 3's objection; a headless LibreOffice conversion
   (faithful, but a large container dependency and a process to supervise — weigh it honestly rather than
   dismissing it); or abandoning the `.docx` entirely and laying the deed out directly with **PDFBox**.

   **On precedent, be careful what the other services actually prove.** PDFBox is widespread in HMCTS but
   mostly for *post-processing* — `sscs-tribunals-case-api` watermarks and applies PDF/A compliance,
   `finrem` date-stamps and flattens. **The one real precedent for generating a filled legal form is
   `et-ccd-callbacks`**, whose `PdfService.convertCaseToPdf` fills the ET1 AcroForm from case data and
   returns `byte[]` — the same shape of problem as LOC020. That route needs a **PDF form template**, which
   this programme does not have and which would have to be authored from the deed.

**Judge the output by eye against the original, not by whether it ran.** A deed that renders with a table
border missing is a court document that looks wrong.

- [ ] A PDF produced from the real template with real-shaped values, compared against the original by eye,
      the chosen approach and its rejected alternatives written into this file with the reason, and the spike
      branch deleted rather than merged

### Task 2: The generator

Read first: task 1's recorded decision, and evidence 4's field table.

- A `DeedDocumentGenerator` — one entry point, taking a `DeedPoll` and returning bytes plus a filename. It
  does not store, does not email, and does not know what a notification is.
- **Adult and minor are two templates and two field sets**, per evidence 4. Resolve the field set with a
  `switch` on `DeedPollType` with no `default`, so decision 2's rule has one implementation rather than two.
- Enum-valued fields — `nationality`, `nationalitySection`, `maritalStatus` — render as the **display text a
  human reads**, not the member name and not the Dynamics integer. `DynamicsValues` already holds display
  strings for states; check whether it does for these, and if not say where the strings come from rather than
  inventing them in the generator.
- A null optional field renders as **empty**, never as `"null"` and never as the field name. A deed with the
  word `deedpoll_oldmiddlename` printed on it would reach a judge.
- Tests, container-free: every bound field in evidence 4's table is populated for a fully-populated adult
  case and for a minor one; the minor deed contains `ageOfMinor` and `parents` and the adult deed contains
  neither; nulls render empty; the output parses as a PDF and is **under 2 MB**; and the two `DeedPollType`
  transgender members produce the same document as their non-transgender counterparts.

- [ ] A deed is generated for all four `DeedPollType` members, every bound field is populated, nulls render
      empty, the output is a valid PDF under 2 MB, and `./gradlew build` green

### Task 3: Storing what was generated, once

Read first: `DocumentService`, `DocumentStore`, `DeedPollSupportingDocument`, and decisions 7 and 8.

- The generated deed is written through the existing `DocumentStore` seam and recorded as a
  `DeedPollSupportingDocument` with `direction = Outgoing`, its `mimeType`, its `sizeBytes` and a filename an
  applicant will recognise on their own computer — not a UUID.
- **It is generated at most once per case.** A second request finds the existing row and returns it rather
  than producing a second deed. This is decision 7 and it is the point of the task.
- The five static forms of decision 4 are attached from wherever flag 1 settles they live. They are the same
  bytes for every applicant and must not be copied per case.
- **Document type**: a generated deed needs a `DeedPollSupportingDocumentType`. Check whether a suitable row
  exists before adding one; if one is added, that is the second migration the constraints allow, and
  `applicableTypes` should reflect that the adult and minor deeds are different documents.
- Tests, container-backed: generating twice for one case yields one row and one blob; the row carries
  `Outgoing`; the stored bytes are byte-identical to what the generator produced; and deleting the case's
  documents through the existing path does not silently orphan a blob.

- [ ] A generated deed is stored exactly once per case as an `Outgoing` supporting document, the static forms
      are attached without being copied per case, and `./gradlew build` green

### Task 4: The two templates and their personalisation

Read first: `EmailTemplate`, `ApplicationRejectedPersonalisation`, `EmailSlots.parse`, and decisions 1, 2,
13 and 16.

- Two new `EmailTemplate` members, keys `forms-for-completion-adult` and `forms-for-completion-minor`, both
  in exclusion group `FORMS_FOR_COMPLETION`, both with **no slots** (decision 13).
- One personalisation record or two — the body text differs enough that two is likely honest, but the
  applicant's own details are identical, so share what is genuinely shared and do not contrive a hierarchy to
  avoid a repeated line.
- The Gazette conditional resolves from `publishToLondonGazette` (decision 16). `onlineApplicationReference`
  appears in the subject line of both and is nullable — resolve it to empty rather than omitting the key,
  for the reason every other template does: Notify refuses a send whose personalisation is missing a
  placeholder the template uses.
- The document keys of decision 9 are **resolved values**, so `EmailSlots.parse` refuses a client that sets
  one, and `NotificationSecurityTest` asserts it alongside the existing templates.
- Tests, container-free: every resolved key present and in `toMap()` order for both templates; the Gazette
  key follows `publishToLondonGazette` both ways; a null `onlineApplicationReference` renders empty rather
  than absent; a request carrying any slot is refused; and **no witness data appears in either resolved map**,
  since the deed's witness blocks are blank by design.

- [ ] Both templates resolve their own personalisation with no slots, the Gazette sentence follows the case,
      client-set values are refused, and `./gradlew build` green

### Task 5: Turning a stored document into a Notify link, at drain time

Read first: `NotifyEmailSender.send`, `NotificationOutbox`, and decision 9.

- Immediately before `sendEmail`, each document id in the personalisation is replaced by
  `NotificationClient.prepareUpload(bytes, filename, true, retention)` — `true` being decision 10's
  `confirm_email_before_download`, and the retention coming from configuration per decision 11.
- **The resolved map is never written back.** It is built, passed to `sendEmail`, and discarded. A test
  should assert the row's `personalisation` is unchanged after a successful send, because this is the
  invariant the whole design rests on and it would break silently.
- **A missing or unreadable document is a `TECHNICAL` failure, not `PERMANENT`.** The document store being
  briefly unavailable must not poison the case — and after notify-1's task 7, `PERMANENT` is the one verdict
  that can now be retried, which is the opposite of what is wanted here. Classify deliberately and say why in
  the javadoc.
- **A document over 2 MB must fail before `sendEmail`**, with a message naming the document and its size.
  `prepareUpload` throws `NotificationClientException` for this, which `NotificationFailure.classify` would
  see as `TECHNICAL` by default — correct, but arrived at by accident, so assert it.
- Tests, container-free with a stubbed store and `StubNotify`: a send resolves both the adult email's three
  documents and the minor email's four; the stored row is unchanged afterwards; a missing document yields
  `TECHNICAL`; an oversized document fails with a message naming it; and `confirm_email_before_download` and
  the retention period reach the client as configured.

- [ ] Documents are resolved to uploads at drain time and never persisted, the frozen row survives a send
      unchanged, missing and oversized documents fail as `TECHNICAL` with useful messages, and
      `./gradlew build` green

### Task 6: When the email is offered, exclusivity, and full validation

Read first: `CaseEmailService.cause()` and `availability()`, notify-3's decision 3, and decisions 14 and 15.

- `cause()` gains its two branches: the case's `DeedPollType` selects the template, and availability requires
  `signatureRequestedDate == null` (decision 15). `reasonNotApplicable()` says which of the two it was —
  "the signature has already been requested" and "this case has no type recorded" are different problems and
  a caseworker can act on the difference.
- **The second exclusion index** of decision 14: partial on `"referralId" IS NULL`, keyed
  `("deedPollId", "exclusionGroup")`, carrying task 7's permanent-failure predicate unchanged. `enqueue`'s
  `ON CONFLICT` target must repeat the predicate **verbatim** — Postgres infers a partial index by matching
  it, and the existing javadoc documents this trap.
- `NotificationSecurityTest` extended rather than duplicated: neither template's personalisation contains a
  value from another case, and the document ids resolve only to documents belonging to the case being
  emailed. **A deed attached to the wrong applicant's email is the worst failure available to this plan** and
  deserves its own named test.
- Tests, container-backed: an adult case is offered ① and never ②; a minor case the reverse; a case whose
  signature has already been requested is offered neither; enqueueing ② for a case that already has ①
  answers `0`; and a referral-driven template is **unaffected** by the new index, so notify-3's rule and this
  one do not interfere.

- [ ] The right template is offered for all four `DeedPollType` members, a case cannot receive both, the new
      index leaves referral-driven templates alone, the security test covers cross-case leakage of documents,
      `./gradlew build` green from cold, checkstyle clean on five source sets, and both totals recorded

---

## Deliberately not in this plan

**Case correspondence history — this is [`notify-5-case-correspondence-history.md`](completed/notify-5-case-correspondence-history.md), now written, and it should run *before* this plan.** The question that
prompted it: *can a caseworker see what an applicant has received, including which documents and what any
curated text said?* Today the answer is **no**, and notify-5's decision 1 is what changes it — the rendered
subject and body come back free in `SendEmailResponse`, so they can be stored at send time with no extra
Notify call. **Ordering matters:** this plan attaches legal documents to emails, and shipping it while a
permanent failure is still invisible means an applicant who never receives their deed forms and a court that
cannot tell. Decision 8 of this plan is what lets notify-5 show *which* documents an email carried.

- **What already exists.** `Notification` freezes `personalisation`, `templateVersion`, `recipient`,
  `createdBy`, `createdOn`, `state`, `attempts` and `failureReason` at enqueue, and
  `NotificationRepository.findByDeedPollIdOrderByCreatedOnDesc` — the case-scoped finder — is already there
  with no caller in `src/main`. The record is real and the query is written; only the route and the words
  are missing. notify-5 has the detail.
- **Downloads are not observable, and the screen must not imply otherwise.** There is no download concept
  anywhere in the Notify Java client; `Notification.getStatus()` is email delivery status only. A screen
  saying "Sent" will be read as "they have it", so notify-5 owns the wording as much as the data.
- **This plan feeds it.** Decision 8 links each generated deed to the case as an `Outgoing` document, and
  decision 9 puts the document references in the frozen personalisation — which together are what let
  notify-5 show *which documents* an email carried rather than only that one was sent.

Also not here: the **⑥ Gazette notice** template the taxonomy names as deferred; **pre-filling any of the five
static forms**; **delivery callbacks**; and **plan 03's CDAM store**, which this plan depends on
architecturally but does not deliver.

---

## Flags for Steve — pick these up on return

1. **The five blank forms do not exist in this workspace and somebody has to supply them.** LOC021, LOC023,
   LOC024, LOC027 and LOC028 are referenced by name in the emails but `doc_templates/` holds only the two
   deeds. They are presumably published HMCTS forms, in which case this is a sourcing exercise rather than a
   design one — but **task 3 cannot finish without them**, and if any of them turns out to need case data
   after all, decision 4 is wrong and the plan grows. Worth confirming early.

2. **Should sending these emails set `signatureRequestedDate`?** Decision 15 established that
   `AwaitingSignature` is derived from that column, so the send and the state change are the same event in
   everything but code. Making the send set it is tidy and matches the taxonomy's "case state after:
   AwaitingSignature". But it couples a communication to a case-state transition, it means a failed send
   leaves the case in a state that claims otherwise, and it makes a re-send impossible without clearing a
   date a caseworker can also edit by hand through `DeedPollPatch`. **A business answer, not an engineering
   one** — the same shape as notify-3b's flag 1, and the two should probably be answered together.

3. **This plan makes `DOCUMENT_STORE_ROOT` urgent.** `RESUME.md` already flags that local disk must not reach
   a deployed environment. Until now the consequence was a caseworker's upload vanishing on a pod restart;
   after this plan it is **a court record vanishing**, and the deed that an applicant signed cannot be
   reproduced because decision 7 forbids regenerating it. Plan 03 moves from "should be written" to
   "blocks deployment of this feature".

4. **Three field mappings I inferred and could not verify.** `deedpoll_referencenumber` — the entity has both
   an `Integer referencenumber` and a `String displayReference`, and the deed and the email subject both use
   the bound name; `deedpoll_date` is labelled *"Date you completed the form"* and there are several dates it
   could be; `deedpoll_status` sits beside Nationality on the deed and is **probably** `maritalStatus`, which
   is the conventional meaning on a deed poll, but that is a reading rather than a fact. All three print on a
   legal document. **The `customXml/item1.xml` part in each `.docx` may well resolve them** — it is the bound
   data part, and task 1's spike will have it open anyway.

5. **The refund paragraph oddity in ④ is still unconfirmed**, carried over from notify-3's flags. Unrelated
   to this plan but it is the same set of documents and the same content author, so it is worth asking once.

6. **Notify's "send a file by email" is a per-service setting and may not be on.** notify-0 lists template
   authoring but I could not confirm it covers this. If the setting is off, every send in this plan fails at
   `prepareUpload` in a way that looks like a code bug. Worth adding to notify-0 explicitly along with
   decision 11's retention number.
