package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.masters.Master;

import java.util.List;

/**
 * The Masters list. The window, the cap and each Master's count in it went to the referral queue's envelope in plan
 * F1, because only the sender needs them; the list stays an envelope so a later field needs no contract change.
 */
public record MasterListResponse(List<MasterListEntryResponse> masters) {

    public static MasterListResponse from(List<Master> masters) {
        return new MasterListResponse(masters.stream().map(MasterListEntryResponse::from).toList());
    }
}
