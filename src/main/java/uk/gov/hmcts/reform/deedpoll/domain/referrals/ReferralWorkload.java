package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;

import java.util.List;

/**
 * One page of the referrals a single judge has in hand: the ones they claimed and have not yet directed, and the
 * directions the office has not yet actioned.
 *
 * <p><strong>A separate type from {@link ReferralPool} rather than a reuse of it, because the two name opposite
 * collections.</strong> The pool is the shared, unclaimed work every judge sees the same view of; this is one
 * judge's own work and nobody else's, and its membership is decided by {@code claimedById}/{@code decidedById}
 * matching the caller's IDAM subject. A single type carrying both would put "the pool" in the name of a response
 * that is not the pool, and a reader would have to know which call produced it to know what it meant. The rows are
 * the same entity and the envelope is the same shape; what differs is what the page is a page <em>of</em>.
 *
 * <p><strong>The total is what makes this a page size and not a cap</strong>, the same as the pool's: a judge with
 * more outstanding work than fits on a screenful is told how much, and reaches the rest by paging.
 *
 * <p>Not permanent judicial history. A referral leaves this list when the office closes the direction or withdraws
 * the referral, and a claim that lapses leaves it and returns to the shared pool -- so what this answers is "what
 * am I in the middle of", which is the question the business testing that asked for it was actually asking.
 *
 * @param referrals the referrals on this page, claimed work first and urgent-first within that, then by the
 *     day sent, as the database ordered them -- see {@code ReferralRepository.findOwnedBy}
 * @param page which page these are, 1-based
 * @param size the page size that was applied, which is not necessarily the one that was requested -- see
 *     {@link CasePaging#MAX_SIZE}
 * @param total how many referrals this judge has in hand in total, across every page
 * @param totalPages how many pages of {@code size} that is, and zero when they have none
 */
public record ReferralWorkload(List<Referral> referrals, int page, int size, long total, int totalPages) {

    public ReferralWorkload {
        referrals = List.copyOf(referrals);
    }

    /**
     * One page, with its page count derived rather than passed in.
     *
     * <p>Derived for the reason {@link ReferralPool#of} derives it: one statement of what a page count is, and no
     * way for a caller to report a total and a page count that disagree.
     */
    public static ReferralWorkload of(List<Referral> referrals, CasePaging paging, long total) {
        return new ReferralWorkload(referrals, paging.page(), paging.size(), total, paging.pagesOf(total));
    }

    /** Whether this page has anything on it, which a judge holding nothing does not. */
    public boolean isEmpty() {
        return referrals.isEmpty();
    }
}
