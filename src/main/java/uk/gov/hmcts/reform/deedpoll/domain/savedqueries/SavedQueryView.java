package uk.gov.hmcts.reform.deedpoll.domain.savedqueries;

import uk.gov.hmcts.reform.deedpoll.entity.SavedQuery;

/**
 * One saved query as one caller sees it: the stored query, plus the two facts that are true of the
 * <em>pair</em> rather than of the query.
 *
 * <p>Both flags are per-caller, which is why neither can be a column and why the service returns this rather
 * than the entity. The same shared query is selected by one caseworker and not by another, and editable by its
 * owner and by an administrator and by nobody else.
 *
 * <p><strong>{@code editable} is published rather than left for the client to derive.</strong> Decision 2's
 * rule is "the owner, or an administrator if it is shared", so a client rendering an edit button correctly
 * would have to reimplement it against its own session -- and a client that reimplements a permission rule is
 * a client that drifts from it, showing a button that 403s or hiding one that would have worked. It is
 * computed by the same predicate the write path refuses with, so the flag and the enforcement cannot disagree.
 *
 * <p>{@code selected} is likewise the answer to "is this in my list", which is what a navigation bar renders
 * and what the {@code selection} sub-resource toggles.
 *
 * @param query the stored query, which the API maps to its own DTO -- as with every other domain service
 *     here, an entity does not leave the controller
 * @param selected whether this caller has chosen to see it
 * @param editable whether this caller may amend or delete it
 */
public record SavedQueryView(SavedQuery query, boolean selected, boolean editable) {
}
