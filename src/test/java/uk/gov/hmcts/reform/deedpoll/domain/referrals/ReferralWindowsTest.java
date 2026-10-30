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
import java.util.Optional;
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
    @DisplayName("in Europe/London, not UTC")
    class Zone {

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
            // 00:59 GMT is inside the morning; one minute later the clocks read 02:00 BST, the afternoon.
            assertThat(ReferralWindows.openAt(Instant.parse("2026-03-29T00:59:00Z"), SMALL_HOURS, EVERY_DAY))
                .contains(window("2026-03-29", WindowSlot.AM));
            assertThat(ReferralWindows.openAt(Instant.parse("2026-03-29T01:00:00Z"), SMALL_HOURS, EVERY_DAY))
                .contains(window("2026-03-29", WindowSlot.PM));
        }

        @Test
        @DisplayName("autumn (25 October 2026): a window ending at 01:30, which happens twice, closes at the first")
        void fallBack() {
            assertThat(ReferralWindows.closesAt(window("2026-10-25", WindowSlot.AM), SMALL_HOURS))
                .isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
            // 01:15 BST and, an hour later, 01:15 GMT both read as before the morning's end, so both are the morning.
            assertThat(ReferralWindows.openAt(Instant.parse("2026-10-25T00:15:00Z"), SMALL_HOURS, EVERY_DAY))
                .contains(window("2026-10-25", WindowSlot.AM));
            assertThat(ReferralWindows.openAt(Instant.parse("2026-10-25T01:15:00Z"), SMALL_HOURS, EVERY_DAY))
                .contains(window("2026-10-25", WindowSlot.AM));
            // 01:30 GMT is the morning's end the second time round, so no window until the afternoon's 02:00.
            assertThat(ReferralWindows.openAt(Instant.parse("2026-10-25T01:30:00Z"), SMALL_HOURS, EVERY_DAY)).isEmpty();
            assertThat(ReferralWindows.nextToOpen(Instant.parse("2026-10-25T01:30:00Z"), SMALL_HOURS, EVERY_DAY))
                .isEqualTo(window("2026-10-25", WindowSlot.PM));
        }
    }

    /**
     * Plan F1's question: is a window open now, and which opens next. Each boundary minute, a weekend, a bank holiday
     * and both 2026 clock changes, on the seeded windows.
     */
    @Nested
    @DisplayName("which window is open, and which opens next (plan F1)")
    class OpenAndNext {

        @ParameterizedTest(name = "at {0} the open window is [{1} {2}] and the next {3} {4}")
        @CsvSource({
            // Wednesday 28 October 2026, every boundary to the minute and below.
            "2026-10-28T00:00,          ,           ,   2026-10-28, AM",
            "2026-10-28T06:59,          ,           ,   2026-10-28, AM",
            "2026-10-28T06:59:59.999,   ,           ,   2026-10-28, AM",
            "2026-10-28T07:00,          2026-10-28, AM, 2026-10-28, PM",
            "2026-10-28T07:01,          2026-10-28, AM, 2026-10-28, PM",
            "2026-10-28T10:29,          2026-10-28, AM, 2026-10-28, PM",
            "2026-10-28T10:29:59.999,   2026-10-28, AM, 2026-10-28, PM",
            "2026-10-28T10:30,          ,           ,   2026-10-28, PM",
            "2026-10-28T13:59,          ,           ,   2026-10-28, PM",
            "2026-10-28T14:00,          2026-10-28, PM, 2026-10-29, AM",
            "2026-10-28T14:29,          2026-10-28, PM, 2026-10-29, AM",
            "2026-10-28T14:29:59.999,   2026-10-28, PM, 2026-10-29, AM",
            "2026-10-28T14:30,          ,           ,   2026-10-29, AM",
            "2026-10-28T23:59,          ,           ,   2026-10-29, AM",
            // Friday: inside its afternoon and after it, the next is Monday's morning.
            "2026-10-30T14:15,          2026-10-30, PM, 2026-11-02, AM",
            "2026-10-30T14:30,          ,           ,   2026-11-02, AM",
            // A weekend is never open, even at a window's times.
            "2026-10-31T07:00,          ,           ,   2026-11-02, AM",
            "2026-11-01T14:15,          ,           ,   2026-11-02, AM",
            // A bank holiday (Monday 31 August) is never open either; the next is Tuesday's morning.
            "2026-08-31T09:00,          ,           ,   2026-09-01, AM",
            "2026-08-31T14:15,          ,           ,   2026-09-01, AM",
            "2026-08-31T06:00,          ,           ,   2026-09-01, AM",
            // Christmas Eve's afternoon is followed by Tuesday the 29th's morning.
            "2026-12-24T14:15,          2026-12-24, PM, 2026-12-29, AM",
        })
        void eachBoundary(String at, String openDate, WindowSlot openSlot, String nextDate, WindowSlot nextSlot) {
            assertThat(ReferralWindows.openAt(london(at), SEEDED, WORKING_DAY))
                .isEqualTo(openDate == null ? Optional.empty()
                    : Optional.of(window(openDate, openSlot)));
            assertThat(ReferralWindows.nextToOpen(london(at), SEEDED, WORKING_DAY))
                .isEqualTo(window(nextDate, nextSlot));
        }

        @Test
        @DisplayName("a window opens at its start time in London, BST or GMT")
        void opensAt() {
            assertThat(ReferralWindows.opensAt(window("2026-10-28", WindowSlot.AM), SEEDED))
                .isEqualTo(Instant.parse("2026-10-28T07:00:00Z"));
            assertThat(ReferralWindows.opensAt(window("2026-10-28", WindowSlot.PM), SEEDED))
                .isEqualTo(Instant.parse("2026-10-28T14:00:00Z"));
            assertThat(ReferralWindows.opensAt(window("2026-09-01", WindowSlot.AM), SEEDED))
                .isEqualTo(Instant.parse("2026-09-01T06:00:00Z"));
            assertThat(ReferralWindows.opensAt(window("2026-09-01", WindowSlot.PM), SEEDED))
                .isEqualTo(Instant.parse("2026-09-01T13:00:00Z"));
        }

        @Test
        @DisplayName("spring (29 March 2026): the Friday before reads GMT and the Monday after BST")
        void springForward() {
            // Friday 27 March: 07:00 GMT is 07:00Z.
            assertThat(ReferralWindows.openAt(Instant.parse("2026-03-27T06:59:00Z"), SEEDED, WORKING_DAY)).isEmpty();
            assertThat(ReferralWindows.openAt(Instant.parse("2026-03-27T07:00:00Z"), SEEDED, WORKING_DAY))
                .contains(window("2026-03-27", WindowSlot.AM));
            // After Friday's afternoon the next is Monday 30 March's morning, which opens at 07:00 BST, 06:00Z.
            ReferralWindow next =
                ReferralWindows.nextToOpen(Instant.parse("2026-03-27T14:30:00Z"), SEEDED, WORKING_DAY);
            assertThat(next).isEqualTo(window("2026-03-30", WindowSlot.AM));
            assertThat(ReferralWindows.opensAt(next, SEEDED)).isEqualTo(Instant.parse("2026-03-30T06:00:00Z"));
            assertThat(ReferralWindows.openAt(Instant.parse("2026-03-30T06:00:00Z"), SEEDED, WORKING_DAY))
                .contains(window("2026-03-30", WindowSlot.AM));
            assertThat(ReferralWindows.openAt(Instant.parse("2026-03-30T05:59:00Z"), SEEDED, WORKING_DAY)).isEmpty();
        }

        @Test
        @DisplayName("autumn (25 October 2026): the Friday before reads BST and the Monday after GMT")
        void fallBack() {
            // Friday 23 October: 07:00 BST is 06:00Z, and the afternoon ends at 14:30 BST, 13:30Z.
            assertThat(ReferralWindows.openAt(Instant.parse("2026-10-23T06:00:00Z"), SEEDED, WORKING_DAY))
                .contains(window("2026-10-23", WindowSlot.AM));
            assertThat(ReferralWindows.openAt(Instant.parse("2026-10-23T13:30:00Z"), SEEDED, WORKING_DAY)).isEmpty();
            ReferralWindow next =
                ReferralWindows.nextToOpen(Instant.parse("2026-10-23T13:30:00Z"), SEEDED, WORKING_DAY);
            assertThat(next).isEqualTo(window("2026-10-26", WindowSlot.AM));
            assertThat(ReferralWindows.opensAt(next, SEEDED)).isEqualTo(Instant.parse("2026-10-26T07:00:00Z"));
            // Monday 26 October: 06:30Z is 06:30 GMT, before the morning.
            assertThat(ReferralWindows.openAt(Instant.parse("2026-10-26T06:30:00Z"), SEEDED, WORKING_DAY)).isEmpty();
            assertThat(ReferralWindows.openAt(Instant.parse("2026-10-26T07:00:00Z"), SEEDED, WORKING_DAY))
                .contains(window("2026-10-26", WindowSlot.AM));
        }

        @Test
        @DisplayName("refuses, rather than searching for ever, when no day in a year is a working day")
        void boundedSearch() {
            assertThatThrownBy(() -> ReferralWindows.nextToOpen(london("2026-10-31T09:00"), SEEDED, date -> false))
                .isInstanceOf(IllegalStateException.class);
            assertThat(ReferralWindows.openAt(london("2026-10-28T09:00"), SEEDED, date -> false)).isEmpty();
        }
    }
}
