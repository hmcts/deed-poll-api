package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.CreateWitnessInput;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.WitnessSchemas;

/**
 * The body of {@code POST /caseworker/cases/&#123;id&#125;/witnesses}.
 *
 * <p>Four fields -- {@code witnessName}, {@code witnessAddress}, {@code witnessOccupation},
 * {@code declarant} -- and all four are required, because all four source columns are {@code NOT NULL}. A
 * witness is entered from a signed declaration that already carries every value, so there is no
 * incremental-capture case to accommodate the way the deed poll itself has.
 *
 * <p>The parent case is the path segment, so a body cannot post a witness onto another case:
 * {@link CreateWitnessInput} has nowhere to put a {@code deedPollId}, and the id reaches the domain as an
 * argument instead. There is no author field either, and that is not an omission -- {@code deedpoll_witness}
 * has the two timestamps and no {@code createdBy} / {@code modifiedBy}.
 *
 * <p>Keyed rather than declared, as every write body here is: the length caps and the "{@code declarant} is a
 * boolean, not a truthy string" rule belong to {@link WitnessSchemas}, so the API and any form cannot disagree
 * about what a witness is, and a caller sending {@code createdOn} is stripped rather than refused -- the
 * reference's "{@code z.object()} strips unknown keys". See {@link KeyedRequestBody}.
 */
public final class CreateWitnessRequest extends KeyedRequestBody {

    /**
     * The validated create input this body means.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError with one entry per field that is missing,
     *     mistyped or over its cap -- every offending field, not only the first, so a form can render them all
     */
    public CreateWitnessInput toInput() {
        return WitnessSchemas.parseCreateWitness(fields());
    }
}
