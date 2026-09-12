package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.UpdateWitnessInput;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.WitnessSchemas;

/**
 * The body of {@code PATCH /caseworker/cases/&#123;id&#125;/witnesses/&#123;witnessId&#125;}.
 *
 * <p>{@code createWitnessSchema.partial()} in the reference: each of the four fields is optional, and one that
 * is present gets the create rule. An omitted field is left exactly as it was stored, which is what makes this
 * a patch rather than a replace, and an empty body is accepted -- it re-stamps {@code modifiedOn} and changes
 * nothing else.
 *
 * <p><strong>An explicit null is a 400, not a clear</strong>, and that is why this cannot be a record of four
 * nullable components: a record would make {@code &#123;"witnessName": null&#125;} and {@code &#123;&#125;}
 * the same body, so a client would get a success and an unchanged row. None of the four columns is nullable, so
 * there is no value for a null to mean -- see {@link UpdateWitnessInput}, which is {@code Optional} throughout
 * for that reason, and {@link KeyedRequestBody} for the mechanism.
 */
public final class UpdateWitnessRequest extends KeyedRequestBody {

    /**
     * The validated patch this body means.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError with one entry per field that is present
     *     and not acceptable, including a field present as null
     */
    public UpdateWitnessInput toInput() {
        return WitnessSchemas.parseUpdateWitness(fields());
    }
}
