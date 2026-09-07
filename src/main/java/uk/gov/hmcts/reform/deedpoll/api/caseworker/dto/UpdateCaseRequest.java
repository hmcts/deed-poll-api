package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollSchemas;

/**
 * The body of {@code PATCH /caseworker/cases/&#123;id&#125;}.
 *
 * <p>PATCH, not PUT, and the distinction is load-bearing rather than stylistic. The edit forms save one tab at a
 * time, so a body carries the handful of fields that tab owns and says nothing about the other sixty; a PUT
 * would mean the caller is stating the whole record, and the same body would clear everything it omits. What
 * makes that expressible is {@link CaseWriteRequest}'s keyed map -- an omitted field is left alone and an
 * explicit null clears the column, which is exactly the pair the reference's tests assert in both directions.
 *
 * <p>An empty body is accepted and is not a no-op: it re-stamps {@code modifiedBy} and re-derives the state, as
 * the reference's update does. The three fields a patch cannot reach whatever it says are
 * {@code referencenumber}, {@code displayReference} and the audit columns -- {@code DeedPollFields} does not
 * make them writable, so they are stripped along with any other unknown key.
 *
 * <p>Two fields a caller may send are honoured conditionally rather than refused, which is the domain's
 * decision and worth knowing here: {@code privacy} is dropped unless the caller is entitled to set it, and
 * {@code state} is overwritten by the state derived from the merged dates. Both are in
 * {@code DeedPollService.updateDeedPoll}, with the reasoning.
 */
public final class UpdateCaseRequest extends CaseWriteRequest {

    @Override
    public DeedPollPatch toPatch() {
        return DeedPollSchemas.parseUpdate(fields());
    }
}
