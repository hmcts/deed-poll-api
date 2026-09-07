import { describe, expect, it } from "vitest";
import { type CardBlock, type CardColumn, splitIntoBlocks, splitIntoColumns } from "./card-layout.js";

const named = (name: string, column?: CardColumn) => ({ name, column });
const names = (items: { name: string }[]) => items.map((i) => i.name);

/** Compact description of a band, so a layout reads as one line per band. */
const shape = (b: CardBlock<{ name: string }>) => (b.kind === "full" ? { full: names(b.rows) } : { left: names(b.left), right: names(b.right) });
const shapes = (bs: CardBlock<{ name: string }>[]) => bs.map(shape);

describe("splitIntoColumns", () => {
  it("honours explicit column positions, preserving order", () => {
    const { left, right } = splitIntoColumns([named("referencenumber", 1), named("privacy", 2), named("dateOfApplication", 1), named("state", 2)]);
    expect(names(left)).toEqual(["referencenumber", "dateOfApplication"]);
    expect(names(right)).toEqual(["privacy", "state"]);
  });

  it("splits an unpositioned group in half, in order", () => {
    const { left, right } = splitIntoColumns([named("a"), named("b"), named("c"), named("d")]);
    expect(names(left)).toEqual(["a", "b"]);
    expect(names(right)).toEqual(["c", "d"]);
  });

  it("gives the left column the extra item on an odd count", () => {
    const { left, right } = splitIntoColumns([named("a"), named("b"), named("c")]);
    expect(names(left)).toEqual(["a", "b"]);
    expect(names(right)).toEqual(["c"]);
  });

  it("drops an unpositioned field to the left of a positioned group", () => {
    // A field added without a `column` must still render. Silently omitting it would
    // make it write-only, the exact class of bug detail-view.ts exists to prevent.
    const { left, right } = splitIntoColumns([named("positioned", 2), named("forgotten")]);
    expect(names(left)).toEqual(["forgotten"]);
    expect(names(right)).toEqual(["positioned"]);
  });

  it("never loses or duplicates an item", () => {
    const items = [named("a", 1), named("b", 2), named("c"), named("d", 2)];
    const { left, right } = splitIntoColumns(items);
    expect([...names(left), ...names(right)].sort()).toEqual(["a", "b", "c", "d"]);
  });

  it("handles an empty group", () => {
    expect(splitIntoColumns([])).toEqual({ left: [], right: [] });
  });

  it("handles a single item", () => {
    const { left, right } = splitIntoColumns([named("only")]);
    expect(names(left)).toEqual(["only"]);
    expect(right).toEqual([]);
  });
});

describe("splitIntoBlocks", () => {
  it("gives an all-two-column section one band, as before", () => {
    expect(shapes(splitIntoBlocks([named("a", 1), named("b", 2), named("c", 1)]))).toEqual([{ left: ["a", "c"], right: ["b"] }]);
  });

  it("keeps a full-width field in source order between two-column bands", () => {
    // The Referral section's real shape: two dates, Referral Notes across the full
    // width, then Master Name. Hoisting the full-width field to either end of the
    // card would reorder the form, which is the whole reason a section is a SEQUENCE
    // of bands rather than one left/right split.
    expect(shapes(splitIntoBlocks([named("dateReadyForMaster", 1), named("dateReferred", 1), named("referralNotes", "full"), named("masterName", 1)]))).toEqual(
      [{ left: ["dateReadyForMaster", "dateReferred"], right: [] }, { full: ["referralNotes"] }, { left: ["masterName"], right: [] }]
    );
  });

  it("groups consecutive full-width fields into one band", () => {
    expect(shapes(splitIntoBlocks([named("a", "full"), named("b", "full")]))).toEqual([{ full: ["a", "b"] }]);
  });

  it("keeps two SEPARATED full-width fields in separate bands", () => {
    // The Referral shape, reduced: grouping by "is there a full band already?" rather
    // than "is the open band full?" passes every single-full test above and still
    // collapses these two into one band, pulling Master Directions up above Master
    // Name. Only a section with two non-adjacent full-width fields catches it.
    expect(shapes(splitIntoBlocks([named("referralNotes", "full"), named("masterName", 1), named("masterDirections", "full")]))).toEqual([
      { full: ["referralNotes"] },
      { left: ["masterName"], right: [] },
      { full: ["masterDirections"] }
    ]);
  });

  it("opens with a full-width band when the first field is full width", () => {
    expect(shapes(splitIntoBlocks([named("a", "full"), named("b", 1)]))).toEqual([{ full: ["a"] }, { left: ["b"], right: [] }]);
  });

  it("halves an unpositioned run, matching splitIntoColumns", () => {
    // The fallback still applies to a run where nothing is positioned, so a section
    // that predates explicit positions lays out exactly as it did before.
    expect(shapes(splitIntoBlocks([named("a"), named("b"), named("c"), named("d")]))).toEqual([{ left: ["a", "b"], right: ["c", "d"] }]);
  });

  it("never loses or duplicates an item", () => {
    const items = [named("a", 1), named("b", "full"), named("c", 2), named("d")];
    const flat = splitIntoBlocks(items).flatMap((b) => (b.kind === "full" ? b.rows : [...b.left, ...b.right]));
    expect(names(flat).sort()).toEqual(["a", "b", "c", "d"]);
  });

  it("handles an empty section", () => {
    expect(splitIntoBlocks([])).toEqual([]);
  });
});
