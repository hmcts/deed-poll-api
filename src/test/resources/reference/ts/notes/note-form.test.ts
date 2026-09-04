import { describe, expect, it } from "vitest";
import { MAX_NOTE_LENGTH, validateNoteText } from "./note-form.js";

describe("validateNoteText", () => {
  it("accepts ordinary note text", () => {
    expect(validateNoteText("Marriage certificate surname differs")).toBeNull();
  });

  it("rejects an empty note", () => {
    expect(validateNoteText("")).toBe("Enter a note");
  });

  it("rejects a whitespace-only note", () => {
    // The gap that motivated this helper: class-validator's @IsNotEmpty rejects only
    // '', null and undefined, so "   " passed and stored a note the grid renders as a
    // blank row. Trimming is the rule on both the SSR form and the API DTO.
    expect(validateNoteText("   \n\t ")).toBe("Enter a note");
  });

  it("rejects a missing field, which is what an absent form control looks like", () => {
    expect(validateNoteText(undefined)).toBe("Enter a note");
    expect(validateNoteText(null)).toBe("Enter a note");
  });

  it("rejects a non-string, which a duplicated form control produces", () => {
    // Two inputs named `note` arrive as an array. It must not be stringified into
    // "a,b" and stored.
    expect(validateNoteText(["a", "b"])).toBe("Enter a note");
  });

  it("accepts a note of exactly the maximum length", () => {
    expect(validateNoteText("x".repeat(MAX_NOTE_LENGTH))).toBeNull();
  });

  it("rejects a note one character over the maximum", () => {
    expect(validateNoteText("x".repeat(MAX_NOTE_LENGTH + 1))).toBe(`Note must be ${MAX_NOTE_LENGTH} characters or less`);
  });

  it("measures length after trimming, so trailing whitespace cannot push it over", () => {
    // The value that gets stored is the trimmed one, so the limit must apply to that.
    expect(validateNoteText(`${"x".repeat(MAX_NOTE_LENGTH)}     `)).toBeNull();
  });

  it("applies the same rule to an amendment as to a new note", () => {
    // The amend form reuses this outright: a note cannot be edited into a blank row
    // any more than it can be created as one.
    expect(validateNoteText("Amended text")).toBeNull();
    expect(validateNoteText("  ")).toBe("Enter a note");
  });
});
