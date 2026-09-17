package uk.gov.hmcts.reform.deedpoll.domain.savedqueries;

import uk.gov.hmcts.reform.deedpoll.domain.PatchField;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSort;

import java.util.List;

/**
 * Update payload for a saved query: the create rules, applied only to the fields that are present.
 *
 * <p><strong>Flat, not nested.</strong> The create payload carries a {@link SavedQueryDefinition} because a
 * created query has a whole view; a patch does not, and a nested definition would have to be all-or-nothing
 * -- renaming a query would then mean resending its conditions, and a client that forgot would silently
 * blank them. Each part of the view patches on its own instead.
 *
 * <p>{@link PatchField} throughout rather than nullable fields, and for all seven rather than only the five
 * whose columns are nullable. {@code filter}, {@code sortField}, {@code sortDirection} and
 * {@code searchTerm} genuinely have three states -- absent, cleared, set -- because clearing a filter is a
 * legitimate edit that must stay distinct from not mentioning it. {@code name}, {@code shared} and
 * {@code conditions} back NOT NULL columns, so {@link SavedQuerySchemas#parseUpdateSavedQuery} refuses a
 * null for those rather than storing one; they use the same type so the record reads in one idiom rather
 * than two, which is the choice {@code UpdateDocumentInput} makes for the same reason.
 *
 * <p><strong>The owner is not expressible here</strong>, so a patch cannot reassign a query to somebody
 * else -- structurally, as {@code UpdateWitnessInput} cannot move a witness between cases.
 *
 * <p>{@code sort} is one field although it is stored as two columns: {@link CaseSort} is validated as a
 * whole, so a patch that changed the direction without the field would have nothing to validate against.
 */
public record UpdateSavedQueryInput(
    PatchField<String> name,
    PatchField<String> description,
    PatchField<Boolean> shared,
    PatchField<String> filter,
    PatchField<List<String>> conditions,
    PatchField<CaseSort> sort,
    PatchField<String> searchTerm) {

    /** An empty patch, which is accepted and changes nothing. */
    public static final UpdateSavedQueryInput EMPTY = new UpdateSavedQueryInput(
        PatchField.absent(),
        PatchField.absent(),
        PatchField.absent(),
        PatchField.absent(),
        PatchField.absent(),
        PatchField.absent(),
        PatchField.absent());

    /** True when the patch names no field at all, so a caller can tell a no-op from a change. */
    public boolean isEmpty() {
        return !name.present()
            && !description.present()
            && !shared.present()
            && !filter.present()
            && !conditions.present()
            && !sort.present()
            && !searchTerm.present();
    }
}
