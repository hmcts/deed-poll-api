package uk.gov.hmcts.reform.deedpoll.domain.masters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.reform.deedpoll.domain.masters.MasterDigestWindowsTest.london;

/**
 * {@link MasterReminders}' pure parts (plan D3, step 3): the sending hours and the run's period key.
 */
class MasterReminderHoursTest {

    private static final Predicate<LocalDate> WEEKDAYS = day -> day.getDayOfWeek().getValue() < 6;

    @Test
    @DisplayName("a working day from 07:00 up to but not including 18:00, London time")
    void hours() {
        assertThat(MasterReminders.inSendingHours(london(2026, 11, 4, 6, 59), WEEKDAYS)).isFalse();
        assertThat(MasterReminders.inSendingHours(london(2026, 11, 4, 7, 0), WEEKDAYS)).isTrue();
        assertThat(MasterReminders.inSendingHours(london(2026, 11, 4, 17, 59), WEEKDAYS)).isTrue();
        assertThat(MasterReminders.inSendingHours(london(2026, 11, 4, 18, 0), WEEKDAYS)).isFalse();
    }

    @Test
    @DisplayName("never on a weekend or a bank holiday")
    void notOnANonWorkingDay() {
        assertThat(MasterReminders.inSendingHours(london(2026, 11, 7, 12, 0), WEEKDAYS)).isFalse();
        LocalDate christmas = LocalDate.of(2026, 12, 25);
        assertThat(christmas.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(MasterReminders.inSendingHours(london(2026, 12, 25, 12, 0),
                                                  WEEKDAYS.and(day -> !day.equals(christmas)))).isFalse();
    }

    @Test
    @DisplayName("the hours are London's in summer time too")
    void summerTime() {
        // 06:30 UTC is 07:30 BST; 17:30 UTC is 18:30 BST.
        assertThat(MasterReminders.inSendingHours(Instant.parse("2026-07-01T06:30:00Z"), WEEKDAYS)).isTrue();
        assertThat(MasterReminders.inSendingHours(Instant.parse("2026-07-01T17:30:00Z"), WEEKDAYS)).isFalse();
    }

    @Test
    @DisplayName("a run's key is its minute and the pod's run id, and fits the ledger's column")
    void periodKey() {
        UUID run = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String key = MasterReminders.periodKey(Instant.parse("2026-11-04T12:15:42.123Z"), run);

        assertThat(key).isEqualTo("2026-11-04T12:15:00Z:00000000-0000-0000-0000-000000000001");
        assertThat(key.length()).isLessThanOrEqualTo(64);
        assertThat(MasterReminders.periodKey(Instant.parse("2026-11-04T12:15:59Z"), run)).isEqualTo(key);
    }
}
