package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteBatchStatus;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRequestState;

import java.time.LocalDateTime;
import java.util.UUID;

public record GazetteStatusResponse(UUID batchId, GazetteRequestState state,
                                    @Schema(nullable = true) LocalDateTime submittedAt,
                                    @Schema(nullable = true) String submittedBy,
                                    @Schema(nullable = true) String recipientEmail,
                                    @Schema(nullable = true) Integer recipientRevision,
                                    int caseCount, @Schema(nullable = true) Integer adultCount,
                                    @Schema(nullable = true) Integer minorCount,
                                    @Schema(nullable = true) LocalDateTime sentAt,
                                    @Schema(nullable = true) GazetteBatchStatus.FailureCode failureCode) {
    public static GazetteStatusResponse from(GazetteBatchStatus row) {
        return new GazetteStatusResponse(row.batchId(), row.state(), row.submittedAt(), row.submittedBy(),
            row.recipientEmail(), row.recipientRevision(), row.caseCount(), row.adultCount(), row.minorCount(),
            row.sentAt(), row.failureCode());
    }
}
