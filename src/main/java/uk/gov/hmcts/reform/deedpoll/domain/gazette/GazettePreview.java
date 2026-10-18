package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import java.time.LocalDateTime;
import java.util.List;

public record GazettePreview(String recipientEmail, int recipientRevision, int caseCount, int adultCount,
                             int minorCount, List<GazetteMember> cases, String selectionFingerprint,
                             LocalDateTime expiresAt) {
}
