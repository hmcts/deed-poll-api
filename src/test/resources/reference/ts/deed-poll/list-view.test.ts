import { describe, expect, it } from "vitest";
import { formatValue, NOT_PROVIDED } from "./detail-view.js";
import { FIELD_BY_NAME } from "./form-fields.js";
import { buildCaseList, CASE_LIST_COLUMNS } from "./list-view.js";

const record = {
  id: "case-1",
  displayReference: "KBDP2026-00001",
  privacy: "Normal" as const,
  dateReceived: new Date("2026-03-01T00:00:00.000Z"),
  type: "Adult" as const,
  state: "Received" as const,
  newForename: "Ada"
};

describe("deed-poll/list-view", () => {
  it("formats a date as GOV.UK style, not ISO", () => {
    expect(buildCaseList([record])[0].dateReceived).toBe("1 March 2026");
  });

  it("formats dates in UTC, so a case does not appear received a day early", () => {
    const row = buildCaseList([{ ...record, dateReceived: new Date("2026-01-01T00:00:00.000Z") }])[0];

    expect(row.dateReceived).toBe("1 January 2026");
  });

  it("labels enums with their human text rather than the enum member", () => {
    const row = buildCaseList([{ ...record, type: "DeedOfRelinquishment", state: "AwaitingFurtherDocs" }])[0];

    expect(row.type).toBe("Deed of Relinquishment");
    expect(row.state).toBe("Awaiting Further Docs/Info");
  });

  it("uses the same labels the detail page uses, by going through the shared field metadata", () => {
    // A second formatter here would be a second opinion about what a Type is called. Asserted
    // against formatValue directly so the two cannot diverge without this failing.
    const row = buildCaseList([record])[0];

    expect(row.type).toBe(formatValue(FIELD_BY_NAME.type, record.type));
    expect(row.state).toBe(formatValue(FIELD_BY_NAME.state, record.state));
    expect(row.privacy).toBe(formatValue(FIELD_BY_NAME.privacy, record.privacy));
  });

  it("keeps the reference verbatim rather than reformatting a display string", () => {
    expect(buildCaseList([record])[0].reference).toBe("KBDP2026-00001");
  });

  it("shows the placeholder for a case with no reference, which the schema permits", () => {
    // createDeedPoll always allocates one, but displayReference is nullable — a seeded or imported
    // row need not have it, and a reference-less case showing as such is the honest rendering.
    const row = buildCaseList([{ ...record, displayReference: null }])[0];

    expect(row.reference).toBe(NOT_PROVIDED);
    expect(row.id).toBe("case-1");
  });

  it("flags a private case separately from its label, so colour is not the only signal", () => {
    const priv = buildCaseList([{ ...record, privacy: "Private" }])[0];
    const norm = buildCaseList([record])[0];

    expect(priv.isPrivate).toBe(true);
    expect(priv.privacy.length).toBeGreaterThan(0);
    expect(norm.isPrivate).toBe(false);
  });

  it("shows the placeholder for an unset date rather than an empty cell", () => {
    expect(buildCaseList([{ ...record, dateReceived: null }])[0].dateReceived).toBe(NOT_PROVIDED);
  });

  it("shows the placeholder for an unset forename", () => {
    expect(buildCaseList([{ ...record, newForename: null }])[0].newForename).toBe(NOT_PROVIDED);
  });

  it("carries the id so the reference can link to the case", () => {
    expect(buildCaseList([record])[0].id).toBe("case-1");
  });

  it("returns an empty array for no cases, so the page renders its own empty state", () => {
    expect(buildCaseList([])).toEqual([]);
  });

  it("preserves the order it was given, since the domain decides the sort", () => {
    const rows = buildCaseList([record, { ...record, id: "case-2", displayReference: "KBDP2026-00002" }]);

    expect(rows.map((r) => r.id)).toEqual(["case-1", "case-2"]);
  });

  it("has a heading for every cell in a row", () => {
    // Guards the pairing the module comment claims: headings and cells are defined together, so a
    // column added to one must be added to the other.
    const row = buildCaseList([record])[0];
    const cells = Object.keys(row).filter((k) => k !== "id" && k !== "isPrivate");

    expect(cells).toHaveLength(CASE_LIST_COLUMNS.length);
  });

  it("resolves every column name it formats against the shared field metadata", () => {
    // If a name were absent, format() would fall back to String() and quietly lose the label or
    // date formatting for that column.
    for (const name of ["privacy", "dateReceived", "type", "state", "newForename"]) {
      expect(FIELD_BY_NAME[name], `${name} is not on any form tab`).toBeDefined();
    }
  });
});
