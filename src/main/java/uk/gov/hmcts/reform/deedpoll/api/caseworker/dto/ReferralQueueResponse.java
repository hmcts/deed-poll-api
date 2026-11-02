package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralQueuePage;

import java.time.Duration;
import java.util.List;

/**
 * One page of the referral queue and the windows it is sent in (plan F1).
 *
 * @param referrals the referrals on this page: waiting to be sent, urgent first then oldest raised; or sent, oldest
 *     sent first
 * @param page which page these are, 1-based
 * @param size the page size that was applied, which is not necessarily the one that was requested
 * @param total how many referrals are in the requested state across every page
 * @param totalPages how many pages of {@code size} that is, and zero when there are none
 * @param window the windows, the cap and the open window's counts
 */
public record ReferralQueueResponse(
    List<ReferralQueueItem> referrals,
    int page,
    int size,
    long total,
    int totalPages,
    ReferralQueueWindowResponse window) {

    public static ReferralQueueResponse of(ReferralQueuePage queue, Duration processingWindow) {
        return new ReferralQueueResponse(
            ReferralQueueItem.from(queue.referrals(), processingWindow),
            queue.page(),
            queue.size(),
            queue.total(),
            queue.totalPages(),
            ReferralQueueWindowResponse.from(queue.window()));
    }
}
