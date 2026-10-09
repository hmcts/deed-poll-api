package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import java.time.LocalDateTime;
import java.util.UUID;

/** Public facts only. Missing legacy submission/recipient/group facts are deliberately null. */
public record GazetteBatchStatus(UUID batchId, GazetteRequestState state, LocalDateTime submittedAt,
                                 String submittedBy, String recipientEmail, Integer recipientRevision,
                                 int caseCount, Integer adultCount, Integer minorCount, LocalDateTime sentAt,
                                 FailureCode failureCode) {
    public enum FailureCode {
        PREPARATION_FAILED, CONFIGURATION_INVALID, PROVIDER_REFUSED, PRE_SEND_EXPIRED, SEND_OUTCOME_UNKNOWN
    }
}
