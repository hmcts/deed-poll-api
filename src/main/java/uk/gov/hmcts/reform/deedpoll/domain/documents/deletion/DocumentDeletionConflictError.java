package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;

/**
 * A 409 for a document deletion request, carrying which {@link DocumentDeletionRefusal} it is, so the HTTP layer can
 * publish the code as well as the words. Still a {@link ConflictError}, so {@code DomainErrorHandler} answers it as
 * one.
 */
public class DocumentDeletionConflictError extends ConflictError {

    private static final long serialVersionUID = 1L;

    private final DocumentDeletionRefusal refusal;

    public DocumentDeletionConflictError(DocumentDeletionRefusal refusal) {
        super(refusal.message());
        this.refusal = refusal;
    }

    public DocumentDeletionRefusal refusal() {
        return refusal;
    }
}
