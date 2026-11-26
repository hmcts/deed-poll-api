package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionNote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The document deletion reason's rule: free text, trimmed, 1 to 500 code points, no control or disguising mark. */
class DocumentDeletionReasonTest {

    /** A character outside the Basic Multilingual Plane: one code point, two UTF-16 units. */
    private static final String ASTRAL = new String(Character.toChars(0x1F4C4));

    @Test
    @DisplayName("one word is a reason, and accepted text is kept exactly, its outer whitespace trimmed")
    void acceptsFreeText() {
        assertThat(DocumentDeletionReason.parse("Duplicate").value()).isEqualTo("Duplicate");
        assertThat(DocumentDeletionReason.parse("  Uploaded in error \u3000").value()).isEqualTo("Uploaded in error");
        assertThat(DocumentDeletionReason.parse("Wrong  case,   see note").value())
            .isEqualTo("Wrong  case,   see note");
        String text = "Caf\u00E9 \u2013 na\u00EFve \u65E5\u672C " // escaped: non-ASCII
            + "\"quoted\" <b>not markup</b> & ' " + ASTRAL;
        assertThat(DocumentDeletionReason.parse(text).value()).isEqualTo(text);
        assertThat(DocumentDeletionReason.parse("x").value()).isEqualTo("x");
    }

    @Test
    @DisplayName("500 code points is allowed and 501 is not, counted as code points rather than UTF-16 units")
    void boundsInCodePoints() {
        assertThat(DocumentDeletionReason.parse("a".repeat(500)).value()).hasSize(500);
        assertThat(DocumentDeletionReason.parse(ASTRAL.repeat(500)).value()).hasSize(1000);
        assertRefused("a".repeat(501), "The reason for deletion must be 500 characters or fewer");
        assertRefused(ASTRAL.repeat(501), "The reason for deletion must be 500 characters or fewer");
        // Trimmed before it is counted.
        assertThat(DocumentDeletionReason.parse(" ".repeat(10) + "a".repeat(500) + " ").value()).hasSize(500);
    }

    @ParameterizedTest(name = "{index}")
    @ValueSource(strings = {"", " ", "   ", "\u3000", "\u2003 \u2003", "\t\n"})
    @DisplayName("missing, empty or whitespace-only text is refused")
    void refusesNothing(String raw) {
        assertRefused(raw, "Enter why this document is being deleted");
    }

    @Test
    @DisplayName("null is refused as missing")
    void refusesNull() {
        assertRefused(null, "Enter why this document is being deleted");
    }

    @ParameterizedTest(name = "{index}")
    @ValueSource(strings = {
        "Line\nbreak", "Carriage\rreturn", "A\ttab", "Nul\u0000byte", "Bell\u0007", "Delete\u007F",
        "C1\u0085next", "C1\u009Fend", "Line\u2028separator", "Paragraph\u2029separator",
        "Right\u202Eoverride", "Left\u202Aembed", "Isolate\u2066here", "Pop\u2069isolate", "Mark\u200Fright",
        "Mark\u200Eleft", "Arabic\u061Cmark",
        "Lone\uD83Dhigh", // escaped: malformed UTF-16
        "Lone\uDCC4low", // escaped: malformed UTF-16
        "Reversed\uDCC4\uD83Dpair"}) // escaped: malformed UTF-16
    @DisplayName("control characters, separators, bidirectional controls and malformed UTF-16 are refused")
    void refusesForbiddenCharacters(String raw) {
        assertRefused(raw, "The reason for deletion must not contain line breaks, tabs or control characters");
    }

    @Test
    @DisplayName("a trailing unpaired surrogate is refused, not trimmed or counted")
    void refusesTrailingLoneSurrogate() {
        String loneHigh = "Ends badly\uD83D"; // escaped: malformed UTF-16
        assertRefused(loneHigh, "The reason for deletion must not contain line breaks, tabs or control characters");
    }

    @Test
    @DisplayName("toString never prints the text, and isParsed is true only for a value that parses to itself")
    void redactedAndIsParsed() {
        DocumentDeletionReason reason = DocumentDeletionReason.parse("Contains Jane Doe's passport number");

        assertThat(reason.toString()).doesNotContain("Jane").isEqualTo("DocumentDeletionReason[redacted]");
        assertThat(reason).isEqualTo(DocumentDeletionReason.parse(" Contains Jane Doe's passport number "))
            .hasSameHashCodeAs(DocumentDeletionReason.parse("Contains Jane Doe's passport number"));
        assertThat(DocumentDeletionReason.isParsed("Duplicate")).isTrue();
        assertThat(DocumentDeletionReason.isParsed(" Duplicate")).isFalse();
        assertThat(DocumentDeletionReason.isParsed("Two\nlines")).isFalse();
        assertThat(DocumentDeletionReason.isParsed("")).isFalse();
        assertThat(DocumentDeletionReason.isParsed(null)).isFalse();
    }

    @Test
    @DisplayName("application deletion's note rule is unchanged: a one-word reason is still not a deletion note")
    void applicationNoteRuleUnchanged() {
        assertThatThrownBy(() -> DeletionNote.parse("Duplicate")).isInstanceOf(InvalidInputError.class);
    }

    private static void assertRefused(String raw, String message) {
        assertThatThrownBy(() -> DocumentDeletionReason.parse(raw))
            .isInstanceOfSatisfying(InvalidInputError.class, error -> {
                assertThat(error.getMessage()).isEqualTo(message);
                assertThat(error.errors()).extracting(FieldError::field).containsExactly("reason");
                if (raw != null && !raw.isBlank()) {
                    assertThat(error.getMessage()).doesNotContain(raw.strip());
                }
            });
    }
}
