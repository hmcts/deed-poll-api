package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

/**
 * Why a preview was not produced: the whole vocabulary, each with its status and its one fixed sentence.
 *
 * <p>The sentence is the only text a refusal carries. It never names the document, its original filename, a parser's
 * diagnostic, the converter's response or its address, so a failure tells the caller what to do and nothing about
 * what was inside the file. Every one of them leaves the original download available, and says so.
 *
 * <p>These are failures of the <em>preview</em> only. A document that is missing, belongs to another case or sits
 * behind a referral the caller cannot see keeps its existing 404, and authentication and authorisation keep their
 * 401 and 403: a source read that was refused is never reported as a preview failure.
 */
public enum PreviewFailure {
    /** The bytes are not a document of the stored type, are damaged, or the stored type has no preview. */
    INVALID_DOCUMENT("PREVIEW_INVALID_DOCUMENT", 422,
        "This document cannot be previewed. Download the original instead."),
    /** A page, pixel, output or time limit was reached; nothing partial is returned. */
    LIMIT_EXCEEDED("PREVIEW_LIMIT_EXCEEDED", 422,
        "This document is too large to preview. Download the original instead."),
    /** The converter is not configured, not reachable, or did not answer with a complete PDF. */
    UNAVAILABLE("PREVIEW_UNAVAILABLE", 503,
        "A preview of this document is not available at the moment. Download the original instead."),
    /** Every conversion permit on this pod is in use; there is no queue. */
    BUSY("PREVIEW_BUSY", 429,
        "Too many previews are being prepared. Try again in a few seconds, or download the original.");

    private final String code;
    private final int status;
    private final String message;

    PreviewFailure(String code, int status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }

    /** The stable machine-readable code the admin adapter switches on. */
    public String code() {
        return code;
    }

    /** The HTTP status the failure is published under. */
    public int status() {
        return status;
    }

    /** The fixed, safe sentence; never extended with anything from the document or the converter. */
    public String message() {
        return message;
    }
}
