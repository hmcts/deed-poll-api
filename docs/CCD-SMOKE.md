# CCD foundation — the operator-run AAT smoke procedure

Written 2026-09-27 by plan 02's Task 9, and step 3 rewritten the same day by plan 02b's Task 6. **This is a procedure, not evidence: none of it has been run**, and none of
it can be until every item in [plan 02's Dependencies](plans/completed/deed-poll-api-02-ccd-foundation.md#dependencies--outside-this-plan-and-not-ralphex-work)
is done. What *has* been proved is the code, against WireMock and against the in-process cftlib stack
(`./gradlew cftlibTest`, `CcdLinkingCftlibTest`), on placeholder identifiers throughout.

Run it by hand, in order, and stop at the first step that does not answer as written. Every step names what it
expects; a different answer is a finding, not something to work around.

## AAT activation inventory — 2026-10-05

Read-only checks against AAT cluster 00 found the following. This is preparation, not a completed smoke.
The API runs `c8c34ff`; it still selects filesystem storage. No activation switch was changed.

| Dependency | Observed state / next action |
|---|---|
| S2S identity | `deed_poll_api` is proposed in `hmcts/service-auth-provider-app#1140`, still a draft pending production keys. The AAT platform key exists, but the running S2S SecretProviderClass does not mount it. Complete registration and verify a lease before calling CCD. |
| Product-vault key | Add the AAT-only infra copy `microservicekey-deed-poll-api` → `api-s2s-secret`, then mount it as `DEED_POLL_API_S2S_SECRET`. Never reuse the web key. |
| CCD/CDAM allowlists | Neither running AAT service allows `deed_poll_api`; add it to each AAT Flux allowlist. The API does not need the case-user-role-management allowlist. |
| Identifiers and system identity | Jurisdiction, case type and system role remain unconfirmed. Provision the dedicated system user, password-grant client and vault credentials; ensure its client audience is accepted by the API persistence chain. |
| Definition and routing | Generate with confirmed IDs, register the mapped IDAM roles at PUBLIC and import. Add the case-type prefix → deed-poll API route in AAT data-store. |
| CDAM service permissions | Add `deed_poll_api`, restricted to the confirmed case type/jurisdiction, to CDAM `service_config.json` with CREATE/READ/UPDATE. `ccd_data` already has wildcard ATTACH/READ. |
| Staff access | This branch supplies the read-only profile for the confirmed admin role. Non-admin caseworkers and judicial document access remain outside that mapping. |
| Existing files and cutover | Inventory Local rows, original bytes and queued email references before any restart/cutover. There is no mixed-backend fallback. Follow 03c and `CDAM-RECOVERY.md`. |

Confirmed endpoint values (from the running AAT platform configuration):

```yaml
IDAM_API_URL: https://idam-api.aat.platform.hmcts.net
IDAM_S2S_AUTH_URL: http://rpe-service-auth-provider-aat.service.core-compute-aat.internal
CCD_DATA_STORE_URL: http://ccd-data-store-api-aat.service.core-compute-aat.internal
CASE_DOCUMENT_AM_URL: http://ccd-case-document-am-api-aat.service.core-compute-aat.internal
DOCUMENT_URL_BASE: http://dm-store-aat.service.core-compute-aat.internal
```

The document URL base matches AAT data-store's deployed URL pattern. Endpoints and an S2S key are insufficient
for activation: retain `CCD_LINKING_SWEEP_ENABLED=false` and `DOCUMENT_STORE=filesystem` until the identity,
definition, routing, permissions and existing-file gates are met. Then run this procedure and `CDAM-SMOKE.md`
in a controlled window, including staff read-back across replicas. Record live evidence separately from local tests.

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
| The linking operation's timings, if the defaults do not suit | `CCD_LINKING_LEASE` (`PT5M`), `CCD_LINKING_SETTLE_AFTER` (`PT10M`), `CCD_LINKING_MAX_ATTEMPTS` (`3`), `CCD_LINKING_RETRY_AFTER` (`PT5M`, times the tries so far); the claimant is `HOSTNAME` |
| The case list request's linking sweep | `CCD_LINKING_SWEEP_ENABLED` (`false`; set `true` only once everything above is in place), `CCD_LINKING_SWEEP_MAX_RECORDS` (`2`), `CCD_LINKING_SWEEP_MAX_RECONCILIATIONS` (`5`), `CCD_LINKING_SWEEP_MAX_DURATION` (`PT15S`) |
| Data-store routes our case type to us | data-store's `ccd.decentralised.case-type-service-urls`, keyed on a **prefix of the case type id** |
| `deed_poll_api` allowed to call data-store; `ccd_data` allowed to call us | data-store's S2S allowlist; ours is the constant `SecurityConfiguration.CCD_PERSISTENCE_CALLERS` |
| The system and `caseworker-deedpoll-admin` roles registered as **user roles** in definition store | definition store's user-role table, at `PUBLIC` |
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
for the three identifiers — one state (`Registered`), two events (`linkDeedPoll`, `updateSupportingDocuments`),
three application fields (`deedPollId`, `deedPollReference`, `supportingDocuments`). `DEED_POLL_SYSTEM` holds CRU
on the case type/state, CR on linking and CRU on the document event, with D only on the documents field.
`DEED_POLL_DOCUMENT_READER` holds R on the case type/state/documents only and maps `caseworker-deedpoll-admin`.
Neither profile is granted history. Compare against the pinned definition; do not import the earlier link-only
shape described in the original plan 02 procedure.

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

**The trigger is the case list request** (plan 02b). `POST /caseworker/cases` does not link; the next
`GET /caseworker/cases` does, through `CcdLinkingSweep`, once `CCD_LINKING_SWEEP_ENABLED=true`. Switch it on only
after steps 1 and 2 answered as written: with the sweep on and onboarding incomplete, every attempt fails before
submission, and each list request drives the head of the queue towards `NeedsRepair`, `CCD_LINKING_MAX_ATTEMPTS`
tries at a time. The sweep links at most `CCD_LINKING_SWEEP_MAX_RECORDS` records per request, oldest first, so
records that already existed are linked first. There is still no HTTP endpoint that links a named record.

With the sweep switched on, for one deed-poll record that has no CCD reference:

1. Create it with `POST /caseworker/cases`, or pick an existing unlinked record, then request `GET /caseworker/cases`
   as any caseworker until that record is at the head of the queue and linked. Expect the list itself to answer
   exactly as it would with the sweep off. A failure is logged at WARN in fixed prose and never reaches the response.
2. Its creation attempt (`deedpoll_ccdcreationattempt`) is `Linked`, and `deedpoll_deedpoll."ccdCaseReference"`
   holds a 16-digit reference. As an admin, `GET /caseworker/ccd-attempts` shows the same thing without database
   access: the record no longer appears in the page, and the `Linked` count has grown by one.
3. Read `GET {data-store}/cases/{reference}` as the system user. Expect the same reference, the confirmed case type
   and jurisdiction, state `Registered`, `deedPollId` equal to the record's UUID and `deedPollReference` equal to
   its display reference.
4. Request the list again. Expect no second case in CCD: a linked record is never selected again.

An attempt left in `AwaitingConfirmation` is the operation doing its job: read its bounded failure detail, fix the
environment, and let later list requests settle it. Each sweep reconciles at most
`CCD_LINKING_SWEEP_MAX_RECONCILIATIONS` expired claims and `AwaitingConfirmation` attempts. **It never reads
`NeedsRepair`.** A `NeedsRepair` attempt stays live and blocks a fresh creation for its record, but it no longer
blocks the queue. A released `Pending` attempt waits `CCD_LINKING_RETRY_AFTER` times its tries before the next one.

### Repairing an attempt

These endpoints need a caseworker holding `caseworker-deedpoll-admin`. Anyone else gets a 403. Neither move calls
CCD or writes a binding. Each is one conditional statement from `NeedsRepair`, and the next list request does the
rest.

1. `GET /caseworker/ccd-attempts[?status=NeedsRepair&page=&size=]`. Expect the counts per status and a page of
   attempts that are not `Linked`, each with its bounded `failureDetail` and `canRetryFromScratch`. `status=Linked`
   is a 400.
2. Read the record and CCD before choosing a move:
   - `POST /caseworker/ccd-attempts/{attemptId}/reconciliation` (`NeedsRepair -> AwaitingConfirmation`) is always
     open. Use it once the environment is fixed, so that the next sweep reads the binding and CCD again.
   - `POST /caseworker/ccd-attempts/{attemptId}/retry` (`NeedsRepair -> Pending`, tries reset) is open only while
     `canRetryFromScratch` is true. That means either nothing was ever submitted to CCD for this attempt, or every
     try was refused with nothing bound (its `failureDetail` ends `no attempts left`). Otherwise it is a 409 that
     says so. Use reconciliation instead.
   - **An attempt that ran out of tries on refused submissions is retryable** (V24, 2026-09-28). Fix the cause of
     the refusals first, or the retry spends its tries the same way. Reconciling it first does not close the retry.
     After a retry, a submission whose outcome is unknown makes the attempt non-retryable again, and that is
     deliberate.
3. Expect a 200 with the attempt as it now stands. An attempt that is not `NeedsRepair` is a 409, including the
   second of two admins acting at once. An unknown id is a 404. Every move, made or refused, is logged at INFO with
   the attempt id and without the admin's identity.

**Never clear a binding or delete an attempt to make a retry happen.** A fresh start-event token mints a fresh
idempotency key and can create a second case for one record. `retry` is safe only because it is refused whenever a
submission may have reached CCD.

## 4. Record the result

Write what ran, when, against which identifiers and with what answer into `docs/RESUME.md`. Only then does
"CCD works in AAT" become a statement anybody should repeat.
