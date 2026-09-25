# notify-4b — generate the deed

## Overview

The second half of the old `notify-4-forms-for-completion.md`, split on 2026-09-25. It is what finally makes ①
and ② send.

**It was "the half with the risk in it" until 2026-09-25, and it no longer is.** Steve settled the output format:
**`.docx` is fine for now**, because it duplicates the existing Power App process — the applicant downloads,
prints and signs in wet ink, and a Word document prints. Faithful in-process Word→PDF conversion was the entire
unresolved risk in this plan and in the programme; deferring it removes that risk rather than managing it. See
decision 5.

[`notify-4a`](notify-4a-attach-files-to-an-email.md) builds the machinery to attach a file and stores the five
blank forms. This plan produces the one document that carries the applicant's data — **LOC020** (adult) and
**LOC022** (minor) — and then authors the two templates that carry it.

**Start here rather than with notify-4a if only one can run.** This plan needs **nothing from outside
engineering**: both deed templates are already in the workspace at
`../deed-poll-admin/.business_context/doc_templates/`. notify-4a is the one waiting on five forms nobody has
supplied and a Notify service setting nobody has switched on.

**Task 1 is still a spike, but a much smaller one.** One question remains — can the 30 content controls be
populated — and it is a known-library problem rather than an open one. It stays a spike because evidence 3 was
wrong about the mechanism (see below) and nobody has driven this document yet. **Do not build tasks 2 onwards
until it is demonstrated on the real template.**

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
fields, no Docmosis `<<field>>` syntax and no plain-text tokens**: `10740` is the Dataverse entity type code.
This cuts directly against the Docmosis route — see decision 2.

**⚠️ Corrected 2026-09-25, and it changes which route Task 1 should try first.** This evidence used to end
*"and `customXml/item1.xml` is the bound data part"*, which reads as though the part is present and merely needs
filling. **It is not in the file.** Measured by listing the archive: `word/_rels/document.xml.rels` declares a
`customXml` relationship, but no `customXml/` entry exists in the zip — a dangling relationship, which is what a
Dynamics template looks like. **Dynamics injects that data part at merge time; that injection *is* the Power App's
population step.** So what is in this repo is the template half of a two-part mechanism whose other half lived in
Dynamics, and there is no example of the data part's shape to copy.

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

Carried from the original plan, which argued them; the numbering is this plan's.

1. **Two templates, not one conditionalised pair.** `forms-for-completion-adult` and
   `forms-for-completion-minor`. They differ in which forms, which exhibits and most of the body text — not in a
   sentence — and a content designer owns the words. One template with that much conditional content is harder to
   proofread than two. (Original decision 1.)

2. **Generated in-process, not through `doc-assembly` / Docmosis.** The supplied files are **not** Docmosis
   templates and would have to be re-authored by hand to become them — evidence 3 is what settles it: XPath data
   bindings, no merge fields, no `<<field>>` syntax. Task 1 tests the other half of the reasoning. (Original
   decision 3.)

3. **`DeedPollType` picks the template, and it has SIX members and THREE templates.** `Adult` and
   `AdultTransgender` take ①; `Minor` and `MinorTransgender` take ②; `DeedOfRelinquishment` takes a third,
   ③′ `forms-for-completion-relinquishment` (Steve, 2026-09-25 — same journey as a change of name); `Unknown`
   takes none, because the wrong deed means an applicant swears the wrong legal instrument and there is no safe
   default. **The full rule, its reasons and its tests live in notify-4a Task 2** — this plan must not restate it
   differently.

   An `AdultTransgender` application falling through to the minor branch would send an applicant an email about
   their child, which is why it is a `switch` with no `default`. The original said "four members" and warned a
   fifth would fail to compile; there were already six. (Original decision 2, corrected.)

   **Whether ③′ needs a generated deed at all is open** — notify-4a flag 5. If it does, it belongs in this plan and
   its template is a third generator input; if its forms are all static, it never touches this plan. Do not assume
   either.

4. **Bind the supplied `.docx` as it is; do not retype it.** The content controls are standard OOXML with XPath
   data bindings, which a library can drive. Retyping the deed would put the court's legal wording under
   engineering's control, which is the wrong owner. (Original decision 5.)

