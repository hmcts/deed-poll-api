import { describe, expect, it } from "vitest";
import type { FieldDef } from "./form-fields.js";
import { collectDateErrors } from "./form-validation.js";

const DATES: FieldDef[] = [
  { name: "signatureRequestedDate", label: "Signature requested date", kind: "date" },
  { name: "signatureReceivedDate", label: "Signature received date", kind: "date" }
];

const parts = (name: string, day: string, month: string, year: string) => ({
  [`${name}-day`]: day,
  [`${name}-month`]: month,
  [`${name}-year`]: year
});

describe("collectDateErrors", () => {
  it("accepts a complete real date", () => {
    const errors = collectDateErrors(parts("signatureRequestedDate", "3", "8", "2026"), DATES);
    expect(errors).toEqual([]);
  });

  it("accepts all three parts blank as a deliberate clear", () => {
    // The clear path must stay open: blanking a signature date is how a caseworker
    // walks a case back, and it is what un-derives the state.
    const errors = collectDateErrors(parts("signatureRequestedDate", "", "", ""), DATES);
    expect(errors).toEqual([]);
  });

  it("rejects a partial date and names the missing parts", () => {
    // Regression: a day with no month or year was silently stored as null, so the
    // page read back "not entered" and the caseworker had no idea the save dropped it.
    const errors = collectDateErrors(parts("signatureRequestedDate", "3", "", ""), DATES);
    expect(errors).toEqual([
      {
        field: "signatureRequestedDate",
        message: "Signature requested date must include a month and year"
      }
    ]);
  });

  it("rejects garbage parts", () => {
    const errors = collectDateErrors(parts("signatureRequestedDate", "99", "99", "abcd"), DATES);
    expect(errors).toEqual([
      {
        field: "signatureRequestedDate",
        message: "Signature requested date must be a real date"
      }
    ]);
  });

  it("rejects a date that would roll over rather than storing the rolled value", () => {
    // Date.UTC(2026, 12, 32) is 2027-02-01. Storing that for "32/13/2026" turns a
    // typo into plausible-looking data on the wrong side of a year boundary.
    const errors = collectDateErrors(parts("signatureRequestedDate", "32", "13", "2026"), DATES);
    expect(errors).toHaveLength(1);
    expect(errors[0].message).toContain("must be a real date");
  });

  it("rejects 31 February", () => {
    const errors = collectDateErrors(parts("signatureRequestedDate", "31", "2", "2026"), DATES);
    expect(errors).toHaveLength(1);
  });

  it("rejects a two-digit year", () => {
    // Date.UTC(26, …) is 1926. Nobody typing "26" means 1926, and a case dated
    // 1926 sorts and filters as garbage rather than announcing itself.
    const errors = collectDateErrors(parts("signatureRequestedDate", "1", "1", "26"), DATES);
    expect(errors).toHaveLength(1);
    expect(errors[0].message).toContain("must be a real date");
  });

  it("reports each bad date field separately", () => {
    const errors = collectDateErrors(
      {
        ...parts("signatureRequestedDate", "1", "", ""),
        ...parts("signatureReceivedDate", "99", "99", "9999")
      },
      DATES
    );
    expect(errors.map((e) => e.field)).toEqual(["signatureRequestedDate", "signatureReceivedDate"]);
  });

  it("ignores non-date and read-only fields", () => {
    const fields: FieldDef[] = [
      { name: "returnReason", label: "Return reason", kind: "textarea" },
      { name: "dateOfApplication", label: "Date of application", kind: "date", readOnly: true }
    ];
    const errors = collectDateErrors({ returnReason: "x", ...parts("dateOfApplication", "99", "99", "x") }, fields);
    expect(errors).toEqual([]);
  });
});
