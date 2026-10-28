package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.AuditedAs;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.ProcessSettingsResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.UpdateProcessSettingsRequest;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsService;

@RestController
@RequestMapping("/caseworker/process-settings")
public class CaseworkerProcessSettingsController {

    private final ProcessSettingsService service;

    public CaseworkerProcessSettingsController(ProcessSettingsService service) {
        this.service = service;
    }

    @Operation(operationId = "getProcessSettings", summary = "Read the Delivery Manager's process settings",
        description = "Requires caseworker-deedpoll-delivery-manager (MANAGE_PROCESS_CONFIG). "
            + "Seeded with a processing window of 10080 minutes (7 days), a 48-hour reminder interval, a cap of 10 "
            + "referrals per Master per window, and referral windows of 07:00-10:30 and 14:00-14:30 Europe/London, "
            + "at revision 0 with null attribution.")
    @GetMapping
    public ProcessSettingsResponse get() {
        return ProcessSettingsResponse.from(service.get(CurrentUsers.required()));
    }

    @Operation(operationId = "updateProcessSettings", summary = "Update the Delivery Manager's process settings",
        description = "Requires caseworker-deedpoll-delivery-manager (MANAGE_PROCESS_CONFIG). "
            + "Supply the revision read with the form; a stale revision returns 409 with no change, even when the "
            + "value already matches. A change increments revision, records attribution and is audited. Saving the "
            + "current values at the current revision is a no-op. referralClaimExpiryMinutes (the processing window: "
            + "how long a Master has to give a direction before the referral is unassigned) and revision are "
            + "required. The other settings are optional for one release: an absent one keeps its stored value, and "
            + "each changed setting writes its own history row. Window times are HH:mm on the quarter hour, each "
            + "window starting before it ends and the morning window ending before the afternoon one starts. A new "
            + "processing window applies from the next sweep; saving it releases nothing itself, and raising it "
            + "never restores a referral already released.")
    @AuditedAs(AuditAction.PROCESS_SETTINGS_CHANGED)
    @PutMapping
    public ProcessSettingsResponse update(@RequestBody UpdateProcessSettingsRequest request) {
        return ProcessSettingsResponse.from(service.update(CurrentUsers.required(), request.fields()));
    }
}
