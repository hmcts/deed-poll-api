import { describe, expect, it } from "vitest";
import { composeDateParts, splitDateParts, submittedDateParts } from "./date-parts.js";

describe("deed-poll/date-parts", () => {
  describe("composeDateParts", () => {
    it("composes a UTC midnight ISO string", () => {
      expect(composeDateParts("1", "3", "2026")).toBe("2026-03-01T00:00:00.000Z");
    });

    it("uses UTC, so a calendar date does not shift with the server's timezone", () => {
      // These are calendar dates with no time component. Local-time construction would move them
      // a day either side of midnight depending on where the process happens to run.
      expect(composeDateParts("1", "1", "2026")).toBe("2026-01-01T00:00:00.000Z");
      expect(composeDateParts("31", "12", "2026")).toBe("2026-12-31T00:00:00.000Z");
    });

    it("returns null when any part is missing, since a partial date is not a date", () => {
      expect(composeDateParts("", "3", "2026")).toBeNull();
      expect(composeDateParts("1", "", "2026")).toBeNull();
      expect(composeDateParts("1", "3", "")).toBeNull();
    });

    it("returns null for a value that is not a real date", () => {
      expect(composeDateParts("abc", "def", "ghi")).toBeNull();
    });

    it("rolls an impossible day over rather than rejecting it, as Date.UTC does", () => {
      // Documented rather than endorsed: 31 February becomes 3 March. collectDateErrors is what
      // rejects it before this is reached on the form path, which is why this is not a bug here.
      expect(composeDateParts("31", "2", "2026")).toBe("2026-03-03T00:00:00.000Z");
    });
  });

  describe("splitDateParts", () => {
    it("splits a Date into day, month and year as strings", () => {
      expect(splitDateParts(new Date("2026-03-01T00:00:00.000Z"))).toEqual({ day: "1", month: "3", year: "2026" });
    });

    it("splits an ISO string as well as a Date, because Prisma returns one and forms post the other", () => {
      expect(splitDateParts("2026-12-31T00:00:00.000Z")).toEqual({ day: "31", month: "12", year: "2026" });
    });

    it("returns empty parts for an unset value, because most of a case's dates are null for most of its life", () => {
      expect(splitDateParts(null)).toEqual({ day: "", month: "", year: "" });
      expect(splitDateParts(undefined)).toEqual({ day: "", month: "", year: "" });
      expect(splitDateParts("")).toEqual({ day: "", month: "", year: "" });
    });

    it("returns empty parts for an unparseable value rather than throwing", () => {
      expect(splitDateParts("not a date")).toEqual({ day: "", month: "", year: "" });
      expect(splitDateParts(new Date("nonsense"))).toEqual({ day: "", month: "", year: "" });
    });

    it("round-trips through composeDateParts", () => {
      const parts = splitDateParts("2026-07-04T00:00:00.000Z");
      expect(composeDateParts(parts.day, parts.month, parts.year)).toBe("2026-07-04T00:00:00.000Z");
    });

    it("does not share the empty object between calls", () => {
      const first = splitDateParts(null);
      first.day = "mutated";
      expect(splitDateParts(null).day).toBe("");
    });
  });

  describe("submittedDateParts", () => {
    it("reads the parts back verbatim, so a rejected value stays on screen", () => {
      const body = { "dateReceived-day": "99", "dateReceived-month": "13", "dateReceived-year": "abcd" };

      expect(submittedDateParts("dateReceived", body)).toEqual({ day: "99", month: "13", year: "abcd" });
    });

    it("does not normalise or reject, which is the whole point of it", () => {
      // If this composed and re-split, the offending 99 would vanish and the caseworker would face
      // an error against a field that looks fine.
      expect(submittedDateParts("d", { "d-day": "99", "d-month": "1", "d-year": "2026" }).day).toBe("99");
    });

    it("yields empty strings for absent or non-string parts", () => {
      expect(submittedDateParts("dateReceived", {})).toEqual({ day: "", month: "", year: "" });
      expect(submittedDateParts("d", { "d-day": ["1"], "d-month": 3, "d-year": null })).toEqual({ day: "", month: "", year: "" });
    });

    it("reads only the named field's parts", () => {
      const body = { "dateReceived-day": "1", "dateEnroled-day": "2", "dateReceived-month": "3", "dateReceived-year": "2026" };

      expect(submittedDateParts("dateReceived", body)).toEqual({ day: "1", month: "3", year: "2026" });
    });
  });
});
