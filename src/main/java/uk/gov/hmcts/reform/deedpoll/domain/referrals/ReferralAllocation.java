package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import java.util.Objects;
import java.util.UUID;

/**
 * One entry of a batch send (plan F1): a referral waiting in the queue and the Masters-list entry to send it to.
 */
public record ReferralAllocation(UUID referralId, UUID masterId) {

    public ReferralAllocation {
        Objects.requireNonNull(referralId, "referralId");
        Objects.requireNonNull(masterId, "masterId");
    }
}
