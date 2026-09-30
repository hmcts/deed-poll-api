package uk.gov.hmcts.reform.deedpoll.storage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentStorageMetricsTest {

    @Test
    void gaugesRefreshCountsAndPublishOnlyBoundedStateLabels() {
        var documents = mock(DeedPollSupportingDocumentRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            new DocumentStorageMetrics(documents).bindTo(registry);
            assertThat(registry.getMeters()).hasSize(DocumentStorageState.values().length);
            for (DocumentStorageState state : DocumentStorageState.values()) {
                when(documents.countByStorageState(state)).thenReturn(3L, 4L);
                var gauge = registry.get("deedpoll.documents.storage").tag("state", state.name()).gauge();
                assertThat(gauge.value()).isEqualTo(3);
                assertThat(gauge.value()).isEqualTo(4);
                assertThat(gauge.getId().getTags()).singleElement().satisfies(tag -> {
                    assertThat(tag.getKey()).isEqualTo("state");
                    assertThat(tag.getValue()).isEqualTo(state.name());
                });
            }
        } finally {
            registry.close();
        }
    }
}
