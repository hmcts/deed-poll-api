/**
 * Two-column card layout for the read-only detail page.
 *
 * The Dynamics form groups fields into bordered cards with two columns of fields
 * (power_app/screenshots/application_general.png). Reproducing that keeps staff's
 * muscle memory intact, which is the point of the exercise — a caseworker should find
 * Privacy in the top right of the General card whichever app they are in.
 *
 * The card chrome itself is NOT custom: `govuk-summary-card` has been part of the
 * Design System since v4.6 and ships in the govuk-frontend 6.1 build we use. Only the
 * split across two columns is ours, and it is done with the standard grid classes.
 *
 * Rows align within each column, not across the two: the left and right columns are
 * independent summary lists, so a long wrapped value on the left cannot drag its
 * right-hand neighbour out of line.
 *
 * The EDIT form does not use this: it renders one stacked column in
 * `govuk-grid-column-two-thirds`, which already matches the source's field order.
 * `splitIntoBlocks` has one caller, `detail-view.ts`.
 */

/**
 * Where an item sits in the card: the left column, the right column, or spanning
 * both. `'full'` ports the source form's `colspan="2"` cells — the long free-text
 * fields (Return Reason, Referral Notes, Master Directions) are laid out across the
 * whole card in Dynamics, not squeezed into a half.
 */
export type CardColumn = 1 | 2 | "full";

/** Anything carrying an optional card column: a FieldDef or a built detail row. */
export interface Columnable {
  column?: CardColumn;
}

export interface Columns<T> {
  left: T[];
  right: T[];
}

/**
 * One horizontal band of a card: either a pair of columns, or a full-width run.
 *
 * A section is a SEQUENCE of these rather than a single {left, right} pair because
 * full-width fields are interleaved with two-column ones in the source. The Referral
 * section goes two dates, Referral Notes full width, Master Name, Master Directions
 * full width, then four more fields — so hoisting all the full-width fields to the
 * end of the card (or the start) would reorder the form.
 */
export type CardBlock<T> = ({ kind: "columns" } & Columns<T>) | { kind: "full"; rows: T[] };

/**
 * Split items into the card's two columns.
 *
 * An item with an explicit `column` goes there, preserving order. When NO item in the
 * group carries one, the group is split in half in order (left gets the extra item on
 * an odd count) — that is the fallback for the sections we have no screenshot of, and
 * it is deliberately deterministic rather than a guess at the source layout.
 *
 * Mixed groups are treated as explicit: unpositioned items fall to the left column, so
 * adding a field without a `column` degrades to a visible layout rather than vanishing.
 * A `'full'` item does the same here — callers that honour full-width strip those out
 * first (see `splitIntoBlocks`), so reaching this with one means it is being laid out
 * by a caller that has no full-width band to put it in.
 */
export function splitIntoColumns<T extends Columnable>(items: T[]): Columns<T> {
  const anyPositioned = items.some((i) => i.column !== undefined);

  if (!anyPositioned) {
    const half = Math.ceil(items.length / 2);
    return { left: items.slice(0, half), right: items.slice(half) };
  }

  return {
    left: items.filter((i) => i.column !== 2),
    right: items.filter((i) => i.column === 2)
  };
}

/**
 * Lay a section out as an ordered sequence of bands, preserving source order.
 *
 * Runs of non-full items become `columns` bands (split by `splitIntoColumns`, so the
 * halving fallback still applies to a run where nothing is positioned); runs of
 * `'full'` items become `full` bands. Consecutive full-width fields share one band —
 * one summary list rather than several adjacent ones, which renders identically and
 * keeps the DOM honest about them being one group.
 *
 * The halving fallback is applied PER RUN, not per section. That only differs from
 * per-section behaviour in a section that mixes unpositioned and full-width fields,
 * which no section does today: adding a `'full'` to a section is the moment to
 * position the rest of it explicitly, because the source form you took the full-width
 * cell from tells you where everything else sits too.
 */
export function splitIntoBlocks<T extends Columnable>(items: T[]): CardBlock<T>[] {
  const blocks: CardBlock<T>[] = [];

  for (const item of items) {
    const isFull = item.column === "full";
    const open = blocks[blocks.length - 1];

    if (isFull) {
      if (open?.kind === "full") open.rows.push(item);
      else blocks.push({ kind: "full", rows: [item] });
      continue;
    }

    // Buffer the run in a columns band, then re-split it once the run is closed:
    // splitIntoColumns needs to see the whole run to know whether to halve it.
    if (open?.kind === "columns") open.left.push(item);
    else blocks.push({ kind: "columns", left: [item], right: [] });
  }

  return blocks.map((b) => (b.kind === "columns" ? { kind: "columns", ...splitIntoColumns(b.left) } : b));
}
