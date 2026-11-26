package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;

/**
 * The body of {@code POST /caseworker/cases/{id}/documents/{documentId}/deletion-requests}: {@code reason} and
 * {@code expectedDocumentVersion}, and nothing else. Keyed, as every write body here is, and read by {@code
 * DocumentDeletionBodies.command}. No author, actor or time is accepted: those come from the verified token and the
 * server's clock. The reason is never logged.
 */
public final class CreateDocumentDeletionRequest extends KeyedRequestBody {

    /** Keys only: the reason must never reach a log line. */
    @Override
    public String toString() {
        return "CreateDocumentDeletionRequest" + fields().keySet();
    }
}
