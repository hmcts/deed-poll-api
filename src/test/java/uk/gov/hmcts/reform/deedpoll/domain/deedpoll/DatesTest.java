package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one place that decides whether a loosely-typed value is a date.
 *
 * <p>The reference does this inline in four files as {@code new Date(String(raw))}, so it has no test block of
 * its own -- but the four call sites depend on every accepted shape, and an unreadable value here becomes
 * {@code null}, which is to say a silently cleared date rather than a failure. So each accepted shape is
 * pinned: a defect on one of them does not announce itself.
 */
class DatesTest {

    @Nested
    @DisplayName("already a date")
    class AlreadyADate {

        @Test
        @DisplayName("keeps a LocalDateTime as it is, since the mapped columns are UTC by construction")
        void keepsALocalDateTime() {
            LocalDateTime value = LocalDateTime.parse("2026-07-01T09:30:00");

            assertThat(Dates.toDateTime(value)).isEqualTo(value);
        }

        @Test
        @DisplayName("reads a LocalDate as the start of that day")
        void readsALocalDate() {
            assertThat(Dates.toDateTime(LocalDate.parse("2026-07-01")))
                .isEqualTo(LocalDateTime.parse("2026-07-01T00:00:00"));
        }

        @Test
        @DisplayName("converts an Instant to UTC wall time")
        void convertsAnInstant() {
            assertThat(Dates.toDateTime(Instant.parse("2026-07-01T09:30:00Z")))
                .isEqualTo(LocalDateTime.parse("2026-07-01T09:30:00"));
        }

        @Test
        @DisplayName("applies an OffsetDateTime's offset rather than discarding it")
        void appliesAnOffset() {
            // +01:00 at 09:30 is 08:30 UTC. Keeping the local part and dropping the offset would store an
            // hour that never happened.
            assertThat(Dates.toDateTime(OffsetDateTime.parse("2026-07-01T09:30:00+01:00")))
                .isEqualTo(LocalDateTime.parse("2026-07-01T08:30:00"));
        }

        @Test
        @DisplayName("applies a ZonedDateTime's zone the same way")
        void appliesAZone() {
            assertThat(Dates.toDateTime(
                ZonedDateTime.parse("2026-07-01T09:30:00+02:00").withZoneSameInstant(ZoneOffset.ofHours(2))))
                .isEqualTo(LocalDateTime.parse("2026-07-01T07:30:00"));
        }
    }

    @Nested
    @DisplayName("text")
    class Text {

        @Test
        @DisplayName("reads an offset date-time, converting to UTC")
        void readsAnOffsetDateTime() {
            assertThat(Dates.toDateTime("2026-07-01T09:30:00Z"))
                .isEqualTo(LocalDateTime.parse("2026-07-01T09:30:00"));
            assertThat(Dates.toDateTime("2026-07-01T09:30:00+01:00"))
                .isEqualTo(LocalDateTime.parse("2026-07-01T08:30:00"));
        }

        @Test
        @DisplayName("reads a date-time with no offset as already UTC")
        void readsALocalDateTimeText() {
            // What the schema accepts and leaves as a string -- the second fallback branch, which nothing
            // else in the suite reaches.
            assertThat(Dates.toDateTime("2026-07-01T09:30:00"))
                .isEqualTo(LocalDateTime.parse("2026-07-01T09:30:00"));
        }

        @Test
        @DisplayName("reads a bare calendar date, which is what a GOV.UK date input submits")
        void readsABareDate() {
            assertThat(Dates.toDateTime("2026-07-01"))
                .isEqualTo(LocalDateTime.parse("2026-07-01T00:00:00"));
        }

        @Test
        @DisplayName("trims surrounding whitespace rather than failing on it")
        void trimsWhitespace() {
            assertThat(Dates.toDateTime("  2026-07-01  "))
                .isEqualTo(LocalDateTime.parse("2026-07-01T00:00:00"));
        }
    }

    @Nested
    @DisplayName("nothing readable")
    class NothingReadable {

        @Test
        @DisplayName("gives null for null, blank text and text that is not a date")
        void givesNullForAnythingUnreadable() {
            assertThat(Dates.toDateTime(null)).isNull();
            assertThat(Dates.toDateTime("")).isNull();
            assertThat(Dates.toDateTime("   ")).isNull();
            assertThat(Dates.toDateTime("not-a-date")).isNull();
            assertThat(Dates.toDateTime("01/07/2026")).isNull();
        }

        @Test
        @DisplayName("gives null for a well-shaped date that is not a real one")
        void givesNullForACalendarImpossibleDate() {
            assertThat(Dates.toDateTime("2026-02-30")).isNull();
        }

        @Test
        @DisplayName("gives null for a value that is not a date at all")
        void givesNullForANonDate() {
            assertThat(Dates.toDateTime(42)).isNull();
        }
    }
}
