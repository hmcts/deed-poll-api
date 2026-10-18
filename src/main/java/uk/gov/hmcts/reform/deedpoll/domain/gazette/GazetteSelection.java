package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;

import java.util.HashSet;
import java.util.List;

/** Shared selection syntax and readiness checks for preview and locked admission. */
public final class GazetteSelection {
    public static final int MAX_CASES = 100;

    private GazetteSelection() {
    }

    public static List<String> ids(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_CASES
            || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 255)
            || new HashSet<>(ids).size() != ids.size()) {
            throw new InvalidInputError("Select between 1 and 100 distinct Gazette cases.");
        }
        return ids.stream().sorted().toList();
    }

    public static void ready(String id, DeedPoll row, boolean reserved) {
        if (row == null) {
            refuse(id, GazetteRefusal.Code.CASE_NOT_READY, GazetteRefusal.Reason.NOT_FOUND);
        }
        if (row.getType() == null || switch (row.getType()) {
                case Unknown, DeedOfRelinquishment -> true;
                default -> false;
            }) {
            refuse(id, GazetteRefusal.Code.CASE_TYPE_UNSUPPORTED, GazetteRefusal.Reason.TYPE_UNSUPPORTED);
        }
        if (reserved) {
            refuse(id, GazetteRefusal.Code.CASE_RESERVED, GazetteRefusal.Reason.RESERVED);
        }
        if (!row.isPublishToLondonGazette() || row.getDateNoticeSentToLondonGazette() != null
            || row.getState() != DeedPollState.Enroled) {
            refuse(id, GazetteRefusal.Code.CASE_NOT_READY, GazetteRefusal.Reason.NOT_READY);
        }
        if (row.getDisplayReference() == null || row.getDisplayReference().isBlank()) {
            refuse(id, GazetteRefusal.Code.CASE_NOT_READY, GazetteRefusal.Reason.REFERENCE_MISSING);
        }
    }

    private static void refuse(String id, GazetteRefusal.Code code, GazetteRefusal.Reason reason) {
        throw new GazetteRefusal(code, List.of(new GazetteRefusal.MemberConflict(id, reason)));
    }
}
