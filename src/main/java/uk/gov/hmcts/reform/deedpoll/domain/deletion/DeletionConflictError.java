package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;

/**
 * A 409 for a deletion request, carrying which {@link DeletionRefusal} it is, so the HTTP layer can publish the code
 * as well as the words. Still a {@link ConflictError}, so {@code DomainErrorHandler} answers it as one.
 */
public class DeletionConflictError extends ConflictError {

    private static final long serialVersionUID = 1L;

    private final DeletionRefusal refusal;

    public DeletionConflictError(DeletionRefusal refusal) {
        super(refusal.message());
        this.refusal = refusal;
    }

    public DeletionRefusal refusal() {
        return refusal;
    }
}
