import { DeedPollState, DeedPollType } from "@hmcts/postgres-prisma";
import { describe, expect, it } from "vitest";
import { buildCaseHeader, ENTITY_LABEL, formatTimestamp, NOT_RECORDED } from "./case-header.js";

/** Flatten the header rows to key = value for easy assertions. */
const flatten = (header: ReturnType<typeof buildCaseHeader>) => Object.fromEntries(header.rows.map((r) => [r.key, r.value]));

describe("formatTimestamp", () => {
  it("formats a timestamp GOV.UK style with a lowercase meridiem", () => {
    // 09:44 UTC in May is 10:44 British Summer Time.
    expect(formatTimestamp(new Date("2026-05-13T09:44:00Z"))).toBe("13 May 2026 at 10:44am");
  });

  it("renders British Summer Time, not UTC", () => {
    // The regression this guards: a save made at 09:44 local BST is stored as
    // 08:44Z and must read back as 9:44am, not 8:44am.
    expect(formatTimestamp(new Date("2026-05-13T08:44:00Z"))).toBe("13 May 2026 at 9:44am");
  });

  it("renders GMT correctly outside summer time", () => {
    expect(formatTimestamp(new Date("2026-01-13T09:44:00Z"))).toBe("13 January 2026 at 9:44am");
  });

  it("formats an afternoon time as pm", () => {
    expect(formatTimestamp(new Date("2026-01-13T15:05:00Z"))).toBe("13 January 2026 at 3:05pm");
  });

  it("accepts an ISO string as well as a Date", () => {
    expect(formatTimestamp("2026-01-13T15:05:00Z")).toBe("13 January 2026 at 3:05pm");
  });

  it.each([null, undefined, "", "not a date"])("renders %p as the placeholder", (raw) => {
    expect(formatTimestamp(raw)).toBe(NOT_RECORDED);
  });
});

describe("buildCaseHeader", () => {
  const record = {
    type: DeedPollType.Adult,
    state: DeedPollState.Received,
    createdBy: "Bence Luo",
    modifiedBy: "# svc_HMCTS_LCPT_Dev",
    modifiedOn: new Date("2026-05-13T08:44:00Z")
  };

  it("reproduces every field from the Dynamics header", () => {
    expect(flatten(buildCaseHeader(record))).toEqual({
      Type: "Adult",
      State: "Received",
      "Modified By": "# svc_HMCTS_LCPT_Dev",
      "Modified On": "13 May 2026 at 9:44am",
      "Created By": "Bence Luo"
    });
  });

  it("captions the record with the entity name", () => {
    expect(buildCaseHeader(record).entityLabel).toBe(ENTITY_LABEL);
  });

  it("marks only State as a tag", () => {
    const tagged = buildCaseHeader(record)
      .rows.filter((r) => r.tag)
      .map((r) => r.key);
    expect(tagged).toEqual(["State"]);
  });

  it("labels enums rather than leaking member names", () => {
    const header = flatten(
      buildCaseHeader({
        ...record,
        type: DeedPollType.AdultTransgender,
        state: DeedPollState.AwaitingFurtherDocs
      })
    );
    expect(header.Type).toBe("Adult Transgender");
    expect(header.State).toBe("Awaiting Further Docs/Info");
  });

  it("renders a row predating the audit columns as Not recorded", () => {
    // The backward-compatibility case: rows created before createdBy/modifiedBy
    // existed have null in both, and must not render "null" or an empty cell.
    const header = flatten(buildCaseHeader({ ...record, createdBy: null, modifiedBy: null }));
    expect(header["Created By"]).toBe(NOT_RECORDED);
    expect(header["Modified By"]).toBe(NOT_RECORDED);
  });

  it("treats a whitespace-only author as unrecorded", () => {
    const header = flatten(buildCaseHeader({ ...record, createdBy: "   " }));
    expect(header["Created By"]).toBe(NOT_RECORDED);
  });

  it("keeps all five rows even when nothing is populated", () => {
    // The header is a fixed set of facts: rows must not appear and disappear per
    // case the way the tab sections' optional fields do.
    const header = buildCaseHeader({});
    expect(header.rows.map((r) => r.key)).toEqual(["Type", "State", "Modified By", "Modified On", "Created By"]);
    expect(header.rows.every((r) => r.value === NOT_RECORDED)).toBe(true);
  });
});
