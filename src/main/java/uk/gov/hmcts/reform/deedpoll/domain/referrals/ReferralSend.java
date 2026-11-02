package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.entity.Referral;

import java.util.List;

/**
 * What a batch send leaves (plan F1): every referral the send named, in the order it named them, each now sent to
 * its Master in the open window -- including any a replay found already sent there -- and the windows as they stand
 * after it.
 *
 * @param referrals the referrals, with their cases loaded
 * @param window the windows, the cap and the open window's counts, read after the send in its transaction
 */
public record ReferralSend(List<Referral> referrals, ReferralQueueWindow window) {

    public ReferralSend {
        referrals = List.copyOf(referrals);
    }
}
