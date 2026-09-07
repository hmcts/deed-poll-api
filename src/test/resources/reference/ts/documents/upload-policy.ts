/**
 * What the service will accept as an uploaded document, and what it says when it will not.
 *
 * The Multer wiring itself has to live in each app — it is Express middleware, and this library is
 * framework-free. The *policy* does not: a size limit and an accepted-type list are decisions about
 * the service, and holding them in one place means the figure someone confirms with the service team
 * is the figure both the API and the SSR form enforce. Duplicating them would be two copies of a
 * number the handover already flags as a guess, free to drift the moment one is corrected.
 *
 * The failure helpers take a plain `code` string rather than a `MulterError`, for the same reason
 * `userFromHeaders` takes a header-getter: it keeps the domain from importing a transport library.
 */
import { InvalidInputError } from "../errors.js";

/** The multipart field name both the API and the SSR form post the file under. */
export const UPLOAD_FILE_FIELD = "file";

/**
 * 25 MB. Raised from 10 MB on Steve's call, 2026-09-03.
 *
 * These are scanned certificates and multi-page deeds, and a colour scan of a multi-page document at
 * 300dpi runs well past 10 MB — so the original figure was likely to reject legitimate documents, which
 * a caseworker experiences as the service being broken rather than as a limit.
 *
 * **Still not a researched number; confirm against real file sizes with the service team.** The message
 * shown to the user is derived from this constant, so correcting it here corrects everything.
 *
 * **Why 25 and not 50.** The file is buffered in memory, so the real ceiling is this figure multiplied
 * by concurrent uploads, against the pod's memory limit — and **the charts set no memory limits at
 * all**, so that ceiling is whatever the `nodejs` subchart defaults to and is not visible from this
 * repository. 25 MB roughly doubles the headroom for genuine documents while keeping the worst case
 * moderate. **Going higher wants explicit `resources.limits.memory` in the charts first**, and ideally
 * streaming to storage rather than buffering — which is what CDAM brings in Phase 10.
 */
export const MAX_UPLOAD_BYTES = 25 * 1024 * 1024;

/**
 * PDF, the common scan formats, and Word for documents produced by the court.
 *
 * **Hygiene, not security.** This is the `Content-Type` the client puts on the multipart part, which
 * a client can simply lie about. It stops an accidental upload of the wrong thing and keeps obvious
 * executables out; it does not verify that the bytes are what they claim. Real inspection and virus
 * scanning belong with CDAM in Phase 10.
 */
export const ALLOWED_UPLOAD_MIME_TYPES = new Set([
  "application/pdf",
  "image/jpeg",
  "image/png",
  "image/tiff",
  "application/msword",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
]);

export function isAllowedUploadType(mimeType: string): boolean {
  return ALLOWED_UPLOAD_MIME_TYPES.has(mimeType);
}

/** The rejection for a type not on the list, attributed to the file input a form renders. */
export function uploadTypeRejection(mimeType: string): InvalidInputError {
  return new InvalidInputError(`${mimeType} is not an accepted file type`, [{ field: UPLOAD_FILE_FIELD, message: "Upload a PDF, image or Word document" }]);
}

/**
 * The rejection for one of Multer's own limits, by its error code.
 *
 * An `InvalidInputError` rather than the raw error, because Multer's is not a domain error: unwrapped
 * it reaches the apps' error handlers unrecognised and becomes a 500, telling a caseworker the server
 * has broken when in fact their file was too big. That is the trap three Phase 3 defects fell into.
 */
export function uploadLimitRejection(code: string): InvalidInputError {
  const message = messageForLimit(code);
  return new InvalidInputError(message, [{ field: UPLOAD_FILE_FIELD, message }]);
}

function messageForLimit(code: string): string {
  if (code === "LIMIT_FILE_SIZE") {
    return `The file must be smaller than ${Math.round(MAX_UPLOAD_BYTES / (1024 * 1024))}MB`;
  }
  if (code === "LIMIT_FILE_COUNT" || code === "LIMIT_UNEXPECTED_FILE") {
    return "Upload one file at a time";
  }
  return "The file could not be read";
}
