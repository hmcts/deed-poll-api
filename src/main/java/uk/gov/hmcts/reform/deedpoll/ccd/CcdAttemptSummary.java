package uk.gov.hmcts.reform.deedpoll.ccd;

import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;
import uk.gov.hmcts.reform.deedpoll.repository.CcdAttemptRow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One CCD creation attempt as an admin repairing it may see it (plan 02b, decision 11).
 *
 * <p><strong>What is not here is the point.</strong> Not the worker or the lease, which are the linking operation's
 * bookkeeping; not the operation id, which is the attempt's identity towards CCD; not the repairer, whose IDAM subject
 * is attribution only; and not {@code submissionMayHaveReached} itself. The flag is replaced by the one question an
 * admin needs answered, {@link #canRetryFromScratch}, which {@link CcdLinkingRule#mayRetryFromScratch} answers -- so
 * the view and the refusal cannot disagree.
 *
 * @param id                  the attempt, which the two repair routes address
 * @param deedPollId          the record it is for
 * @param displayReference    the record's {@code KBDP…} reference, or null for a record that has none yet
 * @param status              where it has got to; never {@code Linked} in the list, which shows only what is not
 * @param tries               how many claims it has had since it was opened or last retried from scratch
 * @param failureDetail       what last went wrong, in fixed prose plus a status or class name, or null
 * @param modifiedOn          when it last moved
 * @param canRetryFromScratch whether "try again from scratch" is open to it now
 */
public record CcdAttemptSummary(UUID id,
                                String deedPollId,
                                String displayReference,
                                CcdCreationAttemptStatus status,
                                int tries,
                                String failureDetail,
                                LocalDateTime modifiedOn,
                                boolean canRetryFromScratch) {

    /** The row as read, with its flag turned into the answer it stands for. */
    public static CcdAttemptSummary of(CcdAttemptRow row) {
        return new CcdAttemptSummary(
            row.id(),
            row.deedPollId(),
            row.displayReference(),
            row.status(),
            row.attempts(),
            row.failureDetail(),
            row.modifiedOn(),
            CcdLinkingRule.mayRetryFromScratch(row.status(), row.submissionMayHaveReached()));
    }

    /** Each row, in order. */
    public static List<CcdAttemptSummary> of(List<CcdAttemptRow> rows) {
        return rows.stream().map(CcdAttemptSummary::of).toList();
    }
}
