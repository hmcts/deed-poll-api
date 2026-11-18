package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.ApiError;
import uk.gov.hmcts.reform.deedpoll.api.AuditedAs;
import uk.gov.hmcts.reform.deedpoll.api.DeletionError;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CreateDeletionRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.DeletionAdmissionResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.DeletionRequestResponse;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService.Admission;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionBodies;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionRequest;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * A Delivery Manager's permanent deletion of an application created in error or duplicated (plan
 * 20261118-dm-application-deletion, Task 7): admitting the request, and reading where it has got to.
 *
 * <p><strong>Capability first, on both routes.</strong> {@code DELETE_APPLICATION} is checked before the path id,
 * the header or the body is read, so a caller without it is a 403 whatever they sent and learns nothing about the
 * case or the request. That is why the header and the status id are bound as text and parsed here rather than by
 * Spring, which would answer a malformed one 400 before the check could run.
 *
 * <p><strong>202, always, and never "deleted".</strong> Admission writes the request, its fence and its audit event,
 * and nothing is removed until the worker's verified cleanup finalises it; a replay of the same key and body answers
 * the same request with 202 again, even once the case is gone. {@code Location} names the status resource, and only
 * its {@code Completed} means the application is gone. Polling it never starts work.
 *
 * <p>Every answer here is {@code no-store}: each describes one request at one moment.
 */
@RestController
public class CaseworkerApplicationDeletionController {

    /** The header admission identifies a request with, as the email send's is. */
    public static final String IDEMPOTENCY_KEY = ApplicationDeletionService.KEY_FIELD;

    /**
     * Where a request is read back; the {@code Location} of every admission.
     */
    public static final String STATUS_PATH = "/caseworker/application-deletions/";

    private final ApplicationDeletionService deletions;

    public CaseworkerApplicationDeletionController(ApplicationDeletionService deletions) {
        this.deletions = deletions;
    }

    @Operation(operationId = "requestApplicationDeletion", summary = "Request permanent deletion of an application",
        description = "Requires caseworker-deedpoll-delivery-manager (DELETE_APPLICATION), checked before the case, "
            + "the header or the body is read. Permanently deletes an application created in error or duplicated; "
            + "this is not a void, archive or withdrawal. 202 means the request is admitted and the application is "
            + "fenced, not that it is deleted: read the Location until its status is Completed. A linked case waits "
            + "for CCD Retain and Dispose (AwaitingDisposal). Send one UUID Idempotency-Key per confirmation journey "
            + "and reuse it, with the same body, on every retry: the same key and body answers the same request with "
            + "202, even after the application is gone; the same key with a different body is 409 KEY_REUSED. A "
            + "second key for the same reason answers the existing request; for the other reason it is 409 "
            + "CONFLICTING_REASON. Never retry with a new key to get past a refusal.")
    @ApiResponse(responseCode = "202", description = "Admitted, or the same request replayed. Not deleted yet.",
        headers = @Header(name = "Location", description = "The request's status resource.",
            schema = @Schema(type = "string")),
        content = @Content(schema = @Schema(implementation = DeletionAdmissionResponse.class)))
    @ApiResponse(responseCode = "400", description = "The Idempotency-Key is missing or not a UUID, the body has a "
        + "key other than reason, note and expectedModifiedOn, or a field is refused, each field's error named. "
        + "Nothing is written.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "There is no such application and no request for it.",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Refused, with a code and fixed words, and nothing written: "
        + "STALE_CONFIRMATION (review the application again), RETAINED_PERMANENTLY, GAZETTE_EVIDENCE, "
        + "RETAINED_DEED_PAYLOAD, RETAINED_STAGED_UPLOADS, DOCUMENT_STORAGE_UNRESOLVED, CCD_CREATION_UNRESOLVED, "
        + "EMAIL_SEND_UNRESOLVED (try again once it settles), KEY_REUSED or CONFLICTING_REASON.",
        content = @Content(schema = @Schema(implementation = DeletionError.class)))
    @AuditedAs(AuditAction.CASE_DELETION_REQUESTED)
    @PostMapping("/caseworker/cases/{id}/deletion-requests")
    public ResponseEntity<DeletionAdmissionResponse> request(
        @PathVariable("id") String id,
        @Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER, required = true,
            schema = @Schema(type = "string", format = "uuid"),
            description = "One UUID per confirmation journey, reused with the same body on every retry of it. "
                + "Missing or not a UUID is a 400 naming the header. The same key with a different body is 409.")
        @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
        @RequestBody(required = false) CreateDeletionRequest body) {

        CurrentUser caller = deletionCaller();
        Map<String, Object> fields = body == null ? Map.of() : body.fields();
        Admission admission = deletions.request(caller, id, DeletionBodies.command(key, fields));
        DeletionRequest request = admission.request();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .location(URI.create(STATUS_PATH + request.id()))
            .cacheControl(CacheControl.noStore())
            .body(DeletionAdmissionResponse.from(request));
    }

    @Operation(operationId = "getApplicationDeletion", summary = "Read a deletion request's status",
        description = "Requires caseworker-deedpoll-delivery-manager (DELETE_APPLICATION), checked before the id is "
            + "read; any Delivery Manager may read any request. Answers whether or not the application still exists. "
            + "Only Completed means the application is deleted; Pending, InProgress, AwaitingDisposal and "
            + "NeedsAttention are a request still being carried out or one that needs somebody, with failure set "
            + "only for NeedsAttention. Reading never starts or retries work. An id that is not a UUID is a 404.")
    @ApiResponse(responseCode = "200",
        content = @Content(schema = @Schema(implementation = DeletionRequestResponse.class)))
    @ApiResponse(responseCode = "404", description = "There is no such deletion request.",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping(STATUS_PATH + "{requestId}")
    public ResponseEntity<DeletionRequestResponse> status(
        @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("requestId") String requestId) {

        CurrentUser caller = deletionCaller();
        DeletionRequest request = deletions.read(caller, requestId(requestId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(DeletionRequestResponse.from(request));
    }

    private static CurrentUser deletionCaller() {
        CurrentUser caller = CurrentUsers.required();
        caller.require(Capability.DELETE_APPLICATION);
        return caller;
    }

    /** A canonical UUID; anything else names no request, in the words an unknown one gets. */
    private static UUID requestId(String raw) {
        UUID id = DeletionBodies.key(raw);
        if (id == null) {
            throw new NotFoundError("Deletion request not found");
        }
        return id;
    }
}
