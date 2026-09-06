package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollSchemas;

/**
 * The body of {@code POST /caseworker/cases}.
 *
 * <p>Every field is optional, which is the reference's schema and not an omission: a case is logged
 * incrementally, mirroring the Dynamics form where a few fields are mandatory at create and the rest are filled
 * through the lifecycle. Per-type mandatoriness is answered by {@code GET /caseworker/cases/field-rules} and
 * enforced at submit, not here.
 *
 * <p>The one field a create cannot do without is {@code dateReceived}, and it is not required by this schema
 * either -- the reference records that as a known sharp edge, and the refusal comes from reference-number
 * allocation, which needs a year to allocate within. It is an {@code InvalidInputError} attributed to
 * {@code dateReceived}, so the answer is a 400 naming the field rather than a 500; see
 * {@code ReferenceNumbers.yearFromDateReceived} and the test that pins it.
 *
 * <p>See {@link CaseWriteRequest} for why this carries a keyed map instead of declared components.
 */
public final class CreateCaseRequest extends CaseWriteRequest {

    @Override
    public DeedPollPatch toPatch() {
        return DeedPollSchemas.parseCreate(fields());
    }
}
