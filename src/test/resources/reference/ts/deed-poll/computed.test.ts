import { describe, expect, it } from "vitest";
import { computedFor, daysBetween, withComputed } from "./computed.js";

/**
 * Derived day-counts, ported from the source calculated fields:
 *   deedpoll_daysreceivedtoenrolled = DateDiff(datereceived, dateenrolled, Days)
 *   deedpoll_daysreceivedtoreturned = DateDiff(datereceived, datereturned, Days)
 */
describe("daysBetween", () => {
  const d = (iso: string) => new Date(iso);

  it("counts whole days between two dates", () => {
    expect(daysBetween(d("2026-07-01T00:00:00Z"), d("2026-07-31T00:00:00Z"))).toBe(30);
  });

  it("returns 0 for the same day", () => {
    expect(daysBetween(d("2026-07-15T00:00:00Z"), d("2026-07-15T00:00:00Z"))).toBe(0);
  });

  it("ignores clock time so a same-day pair is 0, not a rounded 1", () => {
    // These columns are date-only in the source; a stored time must not leak in.
    expect(daysBetween(d("2026-07-15T23:00:00Z"), d("2026-07-15T01:00:00Z"))).toBe(0);
  });

  it("counts across a month boundary", () => {
    expect(daysBetween(d("2026-07-30T00:00:00Z"), d("2026-08-02T00:00:00Z"))).toBe(3);
  });

  it("counts across a leap day", () => {
    expect(daysBetween(d("2028-02-28T00:00:00Z"), d("2028-03-01T00:00:00Z"))).toBe(2);
  });

  it("goes negative when the end precedes the start", () => {
    // Faithful to DateDiff: a data-entry error shows as negative rather than
    // being silently clamped, so it stays visible to the caseworker.
    expect(daysBetween(d("2026-07-31T00:00:00Z"), d("2026-07-01T00:00:00Z"))).toBe(-30);
  });

  it("accepts ISO strings as well as Dates", () => {
    expect(daysBetween("2026-07-01T00:00:00Z", "2026-07-11T00:00:00Z")).toBe(10);
  });

  it.each([
    ["both null", null, null],
    ["start missing", null, new Date("2026-07-31T00:00:00Z")],
    ["end missing", new Date("2026-07-01T00:00:00Z"), null],
    ["start undefined", undefined, new Date("2026-07-31T00:00:00Z")],
    ["unparseable start", "not-a-date", new Date("2026-07-31T00:00:00Z")],
    ["unparseable end", new Date("2026-07-01T00:00:00Z"), "not-a-date"]
  ])("returns null when %s", (_label, from, to) => {
    expect(daysBetween(from as never, to as never)).toBeNull();
  });
});

describe("computedFor", () => {
  it("computes both counts independently", () => {
    expect(
      computedFor({
        dateReceived: new Date("2026-07-01T00:00:00Z"),
        dateEnroled: new Date("2026-07-15T00:00:00Z"),
        dateReturned: new Date("2026-07-20T00:00:00Z")
      })
    ).toEqual({ daysReceivedToEnroled: 14, daysReceivedToReturned: 19 });
  });

  it("nulls the count whose end date is unset", () => {
    expect(
      computedFor({
        dateReceived: new Date("2026-07-01T00:00:00Z"),
        dateEnroled: new Date("2026-07-15T00:00:00Z"),
        dateReturned: null
      })
    ).toEqual({ daysReceivedToEnroled: 14, daysReceivedToReturned: null });
  });

  it("nulls both when the case has no received date", () => {
    expect(
      computedFor({
        dateReceived: null,
        dateEnroled: new Date("2026-07-15T00:00:00Z")
      })
    ).toEqual({ daysReceivedToEnroled: null, daysReceivedToReturned: null });
  });
});

describe("withComputed", () => {
  it("adds the derived fields without mutating or dropping the record", () => {
    const record = {
      id: "abc",
      dateReceived: new Date("2026-07-01T00:00:00Z"),
      dateEnroled: new Date("2026-07-08T00:00:00Z")
    };
    const out = withComputed(record);

    expect(out.id).toBe("abc");
    expect(out.daysReceivedToEnroled).toBe(7);
    expect(out.daysReceivedToReturned).toBeNull();
    // Original untouched — the read path must not sprout fields on the entity.
    expect("daysReceivedToEnroled" in record).toBe(false);
  });
});
