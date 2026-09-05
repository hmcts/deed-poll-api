package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/deed-poll/computed.test.ts} -- the derived day counts,
 * from the source calculated fields {@code deedpoll_daysreceivedtoenrolled} and
 * {@code deedpoll_daysreceivedtoreturned}.
 */
class ComputedTest {

    private static LocalDateTime at(String iso) {
        return LocalDateTime.parse(iso);
    }

    private static DeedPoll caseWith(LocalDateTime received, LocalDateTime enroled, LocalDateTime returned) {
        DeedPoll record = new DeedPoll();
        record.setDateReceived(received);
        record.setDateEnroled(enroled);
        record.setDateReturned(returned);
        return record;
    }

    private static Stream<Arguments> unreadableEnds() {
        return Stream.of(
            Arguments.of("both null", null, null),
            Arguments.of("start missing", null, at("2026-07-31T00:00:00")),
            Arguments.of("end missing", at("2026-07-01T00:00:00"), null),
            Arguments.of("unparseable start", "not-a-date", at("2026-07-31T00:00:00")),
            Arguments.of("unparseable end", at("2026-07-01T00:00:00"), "not-a-date"));
    }

    @Nested
    @DisplayName("daysBetween")
    class DaysBetween {

        @Test
        @DisplayName("counts whole days between two dates")
        void countsWholeDaysBetweenTwoDates() {
            assertThat(Computed.daysBetween(at("2026-07-01T00:00:00"), at("2026-07-31T00:00:00"))).isEqualTo(30);
        }

        @Test
        @DisplayName("returns 0 for the same day")
        void returnsZeroForTheSameDay() {
            assertThat(Computed.daysBetween(at("2026-07-15T00:00:00"), at("2026-07-15T00:00:00"))).isZero();
        }

        @Test
        @DisplayName("ignores clock time so a same-day pair is 0, not a rounded 1")
        void ignoresClockTime() {
            // These columns are date-only in the source; a stored time must not leak in.
            assertThat(Computed.daysBetween(at("2026-07-15T23:00:00"), at("2026-07-15T01:00:00"))).isZero();
        }

        @Test
        @DisplayName("counts across a month boundary")
        void countsAcrossAMonthBoundary() {
            assertThat(Computed.daysBetween(at("2026-07-30T00:00:00"), at("2026-08-02T00:00:00"))).isEqualTo(3);
        }

        @Test
        @DisplayName("counts across a leap day")
        void countsAcrossALeapDay() {
            assertThat(Computed.daysBetween(at("2028-02-28T00:00:00"), at("2028-03-01T00:00:00"))).isEqualTo(2);
        }

        @Test
        @DisplayName("goes negative when the end precedes the start")
        void goesNegativeWhenTheEndPrecedesTheStart() {
            // Faithful to DateDiff: a data-entry error shows as negative rather than being silently
            // clamped, so it stays visible to the caseworker.
            assertThat(Computed.daysBetween(at("2026-07-31T00:00:00"), at("2026-07-01T00:00:00"))).isEqualTo(-30);
        }

        @Test
        @DisplayName("accepts ISO strings as well as dates")
        void acceptsIsoStringsAsWellAsDates() {
            assertThat(Computed.daysBetween("2026-07-01T00:00:00Z", "2026-07-11T00:00:00Z")).isEqualTo(10);
        }

        @ParameterizedTest(name = "returns null when {0}")
        @MethodSource("uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ComputedTest#unreadableEnds")
        @DisplayName("returns null when either end is missing or unreadable")
        void returnsNullWhenEitherEndIsUnreadable(String label, Object from, Object to) {
            // The reference also covers an `undefined` start, which Java does not distinguish from null.
            assertThat(Computed.daysBetween(from, to)).isNull();
        }
    }

    @Nested
    @DisplayName("computedFor")
    class ComputedFor {

        @Test
        @DisplayName("computes both counts independently")
        void computesBothCountsIndependently() {
            assertThat(Computed.computedFor(caseWith(
                at("2026-07-01T00:00:00"),
                at("2026-07-15T00:00:00"),
                at("2026-07-20T00:00:00"))))
                .isEqualTo(new ComputedFields(14, 19));
        }

        @Test
        @DisplayName("nulls the count whose end date is unset")
        void nullsTheCountWhoseEndDateIsUnset() {
            assertThat(Computed.computedFor(caseWith(at("2026-07-01T00:00:00"), at("2026-07-15T00:00:00"), null)))
                .isEqualTo(new ComputedFields(14, null));
        }

        @Test
        @DisplayName("nulls both when the case has no received date")
        void nullsBothWhenTheCaseHasNoReceivedDate() {
            assertThat(Computed.computedFor(caseWith(null, at("2026-07-15T00:00:00"), null)))
                .isEqualTo(new ComputedFields(null, null));
        }
    }

    @Nested
    @DisplayName("withComputed")
    class WithComputed {

        @Test
        @DisplayName("adds the derived fields without mutating or dropping the record")
        void addsTheDerivedFieldsWithoutMutatingTheRecord() {
            DeedPoll record = caseWith(at("2026-07-01T00:00:00"), at("2026-07-08T00:00:00"), null);
            record.setId("abc");

            DeedPollWithComputed out = Computed.withComputed(record);

            assertThat(out.deedPoll().getId()).isEqualTo("abc");
            assertThat(out.computed().daysReceivedToEnroled()).isEqualTo(7);
            assertThat(out.computed().daysReceivedToReturned()).isNull();
            // The reference asserts the derived keys were not spread onto the input. Here the entity is
            // paired with them rather than copied, so the assertion is that it is the SAME instance and
            // has grown nothing -- an entity has no key to sprout.
            assertThat(out.deedPoll()).isSameAs(record);
            assertThat(DeedPollFields.readAll(record)).doesNotContainKey("daysReceivedToEnroled");
        }
    }
}
