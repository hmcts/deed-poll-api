package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import org.springframework.data.domain.Page;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteMember;

import java.util.List;

public record GazetteMembersResponse(List<GazetteMemberResponse> cases, int page, int size,
                                      long total, int totalPages) {
    public static GazetteMembersResponse from(Page<GazetteMember> found) {
        return new GazetteMembersResponse(found.stream().map(GazetteMemberResponse::from).toList(),
            found.getNumber() + 1, found.getSize(), found.getTotalElements(), found.getTotalPages());
    }
}
