package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeletionNoteTest {

    private static final String SECRET = "Created twice by the scanning team";

    @Test
    @DisplayName("ordinary spaces are trimmed from both ends and inner spaces are kept")
    void trimsSpaces() {
        assertThat(DeletionNote.parse("   Created  in error  ").value()).isEqualTo("Created  in error");
    }

    @Test
    @DisplayName("10 and 500 characters are accepted, counted as code points after trimming")
    void boundaries() {
        assertThat(DeletionNote.parse("a".repeat(10)).value()).hasSize(10);
        assertThat(DeletionNote.parse(" " + "a".repeat(500) + " ").value()).hasSize(500);
        // 500 astral characters are 1,000 UTF-16 units and still 500 characters, as Postgres counts them.
        String astral = "😀".repeat(500);
        assertThat(DeletionNote.parse(astral).value()).isEqualTo(astral);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "          ", "too short", "  123456789  "})
    @DisplayName("blank and too-short notes are refused on the note field")
    void tooShort(String raw) {
        assertRefused(raw);
    }

    @Test
    @DisplayName("null and over-long notes are refused")
    void nullAndTooLong() {
        assertRefused(null);
        assertRefused("a".repeat(501));
        assertRefused("😀".repeat(501));
    }

    static Stream<String> controlled() {
        // Built from code units rather than escapes in literals: C0, DEL, C1, and each half of a surrogate pair alone.
        Stream<String> controls = IntStream.of(0x00, 0x09, 0x0a, 0x0d, 0x1f, 0x7f, 0x85, 0x9f)
            .mapToObj(unit -> List.of("Created" + (char) unit + " in error", (char) unit + "Created in error",
                                      "Created in error" + (char) unit))
            .flatMap(List::stream);
        String pair = "😀";
        return Stream.concat(controls, Stream.of("Created in " + pair.charAt(0) + " error",
                                                 "Created in error " + pair.charAt(1)));
    }

    @ParameterizedTest
    @MethodSource("controlled")
    @DisplayName("control characters anywhere, including at the ends, and unpaired surrogates are refused")
    void controls(String raw) {
        assertRefused(raw);
    }

    @Test
    @DisplayName("a refusal never repeats the note, and nor does toString")
    void neverEchoes() {
        assertThatThrownBy(() -> DeletionNote.parse(SECRET + "\n"))
            .isInstanceOfSatisfying(InvalidInputError.class, error -> {
                assertThat(error.getMessage()).doesNotContain("scanning");
                assertThat(error.errors()).extracting(FieldError::message)
                    .allSatisfy(message -> assertThat(message).doesNotContain("scanning"));
            });
        assertThat(DeletionNote.parse(SECRET).toString()).doesNotContain("scanning");
        assertThat(DeletionNote.parse(SECRET)).isEqualTo(DeletionNote.parse(" " + SECRET));
    }

    @Test
    @DisplayName("the fingerprint is stable, lower-case hex, millisecond-precise, and changes with every field")
    void fingerprint() {
        DeletionNote note = DeletionNote.parse(SECRET);
        LocalDateTime at = LocalDateTime.of(2026, 11, 18, 10, 15, 30, 123_456_789);
        String fingerprint = DeletionFingerprint.of(DeletionReason.DUPLICATE, note, at);

        assertThat(fingerprint).matches("^[0-9a-f]{64}$");
        assertThat(DeletionFingerprint.of(DeletionReason.DUPLICATE, DeletionNote.parse(SECRET + "  "),
                                          at.withNano(123_000_000))).isEqualTo(fingerprint);
        assertThat(DeletionFingerprint.of(DeletionReason.CREATED_IN_ERROR, note, at)).isNotEqualTo(fingerprint);
        assertThat(DeletionFingerprint.of(DeletionReason.DUPLICATE, DeletionNote.parse(SECRET + "."), at))
            .isNotEqualTo(fingerprint);
        assertThat(DeletionFingerprint.of(DeletionReason.DUPLICATE, note, at.plusNanos(1_000_000)))
            .isNotEqualTo(fingerprint);
    }

    @Test
    @DisplayName("a reason is one of exactly two names, matched exactly")
    void reasons() {
        assertThat(DeletionReason.values()).extracting(Enum::name).containsExactly("CREATED_IN_ERROR", "DUPLICATE");
        assertThat(DeletionReason.fromName("DUPLICATE")).contains(DeletionReason.DUPLICATE);
        assertThat(DeletionReason.fromName("duplicate")).isEmpty();
        assertThat(DeletionReason.fromName("VOID")).isEmpty();
        assertThat(DeletionReason.fromName(null)).isEmpty();
    }

    @Test
    @DisplayName("only Completed is terminal")
    void statuses() {
        assertThat(DeletionStatus.values()).extracting(Enum::name)
            .containsExactly("Pending", "InProgress", "AwaitingDisposal", "NeedsAttention", "Completed");
        for (DeletionStatus status : DeletionStatus.values()) {
            assertThat(status.isTerminal()).as(status.name()).isEqualTo(status == DeletionStatus.Completed);
        }
    }

    private static void assertRefused(String raw) {
        assertThatThrownBy(() -> DeletionNote.parse(raw))
            .isInstanceOfSatisfying(InvalidInputError.class, error -> assertThat(error.errors())
                .extracting(FieldError::field).containsExactly(DeletionNote.FIELD));
    }
}
