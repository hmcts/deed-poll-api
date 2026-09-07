/**
 * Validation for the note text, shared by the SSR create/amend forms and the API DTO.
 *
 * It lives outside both because the two paths must agree: the DTO's `@IsNotEmpty`
 * rejects only `''`, `null` and `undefined`, so a note of `"   "` passed validation and
 * stored a row the grid renders blank. A browser posting an untouched textarea sends
 * exactly that, so the SSR form would have hit it on day one.
 *
 * The SSR controller needs the message anyway (it re-renders the form rather than
 * returning a 400), so the rule is expressed once as a function and the DTO borrows it
 * through a custom validator.
 */

/**
 * Matches the `@MaxLength` on `CreateNoteDto` and the column width in schema.prisma.
 * Exported so the form's character-count hint cannot drift from the limit that rejects.
 */
export const MAX_NOTE_LENGTH = 2000;

/**
 * The single source of truth for whether a note's text is acceptable: the error message
 * to show, or null when it is valid.
 *
 * Takes `unknown` because both callers receive untrusted input — an Express body value
 * is `any`, and two controls with the same name arrive as an array. A non-string is
 * rejected rather than coerced, so `['a','b']` cannot be stored as "a,b".
 *
 * Length is measured on the trimmed text, since that is what gets stored.
 */
export function validateNoteText(value: unknown): string | null {
  if (typeof value !== "string") return "Enter a note";
  const text = value.trim();
  if (text.length === 0) return "Enter a note";
  if (text.length > MAX_NOTE_LENGTH) {
    return `Note must be ${MAX_NOTE_LENGTH} characters or less`;
  }
  return null;
}
