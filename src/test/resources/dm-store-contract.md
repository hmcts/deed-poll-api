# dm-store stub provenance

The stub targets cftlib **0.19.2377**. `dm-store-openapi.json` is copied verbatim from
`hmcts/cnp-api-docs` at **6d8040564754e7f8db60f146370a37ae8d6e1eba**.
`StubDmStoreContractTest` checks the version, methods, paths, headers and multipart fields;
`./gradlew checkDmStoreSpecDrift` compares the copy with the workspace clone.

These are source-derived responses, **not AAT captures**. Sources below are in
`hmcts/document-management-store-app` at **8b01cdd1** (paths under `src/main/java/uk/gov/hmcts/dm/`):

| Response | Source |
|---|---|
| Upload 200, HAL `_embedded.documents` | `controller/StoredDocumentController.java:123-130`, `hateos/StoredDocumentHalResourceCollection.java` |
| Metadata and self/binary links | `hateos/StoredDocumentHalResource.java:31-82` |
| Binary headers and bytes | `controller/StoredDocumentController.java:190-215` |
| TTL patch returns metadata | `controller/StoredDocumentUpdateController.java:81-92` |
| Bulk metadata/TTL patch returns `result: Success` | `controller/StoredDocumentUpdateController.java:114-140` |
| Soft deletion hides metadata and binary | `service/StoredDocumentService.java:67-73,140-149`, `controller/StoredDocumentDeleteController.java:56-80` |

The published spec marks `user-roles` required on metadata/binary reads, and `classification`
required on binary reads. CDAM's `DocumentStoreClient.prepareRequestHeaders` sends neither.
The actual dm-store handlers bind a header **map**, rather than required individual headers
(`StoredDocumentController.java:149-150,190-193`). The stub records these exact three discrepancies
as explicit contract-test exceptions, rather than silently changing the vendored spec. A changed
required-header set fails the test. The stub requires S2S and the binary read's user-id.

The stub models storage, metadata mutation and deleted-document 404s only. It does not claim to
validate dm-store authentication, MIME detection, blob storage, expiry jobs or restoration. Test
uploads are plain text or a synthetic PDF fixture; the stub recognises that fixture's PDF header only.
Real CDAM performs hashing, attachment and CCD-backed user permission checks.
The AAT smoke procedure must replace these source-derived response shapes with sanitised captures.
