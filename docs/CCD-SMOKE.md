# CCD foundation — the operator-run AAT smoke procedure

Written 2026-09-27 by plan 02's Task 9. **This is a procedure, not evidence: none of it has been run**, and none of
it can be until every item in [plan 02's Dependencies](plans/completed/deed-poll-api-02-ccd-foundation.md#dependencies--outside-this-plan-and-not-ralphex-work)
is done. What *has* been proved is the code, against WireMock and against the in-process cftlib stack
(`./gradlew cftlibTest`, `CcdLinkingCftlibTest`), on placeholder identifiers throughout.

Run it by hand, in order, and stop at the first step that does not answer as written. Every step names what it
expects; a different answer is a finding, not something to work around.

## 0. What has to exist first

Each of these is onboarding and belongs to Steve; the plan's Dependencies section is the list of record.

| Input | Where it lands in this service |
|---|---|
| Confirmed jurisdiction and case type id | `CCD_JURISDICTION_ID`, `CCD_CASE_TYPE_ID` (default `DEED_POLL_PLACEHOLDER`) |
| The IDAM role the system user holds | `CCD_SYSTEM_ROLE` (default `DEED_POLL_PLACEHOLDER`) — mapped onto the `DEED_POLL_SYSTEM` access profile |
| The system user and its password | `DEED_POLL_SYSTEM_USERNAME`, `DEED_POLL_SYSTEM_PASSWORD` (empty by default; `SystemUserTokens` refuses an empty one) |
| IDAM's API, for the password grant | `IDAM_API_URL` (default `http://localhost:5000`), set alongside `IDAM_API_ISSUER_URL` |
| An IDAM OAuth client for the password grant | `IDAM_CLIENT_ID`, `IDAM_CLIENT_SECRET`, `IDAM_CLIENT_REDIRECT_URI` |
| `deed_poll_api` registered with S2S, secret in `deed-poll-aat` | `DEED_POLL_API_S2S_SECRET` |
| Data-store's URL in the environment | `CCD_DATA_STORE_URL` (default is a local stack's 4452) |
| The linking operation's timings, if the defaults do not suit | `CCD_LINKING_LEASE` (`PT5M`), `CCD_LINKING_SETTLE_AFTER` (`PT10M`), `CCD_LINKING_MAX_ATTEMPTS` (`3`); the claimant is `HOSTNAME` |
| Data-store routes our case type to us | data-store's `ccd.decentralised.case-type-service-urls`, keyed on a **prefix of the case type id** |
| `deed_poll_api` allowed to call data-store; `ccd_data` allowed to call us | data-store's S2S allowlist; ours is the constant `SecurityConfiguration.CCD_PERSISTENCE_CALLERS` |
| The system role registered as a **user role** in definition store | definition store's user-role table, at `PUBLIC` |
| data-store → our `.internal` address reachable | network (`azure-platform-terraform#3161` created the address) |

Two things cftlib found that no WireMock test could have, both repeated here because both fail quietly:

- **The routing key is a prefix, and environment-variable binding eats underscores.** Spring reads every `_` in a
  variable name as a separator, so `CCD_DECENTRALISED_CASE-TYPE-SERVICE-URLS_DEED_POLL_X` binds as
  `deed.poll.x` and routes nothing. Use a prefix without underscores, or set the map in yaml.
- **Registering only the access profile is not enough.** Data-store takes a pseudo role assignment's security
  classification from definition store's user-role table keyed on the **IDAM role's** name. Without it, our
  `/ccd-persistence` commits the binding and data-store then fails its own response ("null
  SecurityClassification for role: …") — a 502 to us, and the attempt waits in `AwaitingConfirmation`.

## 1. Generate and import the definition

```bash
export CCD_CASE_TYPE_ID=<confirmed> CCD_JURISDICTION_ID=<confirmed> CCD_SYSTEM_ROLE=<confirmed>
SPRING_PROFILES_ACTIVE=config-gen ./gradlew generateCCDConfig
ls build/definitions/"$CCD_CASE_TYPE_ID"
```

Expect one directory named for the case type. Its contents must match `src/test/resources/ccd-definition/` except
for the three identifiers — one state (`Registered`), one event (`linkDeedPoll`), two fields (`deedPollId`,
`deedPollReference`), `DEED_POLL_SYSTEM` granted create and read and nothing else granted anything. Anything more
is not the definition this service was tested against.

Convert the JSON to a spreadsheet with CCD's definition processor, as `pcs-api`'s `bin/create-xlsx.sh` does
(`hmctsprod.azurecr.io/ccd/definition-processor:latest json2xlsx -D <dir> -o <file>.xlsx`), and import it through
whoever owns the import in AAT (a Dependencies item). Expect the import to be accepted. A refusal naming layouts
("At least one layout case field must be defined…") means the generated definition is not the one pinned in
`DeedPollDefinitionTest`.

## 2. Authentication with the provisioned identities

With the service deployed on the confirmed configuration:

1. **The system user signs in.** A password grant for `DEED_POLL_SYSTEM_USERNAME` against the configured client
   returns a token whose `roles` include `CCD_SYSTEM_ROLE`. If the role is absent, stop: the persistence chain
   will refuse every callback.
2. **`/ccd-persistence/**` refuses everybody but data-store.** From inside the cluster:
   - no `ServiceAuthorization` → **401**;
   - a valid S2S token for `deed_poll_api` (our own name) → **403**;
   - a valid S2S token for any other service but `ccd_data` → **403**.

   Do not try the success path by minting a `ccd_data` token by hand; step 3 exercises it the way data-store does.
3. **Nothing else moved.** A caseworker call to `/caseworker/cases` still needs only the bearer token — no
   `ServiceAuthorization` — and `/callbacks/**` and `/noc/**` stay refused or unmapped.

## 3. Create and read one case

**This step has no trigger yet.** `CcdLinkingService.link(deedPollId)` is the only way a case is created, and by
the plan's design nothing calls it: online submission and migration wire it in their own plans. There is
deliberately no HTTP endpoint for it. Until one of those lands, either the first of them runs this step, or Steve
decides on an operator-only entry point — which would be a new, secured route and its own change.

When a trigger exists, for one deed-poll record that has no CCD reference:

1. Run the linking operation once. Expect `LINKED`.
2. Its creation attempt (`deedpoll_ccdcreationattempt`) is `Linked`, and `deedpoll_deedpoll."ccdCaseReference"`
   holds a 16-digit reference.
3. Read `GET {data-store}/cases/{reference}` as the system user. Expect the same reference, the confirmed case type
   and jurisdiction, state `Registered`, `deedPollId` equal to the record's UUID and `deedPollReference` equal to
   its display reference.
4. Run the linking operation again for the same record. Expect `ALREADY_LINKED` and no second case in CCD.

An attempt left in `AwaitingConfirmation` is the operation doing its job: read its bounded failure detail, fix the
environment, and let `reconcileOutstanding()` settle it. That pass reads only expired claims and
`AwaitingConfirmation`; **it never reads `NeedsRepair`**, and there is no repair operation yet. A `NeedsRepair`
attempt stays live, so the record can be offered no fresh creation until somebody decides what it is, by reading
the record's binding and CCD. The same holds for an attempt whose tries were all refused and nothing was created.
**Never clear a binding or delete
an attempt to make a retry happen** — a fresh start-event token mints a fresh idempotency key and can create a
second case for one record.

## 4. Record the result

Write what ran, when, against which identifiers and with what answer into `docs/RESUME.md`. Only then does
"CCD works in AAT" become a statement anybody should repeat.
