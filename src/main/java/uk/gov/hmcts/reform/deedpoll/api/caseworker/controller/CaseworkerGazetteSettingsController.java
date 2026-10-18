package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.GazetteSettingsResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.UpdateGazetteSettingsRequest;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteSettingsService;

@RestController
@RequestMapping("/caseworker/gazette-settings")
public class CaseworkerGazetteSettingsController {

    private final GazetteSettingsService service;

    public CaseworkerGazetteSettingsController(GazetteSettingsService service) {
        this.service = service;
    }

    @Operation(operationId = "getGazetteSettings", summary = "Read London Gazette recipient settings",
        description = "Requires caseworker-deedpoll-delivery-manager. "
            + "Initially recipient and attribution are null, revision 0.")
    @GetMapping
    public GazetteSettingsResponse get() {
        return GazetteSettingsResponse.from(service.get(CurrentUsers.required()));
    }

    @Operation(operationId = "updateGazetteSettings", summary = "Update London Gazette recipient settings",
        description = "Requires caseworker-deedpoll-delivery-manager. "
            + "Supply the revision read with the form; stale revisions "
            + "return 409 without changes. Each changed address increments revision and records attribution. "
            + "Saving the current address at the current revision is a no-op. No email is sent.")
    @PutMapping
    public GazetteSettingsResponse update(@RequestBody UpdateGazetteSettingsRequest request) {
        return GazetteSettingsResponse.from(service.update(CurrentUsers.required(), request.fields()));
    }
}
