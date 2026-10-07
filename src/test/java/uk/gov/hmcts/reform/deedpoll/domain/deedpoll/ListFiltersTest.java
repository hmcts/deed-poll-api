package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/deed-poll/list-filters.test.ts}, and covers the additional tabs that
 * are not in the reference.
 *
 * <p>{@code awaitingConfirmation} is this service's own: the reference has no confirmation exchange to wait for.
 * It is appended to the ported five rather than inserted among them, which is what the key-order assertion below
 * says -- the list is the render order, so the tab bar a caseworker knows keeps its shape.
 */
class ListFiltersTest {

    @Test
    @DisplayName("puts 'all' first, since it is both the default and the fallback")
    void putsAllFirst() {
        assertThat(ListFilters.LIST_FILTERS.get(0).key()).isEqualTo("all");
    }

    @Test
    @DisplayName("gives 'all' no where clause, so it does not filter")
    void givesAllNoWhereClause() {
        assertThat(ListFilters.LIST_FILTERS.get(0).where()).isEmpty();
    }

    @Test
    @DisplayName("defines 'open' by exclusion, so a state added later shows up as open")
    void definesOpenByExclusion() {
        // The safe direction: a new state appearing in a caseworker's open list is noticeable, whereas one
        // silently missing from it is not.
        Optional<ListFilters.ListFilter> open = ListFilters.LIST_FILTERS.stream()
            .filter(filter -> "open".equals(filter.key()))
            .findFirst();

        assertThat(open).isPresent();
        assertThat(open.get().where())
            .contains(new ListFilters.NotIn(EnumSet.of(DeedPollState.Enroled, DeedPollState.Returned)));
    }

    @Test
    @DisplayName("covers every terminal state with its own filter, and keeps the ported five in front")
    void coversEveryTerminalStateWithItsOwnFilter() {
        // The reference's five, in the reference's order, then this service's own. Appended rather than inserted
        // because the order is the render order: a tab bar a caseworker knows keeps the shape it had, and a
        // bookmarked ?filter= keeps meaning what it meant.
        assertThat(ListFilters.LIST_FILTERS).extracting(ListFilters.ListFilter::key)
            .containsExactly("all", "open", "received", "enroled", "returned", "awaitingConfirmation",
                             "readyForPublication");
    }

    @Nested
    @DisplayName("the awaiting-confirmation tab")
    class AwaitingConfirmationTab {

        @Test
        @DisplayName("is a criterion about the wait and not about the case's state")
        void isACriterionAboutTheWait() {
            // Deliberately not a state: a case waiting for a reply is in whatever state its dates make it,
            // ordinarily Returned, and there is no ninth DeedPollState for the wait. Asserted as the shape rather
            // than described, so a "simplification" into an In or a NotIn fails here.
            assertThat(awaiting().where()).contains(new ListFilters.AwaitingConfirmation());
        }

        @Test
        @DisplayName("asks for the longest wait first, which no other tab does")
        void asksForTheLongestWaitFirst() {
            assertThat(awaiting().defaultSort()).contains(ListFilters.LONGEST_WAIT_FIRST);
            assertThat(ListFilters.LONGEST_WAIT_FIRST.field()).isEqualTo("awaitingConfirmationSince");
            // Ascending on the stamp is descending in days waited: the case at the top is the one at risk of
            // being forgotten, which is the whole reason this tab exists.
            assertThat(ListFilters.LONGEST_WAIT_FIRST.direction()).isEqualTo(CaseSort.Direction.ASCENDING);

            assertThat(ListFilters.LIST_FILTERS)
                .filteredOn(filter -> !List.of("awaitingConfirmation", "readyForPublication").contains(filter.key()))
                .allSatisfy(filter -> assertThat(filter.defaultSort())
                    .as("%s leaves the newest-first default alone", filter.key())
                    .isEmpty());
        }

        @Test
        @DisplayName("is resolvable by key like any other tab, so a URL can ask for it")
        void isResolvableByKey() {
            assertThat(ListFilters.filterFor("awaitingConfirmation").key()).isEqualTo("awaitingConfirmation");
            assertThat(ListFilters.filterTabs()).extracting(ListFilters.FilterTab::key)
                .contains("awaitingConfirmation");
        }

        private ListFilters.ListFilter awaiting() {
            return ListFilters.filterFor("awaitingConfirmation");
        }
    }

    @Test
    void readyForPublicationHasItsOwnCriterionAndOldestEnrolledOrder() {
        ListFilters.ListFilter ready = ListFilters.filterFor("readyForPublication");
        assertThat(ready.where()).contains(new ListFilters.ReadyForPublication());
        assertThat(ready.defaultSort()).contains(new CaseSort("dateEnroled", CaseSort.Direction.ASCENDING));
        assertThat(ListFilters.filterTabs()).contains(
            new ListFilters.FilterTab("readyForPublication", "Ready for publication"));
        assertThat(ListFilters.filterFor(List.of("readyForPublication", "readyForPublication")).key())
            .isEqualTo("all");
    }

    @Nested
    @DisplayName("filterFor")
    class FilterFor {

        @Test
        @DisplayName("resolves a known key")
        void resolvesAKnownKey() {
            assertThat(ListFilters.filterFor("enroled").key()).isEqualTo("enroled");
        }

        @Test
        @DisplayName("falls back to 'all' for an unrecognised key rather than throwing")
        void fallsBackToAllForAnUnrecognisedKey() {
            // The key comes from a URL a caseworker may have edited or bookmarked against an older
            // version. Showing everything is a safe answer; a 400 on the service's front page is not.
            assertThat(ListFilters.filterFor("nonsense").key()).isEqualTo("all");
            // The reference also covers `undefined`, which Java does not distinguish from null.
            assertThat(ListFilters.filterFor(null).key()).isEqualTo("all");
        }

        @Test
        @DisplayName("falls back for a non-string, which a repeated query parameter produces")
        void fallsBackForANonString() {
            assertThat(ListFilters.filterFor(List.of("open", "enroled")).key()).isEqualTo("all");
        }
    }

    @Nested
    @DisplayName("filterTabs")
    class FilterTabs {

        @Test
        @DisplayName("drops the where clause, which is not the template's business")
        void dropsTheWhereClause() {
            // The reference asserts the object's keys are exactly key and label. A record's components are
            // the same statement about its shape, and asserting them means a `where` added to FilterTab
            // fails here rather than leaking a query into a template.
            assertThat(Arrays.stream(ListFilters.FilterTab.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList())
                .containsExactly("key", "label");
            assertThat(ListFilters.filterTabs()).isNotEmpty();
        }

        @Test
        @DisplayName("preserves order, because it is the order the tabs render in")
        void preservesOrder() {
            assertThat(ListFilters.filterTabs()).extracting(ListFilters.FilterTab::key)
                .isEqualTo(ListFilters.LIST_FILTERS.stream().map(ListFilters.ListFilter::key).toList());
        }
    }
}
