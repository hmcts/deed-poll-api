package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.CreateReferralInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSchemas;

/**
 * The body of {@code POST /caseworker/cases/&#123;id&#125;/referrals}.
 *
 * <p>{@code reasons}, and optionally {@code notes}, {@code urgency} and {@code urgencyReason}. Only
 * {@code reasons} is required of its own accord; the other two become required through it --
 * {@code notes} when {@code Other} is among the reasons, {@code urgencyReason} when {@code urgency} is
 * {@code Urgent} -- which is why the rules are in {@link ReferralSchemas} rather than expressed as a required
 * field here.
 *
 * <p><strong>There is no update sibling.</strong> A referral is not edited in place: amending one is
 * withdrawing it and raising another, so this is the only referral body on the surface and the withdrawal
 * sub-resource takes none at all.
 *
 * <p><strong>{@code state}, {@code migrated} and any target judge are stripped rather than refused</strong>, as
 * any unknown key is. Each absence is structural -- {@link CreateReferralInput} has nowhere to put them -- and
 * each is a rule: a created referral is {@code Open}, {@code migrated} belongs to the backfill, and who picks a
 * referral up is a later transition rather than something the caseworker raising it names.
 *
 * <p>Keyed rather than declared, for the reason {@link KeyedRequestBody} sets out. It buys less here than on a
 * patch -- there is no field a referral create can clear -- but it is what keeps the reason vocabulary and the
 * two conditional rules in the domain, where the SSR form reaches them too.
 */
public final class CreateReferralRequest extends KeyedRequestBody {

    /**
     * The validated create input this body means.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError with one entry per offending field --
     *     every failure at once, rather than one round trip per mistake
     */
    public CreateReferralInput toInput() {
        return ReferralSchemas.parseCreateReferral(fields());
    }
}
