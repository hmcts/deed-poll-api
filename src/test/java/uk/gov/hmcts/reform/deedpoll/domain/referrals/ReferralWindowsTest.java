package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ReferralWindowTimes;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ReferralWindows}, exhaustively and with no database: the seeded windows (07:00-10:30 and 14:00-14:30,
 * {@code Europe/London}) and a working-day rule of weekdays less a fixed list of England and Wales bank holidays.
 */
class ReferralWindowsTest {

    private static final ReferralWindowTimes SEEDED = new ReferralWindowTimes(
        LocalTime.of(7, 0), LocalTime.of(10, 30), LocalTime.of(14, 0), LocalTime.of(14, 30));

    /** GOV.UK's England and Wales dates for the cases below. */
    private static final Set<LocalDate> HOLIDAYS = Set.of(
        LocalDate.of(2026, 8, 31), LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 28),
        LocalDate.of(2027, 1, 1));

    private static final Set<DayOfWeek> WEEKEND = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    private static final Predicate<LocalDate> WORKING_DAY = date ->
        !WEEKEND.contains(date.getDayOfWeek()) && !HOLIDAYS.contains(date);

    /** A London local time as the instant it is; every date used here is unambiguous. */
    private static Instant london(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ReferralWindowTimes.ZONE).toInstant();
    }

    private static ReferralWindow window(String date, WindowSlot slot) {
        return new ReferralWindow(LocalDate.parse(date), slot);
    }

    @Nested
    @DisplayName("on a working day (Wednesday 28 October 2026)")
    class WorkingDay {

        @ParameterizedTest(name = "{0} counts towards {1} {2}")
        @CsvSource({
            "2026-10-28T00:00,      2026-10-28, AM",
            "2026-10-28T06:59,      2026-10-28, AM",
            "2026-10-28T07:00,      2026-10-28, AM",
            "2026-10-28T10:29,      2026-10-28, AM",
            "2026-10-28T10:29:59.999, 2026-10-28, AM",
            "2026-10-28T10:30,      2026-10-28, PM",
            "2026-10-28T10:31,      2026-10-28, PM",
            "2026-10-28T13:59,      2026-10-28, PM",
            "2026-10-28T14:00,      2026-10-28, PM",
            "2026-10-28T14:29,      2026-10-28, PM",
            "2026-10-28T14:29:59.999, 2026-10-28, PM",
            "2026-10-28T14:30,      2026-10-29, AM",
            "2026-10-28T14:31,      2026-10-29, AM",
            "2026-10-28T23:59,      2026-10-29, AM",
        })
        void eachBoundaryToTheMinute(String at, String date, WindowSlot slot) {
            assertThat(ReferralWindows.windowFor(london(at), SEEDED, WORKING_DAY)).isEqualTo(window(date, slot));
        }
    }

    @Nested
    @DisplayName("around days that are not working days")
    class NonWorkingDays {

        @ParameterizedTest(name = "{0} counts towards {1} {2}")
        @CsvSource({
            // A Friday afternoon after its window goes to Monday's morning.
            "2026-10-30T14:30, 2026-11-02, AM",
            "2026-10-30T14:29, 2026-10-30, PM",
            // A Saturday and a Sunday, at any time, go to Monday's morning.
            "2026-10-31T00:00, 2026-11-02, AM",
            "2026-10-31T09:00, 2026-11-02, AM",
            "2026-11-01T14:15, 2026-11-02, AM",
            "2026-11-01T23:59, 2026-11-02, AM",
            // A bank holiday (the summer one, a Monday) goes to the next working day, inside its windows too.
            "2026-08-31T09:00, 2026-09-01, AM",
            "2026-08-31T14:15, 2026-09-01, AM",
            // The Friday before it goes past the weekend and the holiday.
            "2026-08-28T14:30, 2026-09-01, AM",
            // Christmas to the second of January: the 25th (a Friday) and the 28th (Boxing Day's substitute) are
            // holidays, so Christmas Eve's afternoon and Christmas Day itself go to Tuesday the 29th.
            "2026-12-24T14:30, 2026-12-29, AM",
            "2026-12-25T10:00, 2026-12-29, AM",
            "2026-12-26T10:00, 2026-12-29, AM",
            "2026-12-28T10:00, 2026-12-29, AM",
            // New Year's Day (a Friday) and the weekend after it go to Monday the 4th.
            "2026-12-31T14:30, 2027-01-04, AM",
            "2027-01-01T08:00, 2027-01-04, AM",
            "2027-01-02T08:00, 2027-01-04, AM",
        })
        void goToTheNextWorkingDaysMorning(String at, String date, WindowSlot slot) {
            assertThat(ReferralWindows.windowFor(london(at), SEEDED, WORKING_DAY)).isEqualTo(window(date, slot));
        }

        @Test
        @DisplayName("refuses, rather than searching for ever, when no day in a year is a working day")
        void boundedSearch() {
            assertThatThrownBy(() -> ReferralWindows.windowFor(london("2026-10-31T09:00"), SEEDED, date -> false))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("in Europe/London, not UTC")
    class Zone {

        @Test
        @DisplayName("reads a summer moment in BST: 09:29 UTC is 10:29 in London, the morning; 09:30 UTC the afternoon")
        void summerTime() {
            assertThat(ReferralWindows.windowFor(Instant.parse("2026-09-01T09:29:00Z"), SEEDED, WORKING_DAY))
                .isEqualTo(window("2026-09-01", WindowSlot.AM));
            assertThat(ReferralWindows.windowFor(Instant.parse("2026-09-01T09:30:00Z"), SEEDED, WORKING_DAY))
                .isEqualTo(window("2026-09-01", WindowSlot.PM));
        }

        @Test
        @DisplayName("a window closes at its end time in London, BST or GMT")
        void closesAt() {
            assertThat(ReferralWindows.closesAt(window("2026-10-28", WindowSlot.AM), SEEDED))
                .isEqualTo(Instant.parse("2026-10-28T10:30:00Z"));
            assertThat(ReferralWindows.closesAt(window("2026-10-28", WindowSlot.PM), SEEDED))
                .isEqualTo(Instant.parse("2026-10-28T14:30:00Z"));
            assertThat(ReferralWindows.closesAt(window("2026-09-01", WindowSlot.AM), SEEDED))
                .isEqualTo(Instant.parse("2026-09-01T09:30:00Z"));
            assertThat(ReferralWindows.closesAt(window("2026-09-01", WindowSlot.PM), SEEDED))
                .isEqualTo(Instant.parse("2026-09-01T13:30:00Z"));
        }
    }

    /**
     * Both 2026 changes fall on a Sunday, and the seeded windows are nowhere near 01:00, so these use windows set in
     * the small hours and a rule that counts every day as a working day. The rules are {@code ZonedDateTime.of}'s:
     * a local time in the spring gap moves forward by the gap's length, and a local time in the autumn overlap
     * takes the earlier offset (BST). A moment is an {@code Instant}, so it always reads as one local time.
     */
    @Nested
    @DisplayName("across a clock change")
    class ClockChanges {

        private static final ReferralWindowTimes SMALL_HOURS = new ReferralWindowTimes(
            LocalTime.of(0, 15), LocalTime.of(1, 30), LocalTime.of(2, 0), LocalTime.of(3, 0));

        private static final Predicate<LocalDate> EVERY_DAY = date -> true;

        @Test
        @DisplayName("spring (29 March 2026): a window ending at 01:30, which does not exist, closes at 02:30 BST")
        void springForward() {
            assertThat(ReferralWindows.closesAt(window("2026-03-29", WindowSlot.AM), SMALL_HOURS))
                .isEqualTo(Instant.parse("2026-03-29T01:30:00Z"));
            // 00:59 GMT is before the morning's end; one minute later the clocks read 02:00 BST, the afternoon.
            assertThat(ReferralWindows.windowFor(Instant.parse("2026-03-29T00:59:00Z"), SMALL_HOURS, EVERY_DAY))
                .isEqualTo(window("2026-03-29", WindowSlot.AM));
            assertThat(ReferralWindows.windowFor(Instant.parse("2026-03-29T01:00:00Z"), SMALL_HOURS, EVERY_DAY))
                .isEqualTo(window("2026-03-29", WindowSlot.PM));
        }

        @Test
        @DisplayName("autumn (25 October 2026): a window ending at 01:30, which happens twice, closes at the first")
        void fallBack() {
            assertThat(ReferralWindows.closesAt(window("2026-10-25", WindowSlot.AM), SMALL_HOURS))
                .isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
            // 01:15 BST and, an hour later, 01:15 GMT both read as before the morning's end, so both are the morning.
            assertThat(ReferralWindows.windowFor(Instant.parse("2026-10-25T00:15:00Z"), SMALL_HOURS, EVERY_DAY))
                .isEqualTo(window("2026-10-25", WindowSlot.AM));
            assertThat(ReferralWindows.windowFor(Instant.parse("2026-10-25T01:15:00Z"), SMALL_HOURS, EVERY_DAY))
                .isEqualTo(window("2026-10-25", WindowSlot.AM));
            // 01:30 GMT is the morning's end the second time round, so the afternoon.
            assertThat(ReferralWindows.windowFor(Instant.parse("2026-10-25T01:30:00Z"), SMALL_HOURS, EVERY_DAY))
                .isEqualTo(window("2026-10-25", WindowSlot.PM));
        }
    }
}
