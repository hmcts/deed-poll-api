package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.masters.Master;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralWindowLoad;

import java.util.List;
import java.util.UUID;

/**
 * The Masters list with what a caseworker's form needs to choose among them (plan D2): the referral window a
 * referral raised now would count towards, the per-Master cap, and each Master's count in that window.
 */
public record MasterListResponse(
    List<MasterListEntryResponse> masters,
    @Schema(description = "The referral window a referral assigned now counts towards: today's morning or "
        + "afternoon, or the next working day's morning outside the windows.") ReferralWindowResponse window,
    @Schema(description = "How many referrals one Master may be assigned per referral window.") int cap) {

    public static MasterListResponse from(List<Master> masters, ReferralWindowLoad load) {
        return new MasterListResponse(
            masters.stream().map(master -> MasterListEntryResponse.from(
                master, load.assignedTo(UUID.fromString(master.id())))).toList(),
            ReferralWindowResponse.from(load.window()),
            load.cap());
    }
}