5. **The output is `.docx`, decided by Steve on 2026-09-25, and that retires this plan's only serious risk.**
   The original said PDF, on the ground that a document a court seals must render identically everywhere. The
   business answer is that `.docx` **duplicates the existing process**: the Power App produced a Word document,
   the applicant prints it and signs in wet ink, and the sealed original comes back by post. So the deed the
   applicant downloads does not need to be a PDF.

   **"For now" is doing work in that sentence.** This is a deliberate deferral, not a finding that PDF is never
   wanted — so keep the generator's output type behind one seam rather than assuming `.docx` throughout, and do
   not let a mime type become a literal in five places. If PDF is asked for later it should be a conversion step
   added after the generator, not a rewrite of it.

   **What this removes:** the whole of the original task 1's second question — `docx4j-export-fo`, Docmosis
   convert, headless LibreOffice, PDFBox — is out of scope. None of it needs evaluating, and the library and
   licence question narrows to whatever drives the content controls. (Original decision 6, superseded.)

6. **Generate once, store the bytes, and never regenerate.** LOC020 *becomes* the sealed deed. If it were
   regenerated after the applicant signed a printed copy, the stored document and the paper in the court's hands
   could differ. A second request finds the existing row. (Original decision 7.)

7. **No caseworker slots on either template — but the email IS personalised, and the two are different
   questions.** The email is the same for every applicant *of a given type* in its wording; it still addresses
   them by name and quotes their reference. See Task 4 for the four resolved keys, which come from the documents
   themselves.

   `slots()` is `List.of()` and `EmailSlots.parse` refuses any slot a client sends. **If the business wants a
   free-text box that is a template change in notify-0 first** — adding a slot the template has no placeholder for
   makes Notify refuse the send with a 400. (Original decision 13.)

   **Re-checked on 2026-09-25 and the conclusion holds, though the original's evidence for it was wrong in its
   detail.** It said neither document has an `[Enter …]` marker *"unlike ③ and ④, which have two each"*. Measured
   across all four source documents, the marker style is not consistent and the counts were off:

   | Source | Caseworker markers |
   |---|---|
   | ③ Application Rejected | `{insert judge's reason for refusal}` — **one**, in braces |
   | ④ Request confirmation | `[Enter judge's comments]`, plus `[child's]` and `[delete if an adult application]` |
   | **① forms adult** | **none, of any style** |
   | **② forms minor** | **none, of any style** |

   So ①/② genuinely have nothing for a caseworker to fill in and the decision stands — but it was reached partly
   by grepping for one marker syntax when the documents use two. ③'s second slot, `additional_notes`, has no
   marker in the source at all: it was an engineering addition, which is worth knowing before citing ③ as
   precedent for anything.

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

### Task 1: The spike — populate the 30 content controls

**Much smaller than it was, and the route is now known rather than open.** `.docx` output is settled (decision 5),
so nothing here evaluates PDF conversion. And Steve supplied
`../deed-poll-admin/.business_context/doc_templates/Deed Poll Adult Deed_completed.docx` on 2026-09-25, which is
the missing half of the mechanism. **Read that file before writing anything.**

**What the supplied file settles, measured rather than assumed:**

| | |
|---|---|
| `customXml/item1.xml` | **present, 130 KB** — the data part the template's bindings point at, and absent from the template itself |
| Root shape | `<DocumentTemplate xmlns="urn:microsoft-crm/document-template/deedpoll_deedpoll/10740/">` then `<deedpoll_deedpoll xmlns="">` — note the namespace **reset** on the child |
| Contents | **93 elements**, the whole entity rather than the 15 bound fields. Every bound field checked is present |
| `customXml/itemProps1.xml` | declares `ds:schemaRef ds:uri="urn:microsoft-crm/…/10740/"`, which is what makes a binding resolve |
| `w:storeItemID` | **`{C9ADC254-2098-471F-A6B0-379E83CFF8E7}` in *both* files**, and it equals the data part's `ds:itemID` |

**It is not a real-data example.** Every element's value is its own name — `<deedpoll_newforename>deedpoll_newforename</…>` — so it is Dynamics' template export, not a generated deed. It gives the *shape*, not the mapping, so flag 1's three ambiguous fields are **still ambiguous**.

