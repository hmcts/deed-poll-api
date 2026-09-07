package uk.gov.hmcts.reform.deedpoll.domain.notes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.reform.deedpoll.domain.notes.NoteForm.MAX_NOTE_LENGTH;
import static uk.gov.hmcts.reform.deedpoll.domain.notes.NoteForm.validateNoteText;

/**
 * Ports every test block in {@code reference/ts/notes/note-form.test.ts}.
 *
 * <p>The reference's {@code toBeNull()} becomes {@code isEmpty()} here, because the ported function
 * returns {@link java.util.Optional} -- see {@link NoteForm#validateNoteText} for why.
 */
class NoteFormTest {

    private static String repeat(int length) {
        return "x".repeat(length);
    }

    @Test
    @DisplayName("accepts ordinary note text")
    void acceptsOrdinaryNoteText() {
        assertThat(validateNoteText("Marriage certificate surname differs")).isEmpty();
    }

    @Test
    @DisplayName("rejects an empty note")
    void rejectsAnEmptyNote() {
        assertThat(validateNoteText("")).contains("Enter a note");
    }

    @Test
    @DisplayName("rejects a whitespace-only note")
    void rejectsAWhitespaceOnlyNote() {
        // The gap that motivated this helper: the DTO's @IsNotEmpty rejects only '', null and
        // undefined, so "   " passed and stored a note the grid renders as a blank row. Trimming is
        // the rule on both the form and the API payload.
        assertThat(validateNoteText("   \n\t ")).contains("Enter a note");
    }

    @Test
    @DisplayName("rejects a missing field, which is what an absent form control looks like")
    void rejectsAMissingField() {
        // TypeScript's undefined and null are one value here: an absent map entry reads as null.
        assertThat(validateNoteText(null)).contains("Enter a note");
    }

    @Test
    @DisplayName("rejects a non-string, which a duplicated form control produces")
    void rejectsANonString() {
        // Two inputs named `note` arrive as a list. It must not be stringified into "[a, b]" and stored.
        assertThat(validateNoteText(List.of("a", "b"))).contains("Enter a note");
    }

    @Test
    @DisplayName("accepts a note of exactly the maximum length")
    void acceptsANoteOfExactlyTheMaximumLength() {
        assertThat(validateNoteText(repeat(MAX_NOTE_LENGTH))).isEmpty();
    }

    @Test
    @DisplayName("rejects a note one character over the maximum")
    void rejectsANoteOneCharacterOverTheMaximum() {
        assertThat(validateNoteText(repeat(MAX_NOTE_LENGTH + 1)))
            .contains("Note must be " + MAX_NOTE_LENGTH + " characters or less");
    }

    @Test
    @DisplayName("measures length after trimming, so trailing whitespace cannot push it over")
    void measuresLengthAfterTrimming() {
        // The value that gets stored is the trimmed one, so the limit must apply to that.
        assertThat(validateNoteText(repeat(MAX_NOTE_LENGTH) + "     ")).isEmpty();
    }

    @Test
    @DisplayName("applies the same rule to an amendment as to a new note")
    void appliesTheSameRuleToAnAmendment() {
        // The amend form reuses this outright: a note cannot be edited into a blank row any more than
        // it can be created as one.
        assertThat(validateNoteText("Amended text")).isEmpty();
        assertThat(validateNoteText("  ")).contains("Enter a note");
    }
}
