# CDAM — the upload, attach, download and delete sequence, read from source

Written 2026-09-28 by plan 03a's Task 1, before any of it was built. **Everything here is read from source, not
observed**: nothing has called CDAM yet, and the operator-run AAT round trip (guard 3) is what will confirm or
correct it. Where a claim could not be settled from source, it says so.

## Sources and versions

Paths are relative to the workspace root (`cft-workspace/`). Abbreviations:

| Prefix | Path | Commit read |
|---|---|---|
| **CDAM** | `apps/ccd/ccd-case-document-am-api/src/main/java/uk/gov/hmcts/reform/ccd/documentam/` | `fa771049` |
| **CDAM-res** | `apps/ccd/ccd-case-document-am-api/src/main/resources/` | `fa771049` |
| **DS** | `apps/ccd/ccd-data-store-api/src/main/java/uk/gov/hmcts/ccd/` | `7d0306e77` |
| **DS-res** | `apps/ccd/ccd-data-store-api/src/main/resources/` | `7d0306e77` |
| **DM** | `apps/dm/document-management-store-app/src/main/` (read with `git show HEAD:`) | `8b01cdd1` |
| **LIB** | `libs/rse-cft-lib/cftlib/` at tag `0.19.2377` (the clone's HEAD is later; read with `git show 0.19.2377:`) | `0.19.2377` |
| **CLIENT** | `libs/ccd-case-document-am-client/src/main/java/uk/gov/hmcts/reform/ccd/document/am/` at tag `1.59.2` | `1.59.2` |

cftlib 0.19.2377 pins CDAM at `6d21b3b6` and data-store at `bf1c3a0b` (`git ls-tree 0.19.2377 projects/`). The local
clones have no `src/main` changes against those pins. The CDAM jar cftlib ships has `application.yaml` and
`service_config.json` byte-identical to source, so cftlib overrides neither.

## Answers the plan needs first

1. **Data-store attaches, not us.** For a decentralised case type, data-store calls CDAM's `attachToCase`
   **before** it calls `/ccd-persistence`, on case creation and on every event. This service never calls
   `attachToCase`, and needs no ATTACH permission of its own. What it must do is put each new document's
   `document_hash` in the **event payload** next to its `document_url`. See [Attach](#2-attach).
2. **dm-store's URL can be overridden without patching cftlib.** Decision 2's premise, that cftlib points CDAM at
   AAT's dm-store, holds only in AAT auth mode. This service runs cftlib with `authMode = Local`
   (`build.gradle`'s `cftlibTest` configuration), and there cftlib never sets `DM_STORE_BASE_URL`. CDAM falls back to
   `http://localhost:4506`. Setting `DM_STORE_BASE_URL` in the `cftlibTest` task's environment, or running the
   WireMock stub on 4506, points CDAM at the stub. See [cftlib](#6-cftlib-dm_store_base_url).
3. **CDAM's service permissions can be replaced but not appended to.** They are a classpath resource with no path
   override. Task 5 has to supply the whole list. See [Service permissions](#7-cdams-service-to-case-type-permissions).
4. **Three more things Tasks 5 and 6 must handle, found on the way:**
   - **Data-store refuses document URLs outside a host pattern.** `ccd.document.url.pattern` defaults to
     `https?://((dm-store:8080/documents/[A-Za-z0-9-]+(?:/binary)?)|…)` (`DS-res/application.properties:87`,
     checked in `DS/domain/types/DocumentValidator.java:78-87`), and cftlib does not set it. The stub's
     `_links.self.href` must therefore read `http://dm-store:8080/documents/{id}`, or `cftlibTest` must set
     `CCD_DOCUMENT_URL_PATTERN`. CDAM itself takes only the last 36 characters of the href as the document id, and
     always calls its own `documentStoreUrl`, so the href's host is never dialled.
   - **Download needs field-level READ for the calling user.** CDAM asks data-store whether *the user whose token
     it was given* can read the document in the case (see [Download](#3-download)). The api calls CDAM as the
     system user, so `DEED_POLL_SYSTEM` needs READ on the new document field, and the case view must project it.
   - **`deed_poll_api` is on neither of CDAM's lists.** It is not in `CASE_DOCUMENT_S2S_AUTHORISED_SERVICES`, and
     `service_config.json` has no entry for it. Both are onboarding, and both have to be supplied in cftlib.
5. **The upload limits are looser than ours.** CDAM allows 1024 MB and checks no MIME type; dm-store allows 500 (media) and
   1024 (other), in a unit its yaml does not state, and all six of `UploadPolicy.ALLOWED_UPLOAD_MIME_TYPES` are on its whitelist.
   `UploadPolicy.MAX_UPLOAD_BYTES` (25 MB) stays. See [Limits](#5-tokens-allow-lists-and-limits). Nothing to flag.

## 1. Upload

`POST /cases/documents` (`CDAM/controller/endpoints/CaseDocumentAmController.java:214-259`), `multipart/form-data`,
with `ServiceAuthorization` and `Authorization: Bearer <user JWT>`.

**Request** (`CDAM/dto/DocumentUploadRequest.java:21-41`):

| Field | Rule |
|---|---|
| `files` | required, at least one |
| `classification` | required, a `Classification` name (`PUBLIC`, `PRIVATE`, `RESTRICTED`) |
| `caseTypeId`, `jurisdictionId` | required, matching `^[a-zA-Z0-9_-]*$` (`CDAM/.../Constants.java:39`) |

A failed rule is a 400 (controller `:433-443`).

**The service's permission.** `checkServicePermission(caseTypeId, jurisdictionId, s2sName, CREATE)` (controller
`:247-252`, implemented at `CDAM/service/impl/DocumentManagementServiceImpl.java:350-415`). The caller is looked up
by the S2S token's subject (`CDAM/.../SecurityUtils.java:57-61`); an unknown service is a 403. The case type passes
if the entry lists it or `"*"`, or if the entry lists none and the permission is in `caseTypeIdOptionalFor`
(`:376-386`). The jurisdiction is a single string that must equal the entry's or be `"*"` (`:388-398`). The entry's
permissions must include CREATE (`:400-405`). Every refusal is a 403.

**What CDAM sends to dm-store** (`CDAM/client/dmstore/DocumentStoreClient.java:277-298`, streaming variant
`:398-401`, selected by `stream.upload.enabled`, `:254-256`): `POST {documentStoreUrl}/documents` with the fields
`classification`, `metadata[jurisdiction]`, `metadata[case_type_id]`, `ttl` and `files`. No `case_id` is sent. The
`ttl` is now plus `documentTtlInDays`, default **1 day** (`CDAM-res/application.yaml:81`). The headers are CDAM's own
S2S token and `user-id`, the IDAM userinfo uid of the caller's user token (`:309-315`).

**Response** (`CDAM/dto/UploadResponse.java`, `CDAM/model/Document.java:24-38`, built at
`DocumentManagementServiceImpl.java:306-330`): `documents[]`, each with `classification`, `size`, `mimeType`,
`originalDocumentName`, `hashToken`, `createdOn`, `createdBy`, `lastModifiedBy`, `modifiedOn`, `ttl`, `metadata`,
and `_links.self.href` / `_links.binary.href`.

**The hash token.**
- At upload it is `SHA-256(salt + documentId + jurisdictionId + caseTypeId)`, hex-encoded. The document id is the
  last 36 characters of `self.href` (`DocumentManagementServiceImpl.java:320-330`, `CDAM/util/ApplicationUtils.java:15-42`).
- Once attached, CDAM computes it as `SHA-256(salt + documentId + caseId + jurisdictionId + caseTypeId)` (`:210-223`).
- It is a keyed hash, not an HMAC. The salt is `idam.s2s-auth.totp_secret` (`CDAM/ApplicationParams.java:22-23`),
  from `CASE_DOCUMENT_AM_API_S2S_SECRET`, default `AABBCCDDEEFFGGHH` (`CDAM-res/application.yaml:70`). Anyone with
  the token can attach the document to a case of the type it names, which is why decision 8 keeps it transient.

**An unattached upload expires.** Until it is attached, its TTL stands, and after the TTL a download is refused (see
[Download](#3-download)). So an upload the api never gets as far as attaching is garbage that dm-store collects.

## 2. Attach

`PATCH /cases/documents/attachToCase` (controller `:308-351`). Body `CDAM/model/CaseDocumentsMetadata.java:27-47`:
`caseId` (required, 16 digits), `caseTypeId`, `jurisdictionId` (same pattern as upload), and `documentHashTokens`,
at least one `{id: UUID, hashToken}` (`CDAM/model/DocumentHashToken.java:17-24`).

**Checks.** The calling service needs ATTACH for the body's case type and jurisdiction (`:340-346`). Then, for each
document (`DocumentManagementServiceImpl.java:97-208`), CDAM reads dm-store's metadata, and:
- with a hash token: a missing document is a 404; one already attached to **the same** case is skipped, so attach
  is idempotent (`:171-180`); otherwise the token is recomputed from the **stored** metadata and a mismatch is a 403,
  "Hash token check failed" (`:182-190`);
- without one: a 403 if `hash.check.enabled` (`HASH_CHECK_ENABLED`, default **false**, `CDAM-res/application.yaml:86-88`),
  otherwise it warns and patches anyway (`:192-208`).

**What it writes.** dm-store `PATCH /documents` with `ttl = null` and metadata `{case_id, case_type_id,
jurisdiction}` (`:156-168`, `DocumentStoreClient.java:261-267`). The null TTL is what stops the document expiring.

**Which stage attaches, for a decentralised case type: data-store, before it delegates to us.**
- **Case creation.** `DS/domain/service/createcase/SubmitCaseTransaction.java:201-223`, in this order:
  `persistCasePointerAndInitId` (`:205`), `grantAccess` (`:207`), `filterDocumentHashesAgainstSavedData`
  (`:209-210`), `attachCaseDocuments` (`:212-217`), then `submitDecentralisedEvent` (`:219-223`). If the service
  answers with errors, warnings or a 4xx, the case pointer is rolled back but **the attach is not undone**
  (`:232-266`).
- **Every later event.** `DS/domain/service/createevent/CreateCaseEventService.java:281-306`: attach at `:287-292`,
  then the decentralised submit at `:294-296`. `createCaseSystemEvent` does the same (`:405-421`).
- Data-store's `decentralised/` package never calls CDAM itself; the attach lives in the shared path above.
- The call is `CaseDocumentService.attachCaseDocuments` (`DS/domain/service/getcasedocument/CaseDocumentService.java:91-109`)
  through `CaseDocumentAmApiClient.java:31-49`, which maps CDAM's 403 to `DocumentTokenException`, 400 to
  `BadSearchRequestException` and 404 to `ResourceNotFoundException`. It is gated by
  `CASE_DOCUMENT_AM_API_ATTACH_DOCUMENT_ENABLED`, default true (`DS-res/application.properties:73`). CDAM's URL is
  `CASE_DOCUMENT_AM_URL`, which cftlib sets to `http://localhost:4455` (`LIB/lib/bootstrapper/.../LibRunner.java:107`,
  `LIB/rse-cft-lib-plugin/.../CftlibExec.java:46`).

**Consequences for this service.**
- The hash token travels **in the event payload**, as `document_hash` beside `document_url`
  (`DS/.../CaseDocumentUtils.java:31-48, 73-80`). Only documents that are new against the stored data are attached
  (`:100-122`, `CaseDocumentService.java:144-161`).
- A missing hash is refused only if data-store's own `HASH_CHECK_ENABLED` is on (default false,
  `DS-res/application.properties:89`; `CaseDocumentService.java:111-123`). This service always sends it.
- **Data-store strips `document_hash` before the data reaches us** (`CaseDocumentService.removeHashes`, `:125-132`;
  called at `SubmitCaseTransaction.java:130, 152-157` and `CreateCaseEventService.java:234, 257-263`). So the
  `/ccd-persistence` handler never sees a hash token, and the case view never has one to project.
- "A lost attach" is therefore a lost event submission. Its recovery is the event's, reading CCD, never a fresh
  upload.

## 3. Download

`GET /cases/documents/{documentId}/binary` (controller `:143-191`), with `ServiceAuthorization` and a user token.
1. CDAM reads dm-store's metadata. A 404 there is a 404.
2. The calling service needs READ for the case type and jurisdiction **stored on the document**.
3. **Attached** (the metadata has a `case_id`): `checkUserPermission(caseId, documentId, READ)`
   (`DocumentManagementServiceImpl.java:332-348`) calls data-store's
   `GET {CCD_DATA_STORE_API_BASE_URL}/cases/{caseId}/documents/{documentId}`
   (`CDAM/client/datastore/CaseDataStoreClientImpl.java:47-80`) with the caller's user token and CDAM's own S2S.
   Data-store's 404, 403 and 400 pass through unchanged, and the permissions it returns must include READ.
4. **Unattached** (no `case_id`): served while its TTL is in the future, otherwise 403 "Document %s can not be
   downloaded as TTL has expired" (`:184-190`, `:139-141`).

The bytes come from dm-store `/documents/{id}/binary`, buffered, or streamed with `Range` forwarded if
`stream.download.enabled` (default false).

**What data-store checks.** `DS/v2/external/controller/CaseDocumentController.java:31-65` →
`GetCaseDocumentOperation.java:38-73`: case-type and state CAN_READ, then fields filtered by CAN_READ
(`AuthorisedGetCaseOperation.java:71-89`). The document must appear as a `document_url` **in the filtered data**, or
the answer is "case document not found". For a decentralised case, that data comes from our
`GET /ccd-persistence/cases` (`DelegatingCaseDetailsRepository.java:172-178`, `ServicePersistenceAPI.java:60`).

So a download needs all three: the document field in the case view, the calling user's role granted READ on that
field, and the document's URL in it. Removing the reference from the case view is itself enough to stop CCD-backed
downloads, before any CDAM delete.

## 4. Soft delete

`DELETE /cases/documents/{documentId}?permanent=false` (controller `:353-383`; `permanent` defaults to false).
CDAM reads the metadata first, so a missing document is a 404, then checks the service's **UPDATE** permission, not
DELETE (`:376`), then calls dm-store `DELETE /documents/{id}?permanent=…` (`DocumentStoreClient.java:218-237`) and
answers 204.

In dm-store (`DM/java/uk/gov/hmcts/dm/controller/StoredDocumentDeleteController.java:56-80`,
`AuditedStoredDocumentOperationsService.java:97-110`, `StoredDocumentService.java:140-149`) a soft delete sets
`deleted = true`; a permanent one also sets `hardDeleted` and removes the blob.

- **A download afterwards is a 404.** dm-store's lookup hides deleted documents (`StoredDocumentService.findOne`,
  `:67-73`; metadata 404 at `StoredDocumentController.java:156-160`; binary via
  `DocumentContentVersionService.findByIdAndDeleted(id, false)`, `:33-37`), so CDAM stops at step 1.
- **It is not reversible through any API.** There is no undelete endpoint, and an update goes through `findOne` and
  gets a 404 too.
- **A second delete of either kind is a silent no-op.** `findOne` hides the document, so a permanent delete *after*
  a soft delete leaves the blob in place. The retention plan must know this: permanent removal has to be the first
  delete, or be done another way.
- dm-store's delete allow-list (`DmServiceAuthFilter.java:44-80`) covers only `/documents/delete` URIs, not a
  single-document delete.

## 5. Tokens, allow-lists and limits

**Every CDAM endpoint needs both tokens.** S2S first (`ServiceAuthFilter`, `CDAM/configuration/SecurityConfiguration.java:71-99`),
then a user JWT with issuer `${IDAM_OIDC_URL}/o` (`CDAM-res/application.yaml:63`). CDAM also sends the user token
to IDAM's userinfo for the `user-id` it passes to dm-store (`SecurityUtils.java:53-55`).

| Call | S2S | User token | Service permission | User permission |
|---|---|---|---|---|
| upload | ✓ | ✓ | CREATE | none |
| attach (data-store's) | ✓ (`ccd_data`) | ✓ | ATTACH | none |
| download | ✓ | ✓ | READ | READ on the field, via data-store (attached only) |
| metadata | ✓ | ✓ | READ | as download |
| delete | ✓ | ✓ | UPDATE | none |

**Allow-lists.**
- CDAM: `CASE_DOCUMENT_S2S_AUTHORISED_SERVICES` (`CDAM-res/application.yaml:72-73`). The default includes
  `ccd_data`, `xui_webapp`, `pcs_api`, `nfdiv_case_api` and others, and **not `deed_poll_api`**.
- CDAM's own outbound identity is `ccd_case_document_am_api` (`CDAM/.../AuthClientConfiguration.java:18-24`).
- Data-store: `DATA_STORE_S2S_AUTHORISED_SERVICES` (`DS-res/application.properties:106`); this service already
  adds itself in `build.gradle`'s `cftlibTest` environment.
- dm-store: `S2S_NAMES_WHITELIST` (`DM/resources/application.yaml:88, 121-122`); CDAM is on it.
- In cftlib, `S2SLib` reads a token's subject without validating it, but **each app's allow-list still applies**
  (`LIB/lib/cftlib-agent/.../S2SLib.java:36-54`), so `deed_poll_api` must be added to CDAM's list there too.

**Limits.**

| Where | Limit | Source |
|---|---|---|
| CDAM | multipart file and request 1024 MB; no MIME check | `CDAM-res/application.yaml:38-42` |
| dm-store | multipart `${MAX_FILE_SIZE:4000MB}` | `DM/resources/application.yaml:22-24` |
| dm-store | MIME whitelist `DM_MULTIPART_WHITELIST`: jpeg, pdf, tiff, png, bmp, text/plain, msword, docx, dotx, xls(x), ppt(x), rtf, csv, audio/mpeg, audio/mp4, video/mp4 | `:209` |
| dm-store | extension whitelist `DM_MULTIPART_WHITELIST_EXT` | `:210` |
| dm-store | `MAX_MEDIA_FILE_SIZE:500`, `MAX_NON_MEDIA_FILE_SIZE:1024` (the unit is not stated in the yaml and was not verified) | `:211-215`; `MultipartFileSizeValidator.java:14-31` |

dm-store checks the MIME type it **detects** from the bytes, not the declared one (`StoredDocumentController.java:103-121`).
So a file `UploadPolicy` admits on its declared type can still be refused by dm-store, and that refusal comes back
through CDAM as a 4xx that Task 4 has to classify. The environment values in AAT and prod are not in these repos; the
defaults above are what source says.

## 6. cftlib: `DM_STORE_BASE_URL`

`LIB/lib/bootstrapper/src/main/java/uk/gov/hmcts/rse/ccd/lib/LibRunner.java:51-55`, run at startup:

```java
if (!"localAuth".equals(System.getenv("RSE_LIB_AUTH-MODE"))) {
    System.setProperty("IDAM_API_URL", "https://idam-api.aat.platform.hmcts.net");
    System.setProperty("DM_STORE_BASE_URL", "http://dm-store-aat.service.core-compute-aat.internal");
}
```

- In AAT auth mode this is unconditional, and a system property outranks the environment and any earlier `-D`.
- `LIB/rse-cft-lib-plugin/src/main/java/uk/gov/hmcts/rse/CftlibExec.java:33-36` sets `RSE_LIB_AUTH-MODE=localAuth`
  when `authMode == Local`, which is this service's setting. **So LibRunner never sets `DM_STORE_BASE_URL` here.**
- CDAM then reads `documentStoreUrl: ${DM_STORE_BASE_URL:http://localhost:4506}` (`CDAM-res/application.yaml:83`,
  `CDAM/ApplicationParams.java:16-17`).

**Overriding it needs no patch.** Any one of:
1. `environment 'DM_STORE_BASE_URL', 'http://localhost:<port>'` on the `cftlibTest` task. `CftlibExec` is a
   `JavaExec` and only adds environment entries.
2. Run the WireMock stub on 4506, CDAM's default.
3. Set `documentStoreUrl` directly, which works in either auth mode because LibRunner never touches that key.

Every app and the test run in **one JVM** (`LIB/rse-cft-lib-plugin/.../CftLibPlugin.java:223-239`), so environment
variables and system properties are shared. Calling `System.setProperty` from inside a test would race the apps'
startup. Set it on the task, before the JVM starts. No other cftlib app reads either key. Option 1 with a fixed port,
or option 2, is Task 6's choice.

## 7. CDAM's service-to-case-type permissions

`CDAM/model/AuthorisedServices.java:12-22` loads `@PropertySource("classpath:service_config.json")` through
`CDAM/.../AuthServicesJsonPropertySourceFactory.java:16-20` into the `authServices` list. There is no environment
variable or property naming the file.

An entry (`CDAM/model/AuthorisedService.java:19-46`):

```json
{
  "id": "deed_poll_api",
  "caseTypeId": ["DEED_POLL_PLACEHOLDER"],
  "jurisdictionId": "DEED_POLL_PLACEHOLDER",
  "permissions": ["CREATE", "READ", "UPDATE"],
  "caseTypeIdOptionalFor": [],
  "jurisdictionIdOptionalFor": []
}
```

(`defaultJurisdictionForTokenGeneration` and `defaultCaseTypeForTokenGeneration` exist too, and matter only to the
HASHTOKEN endpoint, which this service does not call.) Entries of note today in `CDAM-res/service_config.json`:
`ccd_data` with `*`/`*` and ATTACH, READ (`:51-58`); `xui_webapp` with `*`/`*` and CREATE, READ (`:3-10`); `pcs_api`
with `*`/PCS and CREATE, READ, UPDATE, DELETE (`:364-373`). There is no deed-poll entry. This service needs
**CREATE** (upload), **READ** (download) and **UPDATE** (soft delete); not ATTACH, because data-store attaches.

**cftlib offers no hook.** It does not override the file, and CDAM's classpath is a detached configuration with no
consumer extension point (`LIB/rse-cft-lib-plugin/.../CftLibPlugin.java:334-365`).

**How Task 5 can add the placeholder, in order of preference. The first is inferred from Spring's binding rules,
not verified:**
1. **Environment variables on `cftlibTest`** such as `AUTHSERVICES_0_ID=deed_poll_api`,
   `AUTHSERVICES_0_CASETYPEID_0=DEED_POLL_PLACEHOLDER`. A `@PropertySource` has the lowest precedence, so these
   win. But Spring Boot binds a list from the single highest source that has it, so this **replaces the whole
   list**: it must re-declare `ccd_data` (ATTACH, READ), which data-store's attach depends on, plus any other
   caller the test uses. Being shared by one JVM, it applies to every cftlib app, but only CDAM reads
   `authServices`.
2. A `service_config.json` placed ahead of CDAM's on the classpath. The detached configuration makes this harder
   than it sounds, and it would also replace rather than extend the file.

Upstream, onboarding is a PR to `service_config.json` plus `deed_poll_api` in
`CASE_DOCUMENT_S2S_AUTHORISED_SERVICES`. Both are already listed in plan 03a's Dependencies.

## 8. The client, `ccd-case-document-am-client` 1.59.2

`CLIENT/feign/CaseDocumentClientApi.java`: `@FeignClient(url = "${case_document_am.url}/cases/documents",
configuration = FeignSupportConfig.class)` (`:28-30`). Every method takes `Authorization` then
`ServiceAuthorization` first.

| Line | Method | Call |
|---|---|---|
| `:35-38` | `UploadResponse uploadDocuments(auth, s2s, DocumentUploadRequest)` | `POST`, multipart |
| `:40-43` | `ResponseEntity<Resource> getDocumentBinary(auth, s2s, UUID)` | `GET /{id}/binary` |
| `:45-48` | `Document getMetadataForDocument(auth, s2s, UUID)` | `GET /{id}` |
| `:50-54` | `void deleteDocument(auth, s2s, UUID, @RequestParam("permanent") boolean)` | `DELETE /{id}?permanent=` |
| `:56-60` | `DocumentTTLResponse patchDocument(auth, s2s, UUID, DocumentTTLRequest)` | `PATCH /{id}` |
| `:62-65` | `PatchDocumentMetaDataResponse patchDocument(auth, s2s, CaseDocumentsMetadata)` | `PATCH /attachToCase` (not used) |

`DocumentUploadRequest` (`CLIENT/model/DocumentUploadRequest.java:9-17`) is `(classification, caseTypeId,
jurisdictionId, List<MultipartFile> files)`, encoded by `SpringFormEncoder` (`CLIENT/.../FeignSupportConfig.java:18-21`).
That config also registers an `OkHttpClient` bean (`:30-33`), which Task 4 must check does not collide with anything
this service defines. The wrapper `CaseDocumentClient.java` adds `uploadDocuments(auth, s2s, caseTypeId,
jurisdictionId, files[, classification])` with `RESTRICTED` as the default classification (`:33-51`), and href
overloads of the reads (`:57-81`).

## Not settled from source

- The environment values of CDAM's and dm-store's limits in AAT and prod.
- That replacing `authServices` through environment variables works in cftlib (inferred; Task 5 proves or refutes it).
- The unit of dm-store's `sizelimit` values.
- The published specs in `platops/cnp-api-docs` (`6d804056`) were not read for this document; Task 6 copies
  `document-management-store-app.json` from there for guard 2.
