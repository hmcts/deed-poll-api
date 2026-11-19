package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionRequest;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What an admitted (or replayed) deletion request answers with: its id, which the {@code Location} also names, and
 * where it has got to. An accepted request is not a deletion: only {@link DeletionStatus#Completed} is.
 *
 * @param requestId the request, read back at {@code GET /caseworker/application-deletions/{requestId}}
 * @param status where the request has got to; {@code Pending} for a fresh admission
 * @param restorableUntil the end of the grace period, fixed at admission: nothing is removed before it, and until it
 *     a Delivery Manager may restore the application
 */
public record DeletionAdmissionResponse(UUID requestId, DeletionStatus status,
                                        @Schema(description = RESTORABLE_UNTIL) LocalDateTime restorableUntil) {

    /**
     * The published meaning of {@code restorableUntil}, shared with the status response.
     */
    public static final String RESTORABLE_UNTIL = "UTC. The end of the grace period, fixed when the request was "
        + "admitted: a later change to the deletion grace period setting does not move it. Nothing is removed before "
        + "it, and while the request is Pending before it a Delivery Manager can restore the application.";

    public static DeletionAdmissionResponse from(DeletionRequest request) {
        return new DeletionAdmissionResponse(request.id(), request.status(), request.restorableUntil());
    }
}
