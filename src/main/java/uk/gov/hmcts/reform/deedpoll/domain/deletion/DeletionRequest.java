package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of {@code deedpoll_deletion_request} (V48): a Delivery Manager's admitted request to delete an application,
 * and after completion its tombstone.
 *
 * <p>Everything up to {@code ccdCaseReference} is fixed at admission, and the table's trigger refuses to change it;
 * the rest is the worker's. It holds no applicant data but the note, and {@link #toString()} leaves the note out.
 *
 * @param caseId the deleted application's id; there is no foreign key, so it outlives the case
 * @param ccdCaseReference the linked CCD case captured at admission, or null for an unlinked case
 * @param failure an allowlisted failure code when {@link DeletionStatus#NeedsAttention}, else null
 */
public record DeletionRequest(UUID id, String caseId, UUID idempotencyKey, DeletionReason reason, DeletionNote note,
                              String fingerprint, LocalDateTime expectedModifiedOn, String requestedBySubject,
                              String requestedByName, LocalDateTime requestedAt, String ccdCaseReference,
                              DeletionStatus status, String failure, int attempts, UUID owner,
                              LocalDateTime leaseUntil, LocalDateTime nextAttemptAt, LocalDateTime completedAt) {

    @Override
    public String toString() {
        return "DeletionRequest[id=" + id + ", caseId=" + caseId + ", reason=" + reason + ", status=" + status + "]";
    }
}
