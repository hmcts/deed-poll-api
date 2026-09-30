package uk.gov.hmcts.reform.deedpoll.storage;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;

/** Storage counts only: the bounded state label carries no case, document, user or token. */
@Component
public class DocumentStorageMetrics implements MeterBinder {

    private final DeedPollSupportingDocumentRepository documents;

    public DocumentStorageMetrics(DeedPollSupportingDocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (DocumentStorageState state : DocumentStorageState.values()) {
            Gauge.builder("deedpoll.documents.storage", documents, repository -> repository.countByStorageState(state))
                .description("Supporting document rows by storage state")
                .tag("state", state.name())
                .register(registry);
        }
    }
}
