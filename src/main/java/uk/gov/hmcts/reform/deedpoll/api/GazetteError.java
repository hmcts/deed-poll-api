package uk.gov.hmcts.reform.deedpoll.api;

import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRefusal;

import java.util.List;

public record GazetteError(String error, GazetteRefusal.Code code, List<Conflict> conflicts) {
    public static GazetteError from(GazetteRefusal refusal) {
        return new GazetteError(refusal.getMessage(), refusal.code(), refusal.conflicts().stream()
            .map(member -> new Conflict(member.caseId(), member.reason())).toList());
    }

    public record Conflict(String caseId, GazetteRefusal.Reason reason) {
    }
}
