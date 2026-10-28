package uk.gov.hmcts.reform.deedpoll.domain.masters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link MasterDigests} on a timer, every {@code deedpoll.masters.digest-interval} (five minutes), so a digest goes
 * within that long of its window closing.
 *
 * <p>Never throws into the scheduler: a failure is one WARN naming the exception class only, never its message
 * (which can carry SQL and values), and the next run tries again, from the window it did not commit.
 * {@code deedpoll.masters.emails-enabled=false} removes the bean, which is how the Postgres-backed tests keep a timer
 * off their fixtures.
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "deedpoll.masters.emails-enabled", havingValue = "true", matchIfMissing = true)
public class MasterDigestTimer {

    private final MasterDigests digests;

    public MasterDigestTimer(MasterDigests digests) {
        this.digests = digests;
    }

    @Scheduled(fixedDelayString = "${deedpoll.masters.digest-interval:PT5M}",
        initialDelayString = "${deedpoll.masters.digest-interval:PT5M}")
    public void run() {
        try {
            digests.run();
        } catch (RuntimeException failure) {
            log.warn("Master digest job failed; the next run will try again ({})", failure.getClass().getName());
        }
    }
}
