# Deed poll — functional scope gaps: questions

## Why this exists

The business supplied
[`Deed Poll Power App Permissions Summary.docx`](../Deed%20Poll%20Power%20App%20Permissions%20Summary.docx),
which describes the **Power App** this service is a port of. Comparing it against what the service actually
does surfaced capabilities the Power App has and this service does not.

They are not defects. The service was deliberately built as *"only the operations something actually
calls"* — derived from what the staff UI does — so these are gaps against the **legacy system**, not against
this service's own design. Presence in the Power App is not by itself a requirement.

Questions only, deliberately. Nothing here is planned, scoped or estimated until answered, because
answering "not required" is a perfectly good outcome for most of them and cheaper than designing first.

Spans both `deed-poll-api` (the operations) and `deed-poll-admin` (the screens).

---

## 0. The framing question, which may make most of the rest moot

**Q0.1 — Does the new service need functional parity with the Power App, or is the reduced surface the
intended scope?**

Everything below is a specific instance of this. If the answer is "parity", they all become work. If it is
"the reduced surface is what we want", most become a recorded decision and nothing more.

**Q0.2 — Was the reduced surface a deliberate product decision, or a consequence of building to the current
screens?** We do not know which, and it changes how much weight to give the omissions.

**Q0.3 — Who owns this decision?** Whoever it is will need to own the four below as well.

---

## A. Deactivating and reactivating records

The document is specific: Administrators "deactivate Deed Poll records"; Basic Users "reactivate closed Deed
Poll records where permitted"; Administrators can "edit deactivated Enrolled or Returned records".

**This service has no deactivation concept at all** — and, more importantly, neither specification artefact
shows one on the deed poll record. The Dataverse export for the main `deedpoll` table has no `statecode` or
`statuscode` across its 71 columns, while all four child tables (note, witness, supporting document,
supporting document type) have both. That is not an export artefact — `createdon`, `createdby`, `ownerid`
and `modifiedon` are present in both. The vendored prisma schema likewise has only `state DeedPollState?`.

**Q A.1 — Is deactivating a *deed poll record* a real thing in the Power App today, or does the document
mean the child records?** The child records demonstrably support it; the main record appears not to.

**Q A.2 — Could "deactivate" be describing the stock Dataverse command** that every table gets for free,
rather than a designed deed poll feature? If so, "Basic User cannot deactivate" may be a description of
default platform privileges rather than a business rule.

**Q A.3 — What does "closed" mean in "reactivate closed Deed Poll records"?** Is that one of the eight case
states, or is it deactivation under another name? The two readings need different things built.

**Q A.4 — Does the legacy data contain deactivated deed poll records?** This one matters regardless of the
answers above. If it does, migrating into a service with no such concept either loses that state or silently
brings closed cases back as active — and we would need to know before any data moves.

**Q A.5 — If deactivation is real, what should it actually do?** Hide the record from lists and searches,
prevent it being edited, both, or something else? "Deactivated" is not self-defining.

**Q A.6 — What does "where permitted" qualify in "reactivate closed records where permitted"?** It reads
like a condition beyond the role. Is there one, and what is it?

**Also to note back to the business:** we are reporting the specification discrepancy rather than resolving
it ourselves — this repo treats the vendored reference exports as ground truth and forbids editing them, so
if the `deedpoll` export is missing columns it needs correcting at source.

---

## B. Five of the eight case states have no route through the staff app

`DeedPollState` has eight members: Received, ReadyForMaster, ReferredToMaster, Enroled, Returned,
AwaitingFurtherDocs, AwaitingSignature, AwaitingEnrolment.

The service derives only **three** of them, from the enrolment and return dates. In the staff app, State is
displayed **read-only**. So five states currently have no way to be reached by a caseworker.

The document says Basic Users "progress applications through the business process", which may or may not
include those five.

**Q B.1 — Which of the eight states does the real process actually use?** If ReadyForMaster,
AwaitingSignature and the rest are live process steps, this is a wider gap than anything else in this
document, because it affects whether a caseworker can record where a case genuinely is.

**Q B.2 — How does a case reach those states in the Power App today?** Automatically from some other field,
or does someone set it?

**Q B.3 — If someone sets it, is State a field a caseworker edits directly, or the result of an action**
("refer to Master", "request signature")? The two produce very different screens, and the answer also
determines whether it needs its own permission.

**Q B.4 — Is the Referral to Master tab the route to ReferredToMaster?** The service already has referral
fields and a tab that shows or hides on the referral choice, so part of this may exist under another name.

**Q B.5 — Are any of the five states only ever reached by a system or batch process** rather than a person?

---

## C. Deleting a document

Administrator-only in the document. No delete operation exists here.

**Q C.1 — Is deleting a document required, or is the current position (upload and view, no removal)
acceptable?**

**Q C.2 — Should it remove the record permanently, or mark it inactive and hide it?** The child tables carry
`statecode`, so the legacy system may well have done the latter.

**Q C.3 — What should happen to the stored file itself?** Removing a database row and leaving the file, or
removing both, are different commitments — particularly for material supplied by a member of the public.

**Q C.4 — Is there any retention rule that would prevent deletion** for some period or some document types?

---

## D. Deleting a witness

Administrator-only in the document. The service has no witness write operations at all — witnesses are read
as part of the case and maintained through the case itself.

**Q D.1 — Is deleting a witness required?**

**Q D.2 — How are witnesses corrected today** when one is recorded in error — deleted, or amended in place?
If amendment is sufficient, this may already be covered.

**Q D.3 — Is there a minimum number of witnesses a case must retain?** If so, deletion needs a rule, not
just a permission.

---

## E. Managing Supporting Document Types

Administrator-only in the document: create, update and delete. The service exposes the list read-only.

**Q E.1 — Does anyone need to manage these, or are they fixed reference data** that changes rarely enough to
be handled by a release?

**Q E.2 — If they are managed, what happens to documents already recorded against a type that is then
deleted?** This is the question that usually decides whether deletion is allowed at all.

**Q E.3 — Who would do this in practice** — a caseworker acting as Administrator, or a service manager?
It is configuration rather than casework, which may point at a different kind of user.

---

## How to use this

Work through 0 first. If the answer is "the reduced surface is intended", A through E become recorded
decisions and we stop. If it is "parity", they become work — and A and B are the two that need real
answers before anything can be designed, because both concern what a case's lifecycle actually is.

A.4 is worth answering **whichever way Q0.1 goes**, because it is about the data we will migrate rather than
the features we will build.
