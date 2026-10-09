package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import java.time.LocalDateTime;
import java.util.UUID;

/** Internal ledger projection, never an HTTP DTO. */
public record GazetteRequest(
    UUID id, UUID requestId, String payloadHash, String submittedById, String submittedByName,
    LocalDateTime submittedAt, String recipientEmail, int recipientRevision, int adultCount, int minorCount,
    GazetteDeliveryConfiguration configuration, UUID owner, LocalDateTime leaseUntil, GazetteRequestState state,
    LocalDateTime sendStartedAt, UUID providerNotificationId, Integer templateVersion) {
}
