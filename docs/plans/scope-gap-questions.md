# Deed poll — functional parity gaps against the Power App

**Q0.1 is answered: the service needs functional parity with the Power App as a minimum.** Decided
2026-09-12. This file was previously a list of questions; it is now the evidenced gap analysis that
answer requires. The filename is kept deliberately — [`completed/gate-note-deletion.md`](completed/gate-note-deletion.md)
and [`role-model-generalisation.md`](role-model-generalisation.md) cite it, and one of those is a completed
plan whose citations should not be rewritten.

**Every claim below was verified live on 2026-09-12.** Verification commands are given so each row can be
re-checked rather than believed. Three claims in the previous version of this file did not survive that
check; they are recorded in "Corrections" rather than quietly dropped, because two of them had already been
carried into the workspace tracker.

---

## 1. What this was compared against, and what that can support

| Artefact | Location | Weight |
| --- | --- | --- |
| Permissions summary | [`../Deed Poll Power App Permissions Summary.docx`](../Deed%20Poll%20Power%20App%20Permissions%20Summary.docx) | **2,076 characters in total** — two roles, prose lists, an 11-row capability matrix. Primary, but *permissions-scoped*: it describes who may do what, not what the app does |
| Dataverse exports ×5 | `src/test/resources/reference/dataverse_tables/` | Primary. `deedpoll` is **alphabetically truncated at R**; the four child tables are complete (they reach V/W) |
| Business rules | `.../dataverse_tables/deedpoll/business_rules.md` | Primary. Seven rules, including the **complete** eight-state cascade at lines 67–101 |
| Power App screenshots | `power_app/screenshots/*.png` — **cited eight times in `deed-poll-admin` code, never committed** | **Absent.** `git log --all -- 'power_app/*'` returns nothing; no PNG is tracked in that repo at all |
| Conformance harness | `./gradlew test --tests "*Conformance*"` | **393 tests, 0 failures, 0 errors, 0 skipped** |

**There is no Power App application definition anywhere in the workspace** — no `.msapp`, no canvas export,
no screens, no formulas. This analysis is therefore complete **against the artefacts above**, and is not a
complete account of the Power App itself. Nothing here infers a Power App behaviour that no artefact states.

> `power_app_nest/` in `deed-poll-admin` is a gitignored earlier NestJS prototype, not a Power App artefact.
> Its own plan describes it as a port of these same sources. It is a lead, never evidence.

---

## 2. Corrections to the previous version of this file

These matter beyond tidiness: corrections 1 and 2 were carried into §7 of the workspace tracker, so the
board was tracking one gap that does not exist and one whose stated reasoning was unsound.

### Correction 1 — "five of the eight case states are unreachable" is FALSE

The previous §B said *"The service derives only three of them, from the enrolment and return dates… five
states currently have no way to be reached by a caseworker."*

All eight are derived, in both codebases, in the exact order the source specifies:

- `src/main/java/uk/gov/hmcts/reform/deedpoll/domain/deedpoll/CaseState.java:61-84`
- `src/test/resources/reference/ts/deed-poll/case-state.ts:45-52`
- against `.../dataverse_tables/deedpoll/business_rules.md:67-101`

All eight driving dates are **editable** in `deed-poll-admin/libs/deed-poll/src/deed-poll/form-fields.ts`
(lines 96, 115, 224, 225, 250, 251, 267, 286) — none carries `readOnly`. `StateCascadeConformanceTest` is
11 green tests.

**`state` being read-only is correct, not a gap.** In the Power App it is set by the business rule *Set
States based on Dates completed*, never typed. The route to a state is entering the date that implies it,
which is exactly what the service does. **This was never a gap and should come off the board.**

### Correction 2 — the deactivation argument was unsound

The previous §A said *"The Dataverse export for the main `deedpoll` table has no `statecode` or `statuscode`
across its 71 columns, while all four child tables have both. **That is not an export artefact** —
`createdon`, `createdby`, `ownerid` and `modifiedon` are present in both."*

The export is truncated at **"Refund Reference"**. `statecode`'s display name is **"Status"** and
`statuscode`'s is **"Status Reason"** — both **S**, past the cut. The four fields cited to rule out an export
artefact display as Created On, Created By, Owner and Modified On — **C, C, O and M, all before R**. Every
control falls inside the surviving range, so none of them can discriminate.

