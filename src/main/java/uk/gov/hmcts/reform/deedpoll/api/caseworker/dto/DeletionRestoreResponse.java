package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService.Restoration;

/**
 * A restored application deletion: the application is visible again, and the request no longer exists, so its status
 * resource is a 404 from now on.
 *
 * @param caseId the application that is visible again
 * @param status always {@code Restored}
 */
public record DeletionRestoreResponse(String caseId,
                                      @Schema(allowableValues = RESTORED, requiredMode = Schema.RequiredMode.REQUIRED)
                                      String status) {

    public static final String RESTORED = "Restored";

    public static DeletionRestoreResponse from(Restoration restoration) {
        return new DeletionRestoreResponse(restoration.caseId(), RESTORED);
    }
}
