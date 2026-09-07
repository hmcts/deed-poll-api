import { z } from "zod";

/**
 * Shared building blocks for the payload schemas.
 *
 * These replace the prototype's class-validator DTOs. Two behaviours of the old setup are
 * deliberately preserved:
 *
 * - `z.object()` strips unknown keys, which is what `ValidationPipe({ whitelist: true })` did.
 * - `@IsOptional()` skipped validation for both `undefined` and `null`, so a field the caller
 *   omits and a field the caller explicitly clears were both accepted. `.nullish()` keeps that,
 *   which matters for PATCH: sending `null` is how a caseworker empties a date.
 *
 * Where the underlying column is NOT nullable (it has a Prisma default — the two enum
 * defaults, the three int[] multiselects and the three booleans) the schema uses `.optional()`
 * instead, so `null` is rejected by validation rather than reaching Prisma and failing there
 * with a less useful error.
 */

/**
 * An ISO 8601 date or date-time string, matching what `@IsDateString()` accepted.
 *
 * Dates stay strings through validation on purpose: the domain layer coerces them to `Date`
 * immediately before handing them to Prisma, and the SSR forms submit `YYYY-MM-DD`. The union
 * accepts a bare date, a UTC or offset date-time, and a local date-time without a zone.
 */
export const isoDateString = z.union([z.iso.date(), z.iso.datetime({ offset: true, local: true })]);

/** A nullable, optional ISO date string — the shape almost every date field on a case has. */
export const optionalIsoDate = isoDateString.nullish();

/** A nullable, optional string with no length cap (maps a bare `@IsOptional() @IsString()`). */
export const optionalText = z.string().nullish();

/** A nullable, optional string with a maximum length (maps `@MaxLength(n)`). */
export const optionalTextMax = (max: number) => z.string().max(max).nullish();

/** A multiselect option-set stored as int[]. Not nullable — the column defaults to []. */
export const optionSetValues = z.array(z.int()).optional();
