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
            + "The referral claim window starts at 120 minutes, revision 0, with null attribution.")
    @GetMapping
    public ProcessSettingsResponse get() {
        return ProcessSettingsResponse.from(service.get(CurrentUsers.required()));
    }

    @Operation(operationId = "updateProcessSettings", summary = "Update the Delivery Manager's process settings",
        description = "Requires caseworker-deedpoll-delivery-manager (MANAGE_PROCESS_CONFIG). "
            + "Supply the revision read with the form; a stale revision returns 409 with no change, even when the "
            + "value already matches. A change increments revision, records attribution and is audited. Saving the "
            + "current value at the current revision is a no-op. A new claim window applies to every live claim "
            + "from the next judicial pool, My referrals or claim request; saving it releases nothing itself, and "
            + "raising it never restores a claim already released.")
    @AuditedAs(AuditAction.PROCESS_SETTINGS_CHANGED)
    @PutMapping
    public ProcessSettingsResponse update(@RequestBody UpdateProcessSettingsRequest request) {
        return ProcessSettingsResponse.from(service.update(CurrentUsers.required(), request.fields()));
    }
}
