import { describe, expect, it, vi } from "vitest";
import { InvalidInputError } from "../errors.js";
import { allocateForCreate, allocateSerial, compose, formatSerial, parse, prefixForType, reprefixForUpdate, yearFromDateReceived } from "./reference-number.js";

describe("reference-number", () => {
  describe("prefixForType", () => {
    it("uses KBDR for Deed of Relinquishment (type 5)", () => {
      expect(prefixForType("DeedOfRelinquishment")).toBe("KBDR");
    });

    it("uses KBDP for every other type", () => {
      for (const t of ["Adult", "AdultTransgender", "Minor", "MinorTransgender", "Unknown"] as const) {
        expect(prefixForType(t)).toBe("KBDP");
      }
    });

    it("uses KBDP when type is null/undefined", () => {
      expect(prefixForType(null)).toBe("KBDP");
      expect(prefixForType(undefined)).toBe("KBDP");
    });
  });

  describe("formatSerial / compose", () => {
    it("zero-pads the serial to 5 digits", () => {
      expect(formatSerial(1)).toBe("00001");
      expect(formatSerial(42)).toBe("00042");
      expect(formatSerial(99999)).toBe("99999");
    });

    it("composes {prefix}{year}-{serial}", () => {
      expect(compose("KBDP", 2026, 1)).toBe("KBDP2026-00001");
      expect(compose("KBDR", 2025, 123)).toBe("KBDR2025-00123");
    });
  });

  describe("parse", () => {
    it("round-trips a composed reference", () => {
      expect(parse("KBDP2026-00001")).toEqual({
        prefix: "KBDP",
        year: 2026,
        serial: 1
      });
      expect(parse("KBDR2024-01234")).toEqual({
        prefix: "KBDR",
        year: 2024,
        serial: 1234
      });
    });

    it("returns null for malformed / legacy values", () => {
      expect(parse("")).toBeNull();
      expect(parse(null)).toBeNull();
      expect(parse("KBDP2026-1")).toBeNull(); // serial not 5 digits
      expect(parse("XX2026-00001")).toBeNull(); // bad prefix
      expect(parse("legacy-ref-99")).toBeNull();
    });
  });

  describe("yearFromDateReceived", () => {
    it("takes the UTC year from Date Received", () => {
      expect(yearFromDateReceived(new Date("2026-07-31T00:00:00.000Z"))).toBe(2026);
      // 1 Jan just after midnight UTC stays in the new year
      expect(yearFromDateReceived(new Date("2027-01-01T00:30:00.000Z"))).toBe(2027);
    });

    it("throws when Date Received is missing or invalid", () => {
      expect(() => yearFromDateReceived(null)).toThrow();
      expect(() => yearFromDateReceived(undefined)).toThrow();
      expect(() => yearFromDateReceived(new Date("nonsense"))).toThrow();
    });

    it("should throw InvalidInputError, so the route layer maps it to 400 rather than 500", () => {
      // A bare Error here reaches apps/api's error middleware unrecognised and becomes a generic
      // "Internal server error" — which is what POST /deed-polls with no Date Received returned.
      expect(() => yearFromDateReceived(null)).toThrow(InvalidInputError);
      expect(() => yearFromDateReceived(new Date("nonsense"))).toThrow(InvalidInputError);
    });

    it("should attribute the error to dateReceived, so a form can render it against that input", () => {
      expect(() => yearFromDateReceived(null)).toThrow(
        expect.objectContaining({
          errors: [{ field: "dateReceived", message: "Enter the date the application was received" }]
        })
      );
    });
  });

  describe("allocateSerial (atomic per-year counter)", () => {
    it("seeds a new year at 1 via upsert", async () => {
      const tx = {
        referenceCounter: {
          upsert: vi.fn().mockResolvedValue({ year: 2026, lastSerial: 1 })
        }
      };
      const serial = await allocateSerial(tx as never, 2026);
      expect(serial).toBe(1);
      expect(tx.referenceCounter.upsert).toHaveBeenCalledWith({
        where: { year: 2026 },
        create: { year: 2026, lastSerial: 1 },
        update: { lastSerial: { increment: 1 } }
      });
    });

    it("returns the incremented serial for an existing year", async () => {
      const tx = {
        referenceCounter: {
          upsert: vi.fn().mockResolvedValue({ year: 2026, lastSerial: 8 })
        }
      };
      expect(await allocateSerial(tx as never, 2026)).toBe(8);
    });
  });

  describe("allocateForCreate", () => {
    it("derives year, allocates serial, sets prefix from type", async () => {
      const tx = {
        referenceCounter: {
          upsert: vi.fn().mockResolvedValue({ year: 2026, lastSerial: 3 })
        }
      };
      const result = await allocateForCreate(tx as never, {
        type: "DeedOfRelinquishment",
        dateReceived: new Date("2026-05-01T00:00:00.000Z")
      });
      expect(result).toEqual({
        referencenumber: 3,
        displayReference: "KBDR2026-00003"
      });
    });
  });

  describe("reprefixForUpdate", () => {
    it("re-prefixes KBDP→KBDR on a type flip, keeping year + serial", () => {
      expect(reprefixForUpdate("KBDP2026-00007", "DeedOfRelinquishment")).toBe("KBDR2026-00007");
    });

    it("re-prefixes KBDR→KBDP flipping back to a non-relinquishment type", () => {
      expect(reprefixForUpdate("KBDR2026-00007", "Adult")).toBe("KBDP2026-00007");
    });

    it("is a no-op (returns the same value) when type is unchanged in effect", () => {
      expect(reprefixForUpdate("KBDP2026-00007", "Adult")).toBe("KBDP2026-00007");
    });

    it("returns null for an unparseable existing reference", () => {
      expect(reprefixForUpdate("legacy", "Adult")).toBeNull();
      expect(reprefixForUpdate(null, "Adult")).toBeNull();
    });
  });
});
