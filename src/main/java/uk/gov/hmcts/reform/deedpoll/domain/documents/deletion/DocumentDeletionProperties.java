package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Permanent document deletion's budget, remote timeouts and recovery sweep, {@code deedpoll.documents.deletion}
 * (plan 20261125-permanent-document-deletion, Task 4; docs/DOCUMENT-DELETION.md, "Request budget and per-call
 * timeouts").
 *
 * <p><strong>The recovery sweep is on by default.</strong> It starts no deletion: it only resumes a request a member
 * of staff already admitted, whose own request ran out of budget, met an uncertain answer or went away. It is not
 * application deletion's worker and does not read that worker's switch. Disabled, an interrupted request stays
 * {@code InProgress}, fenced and recoverable, and is never reported {@code Completed}.
 *
 * @param requestBudget    how long one staff request may spend on remote steps, from its start; a step starts only
 *                         if what is left covers its allowance, so the answer comes well inside the gateway's 30 s
 * @param cdamConnectTimeout the permanent delete's connect timeout (its own client, not the upload client's)
 * @param cdamReadTimeout    the permanent delete's read timeout
 * @param ccdCallAllowance   what one CCD call (the start, or the submit) may take: the default client's connect plus
 *                           read timeout
 * @param recovery           the sweep
 */
@ConfigurationProperties(prefix = "deedpoll.documents.deletion")
public record DocumentDeletionProperties(Duration requestBudget, Duration cdamConnectTimeout, Duration cdamReadTimeout,
                                         Duration ccdCallAllowance, Recovery recovery) {

    /** The Application Gateway answers for us at 30 s; the budget must leave room for the response. */
    static final Duration BUDGET_CEILING = Duration.ofSeconds(25);

    private static final String PREFIX = "deedpoll.documents.deletion.";

    /**
     * The recovery sweep.
     *
     * @param enabled     whether the timer runs it (default true)
     * @param interval    the delay between runs
     * @param batchSize   the most requests one run claims
     * @param lease       how long a claim lasts before another pod may take the request over; longer than the
     *                    request budget, so a request still working is never taken from it
     * @param maxAttempts the tries a step (or local completion) gets before the request needs attention
     * @param retryBase   the first retry's delay, doubled per try
     * @param retryCap    the longest delay between tries
     */
    public record Recovery(boolean enabled, Duration interval, int batchSize, Duration lease, int maxAttempts,
                           Duration retryBase, Duration retryCap) {

        public Recovery {
            positive(interval, "recovery.interval");
            positive(lease, "recovery.lease");
            positive(retryBase, "recovery.retry-base");
            positive(retryCap, "recovery.retry-cap");
            if (batchSize < 1 || batchSize > 100) {
                throw new IllegalArgumentException(PREFIX + "recovery.batch-size must be between 1 and 100");
            }
            if (maxAttempts < 1 || maxAttempts > 100) {
                throw new IllegalArgumentException(PREFIX + "recovery.max-attempts must be between 1 and 100");
            }
            if (retryCap.compareTo(retryBase) < 0) {
                throw new IllegalArgumentException(PREFIX + "recovery.retry-cap must not be shorter than retry-base");
            }
        }
    }

    public DocumentDeletionProperties {
        positive(requestBudget, "request-budget");
        positive(cdamConnectTimeout, "cdam-connect-timeout");
        positive(cdamReadTimeout, "cdam-read-timeout");
        positive(ccdCallAllowance, "ccd-call-allowance");
        if (recovery == null) {
            throw new IllegalArgumentException(PREFIX + "recovery must be configured");
        }
        if (requestBudget.compareTo(BUDGET_CEILING) > 0) {
            throw new IllegalArgumentException(PREFIX + "request-budget must be at most " + BUDGET_CEILING
                                                   + ", inside the gateway's 30 s");
        }
        if (sumOf(cdamConnectTimeout, cdamReadTimeout).compareTo(requestBudget) > 0
            || ccdCallAllowance.compareTo(requestBudget) > 0) {
            throw new IllegalArgumentException(PREFIX + "a single remote call's allowance must fit the request budget");
        }
        if (recovery.lease().compareTo(requestBudget) <= 0) {
            throw new IllegalArgumentException(PREFIX + "recovery.lease must be longer than request-budget");
        }
    }

    /** What one permanent delete may take: its connect plus read timeout. */
    public Duration cdamCallAllowance() {
        return sumOf(cdamConnectTimeout, cdamReadTimeout);
    }

    /**
     * The delay before try {@code attempts + 1}: the base doubled per try already made, never beyond the cap.
     */
    Duration retryDelay(int attempts) {
        Duration delay = recovery.retryBase();
        for (int tried = 1; tried < attempts && delay.compareTo(recovery.retryCap()) < 0; tried++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(recovery.retryCap()) > 0 ? recovery.retryCap() : delay;
    }

    private static Duration sumOf(Duration connect, Duration read) {
        return connect.plus(read);
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(PREFIX + name + " must be positive");
        }
    }
}
