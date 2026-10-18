package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict JSON shapes; strings and fractional numbers are never coerced into revisions or case IDs. */
public final class GazetteBodies {
    private GazetteBodies() {
    }

    public static List<String> selection(Map<String, Object> fields, boolean admission) {
        Set<String> keys = admission
            ? Set.of("caseIds", "selectionFingerprint", "recipientRevision") : Set.of("caseIds");
        if (!fields.keySet().equals(keys) || !(fields.get("caseIds") instanceof List<?> ids)
            || ids.stream().anyMatch(id -> !(id instanceof String))) {
            throw new InvalidInputError("Invalid Gazette request fields.");
        }
        return GazetteSelection.ids(ids.stream().map(String.class::cast).toList());
    }

    public static String fingerprint(Map<String, Object> fields) {
        if (!(fields.get("selectionFingerprint") instanceof String token) || token.isBlank() || token.length() > 512) {
            throw new InvalidInputError("Invalid Gazette confirmation token.");
        }
        return token;
    }

    public static int revision(Map<String, Object> fields) {
        if (!(fields.get("recipientRevision") instanceof Integer revision) || revision < 0) {
            throw new InvalidInputError("Invalid Gazette recipient revision.");
        }
        return revision;
    }
}
