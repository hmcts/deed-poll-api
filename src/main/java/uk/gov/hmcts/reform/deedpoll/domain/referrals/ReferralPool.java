package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;

import java.util.List;

/**
 * One page of the shared judicial pool, and enough about the whole pool to page through it.
 *
 * <p>The same shape and the same reasoning as {@code CaseResults}: a page of rows cannot say whether there is
 * another one, so the total is carried rather than inferred, and it is a domain record rather than Spring
 * Data's {@code Page} so that what a caller is told about a result set is not the shape of the thing that
 * produced it. A separate type from {@code CaseResults} rather than a generic one, because the two answer
 * different questions -- that one carries a {@code referenceMatch}, which a pool has no notion of, and this one
 * is reached only by a judge.
 *
 * <p><strong>The total is what makes the page size a page size rather than a cap</strong> (decision 15). Ten
 * referrals is a screenful of the judicial dashboard, not a limit on what a judge may see: the whole pool is
 * reachable by paging, and the total is how "sixty referrals waiting" stays visible on a page showing ten.
 *
 * @param referrals the referrals on this page, urgent-first then oldest, as the database ordered them
 * @param page which page these are, 1-based
 * @param size the page size that was applied, which is not necessarily the one that was requested -- see
 *     {@link CasePaging#MAX_SIZE}
 * @param total how many referrals are in the pool in total, across every page
 * @param totalPages how many pages of {@code size} that is, and zero when the pool is empty
 */
public record ReferralPool(List<Referral> referrals, int page, int size, long total, int totalPages) {

    public ReferralPool {
        referrals = List.copyOf(referrals);
    }

    /**
     * One page, with its page count derived rather than passed in.
     *
     * <p>Derived for the reason {@code CaseResults.of} derives it: one statement of what a page count is, and
     * no way for a caller to report a total and a page count that disagree.
     */
    public static ReferralPool of(List<Referral> referrals, CasePaging paging, long total) {
        return new ReferralPool(referrals, paging.page(), paging.size(), total, pages(total, paging.size()));
    }

    /** Whether this page has anything on it, which a page past the end of the pool does not. */
    public boolean isEmpty() {
        return referrals.isEmpty();
    }

    /**
     * How many pages of the given size a total needs, rounded up so a partial last page is a page.
     *
     * <p>Long arithmetic, as {@code CaseResults} does it: the intermediate would overflow an int for a total
     * near {@link Integer#MAX_VALUE}, which is a pool this service will never have but not a reason to write it
     * wrongly.
     */
    private static int pages(long total, int size) {
        return (int) ((total + size - 1) / size);
    }
}
