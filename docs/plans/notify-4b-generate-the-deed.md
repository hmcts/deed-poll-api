# notify-4b — generate the deed

## Overview

The second half of the old `notify-4-forms-for-completion.md`, split on 2026-09-25. This is the half with the
risk in it, and the half that finally makes ① and ② send.

[`notify-4a`](notify-4a-attach-files-to-an-email.md) builds the machinery to attach a file and stores the five
blank forms. This plan produces the one document that carries the applicant's data — **LOC020** (adult) and
**LOC022** (minor) — and then authors the two templates that carry it.

**Start here rather than with notify-4a if only one can run.** This plan needs **nothing from outside
engineering**: both deed templates are already in the workspace at
`../deed-poll-admin/.business_context/doc_templates/`. notify-4a is the one waiting on five forms nobody has
supplied and a Notify service setting nobody has switched on.

**Task 1 is a spike whose job is to fail cheaply.** If the deed cannot be produced faithfully in-process, decision
2's second reason evaporates and the route should be reconsidered rather than forced. **Do not build tasks 2
onwards until one option is demonstrated on the real template.**

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

---

## Decisions taken — implement these, do not revisit

Carried from the original plan, which argued them; the numbering is this plan's.

1. **Two templates, not one conditionalised pair.** `forms-for-completion-adult` and
   `forms-for-completion-minor`. They differ in which forms, which exhibits and most of the body text — not in a
   sentence — and a content designer owns the words. One template with that much conditional content is harder to
   proofread than two. (Original decision 1.)

2. **Generated in-process, not through `doc-assembly` / Docmosis.** The supplied files are **not** Docmosis
   templates and would have to be re-authored by hand to become them — evidence 3 is what settles it: XPath data
   bindings, no merge fields, no `<<field>>` syntax. Task 1 tests the other half of the reasoning. (Original
   decision 3.)

3. **`DeedPollType` picks the template, and it has FOUR members.** `Adult` and `AdultTransgender` take ①;
   `Minor` and `MinorTransgender` take ②. The taxonomy reads as though there were two — an `AdultTransgender`
   application falling through to the minor branch would send an applicant an email about their child. A
   `switch` with no `default`, so a fifth member fails to compile. (Original decision 2.)

4. **Bind the supplied `.docx` as it is; do not retype it.** The content controls are standard OOXML with XPath
   data bindings, which a library can drive. Retyping the deed would put the court's legal wording under
   engineering's control, which is the wrong owner. (Original decision 5.)

5. **The output is PDF, and this is the one genuinely unresolved risk.** The applicant prints the deed, signs it
   before witnesses and a court seals it, so it must render identically everywhere — which a `.docx` does not.
   **Task 1 must answer this, not assume it.** (Original decision 6.)

   **Worth raising before the spike rather than after:** if the court is content for the applicant to receive the
   Word document they are going to print anyway, this risk disappears and task 1 halves. That is a business
   question nobody has been asked. Ask it first — it is cheaper than the spike.

6. **Generate once, store the bytes, and never regenerate.** LOC020 *becomes* the sealed deed. If it were
   regenerated after the applicant signed a printed copy, the stored document and the paper in the court's hands
   could differ. A second request finds the existing row. (Original decision 7.)

7. **No caseworker slots on either template.** Neither supplied document has an `[Enter …]` marker, unlike ③ and
   ④ which have two each: the email is the same for every applicant of a given type. `slots()` is `List.of()`
   and `EmailSlots.parse` refuses any slot a client sends. **If the business wants a notes box that is a template
   change in notify-0 first** — adding a slot the template has no placeholder for makes Notify refuse the send.
   (Original decision 13.)

8. **① and ② are mutually exclusive per CASE, and notify-3's exclusion group does not cover them.** That index is
   partial on `"referralId" IS NOT NULL`, and these emails are not referral-driven — no judge, no referral — so it
   never sees their rows. **Widen the mechanism rather than inventing a second one:** a second partial index on
   `("deedPollId", "exclusionGroup")` for rows where `"referralId" IS NULL`, carrying notify-1 task 7's
   permanent-failure predicate exactly as notify-3 leaves it. Group name `FORMS_FOR_COMPLETION`. (Original
   decision 14.)

9. **Availability is "the application has been checked and no signature has been requested yet."**
   `AwaitingSignature` is derived from `signatureRequestedDate != null`, so the email is offered while that date
   is null — and **sending it is the act that requests the signature**. Whether the send should therefore *set*
   the date is flag 2: a business question the plan must not answer by accident. (Original decision 15.)

