package uk.gov.hmcts.reform.deedpoll.api;

import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRefusal;

/**
 * The 409 body of a refused document deletion request: the fixed words, and the {@link DocumentDeletionRefusal} code
 * a client branches on. Neither echoes the reason, names the document or says which email, deed or upload uses it.
 *
 * @param error the refusal's fixed words, safe to show ({@code DOCUMENT_IN_USE}'s are the ones Admin displays)
 * @param code which refusal it is
 */
public record DocumentDeletionError(String error, DocumentDeletionRefusal code) {

    public static DocumentDeletionError from(DocumentDeletionConflictError refusal) {
        return new DocumentDeletionError(refusal.getMessage(), refusal.refusal());
    }
}
