package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentDeletionRecovery#run}'s own promises (plan 20261125-permanent-document-deletion, Task 4): at most
 * {@code batch-size} requests a run, and nothing thrown into the scheduler.
 */
class DocumentDeletionRecoveryTest {

    private static final int BATCH_SIZE = 2;

    private final DocumentDeletionWork work = mock(DocumentDeletionWork.class);
    private final DocumentDeletionRecovery recovery = new DocumentDeletionRecovery(work, new DocumentDeletionProperties(
        Duration.ofSeconds(20), Duration.ofSeconds(2), Duration.ofSeconds(8), Duration.ofSeconds(15),
        new DocumentDeletionProperties.Recovery(true, Duration.ofMinutes(1), BATCH_SIZE, Duration.ofMinutes(2), 8,
                                                Duration.ofMinutes(1), Duration.ofHours(1))));

    @Test
    @DisplayName("with more due than the batch, a run claims and works exactly batch-size requests")
    void stopsAtTheBatchSize() {
        when(work.claimNext(any())).thenAnswer(invocation -> Optional.of(UUID.randomUUID()));

        assertThat(recovery.run()).isEqualTo(BATCH_SIZE);

        verify(work, times(BATCH_SIZE)).claimNext(any());
        verify(work, times(BATCH_SIZE)).process(any(), any(), any());
    }

    @Test
    @DisplayName("nothing due: a run claims nothing")
    void nothingDue() {
        when(work.claimNext(any())).thenReturn(Optional.empty());

        assertThat(recovery.run()).isZero();
    }

    @Test
    @DisplayName("a failure is logged and swallowed, never thrown into the scheduler")
    void neverThrows() {
        when(work.claimNext(any())).thenThrow(new IllegalStateException("the database went away"));

        assertThat(recovery.run()).isZero();
    }
}
