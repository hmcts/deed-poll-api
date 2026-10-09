package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import java.util.Objects;
import java.util.UUID;

/** Configuration identifiers only: credentials and expiring file tokens never enter the ledger. */
public record GazetteDeliveryConfiguration(UUID templateId, UUID replyToId, int retentionWeeks) {
    public GazetteDeliveryConfiguration {
        Objects.requireNonNull(templateId);
        Objects.requireNonNull(replyToId);
        if (retentionWeeks < 1 || retentionWeeks > 78) {
            throw new IllegalArgumentException("Invalid Gazette download retention");
        }
    }
}