**So reconstruct the data part, and do not touch the document body.** This reverses the order an earlier draft of
this task gave, and the reason is the supplied file:

1. **Add `customXml/item1.xml`, `customXml/itemProps1.xml` and `customXml/_rels/item1.xml.rels` to the package**,
   copying the shape above and filling the bound elements from case data. **The `itemID` must stay
   `{C9ADC254-…}`** — the template's 30 bindings already name it, so there is no GUID to generate and no
   relationship to invent.
2. **`word/document.xml` needs no modification at all.** That is the whole argument for this route: the court's
   legal wording stays byte-identical, which is decision 4 honoured mechanically rather than by care.

**One thing to prove rather than assume, and it is the real risk now.** A content control caches its displayed
text in the body; Word refreshes it from the bound data part when the document opens. So a data part alone may
leave **stale visible text** in a reader that does not resolve bindings. docx4j's binding support updates the
cached text *and* the data part together, which is why it exists — **use that rather than hand-writing the zip**,
and verify by opening the output and reading the values, not by inspecting the XML.

**If that proves unreliable**, fall back to walking the `w:sdt` elements and setting their text directly (Apache
POI `XWPF`), accepting that the data part then goes stale. Say which was chosen and why.

- **Also settle where api gets the templates from.** They live in `deed-poll-admin`'s `.business_context/`, which
  this repo must not read at runtime. They have to be **vendored into this repo as classpath resources** — record
  where, and that a template change then means a commit here. The original plan did not say this and it is not
  optional. **Vendor the completed file too**, as the reference for the data part's shape.
- **Record the library's size and licence** and check the CNP whitelist. Scope is one OOXML library, not a
  conversion stack.
- [ ] every bound field on the real adult template is populated from case data, a human has **opened the result**
      and confirmed the values appear in the right places, `word/document.xml` is unmodified if route 1 was used,
      the templates' home in this repo is decided, the library's size and licence are recorded, and no spike code
      remains

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
- **Personalisation is the four resolved keys the supplied documents already name, plus the Gazette flag and the
  document references.** Read from the source `.docx` on 2026-09-25 rather than chosen: ① and ② each reference
  exactly `deedpoll_referencenumber`, `deedpoll_onlineapplicationreference`, `deedpoll_oldforename`,
  `deedpoll_oldsurname` and `deedpoll_applicantemail` — the same set ③ and ⑤ use, which is why the Notify keys
  should be the same four names those templates already use rather than new ones:

  | Notify key | Column | Where it appears |
  |---|---|---|
  | `reference_number` | the case reference | subject line |
  | `online_application_reference` | `onlineApplicationReference` | subject line, **nullable** |
  | `old_forename` | `oldForename` | opening line |
  | `old_surname` | `oldSurname` | opening line |
  | `publish_to_london_gazette` | `publishToLondonGazette` | the Gazette conditional (decision 10) |

  `deedpoll_applicantemail` is the **recipient**, not a placeholder — it is the address the row is sent to and
  must not become a personalisation key.

  **It addresses the applicant by their OLD name, and that is deliberate rather than an oversight.** The document
  opens *"Regarding the application to enrol a deed poll change of name for `deedpoll_oldforename`
  `deedpoll_oldsurname`"* — the new name is not theirs yet, and the deed the email carries is the instrument that
  changes it. Do not "improve" this to the new name.

  **`online_application_reference` is nullable and must resolve to an empty string, never be omitted** — Notify
  refuses a send whose personalisation is missing a key the template names, which is how every other template
  here handles it.

  Every key present in deterministic order, nullable case values as empty strings, matching
  `ApplicationRejectedPersonalisation`.
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
3. ~~**Decision 5's question has not been asked.**~~ **Answered 2026-09-25: `.docx` is fine for now**, because
   it duplicates the existing Power App process. Recorded here rather than deleted, because it was the right
   question and asking it removed more work than any amount of engineering would have. Kept as a reminder that
   the next such plan should ask before spiking.
4. **A new library and its licence.** Whatever task 1 settles on, check the CNP whitelist before depending on it.
