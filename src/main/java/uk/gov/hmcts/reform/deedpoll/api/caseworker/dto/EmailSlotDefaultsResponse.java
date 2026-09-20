package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.notify.EmailSlotDefaults;

import java.util.List;
import java.util.Map;

/**
 * Server-resolved starting values for one applicable email's editable slots.
 *
 * @param template the stable template key
 * @param fields values keyed by the slot names accepted by preview and send
 */
public record EmailSlotDefaultsResponse(String template, Map<String, String> fields) {

    static EmailSlotDefaultsResponse from(EmailSlotDefaults defaults) {
        return new EmailSlotDefaultsResponse(defaults.template().key(), defaults.fields());
    }

    static List<EmailSlotDefaultsResponse> from(List<EmailSlotDefaults> defaults) {
        return defaults.stream().map(EmailSlotDefaultsResponse::from).toList();
    }
}
