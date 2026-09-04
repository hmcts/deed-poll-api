import type { DeedPollNote } from "@hmcts/postgres-prisma";
import { describe, expect, it } from "vitest";
import { NOT_RECORDED } from "../deed-poll/case-header.js";
import { buildNotesView, shortCode } from "./notes-view.js";

/**
 * A stored note, with the fields under test overridable. `id` is a real-shaped uuid
 * because the view model derives the displayed code from it.
 */
function note(over: Partial<DeedPollNote> = {}): DeedPollNote {
  const at = new Date("2026-08-10T09:05:00Z");
  return {
    id: "67ec4d74-2aa4-4c64-8793-1defd5773a87",
    deedPollId: "case-1",
    note: "This is a note",
    createdBy: "Steve Newman",
    modifiedBy: "Steve Newman",
    createdOn: at,
    modifiedOn: at,
    ...over
  } as DeedPollNote;
}

describe("shortCode", () => {
  it("renders a uuid as two uppercase groups of four", () => {
    expect(shortCode("67ec4d74-2aa4-4c64-8793-1defd5773a87")).toBe("67EC-4D74");
  });

  it("takes the leading digits of the id, so the code is recognisable in the URL", () => {
    // The code must be a visible prefix of the same uuid the note's URL carries —
    // that is the whole reason for using hex rather than a denser encoding.
    const id = "40a61c90-b332-40b6-bbdf-4fd08a851f5d";
    const code = shortCode(id);
    expect(id.replace(/-/g, "").toUpperCase().startsWith(code.replace("-", ""))).toBe(true);
  });

  it("is exactly 8 significant characters plus one separator", () => {
    const code = shortCode("7c78958e-6921-42e3-9988-e297ebda6b5b");
    expect(code).toHaveLength(9);
    expect(code.replace("-", "")).toHaveLength(8);
  });

  it("is stable for the same id and differs for different ids", () => {
    const a = "67ec4d74-2aa4-4c64-8793-1defd5773a87";
    const b = "40a61c90-b332-40b6-bbdf-4fd08a851f5d";
    expect(shortCode(a)).toBe(shortCode(a));
    expect(shortCode(a)).not.toBe(shortCode(b));
  });

  it("does not pad a short id into a fake code", () => {
    // Nothing generates a non-uuid id, but the type permits one; it must not be
    // dressed up as a well-formed code.
    expect(shortCode("abc")).toBe("ABC");
  });

  it("collides only when the leading 8 digits match — the documented trade", () => {
    // Two different notes CAN share a code. This is safe only because notes are listed
    // per-case and the code never resolves a note. If either changes, use the full id.
    expect(shortCode("67ec4d74-0000-0000-0000-000000000001")).toBe(shortCode("67ec4d74-ffff-ffff-ffff-ffffffffffff"));
    // Differing inside the first 8 digits is enough to distinguish them.
    expect(shortCode("67ec4d75-0000-0000-0000-000000000001")).not.toBe(shortCode("67ec4d74-0000-0000-0000-000000000001"));
  });
});

describe("buildNotesView", () => {
  it("formats one note into a display row", () => {
    const view = buildNotesView([note()]);

    expect(view.count).toBe(1);
    expect(view.rows[0]).toEqual({
      id: "67ec4d74-2aa4-4c64-8793-1defd5773a87",
      code: "67EC-4D74",
      note: "This is a note",
      createdBy: "Steve Newman",
      createdOn: "10 August 2026 at 10:05am",
      modifiedBy: "Steve Newman",
      modifiedOn: "10 August 2026 at 10:05am",
      unmodified: true
    });
  });

  it("renders timestamps in Europe/London, not UTC", () => {
    // 09:05 UTC in August is 10:05 BST. A note saved at 10:05 must read back as
    // 10:05am — the same rule the case header follows.
    const view = buildNotesView([note({ createdOn: new Date("2026-08-10T09:05:00Z") })]);
    expect(view.rows[0].createdOn).toBe("10 August 2026 at 10:05am");
  });

  it("renders Not recorded for a note predating the audit columns", () => {
    // The columns are nullable, and every note written before the migration has null
    // in both. The grid must not show "null".
    const view = buildNotesView([note({ createdBy: null, modifiedBy: null })]);

    expect(view.rows[0].createdBy).toBe(NOT_RECORDED);
    expect(view.rows[0].modifiedBy).toBe(NOT_RECORDED);
  });

  it("treats a blank author name as not recorded", () => {
    const view = buildNotesView([note({ createdBy: "   " })]);
    expect(view.rows[0].createdBy).toBe(NOT_RECORDED);
  });

  it("orders newest-modified first, whatever order it is given", () => {
    const older = note({
      id: "older",
      modifiedOn: new Date("2026-08-01T09:00:00Z")
    });
    const newer = note({
      id: "newer",
      modifiedOn: new Date("2026-08-09T09:00:00Z")
    });

    // Ascending in, descending out — the detail page's `include` orders notes
    // ascending for the other tabs, so this model cannot rely on its input.
    expect(buildNotesView([older, newer]).rows.map((r) => r.id)).toEqual(["newer", "older"]);
  });

  it("sorts a note with no usable modifiedOn last, and never drops it", () => {
    const dated = note({ id: "dated" });
    const undatedRows = buildNotesView([note({ id: "undated", modifiedOn: null as never }), dated]).rows;

    expect(undatedRows.map((r) => r.id)).toEqual(["dated", "undated"]);
    expect(undatedRows[1].modifiedOn).toBe(NOT_RECORDED);
  });

  it("flags a note edited since creation as modified", () => {
    const view = buildNotesView([
      note({
        createdOn: new Date("2026-08-10T09:05:00Z"),
        modifiedOn: new Date("2026-08-10T11:30:00Z"),
        modifiedBy: "Jordan Blake"
      })
    ]);

    expect(view.rows[0].unmodified).toBe(false);
    expect(view.rows[0].modifiedBy).toBe("Jordan Blake");
    expect(view.rows[0].modifiedOn).toBe("10 August 2026 at 12:30pm");
  });

  it("compares instants, not formatted strings, when flagging modification", () => {
    // Two writes 20 seconds apart format to the same minute. Comparing the rendered
    // strings would call this unmodified; comparing instants does not.
    const view = buildNotesView([
      note({
        createdOn: new Date("2026-08-10T09:05:00Z"),
        modifiedOn: new Date("2026-08-10T09:05:20Z")
      })
    ]);

    expect(view.rows[0].createdOn).toBe(view.rows[0].modifiedOn);
    expect(view.rows[0].unmodified).toBe(false);
  });

  it("returns an empty view for a case with no notes", () => {
    expect(buildNotesView([])).toEqual({ rows: [], count: 0 });
  });

  it("does not mutate the array it is given", () => {
    const input = [note({ id: "a", modifiedOn: new Date("2026-08-01T09:00:00Z") }), note({ id: "b", modifiedOn: new Date("2026-08-09T09:00:00Z") })];
    buildNotesView(input);
    expect(input.map((n) => n.id)).toEqual(["a", "b"]);
  });
});
