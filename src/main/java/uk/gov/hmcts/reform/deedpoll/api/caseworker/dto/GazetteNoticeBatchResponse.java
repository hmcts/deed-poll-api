package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteNoticeSummary;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** A sent batch: frozen references and attribution, with no subject or request identity. */
public record GazetteNoticeBatchResponse(UUID batchId, LocalDateTime sentAt, String sentBy, int caseCount,
                                        List<String> referenceNumbers, boolean referencesTruncated) {

    public static GazetteNoticeBatchResponse from(GazetteNoticeSummary batch) {
        return new GazetteNoticeBatchResponse(batch.batchId(), batch.sentAt(), batch.sentBy(), batch.caseCount(),
            batch.referenceNumbers(), batch.referencesTruncated());
    }
}
