package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRequest;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionStatus;

import java.util.UUID;

/**
 * What a document deletion request answers with, admitted or replayed: its id, which a 202's {@code Location} also
 * names, and where it has got to. Only {@link DocumentDeletionStatus#Completed} means the document is gone.
 *
 * @param requestId the request, read back at {@code GET /caseworker/cases/{id}/document-deletions/{requestId}}
 * @param status where the request has got to after this call's work
 */
public record DocumentDeletionAdmissionResponse(UUID requestId, DocumentDeletionStatus status) {

    public static DocumentDeletionAdmissionResponse from(DocumentDeletionRequest request) {
        return new DocumentDeletionAdmissionResponse(request.id(), request.status());
    }
}
