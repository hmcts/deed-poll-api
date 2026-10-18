package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazettePreview;

import java.time.LocalDateTime;
import java.util.List;

public record GazettePreviewResponse(String recipientEmail, int recipientRevision, int caseCount, int adultCount,
                                     int minorCount, List<GazetteMemberResponse> cases, String selectionFingerprint,
                                     LocalDateTime expiresAt) {
    public static GazettePreviewResponse from(GazettePreview row) {
        return new GazettePreviewResponse(row.recipientEmail(), row.recipientRevision(), row.caseCount(),
            row.adultCount(),
            row.minorCount(), row.cases().stream().map(GazetteMemberResponse::from).toList(),
            row.selectionFingerprint(), row.expiresAt());
    }
}
