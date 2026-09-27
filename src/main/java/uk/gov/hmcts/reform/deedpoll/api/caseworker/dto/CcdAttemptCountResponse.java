package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;

/**
 * How many CCD creation attempts are in one status.
 *
 * <p>A row rather than a map keyed by status, so the document types the status as the enum it is and a generated
 * client reads it without a cast.
 *
 * @param status the status
 * @param count how many attempts are in it, zero included
 */
public record CcdAttemptCountResponse(CcdCreationAttemptStatus status, long count) {
}
