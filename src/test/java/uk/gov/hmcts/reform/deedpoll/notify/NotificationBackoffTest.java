package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retry arithmetic, with no database, no HTTP and no clock -- every instant here is supplied.
 *
 * <p>Container-free because CI excludes the {@code requires-docker} classes, so a rule stated only in a
 * container-backed test is a rule CI does not check. This is arithmetic and a cap; neither needs Postgres.
 */
class NotificationBackoffTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 12, 0);

    @Test
    @DisplayName("the lease is longer than the first backoff, because their jobs are opposite")
    void theLeaseIsLongerThanTheFirstBackoff() {
        // The two numbers are deliberately different, and the relation between them is the decision worth
        // asserting rather than either value: the lease has to outlast the slowest Notify call, because too
        // short means a slow send is sent twice, while the backoff starts short because the common temporary
        // failure is a rate limit that clears in seconds.
        assertThat(NotificationBackoff.LEASE).isGreaterThan(NotificationBackoff.FIRST_RETRY);
        assertThat(NotificationBackoff.leaseUntil(NOW)).isEqualTo(NOW.plus(NotificationBackoff.LEASE));
    }

    @ParameterizedTest(name = "attempt {0} waits {1} minutes")
    @CsvSource({"1, 1", "2, 2", "3, 4", "4, 8", "5, 16", "6, 32", "7, 60", "8, 60", "40, 60"})
    @DisplayName("doubles from a minute and stops at an hour")
    void doublesFromAMinuteAndStopsAtAnHour(int attempts, long expectedMinutes) {
        // Exponential rather than fixed, because the failure this exists for is a rate limit and retrying a
        // rate limit at a fixed interval is how a service stays rate-limited. Capped, so the doubling cannot
        // put an attempt a week away -- and the last row proves the cap holds however high the counter goes,
        // which is also what stops the shift overflowing.
        assertThat(NotificationBackoff.nextAttemptAt(NOW, attempts))
            .isEqualTo(NOW.plusMinutes(expectedMinutes));
        assertThat(Duration.between(NOW, NotificationBackoff.nextAttemptAt(NOW, attempts)))
            .isLessThanOrEqualTo(NotificationBackoff.MAX_RETRY);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    @DisplayName("waits rather than spinning when the counter says no attempt has been made")
    void waitsRatherThanSpinningOnAnImpossibleCount(int attempts) {
        // Should not happen -- the claim increments before the send -- but a zero here would compute a wait of
        // half a minute and a negative one would shift by a negative amount, so both are pinned to the first
        // retry. The failure this guards against is a row retried as fast as the drain can loop.
        assertThat(NotificationBackoff.nextAttemptAt(NOW, attempts))
            .isEqualTo(NOW.plus(NotificationBackoff.FIRST_RETRY));
    }

    @Test
    @DisplayName("gives a retryable failure a bounded number of attempts")
    void givesARetryableFailureABoundedNumberOfAttempts() {
        // Asked of the count after the failed attempt, because the claim increments before the send: a row
        // reporting its MAX_ATTEMPTS-th failure gets no further one. A row that retried for ever would be a
        // permanent cost on every caseworker read that drains.
        assertThat(NotificationBackoff.exhausted(NotificationBackoff.MAX_ATTEMPTS - 1)).isFalse();
        assertThat(NotificationBackoff.exhausted(NotificationBackoff.MAX_ATTEMPTS)).isTrue();
        assertThat(NotificationBackoff.exhausted(NotificationBackoff.MAX_ATTEMPTS + 1)).isTrue();
    }
}
