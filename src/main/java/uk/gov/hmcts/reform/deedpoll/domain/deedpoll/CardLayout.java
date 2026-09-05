package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import java.util.ArrayList;
import java.util.List;

/**
 * Two-column card layout for the read-only detail page.
 *
 * <p>Ported from {@code reference/ts/deed-poll/card-layout.ts}. The Dynamics form groups fields into
 * bordered cards with two columns of fields
 * ({@code power_app/screenshots/application_general.png}). Reproducing that keeps staff's muscle
 * memory intact, which is the point of the exercise -- a caseworker should find Privacy in the top
 * right of the General card whichever app they are in.
 *
 * <p>Rows align within each column, not across the two: the left and right columns are independent
 * summary lists, so a long wrapped value on the left cannot drag its right-hand neighbour out of line.
 *
 * <p>The EDIT form does not use this: it renders one stacked column, which already matches the
 * source's field order. {@link #splitIntoBlocks} has one caller, {@code DetailView}.
 */
public final class CardLayout {

    private CardLayout() {
    }

    /**
     * Where an item sits in the card: the left column, the right column, or spanning both.
     *
     * <p>{@code FULL} ports the source form's {@code colspan="2"} cells -- the long free-text fields
     * (Return Reason, Referral Notes, Master Directions) are laid out across the whole card in
     * Dynamics, not squeezed into a half.
     *
     * <p>The reference types this as {@code 1 | 2 | "full"}, a union Java cannot express; an enum is
     * the equivalent, and it removes the reference's need to guard against a third integer.
     */
    public enum CardColumn {
        LEFT,
        RIGHT,
        FULL
    }

    /** Anything carrying an optional card column: a field definition, or a built detail row. */
    public interface Columnable {

        /**
         * The column this item was positioned in, or {@code null} if it was never positioned.
         */
        CardColumn column();
    }

    /** The two columns of one band. */
    public record Columns<T>(List<T> left, List<T> right) {
    }

    /**
     * One horizontal band of a card: either a pair of columns, or a full-width run.
     *
     * <p>A section is a SEQUENCE of these rather than a single left/right pair because full-width
     * fields are interleaved with two-column ones in the source. The Referral section goes two dates,
     * Referral Notes full width, Master Name, Master Directions full width, then four more fields -- so
     * hoisting all the full-width fields to the end of the card (or the start) would reorder the form.
     */
    public sealed interface CardBlock<T> permits ColumnsBlock, FullBlock {
    }

    /** A band split across the card's two columns. */
    public record ColumnsBlock<T>(List<T> left, List<T> right) implements CardBlock<T> {
    }

    /** A band spanning the whole card width. */
    public record FullBlock<T>(List<T> rows) implements CardBlock<T> {
    }

    /**
     * Splits items into the card's two columns.
     *
     * <p>An item with an explicit column goes there, preserving order. When NO item in the group
     * carries one, the group is split in half in order (left gets the extra item on an odd count) --
     * that is the fallback for the sections we have no screenshot of, and it is deliberately
     * deterministic rather than a guess at the source layout.
     *
     * <p>Mixed groups are treated as explicit: unpositioned items fall to the left column, so adding a
     * field without a column degrades to a visible layout rather than vanishing. A {@code FULL} item
     * does the same here -- callers that honour full width strip those out first (see
     * {@link #splitIntoBlocks}), so reaching this with one means it is being laid out by a caller that
     * has no full-width band to put it in.
     */
    public static <T extends Columnable> Columns<T> splitIntoColumns(List<T> items) {
        boolean anyPositioned = items.stream().anyMatch(item -> item.column() != null);

        if (!anyPositioned) {
            int half = (items.size() + 1) / 2;
            return new Columns<>(List.copyOf(items.subList(0, half)), List.copyOf(items.subList(half, items.size())));
        }

        return new Columns<>(
            items.stream().filter(item -> item.column() != CardColumn.RIGHT).toList(),
            items.stream().filter(item -> item.column() == CardColumn.RIGHT).toList());
    }

    /**
     * Lays a section out as an ordered sequence of bands, preserving source order.
     *
     * <p>Runs of non-full items become {@link ColumnsBlock} bands (split by
     * {@link #splitIntoColumns}, so the halving fallback still applies to a run where nothing is
     * positioned); runs of {@code FULL} items become {@link FullBlock} bands. Consecutive full-width
     * fields share one band -- one summary list rather than several adjacent ones, which renders
     * identically and keeps the DOM honest about them being one group.
     *
     * <p>The halving fallback is applied PER RUN, not per section. That only differs from per-section
     * behaviour in a section that mixes unpositioned and full-width fields, which no section does
     * today: adding a full-width field to a section is the moment to position the rest of it
     * explicitly, because the source form you took the full-width cell from tells you where everything
     * else sits too.
     */
    public static <T extends Columnable> List<CardBlock<T>> splitIntoBlocks(List<T> items) {
        // Runs are accumulated before any band is built, because splitIntoColumns has to see a whole
        // run to know whether to halve it -- the reference buffers into a mutable band and re-splits
        // afterwards for the same reason.
        List<Run<T>> runs = new ArrayList<>();
        for (T item : items) {
            boolean isFull = item.column() == CardColumn.FULL;
            Run<T> open = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (open == null || open.full != isFull) {
                open = new Run<>(isFull);
                runs.add(open);
            }
            open.items.add(item);
        }

        List<CardBlock<T>> blocks = new ArrayList<>();
        for (Run<T> run : runs) {
            if (run.full) {
                blocks.add(new FullBlock<>(List.copyOf(run.items)));
            } else {
                Columns<T> columns = splitIntoColumns(run.items);
                blocks.add(new ColumnsBlock<>(columns.left(), columns.right()));
            }
        }
        return List.copyOf(blocks);
    }

    /** A consecutive run of items that are all full-width, or all not. */
    private static final class Run<T> {

        private final boolean full;
        private final List<T> items = new ArrayList<>();

        private Run(boolean full) {
            this.full = full;
        }
    }
}
