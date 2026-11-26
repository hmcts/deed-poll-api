package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

/**
 * Where a document deletion request has got to. Stored by {@link #name()} in
 * {@code deedpoll_document_deletion_request.status}.
 *
 * <p>Only {@link #Completed} means the document is gone. The other two are a request still being carried out and
 * one that needs somebody, and no response may call either deleted.
 */
public enum DocumentDeletionStatus {
    /** Admitted and fenced; a remote step is still to be confirmed. The recovery sweep resumes it. */
    InProgress,
    /** A step cannot be finished without somebody; the request's failure code says why. */
    NeedsAttention,
    /**
     * Every remote step is confirmed, the local row is gone, and {@code DOCUMENT_DELETED} and the case note are
     * recorded. Terminal: the request is a receipt, and its reason and document name are cleared.
     */
    Completed
}
