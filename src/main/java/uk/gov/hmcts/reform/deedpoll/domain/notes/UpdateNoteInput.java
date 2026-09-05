package uk.gov.hmcts.reform.deedpoll.domain.notes;

import java.util.Optional;

/**
 * Update payload for a note: the same rule as create, applied only if the text is present.
 *
 * <p>Ported from the {@code UpdateNoteInput} type in {@code reference/ts/notes/schemas.ts}, which is
 * {@code createNoteSchema.partial()}.
 *
 * <p>{@link Optional} rather than a nullable field, because absent and present are the only two states
 * this patch has: the note column is {@code NOT NULL}, so null is not a value a caller may send, and
 * {@link NoteSchemas#parseUpdateNote} refuses it. That is the same absent-versus-null care
 * {@code DeedPollPatch} takes for the case, in the one shape a single non-nullable field needs.
 *
 * <p>As with {@link CreateNoteInput}, {@code createdBy} is not expressible here -- which is how an edit
 * is prevented from rewriting who created a note, structurally rather than by deleting the field from a
 * patch as the case's own update path has to.
 */
public record UpdateNoteInput(Optional<String> note) {
}
