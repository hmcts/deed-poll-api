package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.CardBlock;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.CardColumn;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.Columnable;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.Columns;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.ColumnsBlock;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.FullBlock;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/deed-poll/card-layout.test.ts}.
 *
 * <p>The reference's {@code shape()} helper compresses a band to one line so a layout reads as one line
 * per band; {@link #shapes} is the same idea, rendering a band as text.
 */
class CardLayoutTest {

    /** The reference's `named(name, column)` test item. */
    private record Named(String name, CardColumn column) implements Columnable {
    }

    private static Named named(String name) {
        return new Named(name, null);
    }

    private static Named named(String name, CardColumn column) {
        return new Named(name, column);
    }

    private static List<String> names(List<Named> items) {
        return items.stream().map(Named::name).toList();
    }

    /** One band as a comparable string, so a band SEQUENCE is asserted rather than just membership. */
    private static String shape(CardBlock<Named> block) {
        return switch (block) {
            case FullBlock<Named> full -> "full" + names(full.rows());
            case ColumnsBlock<Named> columns -> "left" + names(columns.left()) + " right" + names(columns.right());
        };
    }

    private static List<String> shapes(List<CardBlock<Named>> blocks) {
        return blocks.stream().map(CardLayoutTest::shape).toList();
    }

    /** Every item of a band, whichever kind it is. */
    private static Stream<Named> rowsOf(CardBlock<Named> block) {
        return switch (block) {
            case FullBlock<Named> full -> full.rows().stream();
            case ColumnsBlock<Named> columns -> Stream.concat(columns.left().stream(), columns.right().stream());
        };
    }

    @Nested
    @DisplayName("splitIntoColumns")
    class SplitIntoColumns {

        @Test
        @DisplayName("honours explicit column positions, preserving order")
        void honoursExplicitColumnPositions() {
            Columns<Named> columns = CardLayout.splitIntoColumns(List.of(
                named("referencenumber", CardColumn.LEFT),
                named("privacy", CardColumn.RIGHT),
                named("dateOfApplication", CardColumn.LEFT),
                named("state", CardColumn.RIGHT)));
            assertThat(names(columns.left())).containsExactly("referencenumber", "dateOfApplication");
            assertThat(names(columns.right())).containsExactly("privacy", "state");
        }

        @Test
        @DisplayName("splits an unpositioned group in half, in order")
        void splitsAnUnpositionedGroupInHalf() {
            Columns<Named> columns = CardLayout.splitIntoColumns(
                List.of(named("a"), named("b"), named("c"), named("d")));
            assertThat(names(columns.left())).containsExactly("a", "b");
            assertThat(names(columns.right())).containsExactly("c", "d");
        }

        @Test
        @DisplayName("gives the left column the extra item on an odd count")
        void givesTheLeftColumnTheExtraItem() {
            Columns<Named> columns = CardLayout.splitIntoColumns(List.of(named("a"), named("b"), named("c")));
            assertThat(names(columns.left())).containsExactly("a", "b");
            assertThat(names(columns.right())).containsExactly("c");
        }

        @Test
        @DisplayName("drops an unpositioned field to the left of a positioned group")
        void dropsAnUnpositionedFieldToTheLeft() {
            // A field added without a column must still render. Silently omitting it would make it
            // write-only, the exact class of bug DetailView exists to prevent.
            Columns<Named> columns = CardLayout.splitIntoColumns(
                List.of(named("positioned", CardColumn.RIGHT), named("forgotten")));
            assertThat(names(columns.left())).containsExactly("forgotten");
            assertThat(names(columns.right())).containsExactly("positioned");
        }

        @Test
        @DisplayName("never loses or duplicates an item")
        void neverLosesOrDuplicatesAnItem() {
            List<Named> items = List.of(
                named("a", CardColumn.LEFT),
                named("b", CardColumn.RIGHT),
                named("c"),
                named("d", CardColumn.RIGHT));
            Columns<Named> columns = CardLayout.splitIntoColumns(items);
            assertThat(names(columns.left())).containsAll(List.of("a", "c"));
            assertThat(names(columns.left()).size() + names(columns.right()).size()).isEqualTo(4);
            assertThat(names(columns.left())).doesNotContainAnyElementsOf(names(columns.right()));
        }

        @Test
        @DisplayName("handles an empty group")
        void handlesAnEmptyGroup() {
            assertThat(CardLayout.splitIntoColumns(List.<Named>of()))
                .isEqualTo(new Columns<Named>(List.of(), List.of()));
        }

        @Test
        @DisplayName("handles a single item")
        void handlesASingleItem() {
            Columns<Named> columns = CardLayout.splitIntoColumns(List.of(named("only")));
            assertThat(names(columns.left())).containsExactly("only");
            assertThat(columns.right()).isEmpty();
        }
    }

    @Nested
    @DisplayName("splitIntoBlocks")
    class SplitIntoBlocks {

        @Test
        @DisplayName("gives an all-two-column section one band, as before")
        void givesAnAllTwoColumnSectionOneBand() {
            assertThat(shapes(CardLayout.splitIntoBlocks(List.of(
                named("a", CardColumn.LEFT),
                named("b", CardColumn.RIGHT),
                named("c", CardColumn.LEFT)))))
                .containsExactly("left[a, c] right[b]");
        }

        @Test
        @DisplayName("keeps a full-width field in source order between two-column bands")
        void keepsAFullWidthFieldInSourceOrder() {
            // The Referral section's real shape: two dates, Referral Notes across the full width, then
            // Master Name. Hoisting the full-width field to either end of the card would reorder the form,
            // which is the whole reason a section is a SEQUENCE of bands rather than one left/right split.
            assertThat(shapes(CardLayout.splitIntoBlocks(List.of(
                named("dateReadyForMaster", CardColumn.LEFT),
                named("dateReferred", CardColumn.LEFT),
                named("referralNotes", CardColumn.FULL),
                named("masterName", CardColumn.LEFT)))))
                .containsExactly(
                    "left[dateReadyForMaster, dateReferred] right[]",
                    "full[referralNotes]",
                    "left[masterName] right[]");
        }

        @Test
        @DisplayName("groups consecutive full-width fields into one band")
        void groupsConsecutiveFullWidthFieldsIntoOneBand() {
            assertThat(shapes(CardLayout.splitIntoBlocks(List.of(
                named("a", CardColumn.FULL),
                named("b", CardColumn.FULL)))))
                .containsExactly("full[a, b]");
        }

        @Test
        @DisplayName("keeps two SEPARATED full-width fields in separate bands")
        void keepsTwoSeparatedFullWidthFieldsInSeparateBands() {
            // The Referral shape, reduced: grouping by "is there a full band already?" rather than "is the
            // open band full?" passes every single-full test above and still collapses these two into one
            // band, pulling Master Directions up above Master Name. Only a section with two non-adjacent
            // full-width fields catches it.
            assertThat(shapes(CardLayout.splitIntoBlocks(List.of(
                named("referralNotes", CardColumn.FULL),
                named("masterName", CardColumn.LEFT),
                named("masterDirections", CardColumn.FULL)))))
                .containsExactly("full[referralNotes]", "left[masterName] right[]", "full[masterDirections]");
        }

        @Test
        @DisplayName("opens with a full-width band when the first field is full width")
        void opensWithAFullWidthBand() {
            assertThat(shapes(CardLayout.splitIntoBlocks(List.of(
                named("a", CardColumn.FULL),
                named("b", CardColumn.LEFT)))))
                .containsExactly("full[a]", "left[b] right[]");
        }

        @Test
        @DisplayName("halves an unpositioned run, matching splitIntoColumns")
        void halvesAnUnpositionedRun() {
            // The fallback still applies to a run where nothing is positioned, so a section that predates
            // explicit positions lays out exactly as it did before.
            assertThat(shapes(CardLayout.splitIntoBlocks(
                List.of(named("a"), named("b"), named("c"), named("d")))))
                .containsExactly("left[a, b] right[c, d]");
        }

        @Test
        @DisplayName("never loses or duplicates an item")
        void neverLosesOrDuplicatesAnItem() {
            List<Named> items = List.of(
                named("a", CardColumn.LEFT),
                named("b", CardColumn.FULL),
                named("c", CardColumn.RIGHT),
                named("d"));
            List<String> flat = CardLayout.splitIntoBlocks(items).stream()
                .flatMap(CardLayoutTest::rowsOf)
                .map(Named::name)
                .sorted()
                .toList();
            assertThat(flat).containsExactly("a", "b", "c", "d");
        }

        @Test
        @DisplayName("handles an empty section")
        void handlesAnEmptySection() {
            assertThat(CardLayout.splitIntoBlocks(List.<Named>of())).isEmpty();
        }
    }
}
