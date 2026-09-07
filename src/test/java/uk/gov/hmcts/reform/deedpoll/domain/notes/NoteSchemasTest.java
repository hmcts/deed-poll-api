package uk.gov.hmcts.reform.deedpoll.domain.notes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports every test block in {@code reference/ts/notes/schemas.test.ts}.
 *
 * <p>{@code safeParse(...).success === false} becomes "throws {@link InvalidInputError}", and
 * {@code error.issues[0].message} becomes the message of the thrown error's one {@link FieldError} --
 * which additionally names the field, so the form can render it against the textarea rather than as a
 * banner.
 */
class NoteSchemasTest {

    private static Map<String, Object> payload(Object... keysAndValues) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            raw.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return raw;
    }

    /** The message the rejection carries, attributed to its field. */
    private static List<FieldError> errorsFrom(ThrowingParse parse) {
        try {
            parse.run();
        } catch (InvalidInputError rejected) {
            return rejected.errors();
        }
        throw new AssertionError("expected the payload to be rejected");
    }

    private interface ThrowingParse {
        void run();
    }

    @Nested
    @DisplayName("createNoteSchema")
    class CreateNoteSchema {

        @Test
        @DisplayName("stores the trimmed value, not what was submitted")
        void storesTheTrimmedValue() {
            CreateNoteInput result = NoteSchemas.parseCreateNote(payload("note", "  Applicant called to chase  "));

            assertThat(result.note()).isEqualTo("Applicant called to chase");
        }

        @Test
        @DisplayName("rejects a whitespace-only note")
        void rejectsAWhitespaceOnlyNote() {
            // The regression this guards: @IsNotEmpty() alone accepted "   ", which is why the payload
            // trims first and delegates to validateNoteText.
            assertThat(errorsFrom(() -> NoteSchemas.parseCreateNote(payload("note", "     "))))
                .containsExactly(new FieldError("note", "Enter a note"));
        }

        @Test
        @DisplayName("rejects an empty note")
        void rejectsAnEmptyNote() {
            assertThatThrownBy(() -> NoteSchemas.parseCreateNote(payload("note", "")))
                .isInstanceOf(InvalidInputError.class);
        }

        @Test
        @DisplayName("rejects a non-string note")
        void rejectsANonStringNote() {
            assertThatThrownBy(() -> NoteSchemas.parseCreateNote(payload("note", 42)))
                .isInstanceOf(InvalidInputError.class);
        }

        @Test
        @DisplayName("applies the length limit to the trimmed text")
        void appliesTheLengthLimitToTheTrimmedText() {
            String padded = "  " + "a".repeat(2000) + "  ";

            assertThat(NoteSchemas.parseCreateNote(payload("note", padded)).note()).hasSize(2000);
            assertThatThrownBy(() -> NoteSchemas.parseCreateNote(payload("note", "a".repeat(2001))))
                .isInstanceOf(InvalidInputError.class);
        }

        @Test
        @DisplayName("surfaces the length message from validateNoteText, so wording lives in one place")
        void surfacesTheLengthMessageFromValidateNoteText() {
            assertThat(errorsFrom(() -> NoteSchemas.parseCreateNote(payload("note", "a".repeat(2001)))))
                .containsExactly(new FieldError("note", "Note must be 2000 characters or less"));
        }

        @Test
        @DisplayName("strips unknown keys, as the whitelisting validation pipe did")
        void stripsUnknownKeys() {
            CreateNoteInput result = NoteSchemas.parseCreateNote(payload("note", "Valid", "createdBy", "spoofed"));

            assertThat(result).isEqualTo(new CreateNoteInput("Valid"));
        }

        @Test
        @DisplayName("rejects a payload with no note at all")
        void rejectsAPayloadWithNoNote() {
            // Not a reference block: there, `z.string()` makes a missing field a type error the tests do
            // not restate. Here the absence reads as null, so it is worth pinning that it is refused
            // rather than stored as the string "null".
            assertThat(errorsFrom(() -> NoteSchemas.parseCreateNote(Map.of())))
                .containsExactly(new FieldError("note", "Enter a note"));
        }
    }

    @Nested
    @DisplayName("updateNoteSchema")
    class UpdateNoteSchema {

        @Test
        @DisplayName("accepts an empty patch")
        void acceptsAnEmptyPatch() {
            assertThat(NoteSchemas.parseUpdateNote(Map.of())).isEqualTo(new UpdateNoteInput(Optional.empty()));
        }

        @Test
        @DisplayName("still applies the note rule when the field is present")
        void stillAppliesTheNoteRuleWhenTheFieldIsPresent() {
            assertThatThrownBy(() -> NoteSchemas.parseUpdateNote(payload("note", "  ")))
                .isInstanceOf(InvalidInputError.class);
        }

        @Test
        @DisplayName("trims a present note, as create does")
        void trimsAPresentNote() {
            // Not a reference block, but the partial schema inherits the transform as well as the rule,
            // and an amend that stored "  text  " would be a whitespace regression on the amend path only.
            assertThat(NoteSchemas.parseUpdateNote(payload("note", "  Amended  ")).note()).contains("Amended");
        }
    }
}
