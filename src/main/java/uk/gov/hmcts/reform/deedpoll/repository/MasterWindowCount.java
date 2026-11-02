package uk.gov.hmcts.reform.deedpoll.repository;

import java.util.UUID;

/**
 * One Master's referrals in one referral window, as {@link ReferralRepository#countSentByMasterInWindow} groups
 * them.
 */
public record MasterWindowCount(UUID masterId, long assigned) {
}
