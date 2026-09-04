import { DeedPollType, MaritalStatus } from "@hmcts/postgres-prisma";
import { describe, expect, it } from "vitest";
import { createDeedPollSchema, updateDeedPollSchema } from "./schemas.js";

describe("createDeedPollSchema", () => {
  it("accepts an empty payload, because a case is logged incrementally", () => {
    expect(createDeedPollSchema.parse({})).toEqual({});
  });

  it("accepts enum members and rejects anything outside the option set", () => {
    expect(createDeedPollSchema.parse({ type: DeedPollType.Minor }).type).toBe(DeedPollType.Minor);
    expect(createDeedPollSchema.safeParse({ type: "NotAType" }).success).toBe(false);
    expect(createDeedPollSchema.safeParse({ maritalStatus: MaritalStatus.Single }).success).toBe(true);
  });

  it("enforces the source's 1000-character limit on name fields", () => {
    expect(createDeedPollSchema.safeParse({ newForename: "a".repeat(1000) }).success).toBe(true);
    expect(createDeedPollSchema.safeParse({ newForename: "a".repeat(1001) }).success).toBe(false);
  });

  it("keeps dates as ISO strings, accepting bare dates and date-times alike", () => {
    // The domain coerces these to Date immediately before Prisma; the SSR forms submit
    // YYYY-MM-DD while the API tends to send a full timestamp, so both must pass.
    for (const value of ["2026-03-01", "2026-03-01T09:30:00Z", "2026-03-01T09:30:00+01:00", "2026-03-01T09:30:00"]) {
      expect(createDeedPollSchema.safeParse({ dateReceived: value }).success).toBe(true);
    }
    expect(createDeedPollSchema.parse({ dateReceived: "2026-03-01" }).dateReceived).toBe("2026-03-01");
  });

  it("rejects a malformed date", () => {
    expect(createDeedPollSchema.safeParse({ dateReceived: "01/03/2026" }).success).toBe(false);
    expect(createDeedPollSchema.safeParse({ dateReceived: "not-a-date" }).success).toBe(false);
  });

  it("accepts null on a nullable field, so a PATCH can clear it", () => {
    expect(createDeedPollSchema.parse({ dateReceived: null }).dateReceived).toBeNull();
    expect(createDeedPollSchema.parse({ masterName: null }).masterName).toBeNull();
  });

  it("rejects null where the column is not nullable, rather than letting Prisma fail later", () => {
    // privacy, urgency, the three multiselects and the booleans all have Prisma defaults, so
    // NULL is not a legal value for them — better a validation error than a database error.
    expect(createDeedPollSchema.safeParse({ privacy: null }).success).toBe(false);
    expect(createDeedPollSchema.safeParse({ iShall: null }).success).toBe(false);
    expect(createDeedPollSchema.safeParse({ publishToLondonGazette: null }).success).toBe(false);
  });

  it("accepts multiselects as integer arrays, preserving the Dynamics values", () => {
    expect(createDeedPollSchema.parse({ iShall: [1, 4] }).iShall).toEqual([1, 4]);
    expect(createDeedPollSchema.safeParse({ iShall: ["1"] }).success).toBe(false);
    expect(createDeedPollSchema.safeParse({ ageOfMinor: 7.5 }).success).toBe(false);
  });

  it("strips unknown keys, as the whitelisting validation pipe did", () => {
    const result = createDeedPollSchema.parse({ newForename: "Ada", displayReference: "KBDP2026-00001" });

    // displayReference is allocated by reference-number.ts and must not be settable by a caller.
    expect(result).toEqual({ newForename: "Ada" });
  });
});

describe("updateDeedPollSchema", () => {
  it("accepts an empty patch", () => {
    expect(updateDeedPollSchema.parse({})).toEqual({});
  });

  it("applies the same format rules to the fields that are present", () => {
    expect(updateDeedPollSchema.safeParse({ newForename: "a".repeat(1001) }).success).toBe(false);
    expect(updateDeedPollSchema.safeParse({ type: "NotAType" }).success).toBe(false);
  });
});
