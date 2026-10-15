package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.AuditedAs;
import uk.gov.hmcts.reform.deedpoll.api.GazetteError;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.GazetteMembersResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.GazettePreviewResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.GazetteRequestsResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.GazetteStatusResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.PreviewGazetteRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.SubmitGazetteRequest;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteBodies;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteDelivery;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazettePreviews;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRequestState;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteStatuses;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/caseworker")
public class CaseworkerGazetteRequestsController {
    private final GazettePreviews previews;
    private final GazetteDelivery delivery;
    private final GazetteStatuses statuses;

    public CaseworkerGazetteRequestsController(GazettePreviews previews, GazetteDelivery delivery,
                                               GazetteStatuses statuses) {
        this.previews = previews;
        this.delivery = delivery;
        this.statuses = statuses;
    }

    @Operation(operationId = "previewGazetteNotice", summary = "Check Gazette selection for confirmation",
        description = "Requires caseworker-deedpoll-delivery-manager. "
            + "Checks recipient, readiness, reservations "
            + "and configuration. "
            + "Returns a 30-minute comparison token. No reservations, uploads or notes are written.")
    @ApiResponse(responseCode = "200",
        content = @Content(schema = @Schema(implementation = GazettePreviewResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = GazetteError.class)))
    @ApiResponse(responseCode = "503", content = @Content(schema = @Schema(implementation = GazetteError.class)))
    @PostMapping("/gazette-notice-previews")
    public GazettePreviewResponse preview(@RequestBody PreviewGazetteRequest body) {
        CurrentUser caller = gazetteCaller();
        return GazettePreviewResponse.from(previews.preview(caller, GazetteBodies.selection(body.fields(), false)));
    }

    @Operation(operationId = "submitGazetteNotice", summary = "Submit or resolve a Gazette request",
        description = "Requires caseworker-deedpoll-delivery-manager. "
            + "Sending is disabled by default. "
            + "When configured and enabled, "
            + "a fresh confirmed request makes at most one email attempt. Repeats resolve recorded status only. "
            + "202 means Preparing/Sending; 200 means terminal, not necessarily sent. "
            + "Reuse the original key and body after a lost response. Never retry an uncertain request with a new key.")
    @ApiResponse(responseCode = "200",
        content = @Content(schema = @Schema(implementation = GazetteStatusResponse.class)))
    @ApiResponse(responseCode = "202",
        content = @Content(schema = @Schema(implementation = GazetteStatusResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = GazetteError.class)))
    @ApiResponse(responseCode = "503", content = @Content(schema = @Schema(implementation = GazetteError.class)))
    @AuditedAs(AuditAction.GAZETTE_NOTICE_SENT)
    @PostMapping("/gazette-notices")
    public ResponseEntity<GazetteStatusResponse> submit(@RequestBody SubmitGazetteRequest body,
                                                       @RequestHeader("Idempotency-Key") String header) {
        CurrentUser caller = gazetteCaller();
        UUID key = key(header);
        List<String> ids = GazetteBodies.selection(body.fields(), true);
        String token = GazetteBodies.fingerprint(body.fields());
        int revision = GazetteBodies.revision(body.fields());
        UUID batch = delivery.submit(caller, key, ids, token, revision);
        GazetteStatusResponse status = GazetteStatusResponse.from(statuses.detail(caller, batch));
        boolean pending = status.state() == GazetteRequestState.Preparing
            || status.state() == GazetteRequestState.Sending;
        return ResponseEntity.status(pending ? 202 : 200)
            .location(URI.create("/caseworker/gazette-notices/" + batch)).body(status);
    }

    @Operation(operationId = "getGazetteRequests", summary = "Read Gazette request statuses",
        description = "Requires caseworker-deedpoll-delivery-manager. "
            + "Newest submission first, then descending batch ID. "
            + "Legacy sent batches remain in sent history. Page defaults to 1; size defaults to 25, capped at 100.")
    @GetMapping("/gazette-notice-requests")
    public GazetteRequestsResponse requests(@RequestParam(required = false) String page,
                                            @RequestParam(required = false) String size,
                                            @RequestParam(required = false) String state) {
        return GazetteRequestsResponse.from(statuses.list(CurrentUsers.required(), page, size, state));
    }

    @Operation(operationId = "getGazetteNotice", summary = "Read one Gazette batch status",
        description = "Requires caseworker-deedpoll-delivery-manager. "
            + "Includes legacy sent batches, with unknown submission, "
            + "recipient and group facts null. Sent means accepted by Notify, not delivered or published.")
    @GetMapping("/gazette-notices/{batchId}")
    public GazetteStatusResponse detail(@PathVariable UUID batchId) {
        return GazetteStatusResponse.from(statuses.detail(CurrentUsers.required(), batchId));
    }

    @Operation(operationId = "getGazetteNoticeCases", summary = "Read all frozen Gazette batch references",
        description = "Requires caseworker-deedpoll-delivery-manager. "
            + "Reference then case ID ordering, independent of live cases. "
            + "Page defaults to 1; size defaults to 25, capped at 100. Includes legacy batches.")
    @GetMapping("/gazette-notices/{batchId}/cases")
    public GazetteMembersResponse members(@PathVariable UUID batchId, @RequestParam(required = false) String page,
                                          @RequestParam(required = false) String size) {
        return GazetteMembersResponse.from(statuses.members(CurrentUsers.required(), batchId, page, size));
    }

    private static CurrentUser gazetteCaller() {
        CurrentUser caller = CurrentUsers.required();
        caller.require(Capability.ACCESS_GAZETTE);
        return caller;
    }

    private static UUID key(String header) {
        try {
            UUID key = UUID.fromString(header);
            if (!key.toString().equalsIgnoreCase(header)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
            return key;
        } catch (IllegalArgumentException invalid) {
            throw new InvalidInputError("Idempotency-Key must be a UUID.");
        }
    }
}
