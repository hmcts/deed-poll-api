import { describe, expect, it } from "vitest";
import { createNoteSchema, updateNoteSchema } from "./schemas.js";

describe("createNoteSchema", () => {
  it("stores the trimmed value, not what was submitted", () => {
    const result = createNoteSchema.parse({ note: "  Applicant called to chase  " });

    expect(result.note).toBe("Applicant called to chase");
  });

  it("rejects a whitespace-only note", () => {
    // The regression this guards: @IsNotEmpty() alone accepted "   ", which is why the
    // prototype trimmed first and delegated to validateNoteText.
    const result = createNoteSchema.safeParse({ note: "     " });

    expect(result.success).toBe(false);
    expect(result.error?.issues[0].message).toBe("Enter a note");
  });

  it("rejects an empty note", () => {
    expect(createNoteSchema.safeParse({ note: "" }).success).toBe(false);
  });

  it("rejects a non-string note", () => {
    expect(createNoteSchema.safeParse({ note: 42 }).success).toBe(false);
  });

  it("applies the length limit to the trimmed text", () => {
    const padded = `  ${"a".repeat(2000)}  `;

    expect(createNoteSchema.parse({ note: padded }).note).toHaveLength(2000);
    expect(createNoteSchema.safeParse({ note: "a".repeat(2001) }).success).toBe(false);
  });

  it("surfaces the length message from validateNoteText, so wording lives in one place", () => {
    const result = createNoteSchema.safeParse({ note: "a".repeat(2001) });

    expect(result.error?.issues[0].message).toBe("Note must be 2000 characters or less");
  });

  it("strips unknown keys, as the whitelisting validation pipe did", () => {
    const result = createNoteSchema.parse({ note: "Valid", createdBy: "spoofed" });

    expect(result).toEqual({ note: "Valid" });
  });
});

describe("updateNoteSchema", () => {
  it("accepts an empty patch", () => {
    expect(updateNoteSchema.parse({})).toEqual({});
  });

  it("still applies the note rule when the field is present", () => {
    expect(updateNoteSchema.safeParse({ note: "  " }).success).toBe(false);
  });
});
