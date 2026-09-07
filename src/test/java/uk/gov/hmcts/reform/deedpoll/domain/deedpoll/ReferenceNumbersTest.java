package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.time.LocalDateTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Ports every test block in {@code reference/ts/deed-poll/reference-number.test.ts} that does not
 * need a database; the allocating blocks are {@link ReferenceNumberServiceTest}. The describe blocks
 * are kept as nested classes and their names as written, so the two files can be read side by side.
 *
 * <p>Three blocks could not port unchanged, and each is noted where it appears: TypeScript's
 * {@code undefined}, an invalid {@code Date}, and the reference's {@code null} returns.
 */
class ReferenceNumbersTest {

    @Nested
    @DisplayName("prefixForType")
    class PrefixForType {

        @Test
        @DisplayName("uses KBDR for Deed of Relinquishment (type 5)")
        void usesKbdrForDeedOfRelinquishment() {
            assertThat(ReferenceNumbers.prefixForType(DeedPollType.DeedOfRelinquishment)).isEqualTo("KBDR");
        }

        @ParameterizedTest
        @EnumSource(value = DeedPollType.class, names = "DeedOfRelinquishment", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("uses KBDP for every other type")
        void usesKbdpForEveryOtherType(DeedPollType type) {
            // The reference lists the other five by hand; enumerating the Java enum instead means a
            // seventh type added later is covered without anyone remembering to add it here.
            assertThat(ReferenceNumbers.prefixForType(type)).isEqualTo("KBDP");
        }

        @Test
        @DisplayName("uses KBDP when type is null")
        void usesKbdpWhenTypeIsNull() {
            // The reference also covers `undefined`, which Java does not distinguish from null.
            assertThat(ReferenceNumbers.prefixForType(null)).isEqualTo("KBDP");
        }
    }

    @Nested
    @DisplayName("formatSerial / compose")
    class FormatSerialAndCompose {

        @Test
        @DisplayName("zero-pads the serial to 5 digits")
        void zeroPadsTheSerialToFiveDigits() {
            assertThat(ReferenceNumbers.formatSerial(1)).isEqualTo("00001");
            assertThat(ReferenceNumbers.formatSerial(42)).isEqualTo("00042");
            assertThat(ReferenceNumbers.formatSerial(99999)).isEqualTo("99999");
        }

        @Test
        @DisplayName("composes {prefix}{year}-{serial}")
        void composesPrefixYearSerial() {
            assertThat(ReferenceNumbers.compose("KBDP", 2026, 1)).isEqualTo("KBDP2026-00001");
            assertThat(ReferenceNumbers.compose("KBDR", 2025, 123)).isEqualTo("KBDR2025-00123");
        }