`src/test/java/uk/gov/hmcts/reform/deedpoll/reference/DataverseArtifacts.java:26-33` documents the truncation
at length, and names State among the post-R columns the export cannot reach.

**Deactivation is still a gap** (item 6 below) — but the previous reasoning about the deed poll record not
supporting it does not hold, and that question is now open rather than answered.

### Correction 3 — witnesses are NOT "maintained through the case itself"

The previous §D said *"The service has no witness write operations at all — witnesses are read as part of the
case and maintained through the case itself."* The first clause is right; the second is false.

`CreateCaseRequest` and `UpdateCaseRequest` each carry **67 properties, none witness-related**. There is no
route by which a witness can be written. This makes item 1 below considerably more serious than the previous
version implied, which asked only about *deleting* a witness.

---

## 3. The gaps

Verify the "Service today" column by listing every operation the contract declares. **Run from the
`deed-poll-api` repo root**, `deed-poll-admin` being a sibling clone:

```bash
python3 -c "import json;d=json.load(open('../deed-poll-admin/libs/deed-poll-api-client/openapi.json'));\
[print(m.upper(),p) for p,o in d['paths'].items() for m in o if m in('get','post','patch','delete')]"
```

That prints 14 lines: `GET /` plus the 13 caseworker operations. Note what is absent — no `/witnesses` path,
no `PATCH` or `DELETE` on documents, no write on `/caseworker/document-types`, and nothing for
deactivate / reactivate.

| # | Capability | Power App evidence | Service today | Verdict | Consequence |
| --- | --- | --- | --- | --- | --- |
| 1 | **Create / edit witnesses** | Matrix: "Add and edit witnesses **✓ ✓**". Prose: Basic User "Create, view and edit witness records". `deeppoll_witness` has 23 columns | **No witness write operation exists.** No `/witnesses` path; only a `WitnessResponse` schema; the two 67-property write schemas contain nothing witness-related | **GAP** | A witness can be *displayed* but **never recorded**. The only case in the service where data can be shown and never entered. Both roles lose a capability both had |
| 2 | **Delete witnesses** | Matrix: ✗ / ✓ | Absent | **GAP** | — |
| 3 | **Edit documents** | Prose: "Upload, view and **edit** supporting documents", both roles | `POST …/documents` and `GET …/{documentId}/file` only. No `PATCH` | **GAP** | A document recorded against the wrong type or description cannot be corrected |
| 4 | **Delete documents** | Matrix: ✗ / ✓ | Absent | **GAP** | — |
| 5 | **Manage Supporting Document Types** — create, update, delete | Matrix: ✗ / ✓ | `GET /caseworker/document-types` only | **GAP** | Reference-data changes require a release |
| 6 | **Deactivate records** | Matrix: ✗ / ✓ | Absent | **GAP**, and its nature is now clear | `DataverseSchemaConformanceTest.java:52-75` lists `statecode` and `statuscode` among **21 "Dynamics system columns present on every table"**, deliberately not ported. So this is very likely **stock Dataverse record deactivation** rather than a designed deed poll feature — the old Q A.2 — and the evidence favours it |
| 7 | **Reactivate closed records** | Matrix: "Reactivate records **✓ ✓**" | Absent | **GAP** | Same mechanism as 6; the two should be designed together |
| 8 | **`deedpoll_MigratedRecordID`** | Export row `Migrated Record ID \| Whole number`. Its sibling flag `deedpoll_MigratedRecord` **is** ported — `reference/deed-poll.prisma:112` | **Deliberately** not ported: `DataverseSchemaConformanceTest.java:290`, reason recorded as *"migration-provenance id; not needed in the rebuilt store"* | **DECISION TO REVISIT** | It is the natural join key from a migrated record back to its legacy identifier. That decision was taken before parity was the requirement, and it bears on the migration question below |
| 9 | **"Administrative record management"** / "Manage records requiring administrative intervention" | Matrix row, ✗ / ✓ | Cannot be assessed | **AMBIGUOUS — not guessed** | The document never defines the term. It could be a feature, or a restatement of the deletion and deactivation rights already listed. **Ask before scoping** |

---

## 4. Where parity is already met

Recorded so these are not re-investigated.

