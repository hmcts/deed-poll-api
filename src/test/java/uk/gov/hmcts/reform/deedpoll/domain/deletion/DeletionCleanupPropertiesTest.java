package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deletion worker's settings refuse a value that would stop it working or let one run claim without bound, at
 * start-up and naming the setting.
 */
class DeletionCleanupPropertiesTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private static DeletionCleanupProperties of(Duration interval, int batchSize, Duration lease, int maxAttempts,
                                                Duration retryBase, Duration pollInterval) {
        return new DeletionCleanupProperties(false, interval, batchSize, lease, maxAttempts, retryBase, pollInterval);
    }

    @Test
    @DisplayName("the batch size is accepted from 1 to 100 and the attempts from 1")
    void boundsAreInclusive() {
        assertThatCode(() -> of(MINUTE, 1, MINUTE, 1, MINUTE, MINUTE)).doesNotThrowAnyException();
        assertThatCode(() -> of(MINUTE, 100, MINUTE, 1, MINUTE, MINUTE)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a batch size outside 1 to 100 is refused")
    void batchSizeIsBounded() {
        assertThatThrownBy(() -> of(MINUTE, 0, MINUTE, 1, MINUTE, MINUTE))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch-size");
        assertThatThrownBy(() -> of(MINUTE, 101, MINUTE, 1, MINUTE, MINUTE))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch-size");
    }

    @Test
    @DisplayName("no attempts at all is refused")
    void attemptsArePositive() {
        assertThatThrownBy(() -> of(MINUTE, 10, MINUTE, 0, MINUTE, MINUTE))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-attempts");
    }

    @Test
    @DisplayName("each duration must be present and positive, and the refusal names it")
    void durationsArePositive() {
        assertThatThrownBy(() -> of(null, 10, MINUTE, 1, MINUTE, MINUTE)).hasMessageContaining("interval");
        assertThatThrownBy(() -> of(MINUTE, 10, Duration.ZERO, 1, MINUTE, MINUTE)).hasMessageContaining("lease");
        assertThatThrownBy(() -> of(MINUTE, 10, MINUTE, 1, MINUTE.negated(), MINUTE))
            .hasMessageContaining("retry-base");
        assertThatThrownBy(() -> of(MINUTE, 10, MINUTE, 1, MINUTE, Duration.ZERO))
            .hasMessageContaining("poll-interval");
    }
}
