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
 * Ports every test block in {@code reference/ts/deed-poll/list-filters.test.ts}.
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
    @DisplayName("covers every terminal state with its own filter")
    void coversEveryTerminalStateWithItsOwnFilter() {
        assertThat(ListFilters.LIST_FILTERS).extracting(ListFilters.ListFilter::key)
            .containsExactly("all", "open", "received", "enroled", "returned");
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
