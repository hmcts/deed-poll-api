package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ConfirmationInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSchemas;

/**
 * The body of
 * {@code POST /caseworker/cases/&#123;id&#125;/referrals/&#123;referralId&#125;/confirmation}.
 *
 * <p>{@code outcome}, and optionally {@code note}. {@code outcome} is required and has no default, because the two
 * answers mean opposite things -- {@code Confirmed} wants the deed enrolled knowing it will be published,
 * {@code Withdrawn} wants a refund -- so a default would record an answer on the applicant's behalf.
 *
 * <p><strong>{@code confirmationReceivedAt} and {@code confirmationRecordedBy} are stripped rather than
 * refused</strong>, as any unknown key is, and both absences are rules: the first comes from the clock and the
 * second from the token, so a body cannot backdate a reply or sign it as somebody else.
 *
 * <p>There is no field for the case's dates either. Recording an answer does not move the case on -- see
 * {@link ConfirmationInput} -- and a payload that could name {@code dateEnroled} would let this operation reach
 * into the date cascade a caseworker is supposed to work through the ordinary case edit.
 *
 * <p>Keyed rather than declared, for the reason {@link KeyedRequestBody} sets out: the vocabulary and the bound on
 * the note live in the domain, where the SSR form reaches them too.
 */
public final class RecordConfirmationRequest extends KeyedRequestBody {

    /**
     * The validated confirmation this body means.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError with one entry per offending field -- every
     *     failure at once, rather than one round trip per mistake
     */
    public ConfirmationInput toInput() {
        return ReferralSchemas.parseConfirmation(fields());
    }
}
