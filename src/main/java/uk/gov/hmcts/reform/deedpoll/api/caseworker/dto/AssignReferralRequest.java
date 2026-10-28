package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSchemas;

import java.util.UUID;

/**
 * The body of {@code PUT /caseworker/cases/&#123;id&#125;/referrals/&#123;referralId&#125;/assignment}:
 * {@code masterId}, required, the Masters-list entry to assign the referral to (plan D2). Keyed, as every write body
 * here is, so a missing or malformed id is refused on the field rather than by the deserialiser.
 */
public final class AssignReferralRequest extends KeyedRequestBody {

    /**
     * The entry this body names.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError on {@code masterId}
     */
    public UUID toMasterId() {
        return ReferralSchemas.parseAssignment(fields());
    }
}
