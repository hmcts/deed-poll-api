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

**⚠️ This paragraph has been corrected twice; the version below is the one the spike measured, and the middle
version was wrong.** The original ended *"and `customXml/item1.xml` is the bound data part"*. A correction on
2026-09-25 replaced that with *"It is not in the file … a dangling relationship"* and built Task 1's whole route on
its absence. **That correction was itself wrong.** Measured by `unzip -l` during Task 1:

| | |
|---|---|
| `customXml/item1.xml` | **present in both templates**, 130,472 bytes in the adult one |
| `customXml/itemProps1.xml`, `customXml/_rels/item1.xml.rels` | present too, and the `customXml` relationship in `word/_rels/document.xml.rels` resolves |
| `ds:itemID` | `{C9ADC254-…}` adult, **`{97E651D3-4026-41A5-9FD2-30805E1EDFD3}` minor** — per template, not a constant |

So there is nothing to *add* to the package. What there is instead is a data part whose **contents** are
unusable, which turns out to matter more — see Task 1.

**4. The bound fields all exist on `DeedPoll`.** Adult carries **30 content controls binding 18 distinct fields**;
minor carries **13 binding 13** — counted in Task 1 by listing every `w:xpath` in each `word/document.xml`. The
"15 distinct" this paragraph used to claim was a miscount and the table below was always right: 11 fields are on
both deeds, 7 more are adult-only (18) and 2 more are minor-only (13). The two sets differ by more than the
template text does:

| Bound name | Column | Notes |
|---|---|---|
| `deedpoll_newforename` / `_newmiddlename` / `_newsurname` | `newForename`, `newMiddleName`, `newSurname` | both |
| `deedpoll_oldforename` / `_oldmiddlename` / `_oldsurname` | `oldForename`, `oldMiddleName`, `oldSurname` | both |
| `deedpoll_nationality` | `nationality` | both, an enum |
| `deedpoll_section` / `_sectionother` | `nationalitySection`, `nationalitySectionOther` | both |
| `deedpoll_referencenumber` | **`displayReference`** — resolved, see below | both |
| `deedpoll_date` | **the application's date** — resolved, but read the caveat | both |
| `deedpoll_applicantbuildingnoname`, `_applicantaddress1`, `_applicantaddress2`, `_applicanttowncity`, `_applicantpostcode`, `_applicantemail` | the matching `applicant*` columns | **adult only** |
| `deedpoll_status` | **`maritalStatus`** — resolved, see below | **adult only** |
| `deedpoll_ageofminor` | `ageOfMinor` | **minor only** |
| `deedpoll_parents` | `parents` | **minor only** |

The witness blocks on both deeds are **blank** — witnesses sign by hand on paper, so nothing in
`DeedPollWitness` is merged into the deed. That is worth knowing before somebody tries.

### The three ambiguous mappings, resolved 2026-09-25

**`deedpoll_referencenumber` → `displayReference`.** Steve read the Dataverse table: it holds the **rendered**
value, `KBDP2026-00001`, not the integer serial. So the deed shows the reference an applicant and a caseworker both
recognise, and api's `referencenumber` integer is never printed.

**`deedpoll_status` → `maritalStatus`.** Confirmed by absence rather than by inference: the supplied data part
contains **no `deedpoll_maritalstatus` element** among its **111** (Task 1's count; the "93" here before was low,
because the 17 elements holding an escaped Word document defeated the tag strip that produced it), so
`deedpoll_status` is the only candidate — Dataverse simply named the column `status`. Note the part *also* carries
`statuscode`, which is Dataverse's own record state and is **not** bound by the deed; do not confuse the two.

**Confirmed a second way in Task 1**, by reading the rendered deed rather than the schema: the adult deed's visible
text puts the control under the label **"Status"**, between "Surname(s)" and "Nationality" in the applicant's own
details — a record-state code would not be printed on a deed the applicant signs.

**`deedpoll_date` → the application's date, and Steve's reasoning explains why it looked unmappable.** LOC020 is
part of the **old paper process**, where the applicant hand-wrote the date they completed the form. There is no such
moment in an online application, so the field has no natural equivalent — which is why the port left it out. Steve's
decision: take it as the date the application was created.

