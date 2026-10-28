package uk.gov.hmcts.reform.deedpoll.domain.masters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralWindow;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ReferralWindowTimes;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MasterDigests#closedWindows}, with no database: which windows a digest run looks at.
 */
class MasterDigestWindowsTest {

    private static final ReferralWindowTimes WINDOWS = new ReferralWindowTimes(
        LocalTime.of(7, 0), LocalTime.of(10, 30), LocalTime.of(14, 0), LocalTime.of(14, 30));

    private static final LocalDate CHRISTMAS = LocalDate.of(2026, 12, 25);

    private static final Predicate<LocalDate> WORKING_DAY = day -> day.getDayOfWeek() != DayOfWeek.SATURDAY
        && day.getDayOfWeek() != DayOfWeek.SUNDAY && !day.equals(CHRISTMAS);

    @Test
    @DisplayName("a window is closed from its end time exactly, and not a minute before")
    void closesAtItsEnd() {
        assertThat(MasterDigests.closedWindows(london(2026, 11, 4, 10, 29), WINDOWS, WORKING_DAY))
            .doesNotContain(new ReferralWindow(LocalDate.of(2026, 11, 4), WindowSlot.AM));
        assertThat(MasterDigests.closedWindows(london(2026, 11, 4, 10, 30), WINDOWS, WORKING_DAY))
            .contains(new ReferralWindow(LocalDate.of(2026, 11, 4), WindowSlot.AM));
    }

    @Test
    @DisplayName("looks back 24 hours, oldest first, and no further")
    void looksBackADay() {
        assertThat(MasterDigests.closedWindows(london(2026, 11, 5, 11, 31), WINDOWS, WORKING_DAY)).containsExactly(
            new ReferralWindow(LocalDate.of(2026, 11, 4), WindowSlot.PM),
            new ReferralWindow(LocalDate.of(2026, 11, 5), WindowSlot.AM));
    }

    @Test
    @DisplayName("a Saturday and a bank holiday have no window")
    void noWindowOnANonWorkingDay() {
        assertThat(MasterDigests.closedWindows(london(2026, 11, 7, 23, 59), WINDOWS, WORKING_DAY)).isEmpty();
        assertThat(MasterDigests.closedWindows(london(2026, 12, 25, 23, 59), WINDOWS, WORKING_DAY)).isEmpty();
    }

    @Test
    @DisplayName("Monday morning looks back over the weekend to nothing older than a day")
    void mondayMorning() {
        assertThat(MasterDigests.closedWindows(london(2026, 11, 9, 10, 35), WINDOWS, WORKING_DAY)).containsExactly(
            new ReferralWindow(LocalDate.of(2026, 11, 9), WindowSlot.AM));
    }

    @Test
    @DisplayName("reads the window times in Europe/London across the clocks going forward")
    void summerTime() {
        // 10:30 BST is 09:30Z.
        Instant justAfter = Instant.parse("2027-04-01T09:31:00Z");
        assertThat(MasterDigests.closedWindows(justAfter, WINDOWS, WORKING_DAY))
            .contains(new ReferralWindow(LocalDate.of(2027, 4, 1), WindowSlot.AM));
    }

    static Instant london(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ReferralWindowTimes.ZONE).toInstant();
    }
}
