package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

/**
 * Why a document deletion request was refused with a 409: a fixed code a client can branch on, and fixed words it
 * can show (docs/DOCUMENT-DELETION.md).
 *
 * <p>Every refusal writes nothing: no request, no step, no audit event and no case note, and the document is still
 * usable. None of the words echo the reason, name the document or say which email, deed or upload is using it: a
 * member of staff who needs that opens the case.
 */
public enum DocumentDeletionRefusal {
    /**
     * Queued or unsettled work recorded in the database will read the document's bytes: a pending email naming it, a
     * deed preparation still retaining its payload, or a staged upload being promoted into it. Admin shows these
     * words exactly (plan 20261125, "Active use and stale confirmation").
     */
    DOCUMENT_IN_USE("This document is currently being used and cannot be deleted. "
        + "Try again once the operation has finished."),
    /** The document's version is not the one the confirmation page showed. */
    DOCUMENT_CHANGED("This document has changed since you reviewed it. Review it again before deleting it."),
    /**
     * The document's upload or attach is in flight or uncertain, or its file is held in a store this deployment does
     * not serve, so whose bytes they are is not yet settled.
     */
    DOCUMENT_STORAGE_UNRESOLVED("This document's storage is not yet settled, so it cannot be deleted yet. "
        + "Try again later."),
    /** Another current document record holds the same file, which a permanent deletion would remove from it too. */
    DOCUMENT_SHARED("This document's file is also used by another document record, so it cannot be deleted."),
    /** An application deletion request was admitted for the case. */
    APPLICATION_DELETION_PENDING("This application is being deleted, so its documents cannot be deleted separately."),
    /** The same key was sent with a different body. */
    KEY_REUSED("This Idempotency-Key was already used for a different document deletion request on this "
        + "application.");

    private final String message;

    DocumentDeletionRefusal(String message) {
        this.message = message;
    }

    /** The fixed words, safe to show and to log. */
    public String message() {
        return message;
    }
}
