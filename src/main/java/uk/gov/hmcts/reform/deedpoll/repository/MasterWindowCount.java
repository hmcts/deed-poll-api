package uk.gov.hmcts.reform.deedpoll.repository;

import java.util.UUID;

/**
 * One Master's referrals in one referral window, as {@link ReferralRepository#countByMasterInWindow} and
 * {@link ReferralRepository#countSentByMasterInWindow} group them.
 */
public record MasterWindowCount(UUID masterId, long assigned) {
}
