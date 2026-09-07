package uk.gov.hmcts.reform.deedpoll.domain.notes;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shape validation for a note create payload and a note update patch.
 *
 * <p>Ported from {@code reference/ts/notes/schemas.ts}. Two behaviours carried over from the DTO it
 * replaces, both load-bearing:
 *
 * <ol>
 *   <li><strong>The value is trimmed before validation, and the trimmed value is what gets stored.</strong>
 *       Without it, {@code "   "} smuggles a blank note past the check and surrounding whitespace lands in
 *       the column. Done at the payload level so every writer gets it, rather than relying on each caller
 *       to remember.</li>
 *   <li><strong>Validation delegates to {@link NoteForm#validateNoteText}</strong> -- the same rule the
 *       forms use -- so the API and the browser cannot disagree about what counts as a note. That function
 *       also supplies the message, so the wording stays in one place.</li>
 * </ol>
 *
 * <p>Unknown keys are stripped rather than reported, as the whitelisting validation pipe did: only
 * {@code note} is read, and {@link CreateNoteInput} has nowhere to put anything else.
 *
 * <p>As in {@code DeedPollSchemas}, only the throwing form of the reference's {@code parse} /
 * {@code safeParse} pair is ported -- {@link InvalidInputError} already carries the per-field failure a
 * {@code safeParse} caller would have inspected, attributed to {@code note} so a form can render it
 * against the textarea.
 */
public final class NoteSchemas {

    private NoteSchemas() {
    }

    /**
     * Validates a create payload.
     *
     * @throws InvalidInputError if the text is missing, not text, blank once trimmed, or over the limit
     */
    public static CreateNoteInput parseCreateNote(Map<String, Object> raw) {
        return new CreateNoteInput(noteText(raw.get("note")));
    }

    /**
     * Validates an update patch. An empty patch is accepted; a present {@code note} gets the create rule.
     *
     * @throws InvalidInputError if {@code note} is present and not acceptable
     */
    public static UpdateNoteInput parseUpdateNote(Map<String, Object> raw) {
        if (!raw.containsKey("note")) {
            return new UpdateNoteInput(Optional.empty());
        }
        return new UpdateNoteInput(Optional.of(noteText(raw.get("note"))));
    }

    /**
     * The trimmed text, or a rejection attributed to the field.
     *
     * <p>An absent or non-string value takes the same "Enter a note" message a blank one does, where the
     * reference gets a type message from {@code z.string()} first: what a caller has to do about it is
     * identical, and one message means the form says the same thing however the field arrived wrong.
     */
    private static String noteText(Object value) {
        Optional<String> message = NoteForm.validateNoteText(value);
        if (message.isPresent()) {
            throw new InvalidInputError("Note payload is not valid",
                List.of(new FieldError("note", message.get())));
        }
        return ((String) value).trim();
    }
}
