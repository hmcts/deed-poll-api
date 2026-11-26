package uk.gov.hmcts.reform.deedpoll.storage;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionStatus;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository;

/**
 * Permanent document deletion requests by status: {@code NeedsAttention} above zero needs a person, and
 * {@code InProgress} that does not fall needs the recovery sweep looked at. The bounded status label carries no case,
 * document, user, reason or locator.
 */
@Component
public class DocumentDeletionMetrics implements MeterBinder {

    private final DocumentDeletionRequestRepository requests;

    public DocumentDeletionMetrics(DocumentDeletionRequestRepository requests) {
        this.requests = requests;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (DocumentDeletionStatus status : DocumentDeletionStatus.values()) {
            Gauge.builder("deedpoll.documents.deletion.requests", requests,
                          repository -> repository.countByStatus(status))
                .description("Permanent document deletion requests by status")
                .tag("status", status.name())
                .register(registry);
        }
    }
}
