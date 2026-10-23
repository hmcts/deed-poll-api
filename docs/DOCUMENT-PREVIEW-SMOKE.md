# Document previews — producer handoff and AAT acceptance

Prepared for [the preview plan](plans/20261023-document-preview-rendering.md). **Not run.** Nothing below has
been provisioned or exercised in a deployed environment: the Docmosis endpoint, its access key and its fonts are
not configured anywhere this chart deploys, and every automated test uses local files, WireMock or the local-only
cftlib stack. The routes, headers, errors and limits are in the README's
[Document previews](../README.md#document-previews) section; this page is what an operator does with them.

Use synthetic cases and synthetic documents only. Record the deployed API commit, the date, each document's
SHA-256, the observed statuses and headers, and the page counts. Keep tokens, cookies, the access key and real
names out of the record.

## What is handed to the admin plan

| | |
|---|---|
| **Producer source** | `feat/document-preview-rendering`, commit `bde3172` (Task 6), on review base `f795067` (Task 5). The contract was first produced at Task 5 and Task 6 changed no published byte. |
| **Artifact** | `OpenAPIPublisherTest`'s document, SHA-256 `86eeee8ad19f8787e29f1ce413ee24d8f62aee412d82c466e0eb2f45c2d7508b`, 103,978 bytes. Identical when regenerated at `f795067` and at `bde3172`. |
| **How** | `JAVA_TOOL_OPTIONS=-XX:UseSVE=0 TESTCONTAINERS_RYUK_DISABLED=true OPENAPI_OUTPUT_PATH=/tmp/preview-task7/openapi-bde3172.json ./gradlew integration --tests '*OpenAPIPublisherTest' --no-watch-fs` in this repository at that commit. Admin then follows its own `libs/deed-poll-api-client/SPEC-PROVENANCE.md` recipe (2-space JSON, then Biome). |
| **Delta** | +2 paths, +2 `GET` operations (`previewCaseDocument`, `previewReferralDocument`), +1 schema (`DocumentPreviewError`). No existing operation changed. Now 41 paths, 57 operations, 61 schemas. |
| **Per operation** | `200` `application/pdf` binary with `Content-Disposition`, `Content-Length`, `Content-Security-Policy`, `X-Content-Type-Options`, `Cache-Control` and `Accept-Ranges`; `403` (the shared `Forbidden`); `404` `ApiError`; `422`/`429`/`503` `DocumentPreviewError` (`error`, `code`); `Retry-After` on `429`; `x-document-preview-limits`. |

`x-document-preview-limits`, as published from the running defaults: `requestBudgetSeconds` 25, `maxInputBytes`
26214400, `maxOutputBytes` 52428800, `maxPages` 500, `maxFramePixels` 25000000,
`maxConcurrentConversionsPerInstance` 3, `busyRetryAfterSeconds` 5, `rangeRequests` `unsupported`. An environment
that overrides one of the `DOCUMENT_PREVIEW_*` variables publishes its own value, so admin should read the
extension rather than hard-code these numbers. Admin's patience for a preview must cover the 25 s budget and stay
under the gateways' 30 s.

The plan's API source/artifact evidence, test counts and format/permission matrix are in its Execution notes,
Tasks 1–6. In short: the five formats render with real parsers on both surfaces, the original's SHA-256 is unchanged
through `/file`, TIFF keeps every frame in order, DOCX takes its page count from the converted PDF, and through real
CDAM (cftlib) a preview reads under the caller's own IDAM id and never the system user's.

## Provisioning needed, none of it done

These belong to other repositories and teams. This repository records them and changes nothing for them.

1. **Converter access.** The Docmosis Tornado owners agree that `deed_poll_api` may call
   `https://docmosis.<env>.platform.hmcts.net/rs/convert` from the `deed-poll` namespace in each environment, and
   issue an access key. em-stitching-api keeps its own key as `docmosis-access-key` in its own vault; whether
   deed poll gets a key of its own or a copy is theirs to decide, and is not assumed here.
2. **The secret.** The key is written as `docmosis-access-key` to `deed-poll-{env}`, by deed-poll-infra or by hand
   (as `notify-api-key` was), in every environment the chart deploys to. **Only then** may the chart's `keyVaults`
   list gain `docmosis-access-key` with alias `DOCMOSIS_ACCESS_KEY`. A chart entry ahead of the secret fails the
   deploy, which is why the chart does not have one yet. Prod's Flux override replaces the whole list and must
   restate it.
3. **The endpoint.** `DOCMOSIS_URL=https://docmosis.{{ .Values.global.environment }}.platform.hmcts.net` (the base:
   this service appends `/rs/convert`), in the chart's `environment` or per environment in cnp-flux-config, in the
   same change as the secret. An `http` or non-base value refuses start-up.
4. **Fonts.** The converter renders with the fonts installed on Tornado, not the applicant's. Confirm with its
   owners which fonts are present (at least Arial, Calibri, Times New Roman and Cambria, the Word defaults most
   uploads use), and what substitution does to the rest. A missing font changes line breaks and page count without
   any error.
5. **Input size and data handling.** Confirm Tornado accepts a 25 MiB DOCX and answers within 20 s (the default
   `DOCMOSIS_READ_TIMEOUT`), and that it keeps neither the document nor the PDF after answering. Applicant
   documents are personal data.

Until 1–3 are done, a DOCX preview answers `503 PREVIEW_UNAVAILABLE` after its caller is authorised and the DOCX
download keeps working. That is the accepted phased release (decision 3): PDF, JPEG, PNG and TIFF previews need
none of this.

## Preconditions

1. Deploy the API at a recorded commit containing the previews, before admin's viewer (producer first).
2. For the DOCX steps only: provisioning 1–3 above, then restart and confirm readiness. Without it, run the
   remaining steps and record DOCX as the expected `503`.
3. Prepare synthetic documents and keep their SHA-256s: a multi-page PDF; a JPEG taken on its side (EXIF
   orientation 6); a PNG; a TIFF with at least three frames of different content, one portrait among landscapes;
   a DOCX of several pages with a table, an image, headers/footers and page breaks, together with a PDF that Word
   itself printed from it (the layout reference); and a DOCX of more than 500 pages or a TIFF frame over 25 million
   pixels for the limit step.
4. Two synthetic caseworkers, two synthetic judges, and one synthetic staff user without CCD read on the case type
   if CDAM is on (see [CDAM-SMOKE.md](CDAM-SMOKE.md)). Read access to `deedpoll_audit_event`.

## Journey

1. **Upload** each document to one synthetic case with `POST /caseworker/cases/{id}/documents`.
2. **Each format, caseworker.** `GET /caseworker/cases/{id}/documents/{documentId}/preview`. Expect `200`,
   `Content-Type: application/pdf`, `Content-Disposition: inline; filename="document-preview.pdf"`,
   `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`,
   `Content-Security-Policy: default-src 'none'; sandbox`, `Accept-Ranges: none`, and a `Content-Length` equal to
   the bytes received. Then open each PDF and check:
   - PDF: the same page count as the original.
   - JPEG: one page, upright, not cropped. PNG: one page, not cropped.
   - TIFF: one page per frame, in frame order, the portrait frame upright on its own page.
   - DOCX: compare with the Word-printed reference, page by page: page count, page breaks, the table, the image,
     headers/footers, and the fonts. **This is the only check of layout fidelity anywhere**; record differences.
   - Download `/file` for each and compare its SHA-256 with the uploaded one.
3. **Audit.** For each preview there is exactly one `deedpoll_audit_event` row with `action = 'DOCUMENT_PREVIEWED'`,
   the document as entity and the caller as actor, and no `DOCUMENT_DOWNLOADED` from the preview (only from step 2's
   `/file`). Paging through the PDF in a browser adds nothing.
4. **Range.** Repeat one preview with `Range: bytes=0-99`. Expect the full `200`, no `Content-Range`, never `206`.
5. **Judicial.** Refer the case. While Open, both judges preview a document through
   `GET /judicial/referrals/{referralId}/documents/{documentId}/preview`. After judge A claims it, judge B gets the
   referral's `404`; A still previews. Close or withdraw it: A gets `404`. A caseworker on the judicial route and a
   judge on the caseworker route get `403`.
6. **Denials.** No token: `401`. Another case's id with this document id: `404`. With CDAM on, the user without CCD
   read gets CDAM's `403`/`404` (an entirely unmapped user `502`), never a `PREVIEW_` code. None of these may record
   `DOCUMENT_PREVIEWED` or produce a converter request; ask the converter owners whether one arrived if in doubt.
7. **Replicas and restart.** Preview the same document through two different pods (or the same pod either side of
   a restart). Both answer the same page count; nothing about a preview survives on a pod, because each request
   renders afresh and deletes its own scratch directory.
8. **Limits.** The over-limit document answers `422 PREVIEW_LIMIT_EXCEEDED` with no PDF, and its `/file` still
   downloads. Four DOCX/TIFF previews started at once on one pod: the fourth is `429 PREVIEW_BUSY` with
   `Retry-After: 5` and no audit row, while a PDF preview still succeeds.
9. **Converter failure.** With the key deliberately wrong (a temporary override in a controlled window), a DOCX
   preview is `503 PREVIEW_UNAVAILABLE` after one converter call, no retry, and the `/file` download still works.
   Remove `DOCMOSIS_URL` and restart: the service starts, DOCX is the same `503`, other formats render. Restore both.
   Check the API's logs for those requests: a status or an exception class only, never the key, the filename,
   the endpoint or the converter's body.

## Results and rollback

Record each step's result against this page and attach the layout comparison. A differing DOCX layout is a finding
for the converter owners (usually fonts), not a reason to change this service's pagination. Any denial step
answering `200`, any extra audit row, or any truncated PDF stops the rollout: keep admin's viewer off and report it.

Previews write nothing, so rollback is redeploying the previous API commit, or leaving `DOCMOSIS_URL` empty to turn
DOCX previews off alone. Original downloads are unaffected either way.
