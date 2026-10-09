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
import uk.gov.hmcts.reform.deedpoll.api.GazetteError;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.GazetteMembersResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.GazettePreviewResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.GazetteRequestsResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.GazetteStatusResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.PreviewGazetteRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.SubmitGazetteRequest;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteBodies;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteConfirmation;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazettePreviews;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRefusal;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRequest;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRequestLedger;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRequestState;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteStatuses;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/caseworker")
public class CaseworkerGazetteRequestsController {
    private final GazettePreviews previews;
    private final GazetteRequestLedger ledger;
    private final GazetteStatuses statuses;

    public CaseworkerGazetteRequestsController(GazettePreviews previews, GazetteRequestLedger ledger,
                                               GazetteStatuses statuses) {
        this.previews = previews;
        this.ledger = ledger;
        this.statuses = statuses;
    }

    @Operation(operationId = "previewGazetteNotice", summary = "Check Gazette selection for confirmation",
        description = "Requires caseworker-deedpoll-admin. Checks recipient, readiness, reservations "
            + "and configuration. "
            + "Returns a 30-minute comparison token. No reservations, uploads or notes are written.")
    @ApiResponse(responseCode = "200",
        content = @Content(schema = @Schema(implementation = GazettePreviewResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = GazetteError.class)))
    @ApiResponse(responseCode = "503", content = @Content(schema = @Schema(implementation = GazetteError.class)))
    @PostMapping("/gazette-notice-previews")
    public GazettePreviewResponse preview(@RequestBody PreviewGazetteRequest body) {
        CurrentUser caller = admin();
        return GazettePreviewResponse.from(previews.preview(caller, GazetteBodies.selection(body.fields(), false)));
    }

    @Operation(operationId = "submitGazetteNotice", summary = "Resolve a Gazette request",
        description = "Requires caseworker-deedpoll-admin. Fresh HTTP submissions are unavailable until the sender "
            + "and recovery slice is implemented, regardless of configuration. Identical recorded requests can be "
            + "resolved: 202 means Preparing/Sending; 200 means terminal, not necessarily sent. "
            + "Reuse the original key and body after a lost response. Never retry an uncertain request with a new key.")
    @ApiResponse(responseCode = "200",
        content = @Content(schema = @Schema(implementation = GazetteStatusResponse.class)))
    @ApiResponse(responseCode = "202",
        content = @Content(schema = @Schema(implementation = GazetteStatusResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = GazetteError.class)))
    @ApiResponse(responseCode = "503", content = @Content(schema = @Schema(implementation = GazetteError.class)))
    @PostMapping("/gazette-notices")
    public ResponseEntity<GazetteStatusResponse> submit(@RequestBody SubmitGazetteRequest body,
                                                       @RequestHeader("Idempotency-Key") String header) {
        CurrentUser caller = admin();
        UUID key = key(header);
        List<String> ids = GazetteBodies.selection(body.fields(), true);
        String token = GazetteBodies.fingerprint(body.fields());
        int revision = GazetteBodies.revision(body.fields());
        GazetteRequest recorded = ledger.repeat(caller, key, ids, token, revision).orElse(null);
        if (recorded == null) {
            GazetteConfirmation.expiry(token);
            // No code/configuration switch may admit a fresh HTTP request without a delivery/recovery adapter.
            throw new GazetteRefusal(GazetteRefusal.Code.GAZETTE_UNAVAILABLE);
        }
        GazetteStatusResponse status = GazetteStatusResponse.from(statuses.detail(caller, recorded.id()));
        boolean pending = status.state() == GazetteRequestState.Preparing
            || status.state() == GazetteRequestState.Sending;
        return ResponseEntity.status(pending ? 202 : 200)
            .location(URI.create("/caseworker/gazette-notices/" + recorded.id())).body(status);
    }

    @Operation(operationId = "getGazetteRequests", summary = "Read Gazette request statuses",
        description = "Requires caseworker-deedpoll-admin. Newest submission first, then descending batch ID. "
            + "Legacy sent batches remain in sent history. Page defaults to 1; size defaults to 25, capped at 100.")
    @GetMapping("/gazette-notice-requests")
    public GazetteRequestsResponse requests(@RequestParam(required = false) String page,
                                            @RequestParam(required = false) String size,
                                            @RequestParam(required = false) String state) {
        return GazetteRequestsResponse.from(statuses.list(CurrentUsers.required(), page, size, state));
    }

    @Operation(operationId = "getGazetteNotice", summary = "Read one Gazette batch status",
        description = "Requires caseworker-deedpoll-admin. Includes legacy sent batches, with unknown submission, "
            + "recipient and group facts null. Sent means accepted by Notify, not delivered or published.")
    @GetMapping("/gazette-notices/{batchId}")
    public GazetteStatusResponse detail(@PathVariable UUID batchId) {
        return GazetteStatusResponse.from(statuses.detail(CurrentUsers.required(), batchId));
    }

    @Operation(operationId = "getGazetteNoticeCases", summary = "Read all frozen Gazette batch references",
        description = "Requires caseworker-deedpoll-admin. Reference then case ID ordering, independent of live cases. "
            + "Page defaults to 1; size defaults to 25, capped at 100. Includes legacy batches.")
    @GetMapping("/gazette-notices/{batchId}/cases")
    public GazetteMembersResponse members(@PathVariable UUID batchId, @RequestParam(required = false) String page,
                                          @RequestParam(required = false) String size) {
        return GazetteMembersResponse.from(statuses.members(CurrentUsers.required(), batchId, page, size));
    }

    private static CurrentUser admin() {
        CurrentUser caller = CurrentUsers.required();
        caller.requireAnyRole(Role.DEEDPOLL_ADMIN);
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
