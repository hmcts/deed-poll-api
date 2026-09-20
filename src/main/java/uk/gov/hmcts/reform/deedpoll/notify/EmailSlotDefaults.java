package uk.gov.hmcts.reform.deedpoll.notify;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-resolved starting values for one applicable email template's caseworker slots.
 *
 * @param template the email these defaults compose
 * @param fields values keyed by the same placeholder names the send and preview requests accept
 */
public record EmailSlotDefaults(EmailTemplate template, Map<String, String> fields) {

    public EmailSlotDefaults {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
