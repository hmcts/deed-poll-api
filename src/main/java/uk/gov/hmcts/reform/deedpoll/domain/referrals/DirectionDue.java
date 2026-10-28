package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * When a Master's direction is due (plan D2): the processing window's start plus its length, computed at read time
 * from the setting as it is now, so a Delivery Manager's change shows at once. Stated once, because the caseworker
 * and the judicial responses must not disagree about it.
 */
public final class DirectionDue {

    private DirectionDue() {
    }

    /**
     * The due instant while the referral is waiting for a direction, otherwise null. A claim made before assignment
     * existed counts from {@code claimedAt}, as the processing window does (D-10).
     */
    public static LocalDateTime by(Referral referral, Duration processingWindow) {
        if (referral.getState() != ReferralState.Claimed) {
            return null;
        }
        LocalDateTime started = referral.getAssignedAt() != null ? referral.getAssignedAt() : referral.getClaimedAt();
        return started == null ? null : started.plus(processingWindow);
    }
}
