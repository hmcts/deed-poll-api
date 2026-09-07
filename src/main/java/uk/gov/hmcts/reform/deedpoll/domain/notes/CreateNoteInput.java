package uk.gov.hmcts.reform.deedpoll.domain.notes;

/**
 * Create payload for a case note ({@code deedpoll_notes}), already trimmed and validated.
 *
 * <p>Ported from the {@code CreateNoteInput} type in {@code reference/ts/notes/schemas.ts}. The text is
 * the only field a caller supplies: authorship comes from the request context and both timestamps are
 * set by the entity.
 *
 * <p>Only reachable through {@link NoteSchemas#parseCreateNote}, which is what makes "unknown keys are
 * stripped" structural rather than a rule someone has to remember -- a caller sending {@code createdBy}
 * has nowhere for it to land.
 *
 * <p>There is no note id: the source's autonumber display code is deliberately not ported (see
 * {@code DeedPollNote} in {@code reference/deed-poll.prisma}), and the grid derives a display code from
 * the row's own id instead -- see {@link NotesView#shortCode}.
 */
public record CreateNoteInput(String note) {
}
