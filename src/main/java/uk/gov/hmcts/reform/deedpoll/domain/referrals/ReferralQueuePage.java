package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;

import java.util.List;

/**
 * One page of the referral queue (plan F1), with its total and the windows.
 *
 * @param referrals the referrals on this page, with their cases loaded, as the database ordered them
 * @param page which page these are, 1-based
 * @param size the page size that was applied
 * @param total how many referrals are in the requested state across every page
 * @param totalPages how many pages of {@code size} that is, and zero when there are none
 * @param window the windows, the cap and the counts, read in the same transaction
 */
public record ReferralQueuePage(List<Referral> referrals, int page, int size, long total, int totalPages,
                                ReferralQueueWindow window) {

    public ReferralQueuePage {
        referrals = List.copyOf(referrals);
    }

    /**
     * One page, with its page count derived as {@link ReferralWorkload#of} derives it.
     */
    static ReferralQueuePage of(List<Referral> referrals, CasePaging paging, long total, ReferralQueueWindow window) {
        return new ReferralQueuePage(referrals, paging.page(), paging.size(), total, paging.pagesOf(total), window);
    }
}
