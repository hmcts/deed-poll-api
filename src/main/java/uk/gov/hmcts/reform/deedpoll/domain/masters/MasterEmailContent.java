package uk.gov.hmcts.reform.deedpoll.domain.masters;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One Master email, ready to send. Its {@link #toString} names nothing, because the recipient is a judge's address.
 */
public record MasterEmailContent(String templateId, String recipient, Map<String, String> personalisation) {

    public MasterEmailContent {
        personalisation = Collections.unmodifiableMap(new LinkedHashMap<>(personalisation));
    }

    @Override
    public String toString() {
        return "MasterEmailContent[redacted]";
    }
}