> **⚠️ One caveat, and it is the reason to pause over a one-line instruction.** "The date the application was
> created" reads most naturally as `createdOn` — but that is an **audit** column, and for a migrated case it is the
> *migration* date, not the application's. `migratedRecord` exists precisely because such cases exist, so a
> literal reading would print a wrong date on a legal document for exactly the cases whose paper origins make
> LOC020 relevant.
>
> **Use `dateReceived`.** It is the business date, it survives migration, and — the clincher — the reference number
> printed on the *same deed* takes its year from it (`ReferenceNumbers`: *"year: 4-digit year taken from Date
> Received"*). Using `createdOn` could therefore print `KBDP2026-00001` beside a 2027 date on one document.
>
> **Confirmed by Steve 2026-09-25: use `dateReceived`.** So this is settled, not pending — but it is recorded as a
> deliberate departure from the literal instruction rather than as the instruction itself, because the next person
> to read "the date the application was created" in a ticket will reach for `createdOn` again.
>
> ~~**Task 2 must still handle a null `dateReceived` deliberately** rather than printing an empty date on a
> deed. Decide and record what it prints: the state cascade treats the column as optional, so a case can reach a
> send without one.~~ **Decided in Task 2: it prints nothing** — no fallback column and no clock, because a
> blank is a line the applicant completes in wet ink and a plausible wrong date on a legal instrument is not
> recoverable by the person holding it. See `DeedValues.date`.
>
> **One thing Task 1 read off the rendered deeds that this decision should be held against: the two deeds label
> the same bound field differently.** Adult LOC020 prints it under **"Date you completed the form"**; minor LOC022
> prints it under **"Date the deed is made"**. `dateReceived` is a fair reading of the first and a poor reading of
> the second — the deed is made when the applicant signs it, which is after this document is generated. Neither
> label is a reason to change the decision, because no column holds a date that has not happened yet, but it is
> the sort of thing a content designer should see once rather than discover in a complaint.

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

**Baseline re-measured at the end of Task 1 and it is unchanged: 2,716 across 341 classes + 13 integration,
`skipped` 0, `failures` 0** (counted from `build/test-results/*/[*].xml` immediately after `./gradlew build`, which
took 4m). That is the expected result, since Task 1 adds two `.docx` files and no code — so this figure is the one
Task 2 should be read against.

**After Task 2: 2,789 across 351 classes + 13 integration, `skipped` 0, `failures` 0, `errors` 0** — `./gradlew
build` in 3m 49s. That is +73 tests across +10 result files (five test classes, one of which has six `@Nested`
groups and so contributes six files), and **every one of them is container-free**, so CI runs the whole of the
generator's coverage. The integration count is deliberately unchanged: nothing in Task 2 touches the database.

**After Task 3: 2,819 across 354 classes + 13 integration, `skipped` 0, `failures` 0, `errors` 0** — `./gradlew
build` in 3m 52s. That is +30 tests across +3 result files, of which **20 are container-free**
(`DeedFilenameTest` 9, `DeedDocumentTypesTest` 11) and 10 need Docker (`DeedDocumentServiceTest`, which asserts
what is in the row and on disk after a real commit — "one row and one blob" cannot be asserted against a mock
that was asked to save twice). The integration count is unchanged again: Task 3 writes through the existing
services, so it adds no integration-source-set test.

**After Task 4: 2,904 across 360 classes + 13 integration, `skipped` 0, `failures` 0, `errors` 0** — `./gradlew
build --rerun-tasks` in 4m 11s. That is +85 tests across +6 result files, of which **76 are container-free** —
Docker-free re-measured with `INTEGRATION_TESTS_ENABLED=false --rerun-tasks` at **1,908 across 253**, against
1,832 after Task 3. The new container-free classes are `FormsForCompletionTest` 15,
`CaseEmailServiceTest$FormsForCompletionEmails` 13, `EmailExclusionGroupTest` 12, `DocumentUploadsTest` 10 and
`FormsForCompletionPersonalisationTest` 8, plus additions to `EmailTemplateTest`.

**The 9 that need Docker are the ones worth knowing about**, because CI runs neither: 4 in
`NotificationRepositoryTest`, which is the only place V20's index ever executes, and 5 in
`CaseworkerEmailControllerTest`, which is the only place that index and the service's reading of it meet. Both are
covered container-free by proxy — `EmailExclusionGroupTest` holds `exclusivePerCase()` against V20's own SQL text,
which is the specific disagreement that would otherwise fail silently — but "the index refuses the row" is a claim
only Postgres can settle. The integration source set is unchanged again: Task 4 adds no integration-source-set test.

**After Task 5 — the final figures, and they are a re-measurement rather than a new number: 2,904 across 360 +
13 integration, `skipped` 0, `failures` 0, `errors` 0** — `./gradlew build --rerun-tasks` in 4m 19s. Identical to
Task 4's, which is the point of measuring it again: nothing in the branch depends on test-execution order or on a
result file left behind by an earlier run. Docker-free re-measured at **1,908 across 253** with
`INTEGRATION_TESTS_ENABLED=false --rerun-tasks` in 1m 10s, also identical.

**The 13 integration tests in that Docker-free count are stale and were not re-run** — checked by file
timestamp, which is the only way to tell: the `integration` result files were 8 minutes older than the `test`
ones. The note below about stale files is therefore not hypothetical; it caught something in this very task.

**Against the plan's baseline of 2,716 across 341 + 13, notify-4b added +188 tests across +19 result files and
no integration-source-set test at all.** Every task's figure above was re-derived from the XML rather than
quoted, and the integration count is unchanged from the baseline through all five tasks — this plan never
touched the database except through existing services and one migration whose only executor is
`NotificationRepositoryTest`.

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
- ~~**A new library is a dependency decision, not a detail.**~~ **Settled by Task 1: no new library.** The
  measurements that rejected `docx4j` and POI are recorded there rather than deleted, because a later PDF step will
  ask the same question.

---

## Tasks

### Task 1: The spike — populate the 30 content controls

**Much smaller than it was, and the route is now known rather than open.** `.docx` output is settled (decision 5),
so nothing here evaluates PDF conversion. And Steve supplied
`../deed-poll-admin/.business_context/doc_templates/Deed Poll Adult Deed_completed.docx` on 2026-09-25, which is
the missing half of the mechanism. **Read that file before writing anything.**

> ### ✅ RAN 2026-09-25. Read this block, not the instructions under it.
>
> **Both deeds generate, and they were driven by `java.util.zip` and `javax.xml` alone — no new dependency.**
> Verified independently: docx4j (spike-only, never added to this repo) loaded each output, discovered its data
> part, resolved all 30 / 13 binding XPaths to the case values, and extracted visible text reading
> *"Forenames(s) Robin … Surname(s) Okonkwo-Hart … Status Married"* with no `deedpoll_` placeholder anywhere.
> **Read "what the spike settled" below before Task 2; three of the instructions this task originally gave are
> wrong and one is dangerous.**

**What the supplied `_completed.docx` settles: nothing. It is a re-save of the template.** Measured, and it is the
reason most of the original route below is wrong:

| | |
|---|---|
| `customXml/item1.xml`, `itemProps1.xml`, `_rels/item1.xml.rels` | **byte-identical** to the template's — `md5sum` of each entry |
| `word/document.xml` | **`<w:body>` onwards is byte-identical**, 91,913 bytes both. The only difference in the whole part is the *order of the `xmlns:` declarations on the root element* |
| Everything else | differs only in `settings.xml`, `styles.xml`, `fontTable.xml`, `docProps/`, `[Content_Types].xml` — the fingerprint of a save, not a merge |

So it is not "the missing half of the mechanism" and there was no missing half: **the data part ships inside the
template.** Nothing was populated in the completed file, and the 30 controls still cache their own field names.

**It was therefore NOT vendored, against this task's instruction**, because vendoring a byte-identical duplicate
would plant the belief that it is an example of merged output, which is the belief that produced this task's wrong
route. The instruction rested on a false premise; the premise is recorded above so the next reader can check rather
than trust.

**What the spike settled:**

1. **There is nothing to add to the package.** `customXml/item1.xml` (130,472 bytes), `itemProps1.xml` and
   `_rels/item1.xml.rels` are all present in both templates and the relationship resolves. Replace one entry;
   invent no relationship.
2. **⚠️ Build the data part from scratch. Do NOT patch the supplied one.** Its 111 elements are the whole Dataverse
   entity, and each value is its own element name — *except* that in the adult template **17 of them hold a ~7 KB
   Word document escaped into the element text** (every bound field but `deedpoll_applicantemail`; it is how
   Dynamics exported the file, and it is what the 130 KB is). A generator that patched the supplied part would
   carry that junk into any element it did not overwrite. Building from scratch also dropped the output from 57,630
   to 49,599 bytes.
3. **⚠️ `ds:itemID` is per template, not the constant this plan claimed.** Adult
   `{C9ADC254-2098-471F-A6B0-379E83CFF8E7}`, minor **`{97E651D3-4026-41A5-9FD2-30805E1EDFD3}`**. Leave
   `itemProps1.xml` untouched and the question never arises; hard-code one GUID and the minor deed's 13 bindings
   resolve against nothing. The spike asserts every `w:storeItemID` in the output equals its own part's `ds:itemID`
   — **Task 2 should keep that assertion**, because its failure is silent.
4. **Root shape, confirmed:** `<DocumentTemplate xmlns="urn:microsoft-crm/document-template/deedpoll_deedpoll/10740/">`
   then `<deedpoll_deedpoll xmlns="">`. The namespace **reset** on the child is load-bearing: the bindings' XPath is
   `/ns0:DocumentTemplate[1]/deedpoll_deedpoll[1]/deedpoll_newforename[1]`, so the root is in `ns0` and everything
   below it is in no namespace. Get that wrong and every binding silently resolves to empty.
5. **Write BOTH halves — the data part *and* the cached text — and `word/document.xml` is therefore modified.**
   This task's route 1 ("`word/document.xml` needs no modification at all") is a trap: the cached run text is what a
   reader that does not resolve bindings displays, and the applicant opens this file in whatever they have. Leaving
   it alone ships a deed reading *"Forenames(s) deedpoll_newforename"* to anyone not using Word. **Decision 4 is
   still honoured**: the only bytes changed in the body are the `w:t` contents *inside* the 30 content controls, and
   the court's wording is untouched. Every control on both templates holds **exactly one `w:t`** and none is nested,
   which is what makes that edit safe rather than delicate — **Task 2 should assert those two facts** rather than
   assume them, since a re-authored template could break either.
6. **XML escaping is the generator's job.** A value of `British & <Commonwealth>` round-tripped correctly through
   both halves only because both escape; docx4j read it back intact.

**The library decision: none, and that is a decision rather than an omission.** Measured on 2026-09-25 by resolving
each in a scratch Gradle project:

| | Resolved weight | Licence | Verdict |
|---|---|---|---|
| **The JDK** (`java.util.zip`, `javax.xml`) | 0 | — | **chosen** |
| `org.docx4j:docx4j-core:11.5.0` + `docx4j-JAXB-ReferenceImpl` | **31 jars, 16.3 MB** — incl. `xalan-interpretive` 1.8 MB, `fontbox` 1.5 MB, `jaxb-runtime` | Apache 2.0 | rejected |
| `org.apache.poi:poi-ooxml:5.4.1` | **13 jars, 18.5 MB** — incl. `poi-ooxml-lite` 5.9 MB, `xmlbeans` 2.2 MB | Apache 2.0 | rejected |

Both licences are fine. The weight is not what either would buy: docx4j's binding support was the argument for it,
and the spike does the same job in ~120 lines because the input is two fixed templates vendored in this repo, not
arbitrary user documents. **The CNP whitelist question is moot for a change that adds nothing** — recorded because
it was asked; the gate actually visible in this repo is `dependencyCheck` against `config/owasp/suppressions.xml`,
and docx4j's `xalan` would have been the argument there. **docx4j was still worth the spike**: it read back a
document the spike's own regexes wrote, which is the only reason to believe the output rather than the code.

- **Templates vendored at `src/main/resources/deed-templates/`** — `loc020-adult-change-of-name-deed.docx` and
  `loc022-minor-change-of-name-deed.docx`, byte-identical copies (`md5sum` checked) of `deed-poll-admin`'s
  `.business_context/doc_templates/`. `README.md` there records provenance, the per-template `itemID`s, the junk
  data part, and **that a template change is now a commit in this repo** with no automation to notice a divergence.
- **No spike code remains**: all of it ran from `/tmp` as single-file `java` programs, and the docx4j evaluation
  from a scratch Gradle project outside the repo. This task adds two `.docx` files, a `README.md` and this record.
- [x] every bound field on the real adult template is populated from case data — 30 bindings / 18 distinct fields,
      and the minor deed's 13 as well; **values confirmed in the right places by extracting the rendered text**
      rather than by a human opening Word, which this devcontainer has no way to do (the extraction shows each
      value adjacent to its own printed label, which is the thing a human would have been checking);
      `word/document.xml` **is** modified, deliberately and only inside the 30 controls — see settled point 5;
      the templates' home is `src/main/resources/deed-templates/`; the library decision is **none**, with docx4j's
      and POI's measured size and licence recorded above; no spike code remains

### Task 2: The generator

Read first: evidence 4, decisions 3 and 4, and whatever task 1 settled.

- A generator that takes a `DeedPoll` and a type and returns bytes. Adult binds **18** distinct fields across 30
  controls, minor 13 across 13 — **bind from the columns evidence 4 lists**; flag 1's three mappings are resolved
  and Task 1 confirmed `deedpoll_status` a second way.
- **Task 1's settled points 1–6 are the implementation.** In particular: build the data part from scratch rather
  than patching the template's, leave `itemProps1.xml` alone so the per-template `itemID` looks after itself, and
  write the cached `w:t` as well as the data part. Keep Task 1's three silent-failure assertions — every
  `w:storeItemID` equals its own part's `ds:itemID`, every control holds exactly one `w:t`, and no control is
  nested.
- Enum and nullable handling matches the existing personalisation: a null column becomes an empty string, never
  the literal `null`, and never the enum's `name()` where a label is wanted.
- Tests: every bound field reaches the output for both types; a null optional column produces an empty binding
  rather than breaking the document; the adult and minor field sets differ exactly as evidence 4 says.

> ### ✅ RAN 2026-09-25. Read this block before Task 3.
>
> **`domain/deeds/`, no new dependency, 73 container-free tests.** `DeedGenerator.generate(DeedPoll,
> DeedTemplate)` returns a `GeneratedDeed`; `DeedTemplate` has two members and **says nothing about
> `DeedPollType`**, so decision 3's rule is still stated once, in notify-4a task 2, and Task 4 supplies the
> template. `DeedOutputFormat` is decision 5's seam: `GeneratedDeed.mimeType()` comes off the format, so Task 3
> must not write the `.docx` mime type as a literal.
>
> **Six things this task settled that the instructions above did not say:**
>
> 1. **A null `dateReceived` prints nothing, and that is the recorded decision** the caveat in evidence 4 asked
>    for. No fallback column and no clock: `createdOn` is an audit column and for a migrated case is the
>    migration date, and `now()` would assert a fact nobody recorded. A blank is a line the applicant completes
>    in wet ink, which is what LOC020 always was. `DeedValues.date` holds the reasoning.
> 2. **`deedpoll_section` renders "Under section Section 1 of the British Nationality Act 1981", and that is
>    faithful rather than a bug.** Dataverse bound the control to the option's *label*, and the vendored
>    reference's `NATIONALITY_SECTION_LABEL` is `Section1 → "Section 1"`, so the existing process printed the
>    same duplication. **A content designer should see this once**; changing it is a change to the court's
>    template, not to this code. Same class of finding as the two deeds labelling `deedpoll_date` differently.
> 3. **The data-part namespace, root element and entity element are read from the template's own bindings rather
>    than held as constants.** Getting any of the three wrong resolves every binding to empty with no error, and
>    a constant can go stale against a re-authored template where a value read from the template cannot. The
>    documented values are still asserted (`10740`, `DocumentTemplate`, `deedpoll_deedpoll`) so a template swap
>    that changes them is visible.
> 4. **Task 1's three assertions are kept, and a fourth was added**: the binding's prefix must actually be
>    mapped by its own `w:prefixMappings`. All four now fail the build through `DeedControlsTest` and
>    `DeedGeneratorTest$Refusals`, which drive synthetic faulty packages through a package-private
>    `generateFrom` — the vendored templates are correct, so they cannot demonstrate a refusal.
> 5. **The output is byte-deterministic** (every zip entry carries a fixed 1980-01-01 timestamp), which is what
>    makes Task 3's "the stored bytes are what the generator produced" a question answerable twice.
> 6. **"The court's wording is untouched" is a check, not an intention.** Emptying every `w:t` in the generated
>    `word/document.xml` gives the template's part back byte for byte, and exactly 30 / 13 text runs differ —
>    each one a run that held a `deedpoll_` name. `DeedPackages` shares no code with the generator and resolves
>    each binding with a real XPath engine, which is the docx4j check from the spike without the 16.3 MB.
>
> Both mutations were tried to prove the suite bites: a data part in the wrong namespace fails 8 tests, and
> skipping the cached-text write fails 13.

- [x] both deeds generate from real case data with every bound field populated, nulls degrade to empty, and
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

> ### ✅ RAN 2026-09-25. Read this block before Task 4.
>
> **`DeedDocumentService.storeDeed(caseId, DeedTemplate)` in `domain/deeds/`, no migration, 30 tests — 20 of
> them container-free.** It writes through `DocumentService.uploadDocument`, so the blob goes to the existing
> `DocumentStore` seam with that method's rollback cleanup behind it rather than through a second upload path.
> `Outgoing` is passed explicitly, because `DocumentService`'s default is `Incoming`.
>
> **Five things this task settled that the instructions above did not say:**
>
> 1. **No suitable `DeedPollSupportingDocumentType` row exists — in any environment — so the service creates
>    its own on first use.** Checked as instructed: `V3__children.sql` creates the table empty, no migration
>    has ever inserted a row, the Dataverse export names no rows either, and a caseworker cannot make one
>    because the type *write* operations deliberately have no HTTP surface. A migration insert was rejected
>    rather than overlooked: it would not remove the runtime check (the row can still be absent, and a service
>    that then threw would refuse an applicant their deed over reference data), and a seeded fixed id would be
>    wiped by any test that clears the table — `DocumentServiceTest` and `DocumentTypeServiceTest` both
>    `deleteAll()` against the shared container database, so a test asserting a seeded row would pass or fail
>    on class order. Find-or-create is idempotent, self-healing and order-independent. **The lookup key is the
>    type's NAME**, which is safe only while nothing can rename a type: if a rename endpoint is ever exposed,
>    that key has to become a stable column, and the comment on `DeedDocumentTypes` says so.
> 2. **Two type rows, and `DeedDocumentTypes.caseTypes` is now the only statement in this repository of which
>    `DeedPollType` takes which deed.** `applicableTypes` cannot be filled without one, and decision 3 is
>    emphatic that the rule is stated once — so LOC020 is `{Adult, AdultTransgender}` (Dynamics 1, 2) and
>    LOC022 `{Minor, MinorTransgender}` (3, 4), from `DynamicsValues` rather than `ordinal()`.
>    **Task 4 must derive its routing from this or pin itself against it, not restate it.**
>    `DeedOfRelinquishment` and `Unknown` are on neither list, asserted as decisions rather than left as a gap:
>    flag 5 is open, and `Unknown` has no safe default.
> 3. **"At most once per case" is per CASE, not per template, and a mismatch is REFUSED.** A lookup narrowed to
>    the requested deed would answer "none yet" for a case whose type was corrected between two requests and
>    store a second legal instrument for one applicant. So both deed types are looked at.
>    ~~and a mismatch **returns the existing deed with a warning** rather than adding to it … Not reachable
>    through the emails anyway, which the exclusion group makes mutually exclusive per case.~~ **Corrected
>    during review, and the reasoning struck through above was wrong on its own terms.** The mismatch *is*
>    reachable: V20's index, like V15's, excludes a `Failed` row with a `PERMANENT` verdict — deliberately, so a
>    send Notify certainly never made can be replaced — so a case whose first forms email failed permanently and
>    whose type was then corrected is offered the *other* forms email with nothing in the outbox in the way.
>    Returning the stored deed there emails an applicant a minor's covering letter carrying an adult's deed,
>    with only a log line to say so. `storeDeed` therefore throws `ConflictError`: the stored deed is neither
>    replaced (it may already be signed in wet ink) nor handed back (the caller is about to put it in front of
>    an applicant under the other deed's covering words), and the caseworker resolves the contradiction.
>    **A second review pass added the same refusal for a document filed under a deed's type that the court never
>    sent out.** `DeedDocumentTypes` creates real `deedpoll_supportingdocumenttype` rows, so from the first deed
>    generated in an environment onward `GET /caseworker/document-types` publishes them and the upload endpoint
>    accepts them as any other `documentTypeId` — and the obvious thing to file under "LOC020 Change of name
>    deed" is the signed deed coming back from the applicant. Keyed on the type's name, the lookup cannot tell
>    that row from a generated one, so answering with it would email the applicant their own returned paperwork
>    as the deed to print, sign and swear. The direction decides: the court's deed went `Outgoing`, and anything
>    else under a deed's type is refused. Refused rather than skipped, because an `Incoming` row is either that
>    or a generated deed whose direction a caseworker patched, and the second would have the service store a
>    *second* deed — which is why `existingDeed` still reads both directions rather than narrowing the query.
> 4. **Idempotency is a read-then-write in the caller's transaction, and there is deliberately no unique
>    index.** The type row's id is data rather than a constant, so there is no predicate a partial index could
>    carry to mean "a deed of a case". What closes the race in practice is the caller: the send that asks for a
>    deed is already guarded by the outbox's unique indexes and shares this transaction. A caller outside that
>    path has to serialise itself, and the service's javadoc says so.
> 5. **The filename is `LOC020-change-of-name-deed-KBDP2026-00001.docx`** — form code as the court writes it,
>    what the document is in words, the reference as the applicant has seen it, and the extension off
>    `DeedOutputFormat` rather than a literal (decision 5). Hyphenated ASCII because it travels through a
>    Notify attachment, an email client and an unknown filesystem; the reference is cleaned rather than
>    trusted, since a name assembled from a column is the shortest route to a path in a response header; and a
>    case with no `displayReference` gets the name without it rather than `...deed-null.docx`.
>
> The mutation was tried to prove the suite bites: disabling the existing-deed check fails 2 tests — the one
> row/one blob/one generate claim and the "keeps the deed it already holds" one.

- [x] a deed is stored exactly once per case as an `Outgoing` document with a recognisable filename, a second
      request returns the first, and `./gradlew build` green

### Task 4: The two templates, their personalisation, and exclusivity

Read first: decisions 1, 3, 7, 8, 9 and 10.

- Two `EmailTemplate` members with no slots (decision 7), chosen by a `switch` over all four `DeedPollType`
  members with no `default` (decision 3). **Task 3 left one statement of which case type takes which deed** —
  `DeedDocumentTypes.caseTypes`, which the document-type row's `applicableTypes` needs — so derive this routing
  from it or pin the two together in a test; do not write the lists a second time.
> **If notify-4a has not run, build the minimum upload seam here rather than stopping.** Task 4 needs a document
> reference to become a `prepareUpload` at drain time, which is notify-4a's Task 1. After the 2026-09-25 Notify
> discovery — the five static forms are attached to the *template* in Notify, so api never touches them — **the deed
> is the only document api supplies**, and that seam is now a handful of lines rather than a subsystem. So: if it
> exists, use it; if not, build it for one document, and record in Task 5 that notify-4a's Task 1 is thereby done
> and its plan needs amending. Do **not** wait, and do **not** build storage for the static forms — Notify holds
> them.

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

> ### ✅ RAN 2026-09-25. Read this block before Task 5.
>
> **Two `EmailTemplate` members, one migration (V20), one new personalisation record, the upload seam, and 85
> tests — 76 of them container-free.** The routing is derived rather than restated: `DeedRouting.deedFor` (in
> `domain/deeds`, searching `DeedDocumentTypes.caseTypes` rather than holding a list) answers which deed a type
> takes, and `notify/FormsForCompletion` is the only place `EmailTemplate` and `DeedTemplate` meet.
> `FormsForCompletionTest` holds the two switches to each other and to the document-type rows, so no case-type
> list exists twice.
>
> **Nine things this task settled that the instructions above did not say:**
>
> 1. **notify-4a's Task 1 is done, and its plan needs amending.** The seam is `notify/DocumentUploads` plus the
>    sealed `ResolvedDocuments`, called from `NotifyEmailSender` immediately before `sendEmail`. It resolves
>    `EmailTemplate.documentKeys()` — never arbitrary personalisation — through `DocumentService.downloadDocument`,
>    which is case-scoped, so a stored id cannot fetch another applicant's deed. All of 4a's decisions 3, 4, 7 and
>    8 are implemented and asserted: the frozen row survives a send unchanged, `confirm_email_before_download` is
>    always true, a missing or unreadable document is `TECHNICAL`, and an oversized one is refused **before**
>    `sendEmail` with a sentence naming the document and the limit. 4a's Tasks 2–4 are untouched.
> 2. **The retention period is configuration with no default, and it is part of being provisioned.**
>    `notify.document-retention-weeks` (`NotifyProperties.documentRetentionWeeks`, `retention()`), and
>    `isProvisioned` now asks **three** questions rather than two: a template that carries a document is
>    unprovisioned without a period, exactly as one that invites a reply is without a reply-to. Not positive means
>    unconfigured — a chart setting `0` would otherwise send an applicant a link to nothing. Unset in every
>    environment, so ① and ② read unprovisioned today for two reasons rather than one. **Weeks and not a
>    `ChronoUnit`**: that is the unit Notify documents, so it is a narrowing rather than a limitation — and it was
>    not verifiable from this devcontainer, which cannot reach Notify's docs.
> 3. **V20's index names the groups it applies to, positively, in an `IN` list.** A case-scoped key over every
>    null-referral row would have made the second *chase* impossible, which notify-3's decision 5 forbids. An
>    exclusion list (`<> 'DOCUMENT_CHASE'`) was rejected because it makes every future group exclusive by default
>    and the failure is a legitimate email refused in production. The price is that the enum and the SQL now state
>    one fact twice, so `EmailExclusionGroup.exclusivePerCase()` is **held against V20's own text** by
>    `EmailExclusionGroupTest` — container-free, because only `NotificationRepositoryTest` executes the index and
>    CI has no Docker. Both directions of that disagreement fail silently: unenforced exclusivity sends two deeds,
>    and an index the service cannot read turns an ordinary conflict into a 500.
> 4. **`resolveRefusedInsert` now has four answers and a third read.** `findBlockingForCase` is V20's index read
>    back through a statement repeating its predicate, asked only where the referral is null *and* the group is
>    exclusive per case. The 409 sentence is per scope — `alreadyRequestedOnThisCase` says "a case gets one of
>    these emails: an applicant signs and swears one deed", because "one referral gets one" would be about nothing.
> 5. **A preview cannot generate the deed, so `compose` takes a function.** `preview` is a read that writes no row
>    and changes no state; decision 6 makes the deed a once-per-case write with a blob behind it. So the send
>    passes `deed -> deeds.storeDeed(...).getId()` and the preview passes a fixed sentence
>    (`DEED_NOT_YET_GENERATED`) — a function rather than a flag, so the preview path has no way to store one. It
>    could not show the real link anyway: Notify creates one only when given the bytes.
> 6. **The deed is stored inside the send's transaction, which is what makes a refusal clean.** A 409 or a reused
>    request id rolls the document row and its blob back with the outbox row, so a case never holds a deed for an
>    email nobody was owed. It also makes the idempotent answer work: `storeDeed` is generate-once, so a retry
>    freezes the same id and `isTheSameSend` compares equal.
> 7. **`signatureRequestedDate` is read and never written** (decision 9, flag 2). The **column** and not `state`:
>    `dateReturned`, `dateEnroled` and `signatureReceivedDate` all outrank the signature request in the cascade, so
>    a rule written against the derived state would offer the forms again to an applicant whose deed had been
>    sealed. **One gap is recorded rather than closed:** decision 9's other half — "the application has been
>    checked" — has no column on `DeedPoll`, and nothing in the cascade models it. So the only enforced condition
>    is the signature date, and a case that is `Returned` or `Enroled` *without* a signature ever having been
>    requested is still offered the forms. That is decision 9 implemented literally; see Task 5.
> 8. **Four refusal sentences for two conditions, because a caseworker does four different things.** An unset type
>    and `Unknown` share one (`TYPE_NOT_ESTABLISHED`) because both mean "set the type"; `DeedOfRelinquishment` has
>    its own, because its type *is* established and what is missing is the document set (4a flag 5); the wrong
>    template for the type composes its sentence from the deed the case does take, so it cannot drift from the
>    routing; and `SIGNATURE_ALREADY_REQUESTED` names the column. ③′ is deliberately **not declared** — that is
>    notify-4a Task 2's, and its documents are unidentified.
> 9. **`CaseworkerEmailControllerTest.FormsForCompletion` is the only place the index and the service's reading of
>    it meet**, and it needed a temporary `local-store-root`: a forms send really runs the generator and really
>    writes a `.docx`, which under the shipped `data/documents` default would have left files in the working tree.
>    It drives the whole journey over HTTP against real Postgres and `StubNotify` — availability, send, a real
>    `prepareUpload` in the request body, the row unchanged afterwards, a second journey answered with the first
>    receipt, and the contradictory send as a 409 naming the first email.
>
> **`EmailNotes` needed no change**, but the two display names had to differ ("Forms for completion" and "Forms
> for completion for a child"): the note is composed from the display name alone, and one name on both would leave
> the case's only timeline unable to say which deed an applicant was sent.

- [x] both templates send with their documents, the four `DeedPollType` members route correctly, a case cannot
      receive both, and `./gradlew build` green

### Task 5: Full validation and handoff

- Full validation commands with `--rerun-tasks`, counts recorded against the baseline, `skipped` confirmed 0.
- Record what notify-2 needs, if anything: **a template with no slots and a document attached may break notify-2
  task 4's assertion that the Email tab needs no change per template.** If it does, that is a finding to report,
  not a change to make quietly in `deed-poll-admin`.
- Confirm every `[ASSUMED]` or provisional statement introduced during the spike is resolved.

> ### ✅ RAN 2026-09-25. This is the end of the plan; read this block and the flags.
>
> **Counts are in Validation Commands above, re-measured both ways and identical to Task 4's.** No code changed
> in this task — it is a measurement, an answer and three handoffs.
>
> **The notify-2 question, answered in three parts, because it is three questions wearing one coat.**
>
> 1. **notify-2 Task 4's assertion holds: the Email tab needs no change for either template.** Read in
>    `deed-poll-admin` rather than assumed. `emails-view.ts`'s `sendableTemplates` filters api's `availability`
>    rows on applicable **and** provisioned **and** a usable key, and `buildUnsendable` turns the rest into a
>    sentence — so today ① and ② appear on the tab as *unsendable* carrying notify-2's own `NOT_PROVISIONED`
>    wording, which is true and actionable, and the moment notify-0 provisions them they appear as two more
>    buttons. Task 4 already pinned that with a test rendering two. Nothing to change.
> 2. **The attachment needs no change either, and notify-2 said so before it could check.** `email-detail.njk`
>    branches on `email.documents.length` and renders a `<ul>` of `document.name`, falling back to "None" — and
>    api publishes `CorrespondenceDocumentResponse(id, name)` where the name is Task 3's filename, so the
>    per-email page will read `LOC020-change-of-name-deed-KBDP2026-00001.docx` with no edit. What is now stale is
>    notify-2's *prose*, not its code: its "What is on the wire but out of scope" section says the list is "empty
>    for every email today". A comment, not a defect.
> 3. **What does break is the COMPOSE step, which is notify-2 Task 5 and not Task 4 — and it is reported here,
>    not fixed in `deed-poll-admin`.** `slots()` is `List.of()` (decision 7), so `buildComposeView` returns
>    `fields: []` and `email-compose.njk` renders a form with **no controls at all**: a caption, a heading, an
>    intro, the hint *"Anything already filled in comes from the case and the Master's direction"* — which names
>    a direction these emails have nothing to do with — and a Continue button. **It works**: `validateEmailSlots`
>    over an empty field list is `ok` with `{}`, `preview` posts an empty slot map, and api's `EmailSlots.parse`
>    accepts it for a slotless template. So no test fails and no send is refused; a caseworker simply reads a
>    page that looks half-loaded. Two answers are available and **both are admin's to choose** — skip straight to
>    the mandatory check page when a template has no slots, or render a page that states what will be sent and
>    offers Continue without a form. See flag 6. Decision 2 of notify-2 makes the *check* step mandatory, so
>    whichever is chosen, the preview must stay.
>
> **No `[ASSUMED]` or `[PROVISIONAL]` marker survives anywhere** — grepped across `src/main/java`,
> `src/test/java`, `src/main/resources` and this file, along with `TODO`/`FIXME`/`XXX`; the only hit was Task 5's
> own instruction to look. The spike's three wrong statements were corrected in place rather than deleted (the
> data part's presence, the per-template `itemID`, and "`word/document.xml` needs no modification"), each with the
> measurement that settled it, because all three were believed on reasonable grounds.
>
> **Three things are open rather than provisional, and the distinction matters — nobody should read them as
> loose ends in the code.** Flag 2 (`signatureRequestedDate`) and notify-4a's flag 5 (the relinquishment deed)
> are business questions this plan deliberately declines to answer; the new flags 5–7 below are two gaps and one
> unverifiable claim, all recorded at the point they were found.
>
> **What this plan hands to notify-0, in full, because "unprovisioned" is three separate settings.**
> `charts/deed-poll-api/values.yaml` needs `NOTIFY_TEMPLATE_FORMS_FOR_COMPLETION_ADULT`,
> `NOTIFY_TEMPLATE_FORMS_FOR_COMPLETION_MINOR` and `NOTIFY_DOCUMENT_RETENTION_WEEKS`; `NOTIFY_EMAIL_REPLY_TO_ID`
> is already there and both templates need it, since both say "you can reply to this email". The content draft
> lives at `deed-poll-admin`'s `.business_context/notify-templates/forms-for-completion.md`. **And the deployment
> blocker is not in this plan at all**: `DOCUMENT_STORE_ROOT` (notify-4a flag 3) — the deed is written through
> `DocumentStore`, so an environment without a real store cannot send either email however well Notify is
> configured.
>
> **Two findings a content designer should see once**, both recorded in Task 2 and repeated here so the handoff
> carries them: `deedpoll_section` renders "Under section **Section 1** of the British Nationality Act 1981"
> because Dataverse bound the control to the option's label, and the two deeds label the same bound date
> differently ("Date you completed the form" / "Date the deed is made"). Both are the court's templates behaving
> exactly as they always did; changing either is a template change, not a code change.

- [x] final counts recorded against the baseline, the notify-2 question answered either way, and no provisional
      statement left standing

---

## Deliberately not in this plan

- **The five blank forms and the Notify file-sending seam.** notify-4a.
- **Setting `signatureRequestedDate` on send.** Flag 2 — a business question.
- **Fixing `DOCUMENT_STORE_ROOT`.** notify-4a's flag 3, and a deployment blocker for both halves.
- **Re-authoring the deed as a Docmosis template.** Decision 2 and evidence 3.

## Flags

1. ~~**Three field mappings inferred and not verified.**~~ **All three resolved 2026-09-25 — see evidence 4.**
   `deedpoll_referencenumber` is the **rendered** value (Steve, read off the Dataverse table), so
   `displayReference`. `deedpoll_status` is **marital status**, confirmed by absence: the data part has no
   `deedpoll_maritalstatus` element, so `deedpoll_status` is the only candidate and api's `maritalStatus` is the
   column. `deedpoll_date` is the application's date — **but see the caveat in evidence 4 about which column that
   is**, because it is the one place where following the instruction literally would be wrong for migrated cases.
2. **Should sending ① or ② set `signatureRequestedDate`?** Decision 9 establishes that sending *is* the
   signature request, so the date arguably follows — but that changes the derived state, and nothing else in this
   service sets a case date as a side effect of an email. A business decision, deliberately unanswered.
3. ~~**Decision 5's question has not been asked.**~~ **Answered 2026-09-25: `.docx` is fine for now**, because
   it duplicates the existing Power App process. Recorded here rather than deleted, because it was the right
   question and asking it removed more work than any amount of engineering would have. Kept as a reminder that
   the next such plan should ask before spiking.
4. ~~**A new library and its licence.**~~ **Closed 2026-09-25: there is no new library.** Task 1 drives both
   templates with `java.util.zip` and `javax.xml`. docx4j (31 jars, 16.3 MB, Apache 2.0) and POI (13 jars, 18.5 MB,
   Apache 2.0) were measured and rejected on weight, not licence — the measurements are in Task 1 so a later PDF
   step does not have to redo them.
5. **Half of decision 9 is not modelled anywhere, so only half of it is enforced.** Raised by Task 4 and left
   open deliberately. "The application has been checked" has **no column on `DeedPoll`** and nothing in the state
   cascade stands for it, so the only condition the service can enforce is `signatureRequestedDate IS NULL`. A case
   that is `Returned` or `Enroled` *without* a signature ever having been requested is therefore still offered the
   forms — reachable only through a data path that skipped the request, but reachable. Closing it needs a business
   answer about what "checked" is and a column to hold it; guessing one here would put an availability rule on a
   field nobody defined. Decision 9 is implemented literally in the meantime.
6. **`deed-poll-admin`'s compose page renders an empty form for a slotless template**, and that is a finding for
   that repo rather than work for this one — Task 5 has the reading and the two candidate answers. It is cosmetic
   rather than functional: the journey completes and the email sends. It is listed as a flag because it is the one
   thing in either repo that notify-4b changes the behaviour of without being able to fix from here, and because
   the hint text naming "the Master's direction" on a forms email is the sort of wrong sentence that survives for
   a year.
7. **"Weeks" as Notify's retention unit is documented but not verified from here.**
   `notify.document-retention-weeks` narrows configuration to the unit GOV.UK Notify states in its own
   documentation for `prepareUpload`'s retention period — but this devcontainer cannot reach that documentation,
   so the claim rests on the API's published shape as remembered rather than as read. If it turns out Notify
   expects something else, the fix is one property and one call site (`NotifyProperties.retention`), and
   `isProvisioned` already refuses to send without a positive value — so the failure mode of being wrong is a
   rejected send at provisioning time, not a document that quietly expires.
