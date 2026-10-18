package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Frozen publication evidence with display-name attribution only. */
public record GazetteNoticeSummary(UUID batchId, LocalDateTime sentAt, String sentBy, int caseCount,
                                  List<String> referenceNumbers, boolean referencesTruncated) {
}
