import { describe, expect, it } from "vitest";
import { buildUpdatePatch } from "./form-payload.js";

describe("buildUpdatePatch", () => {
  it("composes GOV.UK date parts into an ISO string", () => {
    const patch = buildUpdatePatch({
      "dateReceived-day": "15",
      "dateReceived-month": "3",
      "dateReceived-year": "2026"
    });
    expect(patch.dateReceived).toBe("2026-03-15T00:00:00.000Z");
  });

  it("nulls an incomplete or empty date", () => {
    const patch = buildUpdatePatch({ "dateReceived-day": "15" });
    expect(patch.dateReceived).toBeNull();
  });

  it("treats a present checkbox as true and an absent one as false", () => {
    const patch = buildUpdatePatch({ inPerson: "true" });
    expect(patch.inPerson).toBe(true);
    expect(patch.retainPermanently).toBe(false);
  });

  it("parses number fields and nulls blanks", () => {
    const patch = buildUpdatePatch({ ageOfMinor: "7", ageOfMinorMonths: "" });
    expect(patch.ageOfMinor).toBe(7);
    expect(patch.ageOfMinorMonths).toBeNull();
  });

  it("collects multi-checkbox values into an int array", () => {
    const patch = buildUpdatePatch({ iShall: ["1", "4"] });
    expect(patch.iShall).toEqual([1, 4]);
  });

  it("trims text and nulls empty strings", () => {
    const patch = buildUpdatePatch({ newForename: "  Alex  ", newSurname: "" });
    expect(patch.newForename).toBe("Alex");
    expect(patch.newSurname).toBeNull();
  });

  it("omits a non-nullable enum that is absent from the body", () => {
    // Regression: `privacy` renders `disabled` for a non-admin, and browsers do not
    // submit a disabled control. Nulling it wrote null to a NOT NULL column, so a
    // non-admin saving the Application tab got a 500. Absent must mean "leave alone",
    // NOT "clear" — the blank-means-clear rule only holds for nullable columns.
    const patch = buildUpdatePatch({ newForename: "Ada" });
    expect(patch).not.toHaveProperty("privacy");
    expect(patch).not.toHaveProperty("urgency");
  });

  it("still writes a non-nullable enum that IS submitted", () => {
    const patch = buildUpdatePatch({ privacy: "Private" });
    expect(patch.privacy).toBe("Private");
  });

  it("still clears a nullable field submitted blank", () => {
    // The counterpart: the blank-means-clear behaviour must survive for nullable
    // columns, or clearing dateEnroled would stop un-enrolling a case.
    const patch = buildUpdatePatch({ urgencyreason: "" });
    expect(patch.urgencyreason).toBeNull();
  });

  it("ignores read-only/derived fields (state, referencenumber)", () => {
    const patch = buildUpdatePatch({ state: "Enroled", referencenumber: "9" });
    expect(patch).not.toHaveProperty("state");
    expect(patch).not.toHaveProperty("referencenumber");
  });
});
