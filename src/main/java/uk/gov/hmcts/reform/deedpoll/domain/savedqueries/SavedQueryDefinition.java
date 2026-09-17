package uk.gov.hmcts.reform.deedpoll.domain.savedqueries;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseQuery;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSort;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListFilters;

import java.util.List;
import java.util.Optional;

/**
 * What a saved query remembers: the parts of a list URL that define a view.
 *
 * <p><strong>Not a port.</strong> Nothing in {@code src/test/resources/reference} describes saved
 * queries -- the Dynamics original had saved system views, and {@link ListFilters} is those five ported --
 * so this is new behaviour and there is no reference implementation to look for.
 *
 * <p><strong>Deliberately no page.</strong> Page 4 of a previous result set is not a page of this one, and a
 * saved query reopening on page 4 of results that have since changed is worse than useless. A live query
 * already drops the page when a filter changes, so this is the same rule stated once more.
 *
 * <p><strong>The conditions stay as the wire strings the URL carried</strong> --
 * {@code ["state:in:Enroled,Received"]} -- and not as structured objects. That is what lets
 * {@link CaseQuery#parse} validate a submitted query with no adapter and no second parser, and it is what
 * makes a saved query round-trip a list URL unchanged: a client duplicating one reads this and posts it back
 * without translating anything. Structured objects would look tidier and would make "the same validator"
 * impossible.
 *
 * <p>The sort is a validated {@link CaseSort} rather than the pair of column values it is stored as, because
 * the two scalars are only decomposed for storage; {@code CaseSort.parse} is still their validator.
 *
 * @param filter the list filter key, absent when the query names none -- an absent filter is not the same
 *     statement as {@code all}, which a caller may have chosen explicitly
 * @param conditions the field-level conditions, in the order they were sent, each a
 *     {@code field:operator:value} wire string; empty when the query asks nothing
 * @param sort the order asked for, absent when the query names none so the list applies its own default
 *     rather than this query pinning today's default forever
 * @param searchTerm the single search term, absent when the query has none
 */
public record SavedQueryDefinition(
    Optional<String> filter,
    List<String> conditions,
    Optional<CaseSort> sort,
    Optional<String> searchTerm) {

    /** A query that narrows nothing: what an unfiltered list asks. */
    public static final SavedQueryDefinition NONE = new SavedQueryDefinition(
        Optional.empty(), List.of(), Optional.empty(), Optional.empty());

    public SavedQueryDefinition {
        conditions = List.copyOf(conditions);
    }

    /**
     * The stored {@code sortField} value, or null where the query names no order.
     */
    public String sortField() {
        return sort.map(CaseSort::field).orElse(null);
    }

    /**
     * The stored {@code sortDirection} value -- the wire token, not the constant's name.
     */
    public String sortDirection() {
        return sort.map(order -> order.direction().wire()).orElse(null);
    }
}
