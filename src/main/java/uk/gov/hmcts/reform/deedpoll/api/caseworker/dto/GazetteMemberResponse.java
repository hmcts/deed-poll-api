package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteMember;

public record GazetteMemberResponse(String caseId, String referenceNumber) {
    public static GazetteMemberResponse from(GazetteMember member) {
        return new GazetteMemberResponse(member.caseId(), member.referenceNumber());
    }
}
