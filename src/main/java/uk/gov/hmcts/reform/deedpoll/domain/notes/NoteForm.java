package uk.gov.hmcts.reform.deedpoll.domain.notes;

import java.util.Optional;

/**
 * Validation for the note text, shared by the create / amend forms and the API payload.
 *
 * <p>Ported from {@code reference/ts/notes/note-form.ts}. It lives outside both because the two paths
 * must agree: the DTO's {@code @IsNotEmpty} rejected only {@code ''}, {@code null} and
 * {@code undefined}, so a note of {@code "   "} passed validation and stored a row the grid renders
 * blank. A browser posting an untouched textarea sends exactly that, so the form would have hit it on
 * day one.
 *
 * <p>A form controller needs the message anyway -- it re-renders the form rather than returning a 400 --
 * so the rule is expressed once here and {@link NoteSchemas} borrows it.
 */
public final class NoteForm {

    /**
     * Matches the note column's width in {@code reference/deed-poll.prisma}. Exported so a form's
     * character-count hint cannot drift from the limit that rejects.
     */
    public static final int MAX_NOTE_LENGTH = 2000;

    private NoteForm() {
    }

    /**
     * The single source of truth for whether a note's text is acceptable: the error message to show, or
     * empty when it is valid.
     *
     * <p>Takes {@link Object} because both callers receive untrusted input -- a JSON body value is
     * untyped, and two form controls with the same name arrive as a list. A non-string is rejected rather
     * than coerced, so {@code ["a", "b"]} cannot be stored as "a,b".
     *
     * <p>Length is measured on the trimmed text, since that is what gets stored.
     *
     * <p>Returns {@link Optional} where the reference returns {@code string | null}, for the reason
     * {@code ReferenceNumbers.parse} does: TypeScript's strict null checks force the caller to handle the
     * null return and Java has no such check, so the obligation is kept in the type. The ported tests
     * assert {@code isEmpty()} where the reference asserts null.
     */
    public static Optional<String> validateNoteText(Object value) {
        if (!(value instanceof String string)) {
            return Optional.of("Enter a note");
        }
        String text = string.trim();
        if (text.isEmpty()) {
            return Optional.of("Enter a note");
        }
        if (text.length() > MAX_NOTE_LENGTH) {
            return Optional.of("Note must be " + MAX_NOTE_LENGTH + " characters or less");
        }
        return Optional.empty();
    }
}
