package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

/**
 * One remote step of a document deletion, in the order they run. Stored by {@link #name()} in
 * {@code deedpoll_document_deletion_step.kind}; a request has at most one of each.
 */
public enum DocumentDeletionStepKind {
    /** The document event that removes it from the linked CCD case's current collection; the locator is the case. */
    CCD_REFERENCE,
    /**
     * CDAM's {@code DELETE ?permanent=true}, never after a soft delete; the locator is the CDAM document id.
     */
    CDAM_BINARY,
    /** The filesystem store's removal, verified; the locator is the storage key. */
    LOCAL_FILE
}
