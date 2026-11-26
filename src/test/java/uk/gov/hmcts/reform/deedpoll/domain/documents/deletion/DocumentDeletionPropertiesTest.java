package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The budget, the per-call allowances and the sweep's bounds (plan 20261125-permanent-document-deletion, Task 4). */
class DocumentDeletionPropertiesTest {

    static DocumentDeletionProperties.Recovery recovery(Duration lease, int maxAttempts) {
        return new DocumentDeletionProperties.Recovery(true, Duration.ofMinutes(1), 10, lease, maxAttempts,
                                                       Duration.ofMinutes(1), Duration.ofHours(1));
    }

    static DocumentDeletionProperties defaults() {
        return new DocumentDeletionProperties(Duration.ofSeconds(20), Duration.ofSeconds(2), Duration.ofSeconds(8),
                                              Duration.ofSeconds(15), recovery(Duration.ofMinutes(2), 8));
    }

    @Test
    void theDefaultsHold() {
        DocumentDeletionProperties properties = defaults();
        assertThat(properties.cdamCallAllowance()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void theBudgetStaysInsideTheGateway() {
        assertThatThrownBy(() -> new DocumentDeletionProperties(Duration.ofSeconds(26), Duration.ofSeconds(2),
                                                                Duration.ofSeconds(8), Duration.ofSeconds(15),
                                                                recovery(Duration.ofMinutes(2), 8)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("request-budget");
    }

    @Test
    void oneCallMustFitTheBudget() {
        assertThatThrownBy(() -> new DocumentDeletionProperties(Duration.ofSeconds(9), Duration.ofSeconds(2),
                                                                Duration.ofSeconds(8), Duration.ofSeconds(5),
                                                                recovery(Duration.ofMinutes(2), 8)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowance");
        assertThatThrownBy(() -> new DocumentDeletionProperties(Duration.ofSeconds(14), Duration.ofSeconds(2),
                                                                Duration.ofSeconds(8), Duration.ofSeconds(15),
                                                                recovery(Duration.ofMinutes(2), 8)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowance");
    }

    @Test
    void theLeaseOutlastsTheBudget() {
        assertThatThrownBy(() -> new DocumentDeletionProperties(Duration.ofSeconds(20), Duration.ofSeconds(2),
                                                                Duration.ofSeconds(8), Duration.ofSeconds(15),
                                                                recovery(Duration.ofSeconds(20), 8)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease");
    }

    @Test
    void theSweepIsBounded() {
        assertThatThrownBy(() -> recovery(Duration.ofMinutes(2), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentDeletionProperties.Recovery(true, Duration.ofMinutes(1), 0,
                                                                         Duration.ofMinutes(2), 8,
                                                                         Duration.ofMinutes(1), Duration.ofHours(1)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new DocumentDeletionProperties.Recovery(true, Duration.ofMinutes(1), 10,
                                                                         Duration.ofMinutes(2), 8,
                                                                         Duration.ofHours(2), Duration.ofHours(1)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retry-cap");
        assertThatThrownBy(() -> new DocumentDeletionProperties(Duration.ofSeconds(20), Duration.ofSeconds(2),
                                                                Duration.ofSeconds(8), Duration.ofSeconds(15), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRetryDelayDoublesUpToItsCap() {
        DocumentDeletionProperties properties = defaults();
        assertThat(properties.retryDelay(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.retryDelay(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.retryDelay(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(properties.retryDelay(8)).isEqualTo(Duration.ofHours(1));
        assertThat(properties.retryDelay(1000)).isEqualTo(Duration.ofHours(1));
    }
}
