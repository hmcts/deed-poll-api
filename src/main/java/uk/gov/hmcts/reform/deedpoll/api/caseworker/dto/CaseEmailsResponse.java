package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.notify.CaseEmailOverview;

import java.util.List;

/**
 * Everything the Email tab needs from one request.
 *
 * @param availability every known email type and whether it applies
 * @param history every recorded email attempt, newest first
 * @param slotDefaults server-resolved starting values for each applicable template
 */
public record CaseEmailsResponse(
    List<EmailTypeResponse> availability,
    List<EmailHistoryResponse> history,
    List<EmailSlotDefaultsResponse> slotDefaults
) {

    public static CaseEmailsResponse from(CaseEmailOverview overview) {
        return new CaseEmailsResponse(
            EmailTypeResponse.from(overview.availability()),
            EmailHistoryResponse.from(overview.history()),
            EmailSlotDefaultsResponse.from(overview.slotDefaults()));
    }
}
