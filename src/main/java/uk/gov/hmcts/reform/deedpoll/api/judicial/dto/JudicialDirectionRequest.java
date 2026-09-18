package uk.gov.hmcts.reform.deedpoll.api.judicial.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.DirectionInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSchemas;

/**
 * The body of {@code POST /judicial/referrals/&#123;referralId&#125;/direction} -- the only payload the judicial
 * surface accepts.
 *
 * <p>{@code decision} and {@code publishToGazette} are required, and {@code directionsText} is optional: an
 * approval that needs no comment is a real answer, and a required field whose usual value is empty is a field
 * every caller fills in without reading. The rules are {@link ReferralSchemas#parseDirection}'s rather than
 * annotations here, so the same direction validated for this route is validated for the admin's form post.
 *
 * <p><strong>Four things have nowhere to land, and each absence is a rule.</strong> {@link DirectionInput} has no
 * component for them, so they are stripped as any unknown key is rather than refused with a message:
 *
 * <ul>
 *   <li><strong>The referral and its case.</strong> The referral is the path, and the case is resolved from it --
 *       a judicial URL carries no case id at all, so a judge cannot direct an application they named.</li>
 *   <li><strong>The state.</strong> Recording a direction moves the referral to {@code Decided} and nothing else
 *       does. A body that could name {@code Closed} would let a judge close their own referral, which is the
 *       caseworker's acknowledgement that the direction has been actioned.</li>
 *   <li><strong>{@code decidedBy} and {@code decisionDate}.</strong> Both come from the token and the clock. A
 *       direction that could name its own author would be a signature anybody could forge.</li>
 * </ul>
 *
 * <p>Keyed rather than declared, for the reason {@link KeyedRequestBody} sets out. It buys less here than on a
 * patch -- a direction is recorded once and complete, so there is no field to clear -- but it is what keeps the
 * decision vocabulary and the "a boolean, never a coerced string" rule in the domain, where the SSR form reaches
 * them too.
 *
 * <p>A judicial type in the judicial namespace, sharing nothing with {@code CreateReferralRequest}: they are
 * different payloads written by different people for different purposes, and the shared base class is in
 * {@code api} rather than in either namespace precisely so that reusing the <em>mechanism</em> is not reusing a
 * DTO.
 */
public final class JudicialDirectionRequest extends KeyedRequestBody {

    /**
     * The validated direction this body means.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError with one entry per offending field -- every
     *     failure at once, rather than one round trip per mistake
     */
    public DirectionInput toInput() {
        return ReferralSchemas.parseDirection(fields());
    }
}
