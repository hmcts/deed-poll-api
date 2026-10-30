package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;

/**
 * The body of {@code POST /caseworker/referral-queue/sends} (plan F1): {@code allocations}, the referral-to-Master
 * pairs to send together. Keyed, as every write body here is, and read by {@code ReferralQueue.send} only after the
 * capability check, so a caller without it is a 403 whatever they sent.
 */
public final class SendReferralsRequest extends KeyedRequestBody {
}
