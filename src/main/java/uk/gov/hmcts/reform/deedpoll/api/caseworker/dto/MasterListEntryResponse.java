package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.masters.Master;

/**
 * One Masters-list entry, as {@link MasterResponse} publishes it. Each Master's count in the current window is the
 * referral queue's ({@code ReferralQueueWindowResponse}) since plan F1.
 */
public record MasterListEntryResponse(
    String id,
    String name,
    String email,
    @Schema(description = "Whether the Master may be sent referrals. A Master who leaves is marked unavailable"
        + " rather than deleted.") boolean available,
    @Schema(description = "Whether the Master has signed in and so been linked to their IDAM account, by email.")
    boolean linked,
    @Schema(description = "Supply it with a change; a stale revision returns 409.") int revision) {

    static MasterListEntryResponse from(Master master) {
        return new MasterListEntryResponse(master.id(), master.name(), master.email(), master.available(),
                                           master.linked(), master.revision());
    }
}
