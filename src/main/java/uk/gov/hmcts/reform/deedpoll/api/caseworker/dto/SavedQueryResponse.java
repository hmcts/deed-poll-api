package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.SavedQueryView;
import uk.gov.hmcts.reform.deedpoll.entity.SavedQuery;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One saved query, as the caseworker surface publishes it.
 *
 * <p>Every column of {@code deedpoll_savedquery}, plus the two per-caller flags that are not columns at all --
 * see {@link SavedQueryView} for why they cannot be.
 *
 * <p><strong>The view fields are published in the shape they were stored in, which is the shape a list URL
 * carries.</strong> {@code conditions} is the array of {@code field:operator:value} strings, and the order is
 * the two decomposed columns rather than {@code CaseSort}'s wire form. That is what makes duplicating a query a
 * {@code GET} then a {@code POST} of the same values, with no field renamed in between and no client-side
 * translation to keep in step.
 *
 * <p>{@code ownerId} is published as well as {@code ownerName}. It is an IDAM subject rather than anything
 * personal, and a client showing "yours" against a shared library needs to compare it with its own session --
 * whereas comparing display names would make two caseworkers called J Smith the same owner.
 *
 * @param id the query id, which the patch, the delete and the selection sub-resource all address
 * @param name what it is called, trimmed and bounded on the way in
 * @param description the longer explanation, or null
 * @param shared whether it is published to the library, which makes it findable and not selected
 * @param ownerId the owner's IDAM subject, stable across a change of name
 * @param ownerName the owner's display name as it read when the query was saved
 * @param filter the list filter key, or null where the query names none
 * @param conditions the field conditions as wire strings, in the order they were saved; empty for a query that
 *     asks nothing
 * @param sortField the field the query orders by, or null to let the list apply its own default
 * @param sortDirection {@code asc} or {@code desc}, null exactly when {@code sortField} is
 * @param searchTerm the single search term, or null
 * @param selected whether the caller who asked has this query in their own list
 * @param editable whether the caller who asked may amend or delete it -- the owner, or an Administrator if it
 *     is shared
 * @param createdBy the name recorded when the query was saved
 * @param modifiedBy the name recorded for the last amendment, which is not necessarily the owner's
 * @param createdOn when it was saved
 * @param modifiedOn when it was last amended -- equal to createdOn for a query never edited
 */
public record SavedQueryResponse(
    String id,
    String name,
    String description,
    boolean shared,
    String ownerId,
    String ownerName,
    String filter,
    List<String> conditions,
    String sortField,
    String sortDirection,
    String searchTerm,
    boolean selected,
    boolean editable,
    String createdBy,
    String modifiedBy,
    LocalDateTime createdOn,
    LocalDateTime modifiedOn) {

    /** One stored query, as the caller who asked for it sees it. */
    public static SavedQueryResponse from(SavedQueryView view) {
        SavedQuery query = view.query();
        return new SavedQueryResponse(
            query.getId().toString(),
            query.getName(),
            query.getDescription(),
            query.isShared(),
            query.getOwnerId(),
            query.getOwnerName(),
            query.getFilter(),
            List.copyOf(query.getConditions()),
            query.getSortField(),
            query.getSortDirection(),
            query.getSearchTerm(),
            view.selected(),
            view.editable(),
            query.getCreatedBy(),
            query.getModifiedBy(),
            query.getCreatedOn(),
            query.getModifiedOn());
    }

    /** A caller's visible library, preserving the order the domain fetched it in. */
    public static List<SavedQueryResponse> from(List<SavedQueryView> views) {
        return views.stream().map(SavedQueryResponse::from).toList();
    }
}
