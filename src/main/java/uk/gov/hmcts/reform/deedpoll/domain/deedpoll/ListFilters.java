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
 * <p>The reference expresses the criterion as a Prisma {@code where} clause. This port names the shapes it
 * actually uses -- one state, every state but a set, or the outstanding-confirmation clause -- as
 * {@link FilterWhere}, so the list endpoint can turn one into a query without the filter list having to know
 * how cases are stored.
 */
public final class ListFilters {

    /**
     * The waiting list's own order: the case asked longest ago, first.
     *
     * <p>Ascending on the stamp, which is descending in days waited -- the direction a caseworker needs, because
     * the case at risk of being forgotten is the one at the top. Declared before {@link #LIST_FILTERS} because
     * that initialiser reads it, and a static field declared after it would still be null at that point.
     */
    public static final CaseSort LONGEST_WAIT_FIRST =
        new CaseSort("awaitingConfirmationSince", CaseSort.Direction.ASCENDING);

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
     *
     * <p><strong>{@code awaitingConfirmation} is this service's own tab and is appended rather than
     * inserted</strong>, which is why the reference's five keys are still the first five: the order is the
     * render order, so a caseworker's tab bar keeps the shape it had. It is the one tab whose criterion is not
     * about {@code state} at all -- a case waiting for an applicant's reply is in whatever state its dates make
     * it, ordinarily Returned -- and the one that carries its own order, because a list of waiting cases in
     * creation order would bury the longest wait. See {@code V17__case_awaiting_confirmation.sql}.
     */
    public static final List<ListFilter> LIST_FILTERS = List.of(
        new ListFilter("all", "All applications", Optional.empty()),
        new ListFilter("open", "Open", Optional.of(
            new NotIn(EnumSet.of(DeedPollState.Enroled, DeedPollState.Returned)))),
        new ListFilter("received", "Received", Optional.of(new In(EnumSet.of(DeedPollState.Received)))),
        new ListFilter("enroled", "Enroled", Optional.of(new In(EnumSet.of(DeedPollState.Enroled)))),
        new ListFilter("returned", "Returned", Optional.of(new In(EnumSet.of(DeedPollState.Returned)))),
        new ListFilter("awaitingConfirmation", "Awaiting confirmation",
            Optional.of(new AwaitingConfirmation()), Optional.of(LONGEST_WAIT_FIRST)));

    private ListFilters() {
    }

    /**
     * What a filter admits: the reference's {@code Prisma.DeedPollWhereInput}, in the shapes this service uses.
     */
    public sealed interface FilterWhere permits In, NotIn, AwaitingConfirmation {
    }

    /** Only these states. */
    public record In(Set<DeedPollState> states) implements FilterWhere {
    }

    /** Every state except these. */
    public record NotIn(Set<DeedPollState> states) implements FilterWhere {
    }

    /**
     * Cases still waiting for an applicant's confirmation -- {@code awaitingConfirmationSince} is not null.
     *
     * <p>No components: the criterion is the column being populated, and the column is a read-model of the
     * referral table recomputed by the two writes that can change it ({@code ConfirmationWait}). A date bound
     * would be a different filter -- "waiting more than n days" -- which nothing asks for: decision 3 of this
     * plan is that the list makes a forgotten case visible, and it is the ordering that does that.
     */
    public record AwaitingConfirmation() implements FilterWhere {
    }

    /**
     * One filter tab: its key, its label, the criterion it admits, and the order it is asked for by default.
     *
     * <p>The default order is the tab's own rather than the list endpoint's, because it is a property of the
     * question the tab asks: "which case has waited longest" is the whole of the waiting list, where every other
     * tab is a set of cases whose useful default is the newest. A caller that names {@code sort} still wins --
     * see {@link CaseSort#parse(String, CaseSort)} -- so this is a default and not a fixed order.
     *
     * @param defaultSort the order for a request that asked for none, or empty for the newest-first default
     */
    public record ListFilter(String key,
                             String label,
                             Optional<FilterWhere> where,
                             Optional<CaseSort> defaultSort) {

        /** A tab with no order of its own, which is every ported one. */
        public ListFilter(String key, String label, Optional<FilterWhere> where) {
            this(key, label, where, Optional.empty());
        }
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
