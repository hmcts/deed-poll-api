package uk.gov.hmcts.reform.deedpoll.ccd;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The repair view: how many attempts are in each status, and one page of those that are not {@code Linked}.
 *
 * <p>The counts are the whole ledger's and ignore the list's status filter, so an admin looking at one status still
 * sees how much else there is. The page count is derived from the paging, as {@code ReferralPool.of} derives it.
 *
 * @param counts     every status, in lifecycle order, with how many attempts are in it
 * @param attempts   this page of the attempts that are not {@code Linked}
 * @param page       which page, 1-based
 * @param size       how many rows a page holds, never more than {@link CasePaging#MAX_SIZE}
 * @param total      how many attempts the list has in all
 * @param totalPages how many pages of this size that is
 */
public record CcdAttemptOverview(Map<CcdCreationAttemptStatus, Long> counts,
                                 List<CcdAttemptSummary> attempts,
                                 int page,
                                 int size,
                                 long total,
                                 int totalPages) {

    public CcdAttemptOverview {
        // Already every status in lifecycle order: CcdCreationAttempts.countsByStatus is the one place that does that.
        counts = Collections.unmodifiableMap(counts);
        attempts = List.copyOf(attempts);
    }

    /** One page, with its page count derived rather than passed in. */
    public static CcdAttemptOverview of(Map<CcdCreationAttemptStatus, Long> counts,
                                        List<CcdAttemptSummary> attempts,
                                        CasePaging paging,
                                        long total) {
        return new CcdAttemptOverview(counts, attempts, paging.page(), paging.size(), total, paging.pagesOf(total));
    }
}