| Capability | Evidence |
| --- | --- |
| Create / view / edit deed poll records | `POST`, `GET`, `PATCH /caseworker/cases` |
| Create / view / edit notes | Full CRUD, five operations |
| **Delete notes, Administrator-only** | `domain/notes/NoteService.java:159` — takes the caller and throws `ForbiddenError`. Matches the document exactly |
| Upload / view documents | `POST …/documents`, `GET …/{documentId}/file` |
| View Supporting Document Types | `GET /caseworker/document-types` |
| **All seven business rules** | Six pinned by named conformance groups. Rule 1, *Default Minor Deed Poll to Single*, is implemented at `reference/ts/deed-poll/deed-poll-validation.ts:251` and `domain/deedpoll/DeedPollValidation.java:230` — it is a create default, so it is simply not a named gate group |
| **Field coverage** | Of 70 enumerable `deedpoll` columns: 21 system, 54 business, **all mapped** but for the three recorded non-ports (`MigratedRecordID`, `refundamount_Base`, `NoteID`). `DataverseSchemaConformanceTest` = 153 green tests |
| Dynamics option-set integers | `EnumValuesConformanceTest` = 44 green tests |

**The parity gaps are in operations, not in fields.** That is the useful shape of this result: the data model
was ported carefully, and what is missing is the ability to *write* parts of it.

---

## 5. One divergence where the service is more permissive than the document

**The Privacy field.** The document states flatly, under Basic User restrictions: *"Privacy field is
read-only."* But `domain/deedpoll/FormPresentation.java:94-99` permits a non-admin to edit Privacy on a
**new Deed of Relinquishment**.

That carve-out is ported from `OnLoadPrivacyControl` / `OnChangeType` — named Power App logic. So the app's
own behaviour appears to contradict its own permissions summary.

**Which is authoritative cannot be settled from the artefacts** — a summary document and ported app logic
disagree, and only the business can say which describes the intended rule. Under a strict reading of the
document this is a defect to close (one line plus two test updates). Under a reading that trusts the ported
logic it is correct as it stands. **Not resolved here.**

---

## 6. Not determinable from available evidence

Named as unknowns rather than filled in.

- **`deedpoll` columns S–Z: the count is unknown and they cannot be enumerated.** Some post-R columns are
  recoverable by name from other artefacts — Type, State, Urgency, Section, Retain Permanently, and (from
  `business_rules.md`) Signature Requested Date and Signature Received Date — but that is **not** a complete
  set and must not be read as one.
  **Action: ask the business for a complete A–Z `deedpoll` column export.** Per the reference README the fix
  belongs at source; `src/test/resources/reference/` is never edited here.
- **Whether the Power App's deed poll record actually supported deactivation.** Blocked by the truncation.
  Item 6 is a gap either way, but its shape depends on this.
- **What "closed" means** in "reactivate closed Deed Poll records" — one of the eight case states, or
  deactivated under another name. The two readings need different things built.
- **What "where permitted" qualifies** in the same sentence. It reads like a condition beyond the role.
- **Whether legacy data contains deactivated records.** Worth answering regardless, because it is about the
  data to be migrated rather than the features to be built — and item 8 is the field that would let a
  migrated record be traced back.
- **The Power App's screens.** The cited screenshots were never committed, so the port's layout decisions
  cannot be verified against their stated source.

---

## 7. A modelling note

`domain/auth/Role.java` has exactly **one** member, `DEEDPOLL_ADMIN`. The document describes two named roles
— Basic User and Administrator — plus a "Deed Poll User" role mapped to a "Deed Poll Users" security group.

For two roles, "admin or not" is functionally equivalent, so this is not a gap. It is worth knowing because
it bears on the open capability-indirection question in
[`role-model-generalisation.md`](role-model-generalisation.md): with parity now the requirement, items 1–7
above add write operations that need role gating, which strengthens the case for a capability table over a
scattered `isAdmin` boolean.

---

## 8. Suggested order

Not estimates, and not a plan — the plans are separate documents.

1. **Witnesses (items 1–2).** The only capability where data can be displayed and never entered, and the
   only one where a caseworker cannot complete a record at all.
2. **Ask the two questions that block design** — item 9's definition, and the complete column export in §6.
   Both are other people's answers, so raise them early.
3. **Documents (items 3–4).** Edit before delete; delete needs the retention and stored-file questions
   answered first.
4. **Deactivation and reactivation (items 6–7).** Design together, once §6's first bullet is resolved.
5. **Supporting Document Types (item 5).** Needs the "what happens to documents already recorded against a
   deleted type" question answered before deletion is built at all.
6. **`MigratedRecordID` (item 8).** Cheap, and best decided alongside the migration work rather than now.
