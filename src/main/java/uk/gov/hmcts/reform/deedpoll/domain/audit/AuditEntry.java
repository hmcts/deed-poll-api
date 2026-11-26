package uk.gov.hmcts.reform.deedpoll.domain.audit;

import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionNote;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionReason;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionReason;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One audit event, as {@code deedpoll_audit_event} stores it: who, what, to which entity and case, and the names of
 * the fields it touched.
 *
 * <p>Nothing here is applicant data, which is why the event row is kept for seven years while its values are disposed
 * with the case. {@link #detail} is the one free-form part, so its keys are an allowlist of identifiers: a state, a
 * template id, an outcome, a batch id, a document type id, an application deletion's reason, note and request id,
 * and a document deletion's reason and request id, which are typed and checked here. A value never goes in it --
 * that is what {@link AuditValue} and its own table are for.
 *
 * @param caseId the owning case, or null for an entity no case owns (a saved query, the Gazette settings)
 */
public record AuditEntry(AuditAction action, AuditActor actor, String entityType, String entityId, String caseId,
                         List<String> fields, Map<String, String> detail) {

    /**
     * A {@link DeletionReason}'s name, and nothing else.
     */
    public static final String DELETION_REASON = "deletionReason";

    /**
     * The Delivery Manager's {@link DeletionNote}, exactly as it parses: the one detail that is free text, which is why
     * it is checked as the request checks it and documented as never holding applicant details.
     */
    public static final String DELETION_NOTE = "deletionNote";

    /** A deletion request's id, as a canonical UUID. */
    public static final String DELETION_REQUEST_ID = "deletionRequestId";

    /**
     * A staff member's {@link DocumentDeletionReason}, exactly as it parses: free text, checked as the request checks
     * it, and never relaxed into {@link #DELETION_NOTE}'s rule or the other way round. It stays in the event when a
     * later application disposal removes the case's audit values, so the reason survives with the event.
     */
    public static final String DOCUMENT_DELETION_REASON = "documentDeletionReason";

    /** A document deletion request's id, as a canonical UUID. */
    public static final String DOCUMENT_DELETION_REQUEST_ID = "documentDeletionRequestId";

    /**
     * The only keys {@link #detail} may carry.
     */
    public static final Set<String> DETAIL_KEYS =
        Set.of("fromState", "toState", "templateId", "outcome", "batchId", "documentTypeId",
               DELETION_REASON, DELETION_NOTE, DELETION_REQUEST_ID, DOCUMENT_DELETION_REASON,
               DOCUMENT_DELETION_REQUEST_ID);

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
            requireTyped(entry.getKey(), entry.getValue());
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

    /** The detail's keys and never its values: a deletion note or reason in a log would outlive any disposal. */
    @Override
    public String toString() {
        return "AuditEntry[action=" + action + ", entityType=" + entityType + ", entityId=" + entityId
            + ", caseId=" + caseId + ", fields=" + fields + ", detailKeys=" + detail.keySet() + "]";
    }

    /** This event with a deletion request's reason, note and id. */
    public AuditEntry withDeletion(DeletionReason reason, DeletionNote note, UUID requestId) {
        return withDetail(DELETION_REASON, reason.name())
            .withDetail(DELETION_NOTE, note.value())
            .withDetail(DELETION_REQUEST_ID, requestId.toString());
    }

    /** This event with a restored deletion request's reason and id; never its note. */
    public AuditEntry withRestoration(DeletionReason reason, UUID requestId) {
        return withDetail(DELETION_REASON, reason.name())
            .withDetail(DELETION_REQUEST_ID, requestId.toString());
    }

    /** This event with a document deletion request's reason and id. */
    public AuditEntry withDocumentDeletion(DocumentDeletionReason reason, UUID requestId) {
        return withDetail(DOCUMENT_DELETION_REASON, reason.value())
            .withDetail(DOCUMENT_DELETION_REQUEST_ID, requestId.toString());
    }

    /** The deletion keys are typed: a value that is not one of theirs is refused, and never echoed. */
    private static void requireTyped(String key, String value) {
        boolean valid = switch (key) {
            case DELETION_REASON -> DeletionReason.fromName(value).isPresent();
            case DELETION_REQUEST_ID, DOCUMENT_DELETION_REQUEST_ID -> isCanonicalUuid(value);
            case DOCUMENT_DELETION_REASON -> DocumentDeletionReason.isParsed(value);
            case DELETION_NOTE -> isParsedNote(value);
            default -> true;
        };
        if (!valid) {
            throw new IllegalArgumentException("Audit detail " + key + " is not a valid value");
        }
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }

    private static boolean isParsedNote(String value) {
        try {
            return DeletionNote.parse(value).value().equals(value);
        } catch (InvalidInputError refused) {
            return false;
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An audit event needs its " + name);
        }
    }
}