10. **The Gazette sentence is a template conditional, from `publishToLondonGazette`.** The supplied text carries
    it inline as a hedge because a document cannot branch; a template can. The field is a non-null `boolean`
    defaulting to `true`, so the resolved key is always present and never empty. (Original decision 16.)

---

## Validation Commands

**These run after every task, so everything here must be green after every task — not only at the end.**

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew build
```

**Measure the baseline before Task 2 and record it.** As at api master `b01f8f4` (2026-09-25) it was **2,716 unit
tests across 341 classes + 13 integration**, and **1,739** with `INTEGRATION_TESTS_ENABLED=false` — but
**re-measure rather than quoting that**. Task 1 is a spike and is exempt: it may leave throwaway code, and must
leave none behind.

Notes so no task rediscovers them:

- **Measure, do not read the exit code.** A run selecting zero tests also exits 0, and a second `gradle test`
  **wipes `build/test-results/`** — count from the XML immediately after each run and use `--rerun-tasks` when the
  count is the point. **Stale result files are not marked stale.**
- A `--tests` filter matches **method** names too; `@Nested` classes each get their own result file.
- **`checkstyleTest` fails on `severity="warning"`**; the method-name rule rejects a single-letter first word
  followed by a capital.
- **A container or worker startup timeout is an environment failure** — re-run before investigating. The same
  Docker-free build has taken 1m 7s and 35m 32s on identical code. **If a Docker-free run fails, capture the whole
  log before re-running**: one was lost to a `tail -8` and its cause is still unknown.
- The `integration` source set is **excluded in CI**, so give anything CI must enforce a container-free companion.
- **A new library is a dependency decision, not a detail.** `docx4j` and PDF conversion pull in a lot. Record the
  size and the licence of whatever task 1 settles on, and check the CNP whitelist before assuming it can be used.

---

## Tasks

### Task 1: The spike — prove a deed can be produced, before building anything on it

**This task is throwaway and its output is a decision, not code.** Timebox it. If none of the options works,
**stop and report**: decision 2 rests on this, and the honest outcome is to reconsider the route rather than force
one.

Read first: evidence 3 and 4 above, and decisions 4 and 5. **Ask decision 5's question before starting** — if a
`.docx` is acceptable to the court, question 2 below is moot.

Two questions, in order, both answered against `Deed Poll Adult Deed.docx` itself and not a cut-down copy:

1. **Can the bound content controls be driven?** `docx4j`'s binding support is the obvious first attempt — the
   format is standard OOXML data binding and `customXml/item1.xml` is the part to populate. Apache POI's `XWPF`
   is the fallback, walking the `w:sdt` elements directly. `et-ccd-callbacks` already depends on `docx4j` in
   its test scope, so the dependency is not novel in HMCTS.
2. **Can the result become a faithful PDF in-process?** The harder one. In the order I would try them:
   `docx4j-export-fo`; **posting the bound `.docx` to Docmosis's convert endpoint** — what
   `finrem-case-orchestration-service` does in `DocumentConversionService`, and worth noting it is *conversion
   only*, a multipart POST returning bytes, with **no CCD, CDAM, S2S or IDAM involved**, so it does not carry
   decision 2's objection; a headless LibreOffice conversion (faithful, but a large container dependency and a
   process to supervise — weigh it honestly rather than dismissing it); or abandoning the `.docx` and laying the
   deed out directly with **PDFBox**.
- **Also settle where api gets the templates from.** They live in `deed-poll-admin`'s `.business_context/`, which
  this repo must not read at runtime. They have to be **vendored into this repo as classpath resources** — record
  where, and that a template change means a commit here. The original plan did not say this and it is not optional.
- [ ] one route is demonstrated end to end on the real adult template with a rendered PDF a human has looked at,
      OR the task reports that none works; the chosen library's size and licence are recorded; the template's home
      in this repo is decided; and no spike code remains

### Task 2: The generator

Read first: evidence 4, decisions 3 and 4, and whatever task 1 settled.

- A generator that takes a `DeedPoll` and a type and returns bytes. Adult binds 15 distinct fields, minor 13 —
  **bind from the columns evidence 4 lists**, and resolve the three ambiguous mappings in flag 1 before coding
  them rather than guessing.
- Enum and nullable handling matches the existing personalisation: a null column becomes an empty string, never
  the literal `null`, and never the enum's `name()` where a label is wanted.
- Tests: every bound field reaches the output for both types; a null optional column produces an empty binding
  rather than breaking the document; the adult and minor field sets differ exactly as evidence 4 says.
- [ ] both deeds generate from real case data with every bound field populated, nulls degrade to empty, and
      `./gradlew build` green

### Task 3: Store the generated deed exactly once

Read first: `DocumentService`, `DocumentStore`, `DeedPollSupportingDocument`, decision 6, and notify-4a's
Task 1 if it has run.

- Written through the existing `DocumentStore` seam and recorded as a `DeedPollSupportingDocument` with
  `direction = Outgoing`, its `mimeType`, its `sizeBytes` and **a filename an applicant will recognise on their
  own computer** — not a UUID.
- **Generated at most once per case** (decision 6): a second request finds the existing row and returns it.
- A generated deed needs a `DeedPollSupportingDocumentType`; check whether a suitable row exists before adding
  one, and make `applicableTypes` reflect that adult and minor deeds are different documents.
- Tests, container-backed: generating twice for one case yields one row and one blob; the row carries
  `Outgoing`; the stored bytes are byte-identical to what the generator produced; deleting the case's documents
  through the existing path does not orphan a blob.
- [ ] a deed is stored exactly once per case as an `Outgoing` document with a recognisable filename, a second
      request returns the first, and `./gradlew build` green

### Task 4: The two templates, their personalisation, and exclusivity

Read first: decisions 1, 3, 7, 8, 9 and 10.

- Two `EmailTemplate` members with no slots (decision 7), chosen by a `switch` over all four `DeedPollType`
  members with no `default` (decision 3).
- Personalisation resolves the document references notify-4a's seam expects, plus `publishToLondonGazette` for
  the Gazette conditional (decision 10). Every key present in deterministic order, nullable case values as empty
  strings.
- **The migration**: a second partial index on `("deedPollId", "exclusionGroup")` for rows where `"referralId"
  IS NULL`, carrying notify-1 task 7's permanent-failure predicate unchanged (decision 8). **Take the next free
  number by listing the directory** — never reserve one from this document.
- Availability per decision 9, and **do not set `signatureRequestedDate`** — flag 2 is unanswered.
- Tests: an `AdultTransgender` case is offered ① and never ②; both emails cannot be sent for one case; the
  exclusion index refuses the second without hiding which rule fired; the Gazette key is always present.
- [ ] both templates send with their documents, the four `DeedPollType` members route correctly, a case cannot
      receive both, and `./gradlew build` green

### Task 5: Full validation and handoff

- Full validation commands with `--rerun-tasks`, counts recorded against the baseline, `skipped` confirmed 0.
- Record what notify-2 needs, if anything: **a template with no slots and a document attached may break notify-2
  task 4's assertion that the Email tab needs no change per template.** If it does, that is a finding to report,
  not a change to make quietly in `deed-poll-admin`.
- Confirm every `[ASSUMED]` or provisional statement introduced during the spike is resolved.
- [ ] final counts recorded against the baseline, the notify-2 question answered either way, and no provisional
      statement left standing

---

## Deliberately not in this plan

- **The five blank forms and the Notify file-sending seam.** notify-4a.
- **Setting `signatureRequestedDate` on send.** Flag 2 — a business question.
- **Fixing `DOCUMENT_STORE_ROOT`.** notify-4a's flag 3, and a deployment blocker for both halves.
- **Re-authoring the deed as a Docmosis template.** Decision 2 and evidence 3.

## Flags

1. **Three field mappings inferred and not verified.** `deedpoll_referencenumber` — `referencenumber` or
   `displayReference`? — and `deedpoll_date`, described as "Date you completed the form", which matches no
   column cleanly. Resolve before Task 2 codes them.
2. **Should sending ① or ② set `signatureRequestedDate`?** Decision 9 establishes that sending *is* the
   signature request, so the date arguably follows — but that changes the derived state, and nothing else in this
   service sets a case date as a side effect of an email. A business decision, deliberately unanswered.
3. **Decision 5's question has not been asked.** Does the deed have to be a PDF, or will the `.docx` the
   applicant prints anyway do? Asking is cheaper than the spike and could remove its hardest half.
4. **A new library and its licence.** Whatever task 1 settles on, check the CNP whitelist before depending on it.
