package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionRequest;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionStatus;

import java.util.UUID;

/**
 * What an admitted (or replayed) deletion request answers with: its id, which the {@code Location} also names, and
 * where it has got to. An accepted request is not a deletion: only {@link DeletionStatus#Completed} is.
 *
 * @param requestId the request, read back at {@code GET /caseworker/application-deletions/{requestId}}
 * @param status where the request has got to; {@code Pending} for a fresh admission
 */
public record DeletionAdmissionResponse(UUID requestId, DeletionStatus status) {

    public static DeletionAdmissionResponse from(DeletionRequest request) {
        return new DeletionAdmissionResponse(request.id(), request.status());
    }
}
