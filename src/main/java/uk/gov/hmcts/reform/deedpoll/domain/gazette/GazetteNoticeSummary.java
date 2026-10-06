package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import java.time.LocalDateTime;
import java.util.List;

/** Frozen publication evidence with display-name attribution only. */
public record GazetteNoticeSummary(LocalDateTime sentAt, String sentBy, int caseCount,
                                  List<String> referenceNumbers) {
}
