package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;

/**
 * The body of {@code PUT /caseworker/cases/&#123;id&#125;/referrals/&#123;referralId&#125;/assignment}:
 * {@code masterId}, required, the Masters-list entry to move the referral to (plan F1). Keyed, as every write body
 * here is, so a missing or malformed id is refused on the field rather than by the deserialiser -- and read by
 * {@code ReferralService.assign} only after the capability check, so a caller without it is a 403 whatever it holds.
 */
public final class AssignReferralRequest extends KeyedRequestBody {
}
