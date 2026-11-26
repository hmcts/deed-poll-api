package uk.gov.hmcts.reform.deedpoll.storage;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionStatus;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The document deletion gauge the runbook watches (docs/DOCUMENT-DELETION.md): one per status, tagged with it, reading
 * that status's count.
 */
class DocumentDeletionMetricsTest {

    private static final String NAME = "deedpoll.documents.deletion.requests";

    @Test
    @DisplayName("one gauge per status, each tagged with its status and reading its own count")
    void oneGaugePerStatus() {
        DocumentDeletionRequestRepository requests = mock(DocumentDeletionRequestRepository.class);
        DocumentDeletionStatus[] statuses = DocumentDeletionStatus.values();
        for (int i = 0; i < statuses.length; i++) {
            when(requests.countByStatus(statuses[i])).thenReturn((long) i + 1);
        }
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new DocumentDeletionMetrics(requests).bindTo(registry);

        assertThat(registry.find(NAME).gauges()).hasSize(statuses.length);
        for (int i = 0; i < statuses.length; i++) {
            Gauge gauge = registry.get(NAME).tag("status", statuses[i].name()).gauge();
            assertThat(gauge.value()).as(statuses[i].name()).isEqualTo(i + 1.0);
        }
    }
}
