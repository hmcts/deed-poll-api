package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.ccd.CcdAttemptSummary;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One CCD creation attempt, as the admin repair view publishes it (plan 02b, decision 11).
 *
 * <p><strong>Deliberately not the whole row.</strong> The worker, the lease, the operation id, the repairer's IDAM
 * subject and {@code submissionMayHaveReached} are all unpublished, and {@code CcdAttemptDisclosureTest} fails the
 * build if a field of any of those names appears in this package. What an admin needs from the flag is whether the
 * retry is open, so that is what is published.
 *
 * @param id the attempt, which {@code /reconciliation} and {@code /retry} address
 * @param deedPollId the record it is for, which the case detail is read by
 * @param displayReference the record's {@code KBDP…} reference, or null for a record that has none yet
 * @param status where the attempt has got to
 * @param tries how many times it has been claimed since it was opened or last retried from scratch
 * @param failureDetail what last went wrong, in fixed prose plus a status or class name, or null
 * @param modifiedOn when it last moved
 * @param canRetryFromScratch whether {@code POST …/retry} would be accepted now; reconciliation is always open to an
 *     attempt that needs repair
 */
public record CcdAttemptResponse(
    UUID id,
    String deedPollId,
    String displayReference,
    CcdCreationAttemptStatus status,
    int tries,
    String failureDetail,
    LocalDateTime modifiedOn,
    boolean canRetryFromScratch) {

    /** One attempt as the domain resolved it. */
    public static CcdAttemptResponse from(CcdAttemptSummary attempt) {
        return new CcdAttemptResponse(
            attempt.id(),
            attempt.deedPollId(),
            attempt.displayReference(),
            attempt.status(),
            attempt.tries(),
            attempt.failureDetail(),
            attempt.modifiedOn(),
            attempt.canRetryFromScratch());
    }

    /** Each attempt, in the order the domain listed them. */
    public static List<CcdAttemptResponse> from(List<CcdAttemptSummary> attempts) {
        return attempts.stream().map(CcdAttemptResponse::from).toList();
    }
}
