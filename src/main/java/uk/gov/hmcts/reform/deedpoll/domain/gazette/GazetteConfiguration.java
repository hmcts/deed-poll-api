package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.deedpoll.notify.NotifyProperties;

import java.util.UUID;

/** Preview provisioning only. There is no delivery adapter in this slice, regardless of configuration. */
@Component
public class GazetteConfiguration {
    private final NotifyProperties notify;
    private final boolean enabled;

    public GazetteConfiguration(NotifyProperties notify,
                                @Value("${deedpoll.gazette.enabled:false}") boolean enabled) {
        this.notify = notify;
        this.enabled = enabled;
    }

    public GazetteDeliveryConfiguration forGroups(int adults, int minors) {
        if (!enabled || notify.apiKey().isBlank() || notify.retention().isEmpty()) {
            throw new GazetteRefusal(GazetteRefusal.Code.GAZETTE_UNAVAILABLE);
        }
        String group = adults > 0 && minors > 0 ? "mixed" : adults > 0 ? "adult" : "minor";
        try {
            String template = notify.templateId("gazette-" + group);
            String replyTo = notify.emailReplyToId();
            UUID templateId = UUID.fromString(template);
            UUID replyId = UUID.fromString(replyTo);
            if (!templateId.toString().equalsIgnoreCase(template) || !replyId.toString().equalsIgnoreCase(replyTo)) {
                throw new IllegalArgumentException("Non-canonical configuration ID");
            }
            return new GazetteDeliveryConfiguration(templateId, replyId, notify.documentRetentionWeeks());
        } catch (IllegalArgumentException invalid) {
            throw new GazetteRefusal(GazetteRefusal.Code.GAZETTE_UNAVAILABLE);
        }
    }
}
