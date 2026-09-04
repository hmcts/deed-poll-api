import { describe, expect, it } from "vitest";
import { z } from "zod";
import { ForbiddenError, InvalidInputError, NotFoundError, zodErrorToFieldErrors } from "./errors.js";

describe("domain errors", () => {
  it("should set a discriminating name on each error so a mapper can switch on it", () => {
    expect(new NotFoundError("gone").name).toBe("NotFoundError");
    expect(new ForbiddenError("nope").name).toBe("ForbiddenError");
    expect(new InvalidInputError("bad").name).toBe("InvalidInputError");
  });

  it("should remain instanceof Error so an unrecognised error still reaches a generic handler", () => {
    expect(new NotFoundError("gone")).toBeInstanceOf(Error);
    expect(new ForbiddenError("nope")).toBeInstanceOf(Error);
    expect(new InvalidInputError("bad")).toBeInstanceOf(Error);
  });

  it("should default InvalidInputError's field errors to empty rather than undefined", () => {
    // A caller doing `err.errors.map(...)` must not have to null-check.
    expect(new InvalidInputError("bad").errors).toEqual([]);
  });

  it("should retain the field errors it was given", () => {
    const errors = [{ field: "surname", message: "Enter a surname" }];

    const error = new InvalidInputError("Validation failed", errors);

    expect(error.errors).toEqual(errors);
    expect(error.message).toBe("Validation failed");
  });
});

describe("zodErrorToFieldErrors", () => {
  it("should map a flat issue to its field name and message", () => {
    const schema = z.object({ surname: z.string().min(1, "Enter a surname") });

    const result = schema.safeParse({ surname: "" });

    expect(result.success).toBe(false);
    expect(zodErrorToFieldErrors(result.error!)).toEqual([{ field: "surname", message: "Enter a surname" }]);
  });

  it("should dot-join nested and indexed paths so an array element is addressable", () => {
    const schema = z.object({
      witnesses: z.array(z.object({ name: z.string().min(1, "Enter a name") }))
    });

    const result = schema.safeParse({ witnesses: [{ name: "ok" }, { name: "" }] });

    expect(zodErrorToFieldErrors(result.error!)).toEqual([{ field: "witnesses.1.name", message: "Enter a name" }]);
  });

  it("should return every issue, not just the first, so a form can show them all at once", () => {
    const schema = z.object({
      surname: z.string().min(1, "Enter a surname"),
      forename: z.string().min(1, "Enter a forename")
    });

    const result = schema.safeParse({ surname: "", forename: "" });

    expect(zodErrorToFieldErrors(result.error!)).toEqual([
      { field: "surname", message: "Enter a surname" },
      { field: "forename", message: "Enter a forename" }
    ]);
  });

  it("should yield an empty field for a whole-object refinement, marking it form-level", () => {
    const schema = z.object({ from: z.number(), to: z.number() }).refine((v) => v.to > v.from, "To must be after from");

    const result = schema.safeParse({ from: 5, to: 1 });

    expect(zodErrorToFieldErrors(result.error!)).toEqual([{ field: "", message: "To must be after from" }]);
  });

  it("should produce the shape InvalidInputError carries, so both render identically", () => {
    const schema = z.object({ surname: z.string().min(1, "Enter a surname") });
    const result = schema.safeParse({ surname: "" });

    const fromZod = zodErrorToFieldErrors(result.error!);

    expect(new InvalidInputError("Validation failed", fromZod).errors).toEqual(fromZod);
  });
});
