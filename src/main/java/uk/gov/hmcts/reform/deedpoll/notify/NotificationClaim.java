package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.entity.Notification;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/** Immutable claim identity and frozen send inputs; contains no managed entity or lazy relation. */
public record NotificationClaim(UUID id, UUID owner, int generation, String caseId, String template,
                                String recipient, Map<String, Object> personalisation) {
    public NotificationClaim {
        Map<String, Object> frozen = new LinkedHashMap<>();
        personalisation.forEach((key, value) -> frozen.put(key, freeze(value)));
        personalisation = Collections.unmodifiableMap(frozen);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> frozen = new LinkedHashMap<>();
            map.forEach((key, item) -> frozen.put(key, freeze(item)));
            return Collections.unmodifiableMap(frozen);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(NotificationClaim::freeze).toList();
        }
        return value; // JSON scalar: String, Number, Boolean or null.
    }

    static NotificationClaim from(Notification row) {
        return new NotificationClaim(row.getId(), row.getClaimOwner(), row.getAttempts(), row.getDeedPollId(),
                                     row.getTemplate(), row.getRecipient(), row.getPersonalisation());
    }

    /** A disposable transport copy for the sender and its case-scoped document reader. */
    Notification notification() {
        Notification row = new Notification();
        row.setId(id);
        row.setDeedPollId(caseId);
        row.setTemplate(template);
        row.setRecipient(recipient);
        row.setPersonalisation(personalisation);
        row.setAttempts(generation);
        return row;
    }

    @Override
    public String toString() {
        return "NotificationClaim[redacted]";
    }
}
