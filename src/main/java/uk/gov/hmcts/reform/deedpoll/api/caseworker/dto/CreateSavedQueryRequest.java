package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.CreateSavedQueryInput;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.SavedQuerySchemas;

/**
 * The body of {@code POST /caseworker/saved-queries}.
 *
 * <p>{@code name}, and optionally {@code description}, {@code shared}, {@code filter}, {@code conditions},
 * {@code sortField}, {@code sortDirection} and {@code searchTerm}. Only {@code name} is required: a query that
 * narrows nothing is legitimate, because "everything, newest first" is a view somebody wants a tab for.
 *
 * <p><strong>{@code conditions} is the array of wire strings a list URL carried</strong> --
 * {@code ["state:in:Enroled,Received"]} -- and not structured objects, which is what lets the live query's own
 * validator check them with no adapter. A client duplicating a query therefore reads one and posts it back
 * without translating anything.
 *
 * <p><strong>{@code ownerId} and {@code page} are stripped rather than refused</strong>, as any unknown key is.
 * The owner comes from the caller's token, so a body cannot claim a query for somebody else; the page is not
 * part of a view. Both absences are structural -- {@link CreateSavedQueryInput} has nowhere to put either.
 *
 * <p>Keyed rather than declared, for the reason {@link KeyedRequestBody} sets out: the bounds and the filter
 * and sort vocabularies belong to {@link SavedQuerySchemas}, so the API and any query-builder UI cannot
 * disagree about what a saved query is.
 */
public final class CreateSavedQueryRequest extends KeyedRequestBody {

    /**
     * The validated create input this body means.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError with one entry per offending field --
     *     including the entries the live query's own validators raise, under their own field names
     */
    public CreateSavedQueryInput toInput() {
        return SavedQuerySchemas.parseCreateSavedQuery(fields());
    }
}
