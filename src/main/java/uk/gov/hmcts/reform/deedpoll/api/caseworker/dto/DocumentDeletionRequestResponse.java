package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionFailure;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRequest;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionStatus;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

/**
 * One document deletion request as a member of staff reads it, whether or not its document still exists.
 *
 * <p><strong>Deliberately not the whole row.</strong> No reason (the staff member's words are in the audit event and
 * the case note, and a status poll has no need to repeat them), no document name, no requester's subject or name, no
 * idempotency key or fingerprint, no claim or lease, and no step or locator (a CDAM id, a file key or a CCD
 * reference). {@code CaseworkerDocumentDeletionControllerTest} pins the field set.
 *
 * @param documentId the document; it may already be gone
 * @param completedAt when the deletion finished, null until {@link DocumentDeletionStatus#Completed}
 * @param failure why the request needs somebody, only when {@link DocumentDeletionStatus#NeedsAttention}, else null
 */
public record DocumentDeletionRequestResponse(UUID requestId, String documentId, DocumentDeletionStatus status,
                                              LocalDateTime requestedAt, LocalDateTime completedAt,
                                              DocumentDeletionFailure failure) {

    public static DocumentDeletionRequestResponse from(DocumentDeletionRequest request) {
        return new DocumentDeletionRequestResponse(request.id(), request.documentId(), request.status(),
                                                   request.requestedAt(), request.completedAt(), failure(request));
    }

    /**
     * The stored code as a published member. Only {@link DocumentDeletionFailure} names are ever written; a name a
     * later release removed reads as null under a still-published {@code NeedsAttention}, rather than failing the read.
     */
    private static DocumentDeletionFailure failure(DocumentDeletionRequest request) {
        if (request.failure() == null) {
            return null;
        }
        return Arrays.stream(DocumentDeletionFailure.values()).filter(code -> code.name().equals(request.failure()))
            .findFirst().orElse(null);
    }
}
