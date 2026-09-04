import { z } from "zod";
import { validateNoteText } from "./note-form.js";

/**
 * Create payload for a case note (deedpoll_notes). `note` is the only field a caller supplies:
 * authorship comes from the request context and both timestamps are set by the database.
 *
 * Two behaviours carried over from the DTO this replaces, both load-bearing:
 *
 * 1. **The value is trimmed before validation, and the trimmed value is what gets stored.**
 *    Without it, "   " smuggles a blank note past the check and surrounding whitespace lands in
 *    the column. The prototype did this in a `@Transform`, deliberately at the payload level so
 *    every writer gets it rather than relying on each caller to remember.
 * 2. **Validation delegates to `validateNoteText`** — the same rule the SSR forms use — so the
 *    API and the browser cannot disagree about what counts as a note. That function also
 *    supplies the message, so the wording stays in one place.
 *
 * There is no `noteId`: the source's autonumber display code is deliberately not ported (see
 * the note above `DeedPollNote` in the Prisma schema).
 */
export const createNoteSchema = z.object({
  note: z
    .string()
    .transform((value) => value.trim())
    .superRefine((value, ctx) => {
      const message = validateNoteText(value);
      if (message) ctx.addIssue({ code: "custom", message });
    })
});

/** Update payload for a note. Same rule as create, applied only if `note` is present. */
export const updateNoteSchema = createNoteSchema.partial();

export type CreateNoteInput = z.infer<typeof createNoteSchema>;
export type UpdateNoteInput = z.infer<typeof updateNoteSchema>;
