package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSend;

import java.time.Duration;
import java.util.List;

/**
 * The answer to a batch send (plan F1): the referrals it named, as queue rows, each now sent, in the order the body
 * named them, and the windows after it. A replay answers the same, because a referral already sent to that Master in
 * this window is reported as sent.
 *
 * @param referrals the referrals the send named
 * @param window the windows, the cap and the open window's counts, after the send
 */
public record ReferralSendResponse(List<ReferralQueueItem> referrals, ReferralQueueWindowResponse window) {

    public static ReferralSendResponse of(ReferralSend send, Duration processingWindow) {
        return new ReferralSendResponse(ReferralQueueItem.from(send.referrals(), processingWindow),
                                        ReferralQueueWindowResponse.from(send.window()));
    }
}
