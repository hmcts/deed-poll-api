# deed-templates

The two Word templates the deed generator populates, vendored here as classpath resources.

| File | Form | Source |
|---|---|---|
| `loc020-adult-change-of-name-deed.docx` | LOC020, adult change of name deed | `hmcts/deed-poll-admin`, `.business_context/doc_templates/Deed Poll Adult Deed.docx` |
| `loc022-minor-change-of-name-deed.docx` | LOC022, minor's change of name deed | `hmcts/deed-poll-admin`, `.business_context/doc_templates/Deed Poll Minor Deed.docx` |

Both are **byte-identical copies** of those files as at 2026-09-25. They are copied rather than read
from the sibling repo because `deed-poll-admin/.business_context/` is not on this service's runtime
path and never will be — a deployed pod has only this artifact. **The cost of that is real: a change
to either template is a commit in this repo**, and a template changed only in `deed-poll-admin` will
not reach production. There is no automation that notices, so the two copies can diverge silently.

## What is in these files, and why nothing here may be retyped

Each is a Dynamics 365 Word template: the court's legal wording as ordinary body text, with the
case's values held in `w:sdt` content controls that carry an XPath data binding — not merge fields,
not `<<field>>` tokens. The generator populates them without touching a word of the body, which is
how "the court owns the wording, engineering does not" is enforced mechanically rather than by care.

| | LOC020 adult | LOC022 minor |
|---|---|---|
| Content controls | 30 | 13 |
| Distinct bound fields | 18 | 13 |
| `ds:itemID` | `{C9ADC254-2098-471F-A6B0-379E83CFF8E7}` | `{97E651D3-4026-41A5-9FD2-30805E1EDFD3}` |

**The `itemID` differs per template, so it is not a constant to hard-code.** Read it from the
template's own `customXml/itemProps1.xml`, or — better — leave that part alone and let it be whatever
the template says. Every `w:storeItemID` in a template's `word/document.xml` equals its own part's
`ds:itemID`; a generator that wrote one template's GUID into the other would produce a document whose
30 bindings resolve against nothing.

**`customXml/item1.xml` ships inside both templates, and its contents are unusable.** It has 111
elements — the whole Dataverse entity, not the bound subset — and each element's value is its own
name, except that in the adult template 17 of them (every bound field but `deedpoll_applicantemail`)
instead hold a ~7 KB Word document escaped into the element text, which is an artifact of how
Dynamics exported the file. **Build the data part from scratch; do not patch the supplied one**, or
every element the generator does not overwrite carries that junk into a legal document.

See `docs/plans/notify-4b-generate-the-deed.md`, Task 1, for the measurements and the route.
