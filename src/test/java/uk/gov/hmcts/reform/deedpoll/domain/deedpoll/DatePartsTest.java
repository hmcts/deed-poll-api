package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/deed-poll/date-parts.test.ts}.
 *
 * <p>{@code composeDateParts} returns an {@link java.util.Optional} here where the reference returns
 * {@code string | null}, so the blocks that assert {@code toBeNull()} assert {@code isEmpty()} instead --
 * the same deliberate deviation {@code ReferenceNumbersTest} records for {@code parse}.
 */
class DatePartsTest {

    private static Map<String, Object> body(Object... keysAndValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            body.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return body;
    }

    @Nested
    @DisplayName("composeDateParts")
    class ComposeDateParts {

        @Test
        @DisplayName("composes a UTC midnight ISO string")
        void composesAUtcMidnightIsoString() {
            assertThat(DateParts.composeDateParts("1", "3", "2026")).contains("2026-03-01T00:00:00.000Z");
        }

        @Test
        @DisplayName("uses UTC, so a calendar date does not shift with the server's timezone")
        void usesUtcSoACalendarDateDoesNotShift() {
            // These are calendar dates with no time component. Local-time construction would move them a
            // day either side of midnight depending on where the process happens to run.
            assertThat(DateParts.composeDateParts("1", "1", "2026")).contains("2026-01-01T00:00:00.000Z");
            assertThat(DateParts.composeDateParts("31", "12", "2026")).contains("2026-12-31T00:00:00.000Z");
        }

        @Test
        @DisplayName("returns empty when any part is missing, since a partial date is not a date")
        void returnsEmptyWhenAnyPartIsMissing() {
            assertThat(DateParts.composeDateParts("", "3", "2026")).isEmpty();
            assertThat(DateParts.composeDateParts("1", "", "2026")).isEmpty();
            assertThat(DateParts.composeDateParts("1", "3", "")).isEmpty();
        }

        @Test
        @DisplayName("returns empty for a value that is not a real date")
        void returnsEmptyForAValueThatIsNotARealDate() {
            assertThat(DateParts.composeDateParts("abc", "def", "ghi")).isEmpty();
        }

        @Test
        @DisplayName("rolls an impossible day over rather than rejecting it, as the reference does")
        void rollsAnImpossibleDayOver() {
            // Documented rather than endorsed: 31 February becomes 3 March. collectDateErrors is what
            // rejects it before this is reached on the form path, which is why this is not a bug here.
            assertThat(DateParts.composeDateParts("31", "2", "2026")).contains("2026-03-03T00:00:00.000Z");
        }
    }

    @Nested
    @DisplayName("splitDateParts")
    class SplitDateParts {

        @Test
        @DisplayName("splits a date into day, month and year as strings")
        void splitsADateIntoParts() {
            assertThat(DateParts.splitDateParts(LocalDateTime.parse("2026-03-01T00:00:00")))
                .isEqualTo(new DateParts("1", "3", "2026"));
        }

        @Test
        @DisplayName("splits an ISO string as well as a date: the database returns one, forms post the other")
        void splitsAnIsoStringAsWellAsADate() {
            assertThat(DateParts.splitDateParts("2026-12-31T00:00:00.000Z"))
                .isEqualTo(new DateParts("31", "12", "2026"));
        }

        @Test
        @DisplayName("returns empty parts for an unset value: most of a case dates are null for most of its life")
        void returnsEmptyPartsForAnUnsetValue() {
            // The reference also covers `undefined`, which Java does not distinguish from null.
            assertThat(DateParts.splitDateParts(null)).isEqualTo(DateParts.empty());
            assertThat(DateParts.splitDateParts("")).isEqualTo(DateParts.empty());
        }

        @Test
        @DisplayName("returns empty parts for an unparseable value rather than throwing")
        void returnsEmptyPartsForAnUnparseableValue() {
            assertThat(DateParts.splitDateParts("not a date")).isEqualTo(DateParts.empty());
        }

        @Test
        @DisplayName("round-trips through composeDateParts")
        void roundTripsThroughComposeDateParts() {
            DateParts parts = DateParts.splitDateParts("2026-07-04T00:00:00.000Z");
            assertThat(DateParts.composeDateParts(parts.day(), parts.month(), parts.year()))
                .contains("2026-07-04T00:00:00.000Z");
        }

        @Test
        @DisplayName("does not share the empty value between calls")
        void doesNotShareTheEmptyValueBetweenCalls() {
            // The reference mutates the returned object and checks the next call is unaffected, because
            // its EMPTY is a shared object literal it has to spread. A record cannot be mutated, so the
            // hazard is absent by construction and this asserts what the reference was protecting: two
            // calls agree, and neither can be edited.
            assertThat(DateParts.splitDateParts(null)).isEqualTo(DateParts.splitDateParts(null));
            assertThat(DateParts.class.isRecord()).isTrue();
        }
    }

    @Nested
    @DisplayName("submittedDateParts")
    class SubmittedDateParts {

        @Test
        @DisplayName("reads the parts back verbatim, so a rejected value stays on screen")
        void readsThePartsBackVerbatim() {
            Map<String, Object> submitted = body(
                "dateReceived-day", "99",
                "dateReceived-month", "13",
                "dateReceived-year", "abcd");

            assertThat(DateParts.submittedDateParts("dateReceived", submitted))
                .isEqualTo(new DateParts("99", "13", "abcd"));
        }

        @Test
        @DisplayName("does not normalise or reject, which is the whole point of it")
        void doesNotNormaliseOrReject() {
            // If this composed and re-split, the offending 99 would vanish and the caseworker would face
            // an error against a field that looks fine.
            assertThat(DateParts.submittedDateParts("d", body("d-day", "99", "d-month", "1", "d-year", "2026")).day())
                .isEqualTo("99");
        }

        @Test
        @DisplayName("yields empty strings for absent or non-string parts")
        void yieldsEmptyStringsForAbsentOrNonStringParts() {
            assertThat(DateParts.submittedDateParts("dateReceived", Map.of())).isEqualTo(DateParts.empty());
            assertThat(DateParts.submittedDateParts("d", body("d-day", List.of("1"), "d-month", 3, "d-year", null)))
                .isEqualTo(DateParts.empty());
        }

        @Test
        @DisplayName("reads only the named field's parts")
        void readsOnlyTheNamedFieldsParts() {
            Map<String, Object> submitted = body(
                "dateReceived-day", "1",
                "dateEnroled-day", "2",
                "dateReceived-month", "3",
                "dateReceived-year", "2026");

            assertThat(DateParts.submittedDateParts("dateReceived", submitted))
                .isEqualTo(new DateParts("1", "3", "2026"));
        }
    }
}
