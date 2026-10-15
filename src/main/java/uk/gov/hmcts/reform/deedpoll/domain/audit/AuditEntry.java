package uk.gov.hmcts.reform.deedpoll.domain.audit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One audit event, as {@code deedpoll_audit_event} stores it: who, what, to which entity and case, and the names of
 * the fields it touched.
 *
 * <p>Nothing here is applicant data, which is why the event row is kept for seven years while its values are disposed
 * with the case. {@link #detail} is the one free-form part, so its keys are an allowlist of identifiers: a state, a
 * template id, an outcome, a batch id, a document type id. A value never goes in it -- that is what
 * {@link AuditValue} and its own table are for.
 *
 * @param caseId the owning case, or null for an entity no case owns (a saved query, the Gazette settings)
 */
public record AuditEntry(AuditAction action, AuditActor actor, String entityType, String entityId, String caseId,
                         List<String> fields, Map<String, String> detail) {

    /**
     * The only keys {@link #detail} may carry.
     */
    public static final Set<String> DETAIL_KEYS =
        Set.of("fromState", "toState", "templateId", "outcome", "batchId", "documentTypeId");

    public AuditEntry {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
        requireText(entityType, "entityType");
        requireText(entityId, "entityId");
        fields = List.copyOf(fields == null ? List.of() : fields);
        detail = detail == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
        for (Map.Entry<String, String> entry : detail.entrySet()) {
            if (!DETAIL_KEYS.contains(entry.getKey())) {
                throw new IllegalArgumentException("Audit detail may not carry " + entry.getKey());
            }
            Objects.requireNonNull(entry.getValue(), entry.getKey());
        }
    }

    /** An event with no field names and no detail yet. */
    public static AuditEntry of(AuditAction action, AuditActor actor, String entityType, String entityId,
                                String caseId) {
        return new AuditEntry(action, actor, entityType, entityId, caseId, List.of(), Map.of());
    }

    /** This event naming these fields, in this order. */
    public AuditEntry withFields(Collection<String> names) {
        return new AuditEntry(action, actor, entityType, entityId, caseId, new ArrayList<>(names), detail);
    }

    /** This event with one more allowlisted detail. */
    public AuditEntry withDetail(String key, String value) {
        Map<String, String> next = new LinkedHashMap<>(detail);
        next.put(key, value);
        return new AuditEntry(action, actor, entityType, entityId, caseId, fields, next);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An audit event needs its " + name);
        }
    }
}
