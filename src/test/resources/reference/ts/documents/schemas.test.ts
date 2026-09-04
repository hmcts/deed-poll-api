import { DocumentDirection } from "@hmcts/postgres-prisma";
import { describe, expect, it } from "vitest";
import { createDocumentSchema, createDocumentTypeSchema, updateDocumentSchema, updateDocumentTypeSchema } from "./schemas.js";

const validDocument = {
  direction: DocumentDirection.Incoming,
  name: "Birth certificate",
  documentTypeId: "3f0f5b62-1f0a-4c1e-9b3a-2c1d4e5f6a7b"
};

describe("createDocumentSchema", () => {
  it("accepts metadata without the file, which is attached separately", () => {
    expect(createDocumentSchema.parse(validDocument)).toEqual(validDocument);
  });

  it("requires direction, name and the document-type FK", () => {
    for (const field of Object.keys(validDocument)) {
      const partial = { ...validDocument };
      delete partial[field as keyof typeof validDocument];

      expect(createDocumentSchema.safeParse(partial).success, `${field} should be required`).toBe(false);
    }
  });

  it("requires documentTypeId to be a UUID, so a bad FK fails before it reaches the database", () => {
    expect(createDocumentSchema.safeParse({ ...validDocument, documentTypeId: "not-a-uuid" }).success).toBe(false);
  });

  it("rejects a direction outside the option set", () => {
    expect(createDocumentSchema.safeParse({ ...validDocument, direction: "Sideways" }).success).toBe(false);
  });

  it("enforces the 100-character name limit", () => {
    expect(createDocumentSchema.safeParse({ ...validDocument, name: "a".repeat(101) }).success).toBe(false);
  });
});

describe("createDocumentTypeSchema", () => {
  it("accepts a name with no applicability lists", () => {
    expect(createDocumentTypeSchema.parse({ name: "Passport" })).toEqual({ name: "Passport" });
  });

  it("accepts option-set integers for the applicability lists", () => {
    const parsed = createDocumentTypeSchema.parse({
      name: "Marriage certificate",
      applicableMaritalStatuses: [2],
      applicableTypes: [1, 2]
    });

    expect(parsed.applicableMaritalStatuses).toEqual([2]);
    expect(parsed.applicableTypes).toEqual([1, 2]);
  });

  it("rejects non-integer option-set values", () => {
    expect(createDocumentTypeSchema.safeParse({ name: "X", applicableTypes: ["1"] }).success).toBe(false);
    expect(createDocumentTypeSchema.safeParse({ name: "X", applicableTypes: [1.5] }).success).toBe(false);
  });

  it("requires a name", () => {
    expect(createDocumentTypeSchema.safeParse({}).success).toBe(false);
  });
});

describe("partial update schemas", () => {
  it("accept empty patches", () => {
    expect(updateDocumentSchema.parse({})).toEqual({});
    expect(updateDocumentTypeSchema.parse({})).toEqual({});
  });

  it("still validate present fields", () => {
    expect(updateDocumentSchema.safeParse({ documentTypeId: "nope" }).success).toBe(false);
    expect(updateDocumentTypeSchema.safeParse({ name: "a".repeat(101) }).success).toBe(false);
  });
});
