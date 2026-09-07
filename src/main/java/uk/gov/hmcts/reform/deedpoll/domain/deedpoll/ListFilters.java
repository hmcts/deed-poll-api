package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The saved-query filters above the case list, porting the Dynamics system views.
 *
 * <p>Ported from {@code reference/ts/deed-poll/list-filters.ts}. In the domain rather than on the page
 * because each one is a statement about which states count as "open", which is a business rule the list
 * page merely renders. The prototype had them as a module-level constant in its pages controller, which
 * meant the API could not offer the same filters without restating them.
 *
 * <p>The reference expresses the criterion as a Prisma {@code where} clause. This port names the two
 * shapes it actually uses -- one state, or every state but a set -- as {@link StateWhere}, so the list
 * endpoint can turn one into a query without the filter list having to know how cases are stored.
 */
public final class ListFilters {

    /**
     * Order matters: it is the order the tabs render in, and the first entry is the fallback for an
     * unrecognised key.
     *
     * <p>"Open" is defined by exclusion -- everything not yet finished -- rather than by listing the
     * active states. That way a state added to the enum later shows up as open by default, which is the
     * safe direction: a new state appearing in a caseworker's open list is noticeable, whereas one
     * silently missing from it is not.
     *
     * <p>{@code all} deliberately has no criterion at all: an empty one would be an equivalent but
     * noisier way of saying "return everything".
     */
    public static final List<ListFilter> LIST_FILTERS = List.of(
        new ListFilter("all", "All applications", Optional.empty()),
        new ListFilter("open", "Open", Optional.of(
            new NotIn(EnumSet.of(DeedPollState.Enroled, DeedPollState.Returned)))),
        new ListFilter("received", "Received", Optional.of(new In(EnumSet.of(DeedPollState.Received)))),
        new ListFilter("enroled", "Enroled", Optional.of(new In(EnumSet.of(DeedPollState.Enroled)))),
        new ListFilter("returned", "Returned", Optional.of(new In(EnumSet.of(DeedPollState.Returned)))));

    private ListFilters() {
    }

    /**
     * Which states a filter admits: the reference's {@code Prisma.DeedPollWhereInput} on {@code state}.
     */
    public sealed interface StateWhere permits In, NotIn {
    }

    /** Only these states. */
    public record In(Set<DeedPollState> states) implements StateWhere {
    }

    /** Every state except these. */
    public record NotIn(Set<DeedPollState> states) implements StateWhere {
    }

    /** One filter tab: its key, its label, and the states it admits. */
    public record ListFilter(String key, String label, Optional<StateWhere> where) {
    }

    /** A filter as the tab bar needs it -- no criterion, which is not the template's business. */
    public record FilterTab(String key, String label) {
    }

    /**
     * Resolves a filter key from a query string, falling back to the first filter.
     *
     * <p>Falls back rather than throwing because the key comes from a URL a caseworker may have edited
     * or bookmarked against an older version. A 400 on {@code ?filter=nonsense} would be technically
     * defensible and useless: the list is the service's front page, and showing everything is a safe
     * answer.
     *
     * <p>Takes {@link Object} rather than {@link String} for the same reason the reference takes
     * {@code unknown}: a repeated query parameter arrives as a list, and that must fall back too rather
     * than fail to compile at the one call site that has to handle it.
     */
    public static ListFilter filterFor(Object key) {
        return LIST_FILTERS.stream()
            .filter(filter -> filter.key().equals(key))
            .findFirst()
            .orElse(LIST_FILTERS.get(0));
    }

    /** The filters as the tab bar needs them, in render order. */
    public static List<FilterTab> filterTabs() {
        return LIST_FILTERS.stream()
            .map(filter -> new FilterTab(filter.key(), filter.label()))
            .toList();
    }
}
