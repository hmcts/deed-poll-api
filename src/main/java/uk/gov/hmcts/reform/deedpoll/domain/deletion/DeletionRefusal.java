package uk.gov.hmcts.reform.deedpoll.domain.deletion;

/**
 * Why a deletion request was refused with a 409: a fixed code a client can branch on, and fixed words it can show.
 *
 * <p>Every refusal leaves the case exactly as it was, with no request, no inventory and no audit event. None of the
 * words name the applicant, echo the note or say which document, email or attempt is unresolved: a Delivery Manager
 * who needs that opens the case.
 */
public enum DeletionRefusal {
    /** The case changed after the Delivery Manager reviewed it; they review it again. */
    STALE_CONFIRMATION("This application has changed since you reviewed it. Review it again before deleting it."),
    /** The case is marked to be kept permanently, which is never silently overridden. */
    RETAINED_PERMANENTLY("This application is marked to be kept permanently and cannot be deleted."),
    /** A Gazette notice batch or request holds the case as publication evidence. */
    GAZETTE_EVIDENCE("This application is part of Gazette publication audit evidence and cannot be deleted."),
    /** A deed preparation's bytes are still retained as recovery evidence. */
    RETAINED_DEED_PAYLOAD("This application has a deed whose storage is not yet settled. "
        + "Resolve its document storage outcome before deleting the application."),
    /** An owned staged upload still holds its bytes. */
    RETAINED_STAGED_UPLOADS("This application has staged uploads that are not yet settled. "
        + "Remove unpromoted uploads or resolve their storage outcome before deleting the application."),
    /** A supporting document's remote upload or attach is in flight or uncertain. */
    DOCUMENT_STORAGE_UNRESOLVED("A document on this application has an upload that is not yet settled. "
        + "Resolve it before deleting the application."),
    /** The case's CCD creation is in flight, or an unlinked case's may have reached CCD unproven. */
    CCD_CREATION_UNRESOLVED("This application's CCD case creation has not finished or needs repair. "
        + "Resolve it before deleting the application."),
    /** An email about the case has been claimed or started sending and its outcome is not yet recorded. */
    EMAIL_SEND_UNRESOLVED("An email about this application is being sent. Try again once it has settled."),
    /** The same key was sent with a different body. */
    KEY_REUSED("This Idempotency-Key was already used for a different deletion request on this application."),
    /** The application already has a deletion request, for the other reason. */
    CONFLICTING_REASON("This application already has a deletion request for a different reason.");

    private final String message;

    DeletionRefusal(String message) {
        this.message = message;
    }

    /** The fixed words, safe to show and to log. */
    public String message() {
        return message;
    }
}
