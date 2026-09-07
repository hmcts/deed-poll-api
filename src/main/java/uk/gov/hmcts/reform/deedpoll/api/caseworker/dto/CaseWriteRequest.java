package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;

/**
 * A case write body: the fields the caller mentioned, as they arrived.
 *
 * <p><strong>A keyed map rather than seventy-odd nullable components, and it has to be.</strong> A case write
 * turns on the three-way distinction between a field the caller omitted, a field the caller explicitly set to
 * null, and a field the caller gave a value -- clearing {@code dateEnroled} un-enrols a case, whereas not
 * mentioning it must leave it alone. This is the same reasoning, at the transport boundary, that made
 * {@link DeedPollPatch} a map rather than a class; see its javadoc, and the reference's own tests, which assert
 * both directions. {@link KeyedRequestBody} carries the mechanism and the rest of the argument, since the notes
 * payload needs the identical thing for the identical reason.
 *
 * <p>What is specific to a case is here: the schema that decides which keys are fields, and therefore what
 * keeps {@code referencenumber}, {@code displayReference} and the audit columns out of a caller's reach.
 */
public abstract class CaseWriteRequest extends KeyedRequestBody {

    /**
     * The validated patch this body means.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError with one entry per offending field, which
     *     {@code DomainErrorHandler} answers as a 400 carrying the attributions
     */
    public abstract DeedPollPatch toPatch();
}
