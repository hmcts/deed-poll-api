package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.masters.Master;

/** One Masters-list entry. Never the linked IDAM account, only whether there is one. */
public record MasterResponse(
    String id,
    String name,
    String email,
    @Schema(description = "Whether the Master may be assigned referrals. A Master who leaves is marked unavailable"
        + " rather than deleted.") boolean available,
    @Schema(description = "Whether the Master has signed in and so been linked to their IDAM account, by email.")
    boolean linked,
    @Schema(description = "Supply it with a change; a stale revision returns 409.") int revision) {

    public static MasterResponse from(Master master) {
        return new MasterResponse(master.id(), master.name(), master.email(), master.available(), master.linked(),
                                  master.revision());
    }
}