        @Test
        @DisplayName("uses ASCII digits whatever the default locale's numbering system is")
        void usesAsciiDigitsWhateverTheDefaultLocaleIs() {
            // Not a reference block: `String(serial).padStart(5, "0")` cannot do anything else. Java's
            // `%d` can -- it substitutes the zero digit of Locale.getDefault(FORMAT) -- and the value here
            // is persisted, external-facing and permanent, so getting it wrong is not recoverable. Worse,
            // parse() uses `\d`, which is ASCII-only, so the class would stop being able to read back what
            // it had just written and a DP-to-DR flip would silently stop re-prefixing.
            Locale original = Locale.getDefault(Locale.Category.FORMAT);
            try {
                Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG-u-nu-arab"));

                String composed = ReferenceNumbers.compose("KBDP", 2026, 7);

                assertThat(composed).isEqualTo("KBDP2026-00007");
                assertThat(ReferenceNumbers.parse(composed)).isPresent();
            } finally {
                Locale.setDefault(Locale.Category.FORMAT, original);
            }
        }
    }

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("round-trips a composed reference")
        void roundTripsAComposedReference() {
            assertThat(ReferenceNumbers.parse("KBDP2026-00001"))
                .contains(new ReferenceNumbers.Parts("KBDP", 2026, 1));
            assertThat(ReferenceNumbers.parse("KBDR2024-01234"))
                .contains(new ReferenceNumbers.Parts("KBDR", 2024, 1234));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "",
            "KBDP2026-1",     // serial not 5 digits
            "XX2026-00001",   // bad prefix
            "legacy-ref-99"
        })
        @DisplayName("returns empty for malformed / legacy values")
        void returnsEmptyForMalformedValues(String reference) {
            assertThat(ReferenceNumbers.parse(reference)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for a null reference")
        void returnsEmptyForNull() {
            assertThat(ReferenceNumbers.parse(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("yearFromDateReceived")
    class YearFromDateReceived {

        @Test
        @DisplayName("takes the UTC year from Date Received")
        void takesTheUtcYearFromDateReceived() {
            assertThat(ReferenceNumbers.yearFromDateReceived(LocalDateTime.parse("2026-07-31T00:00:00")))
                .isEqualTo(2026);
            // 1 Jan just after midnight UTC stays in the new year
            assertThat(ReferenceNumbers.yearFromDateReceived(LocalDateTime.parse("2027-01-01T00:30:00")))
                .isEqualTo(2027);
        }

        @Test
        @DisplayName("throws when Date Received is missing")
        void throwsWhenDateReceivedIsMissing() {
            // The reference also rejects an invalid Date (`new Date("nonsense")`). A LocalDateTime
            // cannot hold a non-date, so null is the only rejection that survives the port.
            assertThatThrownBy(() -> ReferenceNumbers.yearFromDateReceived(null))
                .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("should throw InvalidInputError, so the route layer maps it to 400 rather than 500")
        void shouldThrowInvalidInputError() {
            // A bare error here reached apps/api's error middleware unrecognised and became a generic
            // "Internal server error" -- which is what POST /deed-polls with no Date Received returned.
            assertThatThrownBy(() -> ReferenceNumbers.yearFromDateReceived(null))
                .isInstanceOf(InvalidInputError.class)
                .hasMessage("Cannot allocate a reference number without a Date Received to derive the year");
        }

        @Test
        @DisplayName("should attribute the error to dateReceived, so a form can render it against that input")
        void shouldAttributeTheErrorToDateReceived() {
            InvalidInputError error = catchThrowableOfType(
                InvalidInputError.class, () -> ReferenceNumbers.yearFromDateReceived(null));

            assertThat(error.errors()).containsExactly(
                new FieldError("dateReceived", "Enter the date the application was received"));
        }
    }

    @Nested
    @DisplayName("reprefixForUpdate")
    class ReprefixForUpdate {

        @Test
        @DisplayName("re-prefixes KBDP->KBDR on a type flip, keeping year + serial")
        void reprefixesToRelinquishment() {
            assertThat(ReferenceNumbers.reprefixForUpdate("KBDP2026-00007", DeedPollType.DeedOfRelinquishment))
                .contains("KBDR2026-00007");
        }

        @Test
        @DisplayName("re-prefixes KBDR->KBDP flipping back to a non-relinquishment type")
        void reprefixesBackToDeedPoll() {
            assertThat(ReferenceNumbers.reprefixForUpdate("KBDR2026-00007", DeedPollType.Adult))
                .contains("KBDP2026-00007");
        }

        @Test
        @DisplayName("is a no-op (returns the same value) when type is unchanged in effect")
        void isANoOpWhenTypeIsUnchangedInEffect() {
            assertThat(ReferenceNumbers.reprefixForUpdate("KBDP2026-00007", DeedPollType.Adult))
                .contains("KBDP2026-00007");
        }

        @Test
        @DisplayName("returns empty for an unparseable existing reference")
        void returnsEmptyForAnUnparseableExistingReference() {
            assertThat(ReferenceNumbers.reprefixForUpdate("legacy", DeedPollType.Adult)).isEmpty();
            assertThat(ReferenceNumbers.reprefixForUpdate(null, DeedPollType.Adult)).isEmpty();
        }
    }
}
