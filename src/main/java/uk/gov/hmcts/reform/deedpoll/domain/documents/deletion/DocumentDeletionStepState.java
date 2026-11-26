package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

/**
 * Whether one remote step is confirmed. Stored by {@link #name()} in {@code deedpoll_document_deletion_step.state}.
 */
public enum DocumentDeletionStepState {
    /** Not yet confirmed; the locator is still held. */
    Pending,
    /** Confirmed by its one receipt (docs/DOCUMENT-DELETION.md, "Remote steps and their receipts"). */
    Done,
    /** Refused in a way another attempt will not fix; the outcome code says how, and the locator is still held. */
    Failed
}
