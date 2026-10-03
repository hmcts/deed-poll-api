package uk.gov.hmcts.reform.deedpoll.notify;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * When a notification is tried again, and when it stops being tried.
 *
 * <p>Its own unit with its own tests, like {@link NotificationFailure}, because it is arithmetic and the
 * outbox around it is a state machine -- and because both numbers below are judgements about how often
 * it is reasonable to ask Notify the same question, which is a thing to state in one place rather than
 * to read out of a loop.
 *
 * <p><strong>Constants rather than configuration, for now.</strong> {@code NotifyProperties} is the
 * configuration record and nothing operational depends on tuning these: with no scheduler, a drain runs
 * when a caseworker reads something, so the backoff is a floor on retry frequency rather than a
 * schedule. When there is real behaviour to observe they become properties, which is additive.
 *
 * <p>The lease bounds pre-send ownership, not permission to replay a send. A durable send-start marker
 * prevents automatic delivery after expiry; only an explicitly retryable refusal clears that marker.
 */
public final class NotificationBackoff {

    /** How long a claim can acquire send authority or settle before recovery must take over. */
    static final Duration LEASE = Duration.ofMinutes(5);

    /** The wait after the first failed attempt. Doubles from here. */
    static final Duration FIRST_RETRY = Duration.ofMinutes(1);

    /** The longest wait, so the doubling does not put an attempt a week away. */
    static final Duration MAX_RETRY = Duration.ofHours(1);

    /**
     * How many attempts a retryable failure gets before the row is failed for good.
     *
     * <p>Eight, which with the doubling below is a little over three hours of trying. A row still
     * failing after that is not failing for a reason another attempt fixes, and a row that retried
     * forever would be a permanent cost on every caseworker read that drains.
     */
    static final int MAX_ATTEMPTS = 8;

    /** How far the doubling is allowed to shift, so the arithmetic cannot overflow a long. */
    private static final int MAX_SHIFT = 20;

    private NotificationBackoff() {
    }

    /**
     * When a row claimed at {@code now} becomes claimable again if the attempt never reports back.
     *
     * @param now the instant the claim is being made at
     * @return {@code now} plus {@link #LEASE}
     */
    public static LocalDateTime leaseUntil(LocalDateTime now) {
        return now.plus(LEASE);
    }

    /**
     * When to try again after an attempt that failed temporarily.
     *
     * <p>Exponential from {@link #FIRST_RETRY}, doubling per attempt and capped at {@link #MAX_RETRY}:
     * 1, 2, 4, 8, 16, 32 minutes, then an hour. Exponential rather than fixed because the failure this
     * exists for is a rate limit, and retrying a rate limit at a fixed interval is how a service stays
     * rate-limited.
     *
     * @param now      the instant the attempt failed at
     * @param attempts how many attempts this row has had, including the one that just failed. A value
     *                 below one is treated as one, so a row whose counter was never incremented still
     *                 waits rather than spinning
     * @return when the row becomes claimable again
     */
    public static LocalDateTime nextAttemptAt(LocalDateTime now, int attempts) {
        int shift = Math.min(Math.max(attempts, 1) - 1, MAX_SHIFT);
        long minutes = Math.min(FIRST_RETRY.toMinutes() << shift, MAX_RETRY.toMinutes());
        return now.plusMinutes(minutes);
    }

    /**
     * Whether a row has had all the attempts a retryable failure gets.
     *
     * <p>Asked of the count <em>after</em> the failed attempt, because the claim increments before the
     * send: a row reporting its eighth failure has had {@link #MAX_ATTEMPTS} and gets no ninth.
     *
     * @param attempts how many attempts this row has had
     * @return true when the next attempt would be one too many
     */
    public static boolean exhausted(int attempts) {
        return attempts >= MAX_ATTEMPTS;
    }
}
