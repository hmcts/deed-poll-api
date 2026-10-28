package uk.gov.hmcts.reform.deedpoll.domain.masters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link MasterReminders} on a timer, every {@code deedpoll.masters.reminder-interval-check} (fifteen minutes), so a
 * reminder goes within that long of falling due, or of the first sending hour after it.
 *
 * <p>Never throws into the scheduler: a failure is one WARN naming the exception class only, never its message
 * (which can carry SQL and values), and the next run tries again, claiming what this one did not commit.
 * {@code deedpoll.masters.emails-enabled=false} removes the bean, which is how the Postgres-backed tests keep a timer
 * off their fixtures.
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "deedpoll.masters.emails-enabled", havingValue = "true", matchIfMissing = true)
public class MasterReminderTimer {

    private final MasterReminders reminders;

    public MasterReminderTimer(MasterReminders reminders) {
        this.reminders = reminders;
    }

    @Scheduled(fixedDelayString = "${deedpoll.masters.reminder-interval-check:PT15M}",
        initialDelayString = "${deedpoll.masters.reminder-interval-check:PT15M}")
    public void run() {
        try {
            reminders.run();
        } catch (RuntimeException failure) {
            log.warn("Master reminder job failed; the next run will try again ({})", failure.getClass().getName());
        }
    }
}
