package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The application deletion worker, {@code deedpoll.deletion.cleanup} (plan 20261118-dm-application-deletion, Task 5).
 *
 * <p><strong>Off by default, and switching it on is part of the rollout, not a default to flip.</strong> With it off,
 * an admitted request stays {@code Pending}: fenced, stored and recoverable, with nothing removed. The CCD half has a
 * switch of its own, the SDK's {@code ccd.decentralised-runtime.retain-and-dispose.mode}, also off by default.
 *
 * @param enabled      whether the timer runs {@link DeletionCleanup#run}
 * @param interval     the timer's delay between runs
 * @param batchSize    the most requests one run claims
 * @param lease        how long a claim lasts before another pod may take the request over
 * @param maxAttempts  the tries a filesystem document gets before the request needs attention
 * @param retryBase    the first retry's delay, doubled per try
 * @param pollInterval how often a request waiting on CCD or on finalisation is looked at; also the retry cap
 */
@ConfigurationProperties(prefix = "deedpoll.deletion.cleanup")
public record DeletionCleanupProperties(boolean enabled, Duration interval, int batchSize, Duration lease,
                                        int maxAttempts, Duration retryBase, Duration pollInterval) {

    public DeletionCleanupProperties {
        positive(interval, "interval");
        positive(lease, "lease");
        positive(retryBase, "retry-base");
        positive(pollInterval, "poll-interval");
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("deedpoll.deletion.cleanup.batch-size must be between 1 and 100");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("deedpoll.deletion.cleanup.max-attempts must be at least 1");
        }
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("deedpoll.deletion.cleanup." + name + " must be positive");
        }
    }
}
