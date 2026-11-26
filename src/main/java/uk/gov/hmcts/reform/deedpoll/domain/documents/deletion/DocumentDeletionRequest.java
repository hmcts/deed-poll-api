package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * One row of {@code deedpoll_document_deletion_request} (V51): a member of staff's admitted request to delete one
 * document permanently, and after completion its receipt.
 *
 * <p>Everything up to {@code requestedAt} is fixed at admission, and the table's trigger refuses to change it. The
 * reason and the document name are held only until completion, which copies them into the audit event and the case
 * note and clears them, so a {@link DocumentDeletionStatus#Completed} request has neither. {@link #toString()} prints
 * neither, nor the requester.
 *
 * @param reason the staff member's words, or empty once completed
 * @param documentName the document's name as confirmed, or null once completed
 * @param failure an allowlisted {@link DocumentDeletionFailure} name when {@code NeedsAttention}, else null
 */
public record DocumentDeletionRequest(UUID id, String caseId, String documentId, UUID idempotencyKey,
                                      Optional<DocumentDeletionReason> reason, String documentName,
                                      String fingerprint, long expectedDocumentVersion, String requestedBySubject,
                                      String requestedByName, LocalDateTime requestedAt,
                                      DocumentDeletionStatus status, String failure, int attempts, UUID owner,
                                      LocalDateTime leaseUntil, LocalDateTime nextAttemptAt,
                                      LocalDateTime completedAt) {

    @Override
    public String toString() {
        return "DocumentDeletionRequest[id=" + id + ", caseId=" + caseId + ", documentId=" + documentId
            + ", status=" + status + "]";
    }
}
