package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionFailure;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionReason;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionRequest;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionStatus;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

/**
 * One deletion request as a Delivery Manager reads it, whether or not its case still exists.
 *
 * <p><strong>Deliberately not the whole row.</strong> No note (the applicant's details must not be in it, and a status
 * poll has no need to repeat it), no requester's subject or name, no idempotency key or fingerprint, no claim or lease,
 * no CCD case reference and no inventory or document locator. {@code ApplicationDeletionHttpTest} pins the field set.
 *
 * @param caseId the application's internal id; the case itself may already be gone
 * @param restorableUntil the end of the grace period fixed at admission; see {@link
 *     DeletionAdmissionResponse#RESTORABLE_UNTIL}
 * @param completedAt when the deletion finished, null until {@link DeletionStatus#Completed}
 * @param failure why cleanup needs attention, only when {@link DeletionStatus#NeedsAttention}, else null
 */
public record DeletionRequestResponse(UUID requestId, String caseId, DeletionReason reason, DeletionStatus status,
                                      LocalDateTime requestedAt,
                                      @Schema(description = DeletionAdmissionResponse.RESTORABLE_UNTIL)
                                      LocalDateTime restorableUntil, LocalDateTime completedAt,
                                      DeletionFailure failure) {

    public static DeletionRequestResponse from(DeletionRequest request) {
        return new DeletionRequestResponse(request.id(), request.caseId(), request.reason(), request.status(),
                                           request.requestedAt(), request.restorableUntil(), request.completedAt(),
                                           failure(request));
    }

    /**
     * The stored code as a published member. Only {@link DeletionFailure} names are ever written; a name a later
     * release removed still reads as needing attention, under the catch-all, rather than failing the read.
     */
    private static DeletionFailure failure(DeletionRequest request) {
        if (request.failure() == null) {
            return null;
        }
        return Arrays.stream(DeletionFailure.values()).filter(code -> code.name().equals(request.failure()))
            .findFirst().orElse(DeletionFailure.CLEANUP_STEP_FAILED);
    }
}
