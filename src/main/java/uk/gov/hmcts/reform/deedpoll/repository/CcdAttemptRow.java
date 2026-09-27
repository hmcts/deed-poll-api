package uk.gov.hmcts.reform.deedpoll.repository;

import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One CCD creation attempt as the repair list reads it: the attempt's own facts and its record's display reference.
 *
 * <p>A projection rather than the entity, for {@link CcdCaseProjection}'s reason one step removed: the entity also
 * carries the worker, the lease, the operation id and the repairer, and none of those is published (plan 02b,
 * decision 11). Selecting only what may be shown means nothing else is loaded to answer the list.
 *
 * <p>{@code submissionMayHaveReached} is here because the domain needs it to answer whether a retry from scratch is
 * allowed, and it stops there: {@code CcdAttemptSummary} carries the answer, not the flag.
 *
 * @param id                       the attempt
 * @param deedPollId               the record it is for
 * @param displayReference         the record's {@code KBDP…} reference, or null for a record that has none yet
 * @param status                   where it has got to
 * @param attempts                 how many claims it has had
 * @param failureDetail            what last went wrong, in fixed prose, or null
 * @param modifiedOn               when it last moved
 * @param submissionMayHaveReached whether {@code submitCaseCreation} may have been called for it
 */
public record CcdAttemptRow(UUID id,
                            String deedPollId,
                            String displayReference,
                            CcdCreationAttemptStatus status,
                            int attempts,
                            String failureDetail,
                            LocalDateTime modifiedOn,
                            boolean submissionMayHaveReached) {
}
