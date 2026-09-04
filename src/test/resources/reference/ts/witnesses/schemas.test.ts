import { describe, expect, it } from "vitest";
import { createWitnessSchema, updateWitnessSchema } from "./schemas.js";

const valid = {
  witnessName: "Sam Doe",
  witnessAddress: "1 High St",
  witnessOccupation: "Teacher",
  declarant: false
};

describe("createWitnessSchema", () => {
  it("accepts a complete witness", () => {
    expect(createWitnessSchema.parse(valid)).toEqual(valid);
  });

  it("requires every field, because the source does", () => {
    for (const field of Object.keys(valid)) {
      const partial = { ...valid };
      delete partial[field as keyof typeof valid];

      expect(createWitnessSchema.safeParse(partial).success, `${field} should be required`).toBe(false);
    }
  });

  it("enforces the source column lengths", () => {
    expect(createWitnessSchema.safeParse({ ...valid, witnessName: "a".repeat(101) }).success).toBe(false);
    expect(createWitnessSchema.safeParse({ ...valid, witnessAddress: "a".repeat(251) }).success).toBe(false);
    expect(createWitnessSchema.safeParse({ ...valid, witnessOccupation: "a".repeat(101) }).success).toBe(false);
  });

  it("requires declarant to be a boolean, not a truthy string", () => {
    expect(createWitnessSchema.safeParse({ ...valid, declarant: "true" }).success).toBe(false);
  });
});

describe("updateWitnessSchema", () => {
  it("accepts a partial patch", () => {
    expect(updateWitnessSchema.parse({ witnessOccupation: "Nurse" })).toEqual({ witnessOccupation: "Nurse" });
  });

  it("still enforces lengths on present fields", () => {
    expect(updateWitnessSchema.safeParse({ witnessName: "a".repeat(101) }).success).toBe(false);
  });
});
