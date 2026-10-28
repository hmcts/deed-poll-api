package uk.gov.hmcts.reform.deedpoll.repository;

import java.util.UUID;

/**
 * One Master's assigned referrals in one referral window, as {@link ReferralRepository#countByMasterInWindow}
 * groups them.
 */
public record MasterWindowCount(UUID masterId, long assigned) {
}
