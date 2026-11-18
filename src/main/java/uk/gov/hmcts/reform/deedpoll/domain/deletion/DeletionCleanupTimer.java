package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link DeletionCleanup#run} on a timer, every {@code deedpoll.deletion.cleanup.interval}.
 *
 * <p>Absent unless {@code deedpoll.deletion.cleanup.enabled} is true, which it is nowhere by default: activation waits
 * for the rollout evidence (plan Task 9). Disabling it leaves every admitted request fenced and stored, with its
 * inventory and any claim it held; a lapsed claim is taken over by the next run once it is enabled again.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "deedpoll.deletion.cleanup.enabled", havingValue = "true")
public class DeletionCleanupTimer {

    private final DeletionCleanup cleanup;

    public DeletionCleanupTimer(DeletionCleanup cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(fixedDelayString = "${deedpoll.deletion.cleanup.interval}",
        initialDelayString = "${deedpoll.deletion.cleanup.interval}")
    public void run() {
        cleanup.run();
    }
}
