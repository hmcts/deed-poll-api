# Deed poll — access levels: questions for the business

> **RESOLVED 2026-09-11. This document was never sent.** The business supplied
> [`Deed Poll Power App Permissions Summary.docx`](../Deed%20Poll%20Power%20App%20Permissions%20Summary.docx),
> which answered questions 2–6, and the two it did not settle were decided directly:
>
> | | Outcome |
> |---|---|
> | 1. Does Private restrict access? | **No** — a handling marker. The document has no row about who may see a case |
> | 2. Amend / delete a note | **Split**: edit both roles, delete Administrator only |
> | 3. Enrol / return | **Both roles** |
> | 4. Create a case | **Both roles** |
> | 5. Documents | Upload, view, edit **both**; delete **Administrator only** |
> | 6. More, or override? | **Additive** — "includes all Basic User permissions plus". Privacy is the sole override |
> | 7. Superuser | **Dropped.** Not deferred — the concept is not being built |
>
> Privacy was decided as: *only an admin may change the setting after an application has been created* —
> which is what the code already does, so the apparent conflict with the document resolved in favour of the
> code. See [`role-model-generalisation.md`](role-model-generalisation.md) for what this means in practice.
>
> Kept rather than deleted, because the questions record what was uncertain and why — and question 1's
> reasoning still matters if Privacy is ever reconsidered.

## Why we are asking

We are building the staff service so that what a person can do depends on their role. The intended levels
are **caseworker** and **admin**, with a **superuser** level allowed for in the design so it can be added
later without rework.

We can build the mechanism without knowing the rules. We cannot choose the rules ourselves, because they
are decisions about how the work should be done rather than technical choices. Today the service has
**one** role-dependent behaviour in it, and everything else is available to anyone who can sign in.

Each question below can be answered in a sentence. Where we can proceed on a sensible assumption we have
said what we will assume, so nothing is blocked waiting for a complete set of answers — but an assumption
we get wrong is more expensive to unpick later than a decision made now.

## What the service does today

For context, this is the current position, not a proposal:

- Anyone who can sign in can see **every** deed poll case. There is no notion of a case belonging to a
  caseworker, a team or a location.
- Anyone who can sign in can create a case, amend any of its details, add notes, and **amend or delete
  anyone else's notes**.
- The only thing an admin can do that a caseworker cannot is **change the Privacy setting** on a case. For
  a caseworker that setting is decided automatically by the application type and then locked.

## The questions

### 1. Does "Private" mean anyone is prevented from seeing the case?

**This is the most important question and it changes the size of the work.**

Cases carry a Privacy setting of either Normal or Private. Today Private is a **label only** — it is shown
on screen, and it changes nothing about who can open the case or what they can see. Every case remains
visible to every member of staff.

- If Private is meant as a **handling marker** — "treat this sensitively" — then we are already doing what
  was intended, and nothing more is needed.
- If Private is meant to **restrict access** to certain staff, that is a substantially larger piece of work
  involving other HMCTS teams, and we need to know now rather than discover it later.

*What we will assume if unanswered:* that it is a handling marker, because that is what the previous system
did.

### 2. Who should be able to amend or delete a note on a case?

Notes are the record of what has happened on a case and who did it. We record the author of every note.
At present any member of staff can amend or delete any other person's note, including deleting it outright.

Please choose one:

- **anyone** — as now
- **the author only** — you may amend or remove your own notes, nobody else's
- **admin only** — caseworkers can add notes but not change or remove them

*What we will assume if unanswered:* admin only, because deleting a record of what happened is the kind of
action that usually needs the higher level. We would rather be corrected than guess quietly.

### 3. Who should be able to record that a case has been enroled or returned?

These are the two points where a case moves on: recording the date it was enroled, or the date it was
returned to the applicant. Both are currently open to anyone who can sign in.

Please choose: **both levels** or **admin only**.

### 4. Who should be able to create a new case?

Currently anyone who can sign in.

Please choose: **both levels** or **admin only**.

### 5. Who should be able to upload and download documents on a case?

Documents on a case can include material supplied by a member of the public. Uploading and downloading are
currently open to anyone who can sign in, and they can be answered separately if that is useful.

Please choose, for each of upload and download: **both levels** or **admin only**.

### 6. Does "admin" mean *can do more*, or *can override*?

Worth separating, because the one rule that exists today is the second kind. A caseworker does not get a
*reduced* version of the Privacy setting — they get one decided for them automatically, and the admin is
the person who can depart from it.

Both readings are reasonable and they lead to different designs, so we would rather know which you have in
mind than infer it from the individual answers above.

### 7. What is superuser for?

It has been asked for so the design can accommodate it, which is a good reason to leave room for it. But we
would be guessing at what it should be able to do.

Is it:

- a **break-glass** account, for recovering from a problem
- a **service management** role — configuration and reference data rather than casework
- a **third tier of business permission**, above admin, on the same kinds of decision as above

We are deliberately **not** creating it yet. A role that exists but grants nothing would appear to work
while doing nothing, and somebody would eventually be given it in good faith. Once the above is settled,
adding it is a small change.

## What we are doing in the meantime

Nothing that depends on these answers, and nothing that would need undoing:

- Restructuring how the service represents permissions internally, so that the answers above become
  configuration rather than redevelopment. No change to how the service behaves.
- Leaving today's single rule exactly as it is until told otherwise.

## Where a "no change" answer is still worth having

Three of the above may well come back as "leave it as it is", and that is a legitimate answer we would
record rather than revisit. The one we would ask you not to leave unanswered is **question 1**, because it
is the only one where the current behaviour might not match what the words on screen imply to the people
using them.
