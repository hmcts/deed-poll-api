package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.SavedQuerySchemas;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.UpdateSavedQueryInput;

/**
 * The body of {@code PATCH /caseworker/saved-queries/&#123;id&#125;}.
 *
 * <p>The create body's fields, each optional, each getting the create rule if it is present. An empty patch is
 * accepted and re-stamps {@code modifiedOn} and {@code modifiedBy}, as an empty case patch does.
 *
 * <p><strong>This is the body that most needs a keyed map.</strong> Four of the columns are nullable --
 * {@code description}, {@code filter}, the order and {@code searchTerm} -- so a caller emptying the search box
 * means "this query has no search term now", and a caller renaming the query means "leave the search term
 * alone". A record of nullable components collapses those two into the same request, so every rename would
 * silently blank the view. See {@link KeyedRequestBody}.
 *
 * <p>{@code name}, {@code shared} and {@code conditions} back NOT NULL columns, so an explicit null for those
 * is a 400 naming the field rather than a database error naming a constraint. An empty {@code conditions} array
 * is how a caller says "ask nothing".
 *
 * <p>{@code ownerId} is stripped as any unknown key is, so a patch cannot reassign a query to somebody else --
 * structurally, since {@link UpdateSavedQueryInput} has nowhere to put one.
 */
public final class UpdateSavedQueryRequest extends KeyedRequestBody {

    /**
     * The validated patch this body means.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError with one entry per present-but-unacceptable
     *     field
     */
    public UpdateSavedQueryInput toInput() {
        return SavedQuerySchemas.parseUpdateSavedQuery(fields());
    }
}
