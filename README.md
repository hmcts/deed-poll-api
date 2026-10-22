# deed-poll-api

## Purpose

The backend service for deed poll case management. It serves the caseworker admin service and the
judiciary's own referral screens, and will later also serve the citizen-facing service — which is why
it has separate `/caseworker/**`, `/judicial/**` and `/citizen/**` namespaces, sharing no DTO between
them.

It is a port of the TypeScript domain layer in the sibling repo
[`hmcts/deed-poll-admin`](https://github.com/hmcts/deed-poll-admin) (`libs/deed-poll`), which is the
specification for this service. Build plans live under [`docs/plans/`](docs/plans).

## What's inside

Scaffolded from [`hmcts/spring-boot-template`](https://github.com/hmcts/spring-boot-template), so it
contains:
 * application skeleton
 * common plugins and libraries
 * [HMCTS Java plugin](https://github.com/hmcts/gradle-java-plugin)
 * docker setup
 * OpenAPI documentation (the template's publish to [hmcts/cnp-api-docs](https://github.com/hmcts/cnp-api-docs)
   is not wired up here — see "The OpenAPI document" below)
 * code quality tools already set up
 * MIT license and contribution information
 * Helm chart using chart-java.

The application exposes a health endpoint (http://localhost:4550/health) and a Prometheus metrics
endpoint (http://localhost:4550/prometheus). Those are the only actuator endpoints exposed —
`management.endpoints.web.exposure.include` lists `health, info, prometheus` and nothing else.

## The API surface

Thirty-four caseworker operations across twenty-one paths, plus eight judicial ones on a namespace of their
own (below), three privileged CCD-attempt ones (see [Repairing attempts](#repairing-attempts)), and eight
Delivery Manager-only Gazette operations (see [Gazette publication foundation](#gazette-publication-foundation)).
`GET /me` (`getCallerAccess`) sits outside every namespace: any authenticated caller gets `staff`, `judicial` and
`capabilities` for themselves, `no-store`, with no identity, so the admin app renders what the api will enforce.
`/swagger-ui.html` is the authority; this table is the map — and `OpenAPIPublisherTest` asserts each count,
so a route added without a row here is a count that stops agreeing with the spec. `/me` and `/` are the only
documented paths outside the counted surfaces; `documentsNoUnlistedPath` allowlists them by name.

| Method | Path | What it does |
|---|---|---|
| GET | `/me` | The caller's own access: `staff` and `judicial` (does each area admit their roles) plus `capabilities` in `Capability` order. Any authenticated caller, `no-store`, no identity; a caller with no deed-poll role gets `false`, `false`, `[]` rather than 403 |
| GET | `/caseworker/cases` | The case list — a `filter` key (one of seven tabs), `where` conditions, a `q` search term, `sort`, `page` and `size`. The default order is newest first except `awaitingConfirmation` (longest wait first) and `readyForPublication` (oldest enrolment first) |
| GET | `/caseworker/cases/field-rules` | Show / hide / mandate rules for a `type`, `migrated` and `publishToGazette` |
| GET | `/caseworker/cases/{id}` | One case **with** its notes, witnesses and documents — one call, not four |
| GET | `/caseworker/document-types` | Every supporting-document type, with its applicability arrays |
| POST | `/caseworker/cases` | Create a case, allocating its reference number; `returnReason`, `referralNotes` and `masterDirections` are capped at 2,000 characters, a CRLF or CR counting as one |
| PATCH | `/caseworker/cases/{id}` | Apply a patch — PATCH, not PUT, because the forms save one tab at a time; the same 2,000-character cap applies to a narrative field the patch carries |
| GET | `/caseworker/cases/{id}/notes` | A case's notes |
| POST | `/caseworker/cases/{id}/notes` | Add a note |
| GET | `/caseworker/cases/{id}/notes/{noteId}` | One note |
| PATCH | `/caseworker/cases/{id}/notes/{noteId}` | Amend a note |
| DELETE | `/caseworker/cases/{id}/notes/{noteId}` | Delete a note — 204, and 404 through the wrong case |
| GET | `/caseworker/cases/{id}/witnesses` | A case's witnesses and declarants, oldest first |
| POST | `/caseworker/cases/{id}/witnesses` | Add a witness — all four fields required; name, address and occupation are trimmed, and a blank one is a 400 |
| GET | `/caseworker/cases/{id}/witnesses/{witnessId}` | One witness |
| PATCH | `/caseworker/cases/{id}/witnesses/{witnessId}` | Amend a witness — an omitted field is left alone; a present text field is trimmed and must not be blank |
| DELETE | `/caseworker/cases/{id}/witnesses/{witnessId}` | Delete a witness — **Team leader or Delivery Manager (legacy admin temporarily)**, 204, and 404 through the wrong case |
| POST | `/caseworker/cases/{id}/documents` | Upload a file (multipart) and record its document row |
| GET | `/caseworker/cases/{id}/documents/{documentId}/file` | Stream a document's stored file back |
| GET | `/caseworker/saved-queries` | The shared library plus the caller's own, each flagged `selected` and `editable` |
| POST | `/caseworker/saved-queries` | Save a query — `name` required, unshared unless `shared` says otherwise, and **only a Team leader, Delivery Manager or temporary legacy admin may set `shared`** (403 otherwise) |
| GET | `/caseworker/saved-queries/{id}` | One saved query, if it is shared or theirs |
| PATCH | `/caseworker/saved-queries/{id}` | Amend one — the owner, or an **privileged query manager** if it is shared. Setting `shared` true needs MANAGE_SHARED_QUERIES; clearing it does not |
| DELETE | `/caseworker/saved-queries/{id}` | Delete one — same rule, 204, and its subscribers' selections cascade |
| PUT | `/caseworker/saved-queries/{id}/selection` | Add it to the caller's own list — 204, idempotent, no edit rights needed |
| DELETE | `/caseworker/saved-queries/{id}/selection` | Remove it from the caller's own list — 204 whether or not it was in it |
| GET | `/caseworker/cases/{id}/referrals` | A case's referrals to the judiciary, newest first — the history and the live one |
| POST | `/caseworker/cases/{id}/referrals` | Raise one — at least one reason, and **409** if the case already has one that is not closed or withdrawn |
| GET | `/caseworker/cases/{id}/referrals/{referralId}` | One referral |
| POST | `/caseworker/cases/{id}/referrals/{referralId}/withdrawal` | Withdraw one — a transition to `Withdrawn`, and **409** if it is already terminal |
| POST | `/caseworker/cases/{id}/referrals/{referralId}/closure` | Close one whose direction has been actioned — a transition to `Closed`, and **409** unless it is `Decided` |
| POST | `/caseworker/cases/{id}/referrals/{referralId}/confirmation` | Record the applicant's answer to the *Request confirmation to proceed* email — `outcome` (`Confirmed` or `Withdrawn`) and an optional `note`. **409** if an answer is already stored (naming it and its date) and **409** if nobody asked this referral to confirm |
| GET | `/caseworker/cases/{id}/emails` | The Email tab in one call — which templates apply and why not, what has already been sent, and the slot defaults |
| POST | `/caseworker/cases/{id}/emails/{template}/preview` | What Notify would render for this case and these slots — writes nothing |
| POST | `/caseworker/cases/{id}/emails/{template}` | Enqueue one — an outbox row and a case note in one local transaction after any CDAM deed preparation. Requires an **`Idempotency-Key`** UUID header; a repeat of one key answers **200** with `enqueued: false`, the same key used for different content is a **409**, and so is the email of the group that contradicts one already asked for |

There are deliberately **no** case deletes, no document-type *endpoint* and no `validate` endpoint. The one
writer of document types is `DeedDocumentService`, which creates the LOC020 and LOC022 rows the first time each
deed is generated because no migration seeds them — so `GET /caseworker/document-types` can grow by two without
anybody calling the API. The domain
implements and tests them; nothing consumes them, and the sibling prototype's mistake was building an API
for an imagined consumer. There is also no *duplicate* endpoint for a saved query: a client reads one and posts
a new one with a new name, and there is no server-side invariant an endpoint would protect.

The seven saved-query routes were the nineteenth to twenty-fifth, and they are the first resource here that is
**not nested under a case** — a saved query is a way of finding cases rather than a thing belonging to one. The
notes and witnesses routes get their scoping from the parent id in the path; this one has to get it from the
entity, which is why its permission rules are written out rather than implied:

- A query is **visible** if it is `shared` or the caller owns it. One that is neither is **404**, carrying the
  message an id that never existed gets, so no id is discoverable by watching status codes. That includes an
  privileged query manager naming somebody else's unshared query: the override reaches shared queries only, and the
  visibility rule is what enforces that rather than a second check.
- A query the caller can see but may not change is **403**. They are already looking at it, so there is nothing
  left to conceal, and a 404 there would be a lie a client cannot act on.
- **Selection is a separate sub-resource** rather than a field on the query. Amending a query is the owner's
  right; choosing to see it is every caseworker's, and keeping them apart is what stops "add this to my list"
  needing edit permission. A saved query is a **live reference, not a copy** — subscribers read the owner's
  edits, because a shared definition each subscriber owns a diverging copy of is the spreadsheet this service
  replaces.

Sharing is a **library, not a push**: publishing a query makes it findable, and each caseworker chooses which
found queries appear in their own navigation. Sharing with a *named* colleague is deliberately absent — this
service has no user directory, and a query is already a URL, so pushing one at somebody is a link in Slack.

The six referral routes replace a process that ran on email
and a Teams channel: caseworkers batched ten referrals to a Master twice a day and polled a shared mailbox for
the returned direction. A referral is a **row of its own**, not a field on the case — the ported `dateReferred`
/ `masterName` / `masterDecision` block on `deedpoll_deedpoll` held one referral per case, latest-wins, and it
stays as the historical snapshot while a case may now carry many referrals over its lifetime. Five rules are
worth knowing before calling them:

- **At most one live referral per case.** A second while one is still `Open`, `Claimed` or `Decided` is a
  **409**, not a 400: nothing about the payload is wrong, and the same request succeeds once the live one is
  closed or withdrawn.
- **Withdrawal is a transition, not a delete or an edit.** The row survives as `Withdrawn`, distinct from
  `Closed` — closed means a direction was actioned — and there is no in-place editing of an open referral, so
  amending one is withdrawing it and raising another. (A *direction* is different: the directing judge may
  revise or withdraw it until the office closes the referral, which sets `directionChangedAt`.)
- **Closing is only possible once a direction has come back.** `Decided → Closed`, and anything else is a
  **409** — closing an `Open` or `Claimed` referral would take it out of the pool with no direction on it, and
  closing a `Withdrawn` one would relabel an abandoned referral as an actioned one. Closing clears nothing and
  writes nothing back to the case's flat `masterDecision` / `masterDirections` columns: those stay the
  pre-migration snapshot, and the live direction is on the referral.
- **The applicant's confirmation is recorded once and never overwritten.** A second answer is a **409** naming
  what is stored and its date; an answer on a referral nobody asked to confirm is a **409** in different words.
  Recording one moves no case date and adds no case state — the wait is surfaced beside the derived state, never
  inside it — so the caseworker then does whatever the process requires through the ordinary case edit.
  The other end of that rule is on the Email tab: once an answer is stored, *Request confirmation to proceed* is
  no longer offered for that referral and sending it is a **409**. Asking again would re-start a clock that
  nothing reads — the waiting list counts only referrals with no answer yet — so the applicant would have been
  chased with the case absent from the list of cases waiting, and the new reply could never be recorded.
- **No role gate anywhere below these routes.** Any authenticated staff caller may raise, read, withdraw, close
  and record a confirmation: in the process this replaces, anybody who works a case can email a Master and action
  the reply, and anybody can read the applicant's reply out of the shared inbox. The
  judicial role is the one that is different — `Role.JUDICIAL`, which IDAM does not issue and only the off-by-default AM role source can grant — and it gates
  the two transitions that are not on this surface.

**Every successful referral transition writes exactly one case note**, attributed to whoever caused it, because
there is no timeline concept in this domain beyond the notes grid and the referral has to appear where
caseworkers already look: raise, withdraw and close to the caseworker, claim and direction to the judge, and
the inactivity sweep's to the service account, because a lapsed claim erases the fact it is about. Recording the
applicant's confirmation writes one too, although it moves no state — the answer is an event, and the grid is
the only place it would appear.

**A third email, and the first one the case rather than a referral entitles.** *Chase missing documents* is
offered while the case's derived state is `AwaitingFurtherDocs` — the state the cascade derives from
`awaitingFurtherDocsInfo`, and only while no later date has moved the case past it — and it is a **409** otherwise,
in words that name the date to record rather than a field to correct. It reads no referral at all, so its outbox
row carries no `referralId` and sits outside the exclusion-group key: **a second chase is legitimate** and
deliberately not refused, which is why the send carries an idempotency key rather than relying on a database
constraint. Its one slot, `documents_required`, is **required and pre-populated from nothing** — this service
cannot derive which documents are outstanding — so the compose form opens on an empty box and an empty body is a
400 naming the slot; it is capped at 4,000 characters. The email invites a reply, so it reads **unprovisioned**
unless both its template id and `NOTIFY_EMAIL_REPLY_TO_ID` are set.

**A fourth and fifth email send the applicant the deed to sign, and they are the first that carry a file.**
*Forms for completion* and *Forms for completion for a child* are offered while the case's `signatureRequestedDate`
is null **and** the case's type routes to that email's deed — `Adult` and `AdultTransgender` take LOC020,
`Minor` and `MinorTransgender` take LOC022, and `DeedOfRelinquishment` and an unknown or unset type are offered
neither, each with its own sentence. They carry one **optional** slot, `additional_notes` — the same box, and the
same 1,000-character cap, as the two referral-outcome emails — which opens empty and may be sent empty. The notes are
part of the frozen personalisation, so one `Idempotency-Key` reused with different notes is a **409**, and the
Notify template has to place `((additional_notes))`: Notify drops a key its template does not name, so without the
placeholder a caseworker's note never reaches the applicant. Sending
one deliberately does **not** set `signatureRequestedDate` — who should, and when, is a business question this
service does not answer.

They are **mutually exclusive per case** rather than per referral, which is V20's unique index: no judge is
involved, so the rows carry no `referralId` and V15's key never sees them, and the second email is a **409**
naming the first. That is the opposite of the chase, where a second reminder is legitimate. A case that already
holds one deed and is asked for the other is refused too, in the deed's own words rather than the email's — the
route is a case whose type was corrected after its first forms email failed permanently, and an applicant must
not be sent a child's covering letter carrying an adult's deed.

The deed reaches the applicant as a **GOV.UK Notify download link with a confirmation step**, not an attachment —
the deed carries their name, address and email, so a URL anybody holding it could fetch is the wrong default. The
five and four static forms the emails also name are attached to the **Notify template**, so this service neither
stores nor sends them. Notify's *preview* nonetheless demands a value for each attached file's placeholder, so a
preview it refuses for exactly that is retried once with a sentence saying where the link will go under each
missing key shaped like a file name (`AttachmentStandIns`). A key this service owns is never stood in for, so a
misspelt `((deed_document))` still fails the preview with a 502, and nothing stood in reaches a send. Both emails invite a reply, so each reads **unprovisioned** unless its template id,
`NOTIFY_EMAIL_REPLY_TO_ID` *and* `NOTIFY_DOCUMENT_RETENTION_WEEKS` are all set — the first emails to need three
settings, and the retention has no working default because how long an applicant has to print, gather two
witnesses and find a commissioner of oaths is not this service's number to guess. It has to be **1 to 78 weeks**,
the range Notify accepts; anything else reads as unset, because the client checks only the 2 MB size locally and
an out-of-range value would otherwise fail permanently at Notify after the deed had already been stored.

**The send requires an `Idempotency-Key`, and the client has to make it mean something.** One compose journey
mints one UUID and sends it with every retry of the final POST — a request that timed out, a double-click, a
resubmit after a validation failure — and a later journey mints another. That is what lets one case be chased
twice on purpose while a double-click still sends one email, and it is a **breaking change for any client that
predates it**: a POST with no header is a **400** naming `Idempotency-Key`, and so is one whose value is not a
UUID. A repeat of the same key for the same case and content answers **200 with `enqueued: false`** and the first
send's receipt; the same key for a different email — edited text, a corrected applicant address, another refusal —
is a **409** telling the caseworker to start the email again, because neither reading is safe. A fresh UUID per
request satisfies the schema and destroys the protection. Neither the availability `GET` nor the preview takes the
header; neither writes a row. The key is stored as `requestId` (V19, unique per `("deedPollId", "requestId")`, so
one client's chosen UUID can never reach another case) and is published nowhere — not in a DTO, a note or a log.
It is consulted only once the insert reaches the database, so a retry that arrives *after* the case has moved on
is refused on the new state rather than answered with the earlier receipt: nothing is sent twice, and the Emails
tab is where the send that did happen is visible.

**An email writes case notes on the same reasoning, and they are markers rather than the record.** Enqueuing one
writes *"&lt;Name&gt; email sent. See the Emails tab for its delivery status."* in the caseworker's own transaction, so
a refused or rolled-back send leaves none and an idempotent repeat send writes no second one. An email that
**terminally** fails — Notify refused it, or it was retryable and ran out of attempts — writes a second note
pointing at the tab for the reason; a transient failure that will be retried writes nothing, because a note per
attempt would bury the timeline. The Emails tab holds the content, recipient, personalisation, state and the
provider's reason, and the note deliberately repeats none of them: it names the tab in prose rather than linking
to it, because note text is escaped when rendered and nothing may parse it. The failure note is attributed to the
caseworker who sent the email rather than to the service account that drained it, so the send and its outcome read
as one story — which also means a note can appear on a case during somebody else's request, since the drain runs
after whichever send commits. A replacement send after a terminal failure therefore leaves three notes for what a
caseworker thinks of as one email; that is the timeline being honest, and notes are never revised.

**A sixth case-list tab, `awaitingConfirmation`.** Where a judge has refused a request not to publish, the office
emails the applicant to ask whether they still want the deed enrolled; until the reply is recorded the case is
waiting, and this tab is the list of them. It is the one tab whose criterion is **not a case state** — a waiting
case is in whatever state its dates make it, ordinarily `Returned`. It defaults to longest wait first, because
a waiting list in creation order buries the case most at risk of being forgotten. The `readyForPublication`
filter separately defaults to oldest enrolment first. A `sort` the caller names still wins. Each row carries
`daysAwaitingConfirmation`, which reads 0 on the day the email went out, and the case read carries `awaitingConfirmationSince`. That column is a **read-model**
recomputed from the referral table by the send and by the recording; it is readable and not writable, so no patch
can put a case into the waiting list or take it out without an answer.

**Claiming a referral and recording a direction are the judge's half of the loop, and they have no route on this
surface.** Both are transitions on `ReferralService` — role-gated to `Role.JUDICIAL` — exposed on the judicial
namespace below rather than bolted onto the caseworker one. A claim lapses after
`deedpoll.referrals.claim-expiry` (two hours by default) of the holding judge's inactivity, taken back as a side
effect of loading either judicial list — the shared pool or the judge's own — rather than by a scheduler.

### The judicial surface

**Eight operations, on a namespace of their own.** They are *not* part of the thirty-four above: a judge is not a
caseworker with fewer permissions, and `/judicial/**` has its own controllers and its own DTOs so that a
caseworker response — which carries every field a caseworker sees, the privacy fields included —
cannot be reused for the judiciary by accident. An ArchUnit rule asserts the separation in bytecode.

| Method | Path | What it does |
|---|---|---|
| GET | `/judicial/referrals` | The shared pool — the `Open` referrals waiting for a judge, urgent first then longest-waiting, paged ten at a time with the pool's total |
| GET | `/judicial/referrals/mine` | This judge's own work — their `Claimed` referrals before their `Decided` ones, urgent first then longest-waiting, paged the same ten at a time |
| GET | `/judicial/referrals/{referralId}` | One referral, the wide `JudicialApplicationView` of its application (details, witnesses, readable documents; no case id, payment or refund data, or office processing record), and the case's notes. Opening it records `CASE_VIEWED` |
| GET | `/judicial/referrals/{referralId}/documents/{documentId}/file` | Download one of the application's readable documents — any judge who can see an `Open` referral may. Records `DOCUMENT_DOWNLOADED` and is served with the caseworker download's headers |
| POST | `/judicial/referrals/{referralId}/claim` | Claim a pool referral — it becomes theirs and drops off every other judge's |
| POST | `/judicial/referrals/{referralId}/direction` | Record a direction — `decision`, optional `directionsText`, and `publishToGazette` |
| PUT | `/judicial/referrals/{referralId}/direction` | Revise the judge's own direction (the `POST`'s body) while the referral is `Decided` and not yet closed |
| POST | `/judicial/referrals/{referralId}/direction/withdraw` | Withdraw the judge's own direction, no body — the referral returns to `Claimed` by the same judge, who may direct again |

Every route requires `Role.JUDICIAL`, which **IDAM does not issue** (only the off-by-default AM role source can grant it), enforced in the domain rather than by a
route matcher — so the whole surface fails closed for every token in existence today. Five rules are the surface:

- **Keyed on the referral, never on the case.** There is no `{caseId}` in a judicial URL, because a judge does
  not browse cases and must not be able to name one; the service resolves the case *from* the referral. That
  inverts the caseworker surface's case-scoping rule, so the safety boundary **moves** rather than disappearing:
  where a caseworker read is scoped by its parent, a judicial read is scoped by visibility.
- **Visibility.** A referral is visible to a judge if it is `Open` — in the pool — or it is `Claimed`/`Decided`
  and it is theirs. Anything else is **404**: withdrawn, closed, or another judge's, in the same words an id that
  never existed gets, so no referral id is discoverable and one judge cannot learn another holds a case. The only
  403 here is the role gate. A referral they can see but not act on is a **409** — claiming one somebody got to
  first, directing one they no longer hold — because by then they are looking at it.
- **Ownership is the token's IDAM subject, not a display name.** `claimedById` and `decidedById` are what the
  claim, the direction, the visibility rule and the personal list all compare — so two judges sharing a display
  name cannot reach each other's referrals, and a judge whose name changes keeps their own. The name columns
  remain, as the attribution staff read; they authorise nothing. Neither id is published in any DTO, note or log,
  and a pre-migration row whose owner id is null belongs to nobody: it is invisible and cannot be directed.
- **Four writes and no more.** Claim, direct, and the directing judge's own revise and withdraw. No editing case
  data, no notes, no raising or withdrawing referrals, and no closure: closing is the office's acknowledgement that
  the direction has been actioned. Revise and withdraw require `Decided` by the caller, checked under the row lock,
  so a close that commits first makes them a 404. Both set the never-cleared `directionChangedAt` (V38), which
  caseworkers see as a flag with a case note, and record `REFERRAL_DIRECTION_REVISED`/`_WITHDRAWN`. A withdrawn
  direction is a live claim again, so the inactivity sweep can return it to the pool. Claim and direct are the
  api-2 transitions, so nothing about a state change is decided on this surface.
- **Three reads, and every one of them writes.** Loading either list — the shared pool or a judge's own — runs the
  inactivity sweep first, so a lapsed claim is released and the referral is back in the pool in the same response
  that observed it, and off the holder's own list in the same request; and opening a referral a judge *holds*
  refreshes its inactivity clock, because a judge reading the papers is working the case. Opening a pool referral
  they have not claimed touches nothing. The personal list sweeps for the same reason the pool does: otherwise a
  stale claim would sit in "My referrals" and opening it would revive ownership after the window had elapsed,
  merely because nobody had loaded the pool first.

**"My referrals" is the visibility rule's own list, not a history.** It holds exactly the referrals a judge may
see because they own them — `Claimed` where they hold the claim, `Decided` where they gave the direction — so
`Open` belongs to the pool instead, and `Withdrawn` and `Closed` drop off because the office has taken the work
back or actioned it. The same predicate drives the list and the individual read, so a row it shows can never link
to a 404. Whose list it is comes only from the token: there is no `?owner=` or `?claimedBy=`, because a list keyed
on anything a caller sent would be the enumeration the 404s above exist to prevent. It carries referral fields
only, exactly as a pool row does, and it exists because business testing found that claiming a referral took it
off the only page that linked to it.

The judicial application view is **wide by the business's decision of 2026-10-19** (it replaced the 2026-09-17
minimum): details, contact details, representative, nationality, minor and parent details, privacy (read-only),
the previous system's referral fields, witnesses and readable documents. It excludes the case id and reference,
payment and refund data, and the office's processing record. The address lines, declaration multiselects,
`publishToLondonGazette`, `migratedRecord` and `retainPermanently` stay out until the business answers.
Any *addition* to that set is a legal and policy decision, not an engineering one.

The witness delete was the eighteenth, and is **the one witness route restricted by role**: the capability
historical matrix restricted "Delete witnesses" to Administrator, like deleting a note. Today
DELETE_WITNESS grants that privilege to Team leader, Delivery Manager and temporary legacy admin. The check lives in `WitnessService.removeWitness` rather than on the route, so it holds for every
future caller, and it **refuses before it resolves** — a caller without the role gets 403 whether or not
the witness exists, so the status code cannot be used to tell an existing witness from a missing one. The
delete is a hard one, on the notes precedent, and there is **no minimum number of witnesses** a case must
retain: no artefact states one, so an authorised caller may delete the last witness on a case. That is the one
decision here the business might want changed.

Before the route existed, `removeWitness` was an ungated two-argument method safe only because nothing
called it — the shape the note delete had before it was gated. Adding the route without the gate would have
reintroduced that, so the gate landed with it.

The witness create, read and edit were the fourteenth to seventeenth, and arrived the same way the note
delete did: `deed-poll-admin` renders a case's witnesses and had no way to record one, which was the only
place on this surface where data was readable and not writable. The business document gives Basic User
"Create, view and edit witness records", so the capability existed and the route did not.
`WitnessService` already had all five operations and their tests — the routes are the consumer it was
keeping itself for. Neither write is role-gated: the capability matrix marks "Add and edit witnesses" for
both roles.

The note delete was the thirteenth, added 2026-09-08 on exactly that principle rather than against it:
`deed-poll-admin` is losing its data-access layer to this service and its note form has always had a delete
button, so the operation acquired a real consumer. `NoteService.removeNote` already existed and was already
tested — this was the route it was keeping itself for. It is **Team leader or Delivery Manager (legacy admin temporarily)**, enforced the same way
the witness delete is: in `NoteService.removeNote` rather than on the route, using DELETE_NOTE.
Team leader, Delivery Manager and the temporary legacy admin each grant that capability.

Every failure answers the same envelope — `{"error": "...", "errors": [{"field": "...", "message": "..."}]}`
(`api/ApiError.java`), so a form can render a message against the input it belongs to.

`/citizen/**` exists, is **empty**, and has its own controller and DTO packages. It is reserved for
`deed-poll-web` and already sits behind an S2S-only gate (below); its first endpoint still needs its own rate
limiting and OpenAPI security scheme. An ArchUnit rule (`NamespaceSeparationSecurityTest`) is what stops a later plan
sharing a caseworker DTO into it.

## Authentication

Every route requires a forwarded IDAM bearer token except a short infrastructure allowlist — `/`,
`/health/**`, `/info`, `/prometheus`, `/v3/api-docs/**`, `/swagger-ui/**`, `/favicon.ico`. See
`SecurityConfiguration.PUBLIC_PATHS`. Authentication is by omission: a route added later is protected
unless somebody adds it to that list, rather than protected when somebody remembers to.

One consequence worth knowing: an unknown path answers **401, not 404**, because authorisation runs
before routing. Which paths exist is not something an unauthenticated caller needs to learn.

Two variables must be set before any `/caseworker/**` call can succeed, and **both default to empty
so that an unconfigured environment refuses every token** rather than trusting an issuer nobody chose:

| Variable | Meaning |
|---|---|
| `IDAM_API_ISSUER_URL` | The IDAM issuer. Endpoints are discovered from it, never configured |
| `IDAM_API_AUDIENCES` | Comma-separated `aud` values that are ours. Empty refuses everything |
| `IDAM_API_ADDITIONAL_ISSUERS` | Optional comma-separated absolute `https` `iss` values accepted besides the discovered one (AAT's ForgeRock issuer). A name, not a trust root. Empty by default |

IDAM roles come from `/o/userinfo`, not from the token, and the lookup is uncached; with `ROLE_ASSIGNMENT_ENABLED` true, AM role assignments are unioned in (see below). The application maps
verified roles to explicit capabilities:

| Role claim | Privileges |
|---|---|
| `caseworker-deedpoll-team-leader` | Privacy override, note/witness deletion, shared-query management, CCD repairs |
| `caseworker-deedpoll-delivery-manager` | All Team leader privileges plus the entire Gazette facility |
| `caseworker-deedpoll-admin` (deprecated) | Temporary Team leader compatibility; no Gazette access |

Each new role works alone. Neither grants judicial or system access. Private saved queries remain
owner-only; shared-query management does not bypass that rule. The privacy adapter derives its flag
from OVERRIDE_PRIVACY. The transport on this baseline uses `anyRequest().authenticated()`; domain
capability gates enforce independently. The separate staff-gate integration must admit both new roles
to casework while preserving its configured mode and judicial boundary.

All eight Gazette HTTP operations and `GET /caseworker/cases?filter=readyForPublication`, including
its count and saved-query links, require `caseworker-deedpoll-delivery-manager`. Other authenticated
roles receive 403 before reads, outbox drain or CCD sweep; unauthenticated callers receive 401.
Ordinary case fields, conditions and visibility are unchanged. Background delivery uses durable
admission evidence and does not reauthorise an already admitted request. See the
[permission contract and rollout](docs/plans/completed/20261009-team-leader-delivery-manager.md).
Release owners should also use the [API release handoff](docs/STAFF-ROLE-RELEASE.md).

AM's organisational role assignments can be a second source of those same roles. This is off by default.
When enabled, `ctsc` adds caseworker, `ctsc-team-leader` adds Team leader (and Delivery Manager if it carries
the Gazette skill), and `judge`/`fee-paid-judge` add judicial. Only `ORGANISATION` assignments in the configured
jurisdiction and inside their begin/end window count. They are read from RAS once per request, uncached. If RAS
fails, the caller keeps their IDAM roles and nothing more. The CCD system user and `/ccd-persistence/**` never
consult it.

| Variable | Default | Meaning |
|---|---|---|
| `ROLE_ASSIGNMENT_ENABLED` | `false` | Ask RAS at all. The chart sets it false explicitly |
| `ROLE_ASSIGNMENT_URL` | `http://localhost:4096` | RAS's base URL; set by flux per environment |
| `ROLE_ASSIGNMENT_JURISDICTION` | `DEEDPOLL` | The `attributes.jurisdiction` an assignment must carry |
| `ROLE_ASSIGNMENT_GAZETTE_SKILL` | empty | The `ctsc-team-leader` authorisation that grants Delivery Manager; empty grants none |

Enabling it also needs the outbound S2S settings (`IDAM_S2S_AUTH_URL`, `DEED_POLL_API_S2S_SECRET`) and RAS
admitting `deed_poll_api`. See `CLAUDE.md` for the activation order.

`/citizen/**` is the second exception, also with a chain of its own: it admits only a verified `deed_poll_web` S2S
token and never reads an IDAM bearer (a bearer alone is 401; another service's S2S token is 403).

`/ccd-persistence/**` is the first exception, with a chain of its own: it is where CCD's data-store calls this
service back, so it admits an S2S token verified as `ccd_data` and nobody else, plus an IDAM user whose role
is the configured `CCD_SYSTEM_ROLE`, mapped onto the `DEED_POLL_SYSTEM` access profile. The generator's
`/callbacks/**` routes are refused to everybody. The caseworker and judicial namespaces still need no S2S.

## CCD

Each deed-poll record may be linked to one decentralised CCD case (plan 02): data-store holds the
record ID, display reference and attached supporting-document references; the relational record stays authoritative.
The linking operation, `CcdLinkingService.link`, is called by `CcdLinkingSweep` after every `GET /caseworker/cases` has computed its answer — at most
`CCD_LINKING_SWEEP_MAX_RECORDS` of the oldest unlinked records, then at most `CCD_LINKING_SWEEP_MAX_RECONCILIATIONS`
outstanding attempts, stopping at the first call CCD refuses or does not answer or once `CCD_LINKING_SWEEP_MAX_DURATION` has passed, and never changing the response
(plan 02b). **It is off by default** (`CCD_LINKING_SWEEP_ENABLED=false`), so in every deployed environment nothing
calls it yet, and every identifier below that names CCD is an unconfirmed placeholder. `POST /caseworker/cases`
does not link. [`docs/CCD-SMOKE.md`](docs/CCD-SMOKE.md) is the procedure for the first real environment.

| Variable | Default | What it is |
|---|---|---|
| `CCD_CASE_TYPE_ID`, `CCD_JURISDICTION_ID`, `CCD_SYSTEM_ROLE` | `DEED_POLL_PLACEHOLDER` | the definition's identifiers, and the IDAM role data-store's calls must carry |
| `CCD_DATA_STORE_URL` | `http://localhost:4452` | data-store, for the linking operation |
| `IDAM_API_URL` | `http://localhost:5000` | IDAM's API, for the system user's password grant |
| `IDAM_CLIENT_ID`, `IDAM_CLIENT_SECRET`, `IDAM_CLIENT_REDIRECT_URI` | empty | the OAuth client for that grant |
| `DEED_POLL_SYSTEM_USERNAME`, `DEED_POLL_SYSTEM_PASSWORD` | empty | the system user; an empty one is refused |
| `IDAM_S2S_AUTH_URL`, `DEED_POLL_API_S2S_SECRET`, `S2S_SERVICE_NAME` | `http://localhost:4502`, empty, `deed_poll_api` | outbound S2S, for calls to data-store |
| `CCD_LINKING_LEASE`, `CCD_LINKING_SETTLE_AFTER`, `CCD_LINKING_MAX_ATTEMPTS`, `CCD_LINKING_RETRY_AFTER` | `PT5M`, `PT10M`, `3`, `PT5M` | how long a claim is held, how long an unknown outcome is waited on, how many refused tries, how long a refused try waits (times the tries so far) |
| `CCD_LINKING_SWEEP_ENABLED`, `CCD_LINKING_SWEEP_MAX_RECORDS`, `CCD_LINKING_SWEEP_MAX_RECONCILIATIONS`, `CCD_LINKING_SWEEP_MAX_DURATION` | `false`, `2`, `5`, `PT15S` | whether the case list request links records to CCD cases -- off until onboarding, because before it every attempt ends `NeedsRepair` -- how many records it links and outstanding attempts it reads per request, and how long it may run before it starts nothing more |
| `HOSTNAME` | `deed-poll-api` | who holds a claim; Kubernetes sets it to the pod's name |

`CoreCaseDataClientAutoConfiguration` is excluded on purpose: it would add a health indicator calling
data-store, and the unauthenticated `/health` aggregates every indicator, so a data-store outage would read as
this service being down. `CcdConfiguration` registers the client instead.

### Repairing attempts

Three REPAIR_CCD-protected operations under `/caseworker/ccd-attempts`, counted apart from the caseworker surface as
`CCD_ATTEMPT_PATHS`. Each needs Team leader, Delivery Manager or temporary legacy admin (403 otherwise), and neither move calls CCD or writes a
binding: the next case-list sweep does the rest.

| Method | Path | What it does |
|---|---|---|
| GET | `/caseworker/ccd-attempts` | Counts per status for the whole ledger, and a page of the attempts that are not `Linked`, longest-unmoved first. `status` narrows the page to one live status (`Linked` or anything else is a 400); `page` and `size` as the case list's, 25 by default and never more than 100 |
| POST | `/caseworker/ccd-attempts/{attemptId}/reconciliation` | Reconcile again: `NeedsRepair -> AwaitingConfirmation`, restarting the settle window |
| POST | `/caseworker/ccd-attempts/{attemptId}/retry` | Try again from scratch: `NeedsRepair -> Pending`, its tries reset — a 409 unless `canRetryFromScratch`, because an attempt that may have reached CCD could create a second case |

An unknown id is a 404, and an attempt that is not `NeedsRepair` a 409.

## Documents and uploads

Files go through the `DocumentStore` interface. `LocalFilesystemDocumentStore` remains the default, with blobs
under `DOCUMENT_STORE_ROOT`. Plan 03a adds `CdamDocumentStore`: upload as the system user, attach through a CCD
document event, download under the caller's own token and best-effort soft delete. The case must already be linked;
an upload against an unlinked case returns 409. Only `Attached` rows are downloadable on CDAM.

| Environment variable | Default | Purpose |
|---|---|---|
| `DOCUMENT_STORE` | `filesystem` | Exactly `filesystem` or `cdam`; any other value refuses startup. Filesystem also requires the environment guard below |
| `ENVIRONMENT_NAME` | empty locally; chart uses `global.environment` | Filesystem permits only `aat`, `preview`, `demo`, `ithc`, `perftest`, or an empty name with `CLUSTER_NAME` absent. Preview explicitly sets `preview` |
| `CLUSTER_NAME` | unset locally; supplied by Flux | Its presence (even empty) prevents an unnamed deployment being treated as a local run |
| `DOCUMENT_STORE_ROOT` | `data/documents` | Local development directory, gitignored |
| `CASE_DOCUMENT_AM_URL` | `http://localhost:4455` (chart: placeholder) | CDAM endpoint; local/default values are refused when CDAM is enabled outside local tests |
| `DOCUMENT_URL_BASE` | `http://dm-store:8080` | Reference URL base accepted by data-store's document URL validator; not a binary download destination |

CDAM also requires confirmed `CCD_CASE_TYPE_ID`, `CCD_JURISDICTION_ID` and `CCD_SYSTEM_ROLE`, plus the system-user
and S2S credentials used by linking. Startup rejects placeholder identifiers with CDAM enabled. Local `test` and
`cftlib` profiles can use placeholders only against loopback CDAM. Staff need CCD read permissions on the case and
document field; an authenticated API user does not automatically have these permissions.

See [the AAT procedure](docs/CDAM-SMOKE.md) and [storage recovery](docs/CDAM-RECOVERY.md).
`deedpoll_documents_storage{state="…"}` exposes counts without identifiers or tokens. The full local round trip
runs under `./gradlew cftlibTest`, with real CCD/CDAM and a WireMock dm-store. Its source-derived contract and
limitations are in `src/test/resources/dm-store-contract.md`; `./gradlew checkDmStoreSpecDrift` checks the pinned
spec against the workspace's cnp-api-docs clone.

**Not every document arrives from a caseworker.** Sending either *Forms for completion* email generates the
case's deed and stores it through the same path, as an `Outgoing` document with an applicant-facing filename
(`LOC020-change-of-name-deed-KBDP2026-00001.docx`) filed under a document type the service creates on first use.
It is generated **once per case** and never again — an applicant signs the paper in wet ink, so the bytes the
court holds and the paper in their hands cannot be allowed to differ. Filesystem sends retain transactional
rollback cleanup. On CDAM, a durable preparation freezes bytes, local identity and SHA-256 before upload;
attachment and verified read-back precede final enqueue. A refused enqueue keeps that attachment for reuse.
Later drains use a separate case-scoped service reader with fresh system/S2S credentials. Public downloads
still use the staff token, with no fallback on denial. Claims, send-start and outcomes commit separately;
a possibly-sent email stops for investigation (including emails without documents).

> **Filesystem storage is temporary and never permitted in production.** The startup guard allows only the
> exact non-production names above, or an unnamed local run with `CLUSTER_NAME` absent. Unknown names, case
> variations and an unnamed cluster deployment refuse startup. Spring profiles do not bypass it; there is no
> bypass switch. This allowance is not approval to hold real applicant documents in non-production.
> Pod disk is ephemeral, replicas
> do not share a volume, and the chart declares none — a deployed environment running this loses every
> uploaded file on the next restart and serves 404s from whichever replica did not receive the upload.
> CDAM uploads, generated deeds and correspondence reads are implemented and proven locally. Existing
> filesystem migration remains 03c. Switching backends does not migrate files in either direction.

Ship the guard and chart environment setting together. Before any rollout replaces a pod, inventory document
rows by store and filesystem file counts per pod (aggregate counts only), and resolve any preservation needs.
This change does not alter replicas, migrate documents or activate CDAM. After deployment, verify API startup,
admin readiness and upload/download round trips; local tests and chart rendering do not replace these checks.

### Durable preparation and owned staging configuration

`DOCUMENT_STAGING_MAX_TOTAL_BYTES` is required for new CDAM deed preparations even with staging disabled.
It caps all retained payloads together; failed/uncertain work consumes capacity until safely resolved.
The default zero refuses admission. Owned staging is an internal domain API only: enabling it does not
add a citizen route or transfer browser uploads. See [the adapter contract](docs/CDAM-CALLERS.md#task-6-implementation--owned-staging-2026-10-02).

| Environment variable | Default | Activation requirement |
|---|---|---|
| `DOCUMENT_STAGING_MAX_TOTAL_BYTES` | `0` | Positive shared durable-byte cap, agreed with database/backup owners |
| `DOCUMENT_STAGING_ENABLED` | `false` | Requires `DOCUMENT_STORE=cdam` and every setting below |
| `DOCUMENT_STAGING_RECORD_MAX_FILES` | `0` | Positive staged-file count per existing record |
| `DOCUMENT_STAGING_RECORD_MAX_BYTES` | `0` | Positive retained-byte budget per record |
| `DOCUMENT_STAGING_DRAFT_MAX_BYTES` | `0` | Positive retained-byte budget per citizen draft |
| `DOCUMENT_STAGING_RECORD_EXPIRY` | `0s` | Positive duration from first record admission |
| `DOCUMENT_STAGING_DRAFT_EXPIRY` | `0s` | Positive duration from first draft admission |
| `DOCUMENT_STAGING_CLEANUP_INTERVAL_MS` | `0` | Positive fixed delay and initial delay, in milliseconds |
| `DOCUMENT_STAGING_CLEANUP_ROWS` | `0` | Positive maximum rows per scheduled run |
| `DOCUMENT_STAGING_CLEANUP_BYTES` | `0` | Per-run byte budget, at least 26,214,400 bytes |
| `DOCUMENT_STAGING_CLEANUP_TIME` | `0s` | Positive elapsed-time budget for starting cleanup work |

Durations accept Spring duration syntax such as `PT1H`; examples are syntax only, not production values.
Missing/invalid settings prevent staging startup. Citizen staging preserves 10 files/draft and 7,340,032
bytes/file; records preserve 26,214,400 bytes/file. Owner budgets count retained expired/promoting bytes.
Cleanup runs independently of visits, across replicas, and deletes only expired Staged payloads, preserving
tombstones. It never expires generated preparations or uncertain promotions. Expiry differs from the unpaid
payment deadline, CDAM upload TTL and Notify link retention. Disabling staging also stops cleanup.
The [recovery and release handoff](docs/CDAM-RECOVERY.md) specifies monitoring, ownership and rollback limits.

The limits are **25 MB per file** and 26 MB per request, and they come from
`UploadPolicy.MAX_UPLOAD_BYTES` rather than from a property — the same constant derives the refusal
message ("The file must be smaller than 25MB"), and a property an environment could override would
let the limit being enforced and the limit being quoted to the caller drift apart. A consequence for
operators: declaring `MultipartConfigElement` as a bean makes **`spring.servlet.multipart.max-file-size`
inert**, so setting it changes nothing. Change the constant.

A **second, smaller limit** applies to anything sent to an applicant: GOV.UK Notify accepts 2 MB, which is
`DocumentUploads.MAX_UPLOAD_BYTES`, and a document over it is refused before `sendEmail` in a message naming the
file and its size — the client's own 413 names neither, and the refusal is what a caseworker reads on the Emails
tab.

The accepted declared types in `UploadPolicy` are PDF, JPEG, PNG, TIFF and Word DOCX. Legacy binary
Word `.doc` is refused (policy agreed 2026-10-16); existing documents are not rescanned or deleted.
Admin's matching allowlist is only a browser-facing convenience. `UploadContentPolicy` is authoritative:
`DocumentService` checks bytes before storage, and `StagingPolicy` checks before quota/payload persistence.
Content refusals are HTTP 400 with a `file` field error. The 25 MiB upload cap is unchanged.

Images and PDFs must match their declared type's signature. PDF name checks, including `#xx` escapes,
refuse `/Encrypt`, `/JavaScript`, `/JS`, `/Launch`, `/EmbeddedFile`, `/RichMedia` and `/XFA`. This is an
interim tripwire, **not a complete PDF parser**: compressed object streams can hide active names, and
matching names in comments or stream data can cause refusals. Signature checks do not prove an image
or PDF is well-formed or harmless. OLE containers are refused with guidance to upload an unencrypted `.docx`:
their signature cannot distinguish legacy `.doc` from encrypted DOCX. Non-Word or malformed ZIP packages
get a format-mismatch message, not a claim that macros were found. ZIP-based DOCX must have a normal
Word main part and no macro/ActiveX parts, `word/embeddings/` parts or external attached-template,
OLE-object or frame relationships. Ordinary external hyperlinks are allowed, never fetched by inspection.

DOCX inspection first bounds expansion to 25 MiB per entry, 50 MiB total and 1,000 entries, with at least
a 1% compressed/expanded ratio for entries over 100,000 bytes. POI's process-wide ratio, entry-size and
entry-count guards are set at application startup, before singleton reader beans are created, by
`DocumentParserConfiguration`; loading the first DOCX no longer changes global parser behaviour.
The limits are never weakened per request. Malformed or
unsupported packages fail closed. Refusal logging contains only a fixed format category, never parser
exceptions, filenames or bytes. These checks do not replace CDAM malware scanning or activate CDAM.

A narrower list decides which types are served `inline` rather than as an attachment
(`DocumentFileResponses.INLINE_MIME_TYPES`, shared by the caseworker and judicial downloads); every
download is served under `Content-Security-Policy: default-src 'none'; sandbox`. Download classification still uses stored MIME
metadata: these admission checks do not make later metadata edits safe. The separate metadata-integrity
follow-up must guard any future metadata PATCH route; there is no such public route today.

Download headers are independent of the backing store: filesystem and CDAM responses both carry
`X-Content-Type-Options: nosniff` and `Cache-Control: no-store` alongside that CSP. Disposition follows
the recorded MIME type: PDF, JPEG and PNG types are inline; Word and TIFF types are attachments.
The download path does not re-inspect file contents; new admissions pass the content policy above before
the declared type is recorded. Regression tests exercise the five accepted types plus historical `.doc`
metadata against the real local store and the CDAM WireMock journey, including conflicting upstream upload
metadata and download headers. These local tests do not establish live CDAM onboarding or malware scanning.

## Plugins

The project contains the following plugins:

  * HMCTS Java plugin

    Applies code analysis tools with HMCTS default settings. See the [project repository](https://github.com/hmcts/gradle-java-plugin) for details.

    Analysis tools include:

    * checkstyle

        https://docs.gradle.org/current/userguide/checkstyle_plugin.html

        Performs code style checks on Java source files using Checkstyle and generates reports from these checks.
        The checks are included in gradle's *check* task (you can run them by executing `./gradlew check` command).

    * org.owasp.dependencycheck

        https://jeremylong.github.io/DependencyCheck/dependency-check-gradle/index.html

        Provides monitoring of the project's dependent libraries and creating a report
        of known vulnerable components that are included in the build. To run it
        execute `gradle dependencyCheck` command.

  * jacoco

    https://docs.gradle.org/current/userguide/jacoco_plugin.html

    Provides code coverage metrics for Java code via integration with JaCoCo.
    You can create the report by running the following command:

    ```bash
      ./gradlew jacocoTestReport
    ```

    The report will be created in build/reports subdirectory in your project directory.

  * io.spring.dependency-management

    https://github.com/spring-gradle-plugins/dependency-management-plugin

    Provides Maven-like dependency management. Allows you to declare dependency management
    using `dependency 'groupId:artifactId:version'`
    or `dependency group:'group', name:'name', version:version'`.

  * org.springframework.boot

    http://projects.spring.io/spring-boot/

    Reduces the amount of work needed to create a Spring application

  * hmcts.ccd.sdk

    https://github.com/hmcts/ccd-config-generator

    The decentralised CCD SDK. Its BOM versions `decentralised-runtime`, and it adds the task that writes the
    CCD definition with no database:

    ```bash
      SPRING_PROFILES_ACTIVE=config-gen ./gradlew generateCCDConfig
    ```

  * com.github.hmcts.rse-cft-lib

    https://github.com/hmcts/rse-cft-lib

    The in-process CFT stack that `./gradlew cftlibTest` runs the linking operation against. Local only: nothing
    in `build`, `check` or the Jenkinsfile runs it. Both plugins and their dependencies resolve from HMCTS's
    Azure Artifacts feed, declared in `settings.gradle` and `build.gradle`.


  * com.github.ben-manes.versions

    https://github.com/ben-manes/gradle-versions-plugin

    Provides a task to determine which dependencies have updates. Usage:

    ```bash
      ./gradlew dependencyUpdates -Drevision=release
    ```

## Building and deploying the application

### Building the application

The project uses [Gradle](https://gradle.org) as a build tool. It already contains
`./gradlew` wrapper script, so there's no need to install gradle.

**Requires JDK 21.** `build.gradle` pins the toolchain and the runtime image is
`base/java:21-distroless`; an older JDK fails before compiling anything, because Gradle has no
resolver configured to download a 21 toolchain for itself.

To build the project execute the following command:

```bash
  ./gradlew build
```

### The database, and what the tests need from Docker

The service uses Postgres, with the schema owned by Flyway (`src/main/resources/db/migration`).
`docker compose up` starts a local Postgres alongside the API; it is published on **5433**, not
5432, because the sibling `deed-poll-admin` stack already binds 5432 and both are routinely
running at once.

Tests never touch that database. Anything needing one starts a throwaway Postgres via
Testcontainers, so `./gradlew build` requires a working Docker but no local setup.

Jenkins explicitly enables the PostgreSQL-backed tests on PR and master builds. The CNP library
selects the environment's Ubuntu worker; its image includes Docker and its startup configuration
grants `jenkinsssh` access. A `before('test')` check verifies daemon access, and Testcontainers uses
the HMCTS ACR image mirror after the shared pipeline logs in. Unit and integration coverage feed
the same JaCoCo XML report for Sonar; both sets of test results are published to Jenkins.

`INTEGRATION_TESTS_ENABLED=false` remains available for a local Docker-free run. It excludes
database-backed tests and is not equivalent to the CI coverage run. An unavailable Docker daemon
in Jenkins fails the build rather than falling back to that partial suite.

Two things can make Testcontainers fail on a developer machine for reasons unrelated to the
code, both when the build itself runs inside a container that shares the host's Docker daemon:

- **Ryuk, the resource reaper, cannot be reached.** It listens on IPv6 inside its container,
  and some Docker Desktop configurations do not forward that. Export
  `TESTCONTAINERS_RYUK_DISABLED=true`; containers are still removed on a clean JVM exit, only
  crash-cleanup is lost. This is deliberately **not** set in the repo, so CI keeps the reaper.
- **Published ports are not forwarded.** The test harness already handles this by reaching
  containers over the Docker bridge network in preference to their published port — see
  `ContainerDatabase` for the detail.

### Abuse limits

V37 adds `deedpoll_request_budget`, the per-subject counters behind `429` on search, email preview, email send,
upload and the Gazette routes. `RATE_LIMIT_MODE` (`off`/`observe`/`enforce`, default `observe`) switches only
those budgets. Three limits enforce whatever the mode: the per-pod in-flight upload cap
(`RATE_LIMIT_UPLOAD_IN_FLIGHT`), the daily per-case, per-template email cap (`EMAIL_MAX_PER_CASE_PER_TEMPLATE_PER_DAY`,
default 3; a fourth distinct-key send is a `409`, a replayed key is still `200`) and the case list statement
timeout (`LIST_STATEMENT_TIMEOUT`). See [docs/RATE-LIMITS.md](docs/RATE-LIMITS.md).

### The audit trail

V36 adds `deedpoll_audit_event` (who, when, action, entity, case and field names; kept 7 years) and
`deedpoll_audit_event_value` (before/after values, disposed with the case). Row triggers make both append-only;
only the two `SECURITY DEFINER` disposal functions can delete, and nothing schedules them yet. Every
caseworker and judicial write records an event in its own transaction, so a failed audit insert rolls the change
back. A case view is audited and fails open; a document download is audited and fails closed. Authentication and
authorisation failures are logged by `SecurityEvents` with identifiers only. No endpoint reads the trail yet.

### Running the application

Create the image of the application by executing the following command:

```bash
  ./gradlew assemble
```

Note: Docker Compose V2 is highly recommended for building and running the application.
In the Compose V2 old `docker-compose` command is replaced with `docker compose`.

Create docker image:

```bash
  docker compose build
```

Run the distribution (created in `build/install/deed-poll-api` directory)
by executing the following command:

```bash
  docker compose up
```

This will start the API container exposing the application's port
(set to `4550` in `application.yaml`).

In order to test if the application is up, you can call its health endpoint:

```bash
  curl http://localhost:4550/health
```

You get a status and nothing else:

```
  {"status":"UP"}
```

That is deliberate, and it is worth knowing before you go looking for the missing detail. This endpoint
is on the unauthenticated allowlist and it aggregates *every* registered contributor — including ones
nobody here chose, such as Boot's auto-configured `diskSpace`, whose details name the pod's absolute
filesystem path and its free bytes. So `management.endpoint.health.show-details` is `never` at the
endpoint level, and the two probe groups re-enable details only for themselves, where the include list
is closed and what can be published is reviewable. Use `/health/readiness` when you want the breakdown.

### The two probes, and why they differ

There are two Kubernetes probes and they deliberately do not check the same things.

```bash
  curl http://localhost:4550/health/readiness
  curl http://localhost:4550/health/liveness
```

**Readiness** — should traffic come to this pod? This is the one that shows its working:

```
  {"status":"UP","components":{
    "db":{"status":"UP"},
    "deedPollSchema":{"status":"UP","details":{"tables":25}},
    "readinessState":{"status":"UP"}}}
```

It checks the database twice over: `db` is `DatabaseHealthIndicator` and `deedPollSchema` asks whether
every table in `DeedPollSchema.DEED_POLL_TABLES` is actually there. A connection check alone passes against an unmigrated
database, where the probe stays green while every request 500s.

`db` is ours rather than Spring Boot's — `management.health.db.enabled` is `false` — because Boot's
`DataSourceHealthIndicator` reports a failure as `Health.down(exception)`, and a JDBC message names the
host, the port, the database and the user it failed to authenticate. This group runs with
`show-details: always` on an endpoint that takes no credential, so that message is logged and not
published; a healthy `db` therefore has no details at all. When a table is missing the response
**names it**, so a page that says "readiness DOWN" comes with the diagnosis attached rather than
needing one:

```
  {"status":"DOWN","components":{
    "db":{"status":"UP"},
    "deedPollSchema":{"status":"DOWN","details":{
      "error":"the deed-poll schema is incomplete. Has the migration job run?",
      "missingTables":["deedpoll_notes","deedpoll_witness"]}},
    "readinessState":{"status":"UP"}}}
```

**Liveness** — should this process be killed and restarted? It checks nothing but the process
answering, on purpose. Restarting cannot create a missing table and cannot fix someone else's
database, so putting the database in liveness turns a blip into a rolling restart of every replica
and destroys the pods that would have recovered on their own.

Both are public, because Kubernetes has no token to present. `uk.gov.hmcts.reform.deedpoll.health`
and the `management.endpoint.health.group` block in `application.yaml` are the two places this is
written down; `HealthProbeTest` asserts it over HTTP, including that readiness answers **503** and
not 200 when the database is unreachable.

**Kubernetes does not consult them yet.** The chart is now `charts/deed-poll-api`, with this service's
name, image and ingress host, but no probe path is configured — so these endpoints are tested and not
yet wired. Naming the two paths is part of the outstanding deployment onboarding.

### Gazette publication foundation

`GET /caseworker/cases?filter=readyForPublication` uses the working predicate
`publishToLondonGazette=true AND dateNoticeSentToLondonGazette IS NULL AND state=Enroled`,
excluding `type=DeedOfRelinquishment`. Other types, including unset types, retain their
existing behaviour. The exclusion applies before pagination and to the total used by admin
for its ready count.
The default sort is `dateEnroled:asc`; an explicit `sort` overrides it. Existing `where`,
search and pagination still apply. Unknown or repeated filter values fall back to `all`.
The filter is also accepted by saved-query create/update requests and is available to
callers with case-list access. This implementation does not constitute new business sign-off.

| Method | Path | Access and response |
|---|---|---|
| GET | `/caseworker/gazette-settings` | Delivery Manager-only current recipient, revision and change attribution |
| PUT | `/caseworker/gazette-settings` | Delivery Manager-only recipient update with optimistic revision check |
| GET | `/caseworker/gazette-notices` | Requires `caseworker-deedpoll-delivery-manager`; other authenticated roles receive 403. Returns paged publication history |

History accepts `page` (1-based, default 1) and `size` (default 25, capped at 100),
using the existing paging validation. It returns `batches`, `page`, `size`, `total`
and `totalPages`. Each batch exposes `batchId`, `sentAt`, `sentBy` (display name), `caseCount`,
`referenceNumbers` and `referencesTruncated`. References are frozen on the audit membership,
with a preview of at most 100 per batch in lexical display order (not numerical reference or send order).
`referencesTruncated` is true when more members exist; `caseCount` remains the full frozen count.
The query limits membership in PostgreSQL before loading it into memory. References are never
read from today's case. Batches are newest first, with descending
batch UUID as the equal-timestamp tie-breaker. There is no custom filtering or sorting.
An empty history returns an empty `batches` array and zero totals. Neither subject IDs
nor request IDs are published.

Recipient settings start unset at revision 0. `PUT` takes only `recipientEmail` and
`revision`: one validated email address (trimmed, maximum 254 characters and 64 for
the local part) and the integer revision returned by `GET`. Blank/null recipients
are refused. A stale revision returns 409 without changing the setting. Saving an
unchanged address at its current revision is a no-op. V33 persists the singleton
and an append-only application change history with previous/new addresses, UTC
change time and verified caller subject/display name. Subject IDs are not exposed.
Settings and their audit change commit in one transaction; concurrent first saves
have only one winner. The settings endpoint sends no email. Future batch sending
must check the confirmed settings revision and snapshot its accepted recipient.

V31 adds the batch and membership audit tables, including database-enforced request-ID
uniqueness. V32 adds indexes for case membership checks and bounded reference previews.
The request ledger persists reservations, ownership and delivery evidence, with atomic
local case-date/history/note settlement. Active requests block case edits; retained
request evidence prevents deletion. Uncertain outcomes keep reservations.

| Method | Path | Behaviour (Delivery Manager-only) |
|---|---|---|
| POST | `/caseworker/gazette-notice-previews` | Check up to 100 distinct cases, recipient and configuration; return counts, references and a 30-minute confirmation token |
| POST | `/caseworker/gazette-notices` | Submit one confirmed batch when enabled; repeated key/body/subject reads status without another send |
| GET | `/caseworker/gazette-notice-requests` | Paged request statuses; optional exact state filter |
| GET | `/caseworker/gazette-notices/{batchId}` | Current request or legacy sent status; unavailable legacy facts are null |
| GET | `/caseworker/gazette-notices/{batchId}/cases` | All frozen references, paged at 25 by default, capped at 100 |

Preview writes no evidence or files. Domain admission rechecks the fingerprint and
recipient revision under locks and captures immutable mapped values for spreadsheet
generation. The [clean templates](src/main/resources/gazette-templates/README.md)
retain visible columns and blank missing values. No live-case reread is needed to
produce files after admission. No export/archive endpoint exists.

A repeated HTTP request uses its original UUID `Idempotency-Key` and exact body.
202 means preparing/sending; 200 means a terminal status, **not necessarily sent**.
Both return a status Location. Read `state` and fixed `failureCode`; never use a new
key to work around uncertainty. Subjects, request keys, owner claims and provider
receipts are not published. Sent means accepted by Notify, not delivered or published.

`GAZETTE_ENABLED` defaults false; provisioned previews also need the configured
recipient, the required `NOTIFY_TEMPLATE_GAZETTE_ADULT`/`MINOR`/`MIXED` variant and
existing Notify key/reply-to/retention settings. Disabled or incomplete configuration
returns 503 `GAZETTE_UNAVAILABLE` before admitting a new request. Existing requests
remain readable and repeatable while delivery is disabled.

The originating POST generates all required files, commits send authority, then sends
one email with download links. Known refusals (400/403/429) end `NotSent`; ambiguous
responses or failed local acceptance settlement stop as `Uncertain`. No request is
automatically sent again. Recovery runs independently every minute, settling at most
25 expired requests per pass without calling Notify. It releases expired preparation
reservations and retains every possible-send reservation. File bytes and upload values
stay in memory; only file metadata/checksums and provider receipt evidence are stored.

Gazette uses a dedicated HTTP transport with connection retries and redirects disabled,
a non-replayable body, 5-second connect, 30-second read/write and 60-second call timeouts.
It connects directly; deployment egress must allow this and must not replay POSTs.
Activation still requires Notify provisioning, support ownership and controlled
recipient/download evidence. See the [delivery and recovery runbook](docs/GAZETTE-RECOVERY.md).

See the active [parent plan](docs/plans/gazette-1-ready-list-and-batch-send.md),
[ledger evidence](docs/plans/completed/gazette-1c-request-ledger-and-guards.md) and
[preview/status implementation](docs/plans/completed/gazette-1d-preview-admission-and-status.md), and
[delivery/recovery evidence](docs/plans/completed/gazette-1e-delivery-and-recovery.md).

### The OpenAPI document

Published at `/v3/api-docs`, and browsable at `/swagger-ui.html`. `springdoc.packagesToScan` covers
`…deedpoll.api`, so a controller is documented by being in the package it belongs in rather than by
somebody remembering to add it. `OpenAPIPublisherTest` writes the document to
`/tmp/openapi-specs.json` by default (the pipeline contract). For an isolated local run, set
`OPENAPI_OUTPUT_PATH` to an absolute filename in an existing worktree-specific evidence directory. It asserts the caseworker, judicial and admin surfaces —
both operation counts and every path — rather than merely being served.

`OpenAPIConfiguration` declares `servers: [{ url: "/" }]`, resolved against the origin serving the API
document ([OpenAPI relative URLs](https://spec.openapis.org/oas/v3.1.0.html#relative-references-in-urls)).
Swagger's requests therefore stay on that host and scheme instead of a generated localhost URL or a
fixed environment. The publisher test checks global, path and operation server declarations for both
HTTP and HTTPS document requests. Consumers of a downloaded artifact must supply their API base URL;
admin already uses `DEED_POLL_API_URL` independently of this metadata.

`OpenAPIForbiddenResponses` publishes a shared `Forbidden` response (the `ApiError` body) as the 403 of every
operation under a `StaffArea`, selected by `StaffArea.of` so a new staff route is covered by being where it belongs.
`GET /`, `GET /me`, an operation with an empty `security` override and one that already declares its own 403 are
left alone. This documents existing behaviour only; no runtime change. `OpenAPIForbiddenResponsesTest` and
`OpenAPIPublisherTest` assert it. The Fortify rescan and refreshing deed-poll-admin's copied `openapi.json` are
separate follow-ups.

This is a documentation correction, not TLS enforcement: a document served over HTTP still resolves
to HTTP. No runtime TLS rollout is required for this change. Fortify acceptance of the relative URL
must be retested; transport security still needs verified deployment/network evidence before finding
`3B96C2B66F875E1E836CEAB415F8EA96` can be closed.

**Nothing currently consumes that file.** The enabled `integration` source set produces it during
Jenkins `check` and a local `./gradlew integration`, but `Jenkinsfile_CNP` has no publishing step.
Restoring the publish to
[`hmcts/cnp-api-docs`](https://github.com/hmcts/cnp-api-docs) is an accepted outstanding gap — see
`docs/RESUME.md`.

### Alternative script to run application

To skip all the setting up and building, just execute the following command:

```bash
./bin/run-in-docker.sh
```

For more information:

```bash
./bin/run-in-docker.sh -h
```

The script sets **no** environment variables of its own — it runs `./gradlew clean` / `assemble` when
asked and then `docker compose up`, so `-c` and `-i` are the only options that do anything and the
"Available parameters" block is empty. Configuration comes from `docker-compose.yml` and `.env`; see
[Authentication](#authentication) for the two variables a caseworker call needs.

Whenever any variable is changed, or any other script regarding docker image/container build, the suggested way to ensure all is cleaned up properly is by this command:

```bash
docker compose rm
```

It clears stopped containers correctly. Might consider removing clutter of images too, especially the ones fiddled with:

```bash
docker images

docker image rm <image-id>
```

There is no need to remove postgres and java or similar core images.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details
