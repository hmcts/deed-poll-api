package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.reform.deedpoll.api.ApiError;
import uk.gov.hmcts.reform.deedpoll.api.AuditedAs;
import uk.gov.hmcts.reform.deedpoll.api.DeletionError;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CreateDeletionRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.DeletionAdmissionResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.DeletionRequestResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.DeletionRestoreResponse;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService.Admission;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionBodies;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionRequest;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A Delivery Manager's permanent deletion of an application created in error or duplicated (plan
 * 20261118-dm-application-deletion, Task 7): admitting the request, reading where it has got to, and restoring the
 * application during the request's grace period (addendum 20261119-deletion-grace-period-and-restore).
 *
 * <p><strong>Capability first, on every route.</strong> {@code DELETE_APPLICATION} is checked before the path id,
 * the header or the body is read, so a caller without it is a 403 whatever they sent and learns nothing about the
 * case or the request. That is why the header and the status id are bound as text and parsed here rather than by
 * Spring, which would answer a malformed one 400 before the check could run. The body is not bound at all for the
 * same reason: a {@code @RequestBody} would be read and deserialised before the method is entered, so unparseable
 * JSON or an unsupported media type would answer 400 or 415 to a caller who is owed 403, and a caller without the
 * capability could make the pod buffer a body of any size first. After the check the body is read here, at most
 * {@link #MAX_BODY_BYTES} of it (413 beyond), and only as {@code application/json} (415 otherwise).
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

    /**
     * The most of an admission body that is ever read. A note is at most {@link
     * uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionNote#MAX_LENGTH} code points, which even fully escaped as
     * JSON surrogate pairs is about 6 KB; the rest of the body is a reason and a timestamp.
     */
    static final int MAX_BODY_BYTES = 16 * 1024;

    private final ApplicationDeletionService deletions;
    private final ObjectMapper json;

    public CaseworkerApplicationDeletionController(ApplicationDeletionService deletions, ObjectMapper json) {
        this.deletions = deletions;
        this.json = json;
    }

    @Operation(operationId = "requestApplicationDeletion", summary = "Request permanent deletion of an application",
        description = "Requires caseworker-deedpoll-delivery-manager (DELETE_APPLICATION), checked before the case, "
            + "the header or the body is read. Permanently deletes an application created in error or duplicated; "
            + "this is not a void, archive or withdrawal. 202 means the request is admitted and the application is "
            + "fenced, not that it is deleted: read the Location until its status is Completed. Nothing is removed "
            + "before restorableUntil, the end of the grace period set by the Delivery Manager's process setting "
            + "when the request is admitted. A linked case waits "
            + "for CCD Retain and Dispose (AwaitingDisposal). Send one UUID Idempotency-Key per confirmation journey "
            + "and reuse it, with the same body, on every retry: the same key and body answers the same request with "
            + "202, even after the application is gone; the same key with a different body is 409 KEY_REUSED. A "
            + "second key for the same reason answers the existing request; for the other reason it is 409 "
            + "CONFLICTING_REASON. Never retry with a new key to get past a refusal.",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = CreateDeletionRequest.class))))
    @ApiResponse(responseCode = "202", description = "Admitted, or the same request replayed. Not deleted yet. After a "
        + "restore, repeating the original request is 409 STALE_CONFIRMATION, not a replay.",
        headers = @Header(name = "Location", description = "The request's status resource.",
            schema = @Schema(type = "string")),
        content = @Content(schema = @Schema(implementation = DeletionAdmissionResponse.class)))
    @ApiResponse(responseCode = "400", description = "The Idempotency-Key is missing or not a UUID, the body has a "
        + "key other than reason, note and expectedLastChangedOn, or a field is refused, each field's error named. "
        + "Nothing is written.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "There is no such application and no request for it.",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "413", description = "The body is over 16 KB. Nothing is written.")
    @ApiResponse(responseCode = "415", description = "The body is not application/json. Nothing is written.")
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
        HttpServletRequest http) throws HttpMediaTypeNotSupportedException {

        CurrentUser caller = deletionCaller();
        CreateDeletionRequest body = decode(http);
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
            + "Only Completed means the application is deleted. While the request is Pending and restorableUntil "
            + "has not passed, nothing has been removed and the application can be restored. Pending, InProgress, "
            + "AwaitingDisposal and "
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

    @Operation(operationId = "restoreApplicationDeletion", summary = "Restore an application during its grace period",
        description = "Requires caseworker-deedpoll-delivery-manager (DELETE_APPLICATION), checked before the id is "
            + "read; any Delivery Manager may restore any request. Allowed only while the request is Pending, cleanup "
            + "has not started and restorableUntil has not passed. Restoring deletes the deletion request, so the "
            + "application is visible and can be changed again at once, and the request's status resource is a 404 "
            + "from then on. Nothing outside the service is removed before restorableUntil, so nothing remote is "
            + "undone. Deleting the application again needs a new review and a new request. Takes no body. An id "
            + "that is not a UUID, or a request already restored, is a 404.")
    @ApiResponse(responseCode = "200", description = "Restored.",
        content = @Content(schema = @Schema(implementation = DeletionRestoreResponse.class)))
    @ApiResponse(responseCode = "404", description = "There is no such deletion request, or it was already restored.",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Refused, with a code and fixed words, and nothing written: "
        + "RESTORE_WINDOW_CLOSED (restorableUntil has passed) or CLEANUP_STARTED (the deletion is under way or done).",
        content = @Content(schema = @Schema(implementation = DeletionError.class)))
    @AuditedAs(AuditAction.CASE_DELETION_RESTORED)
    @PostMapping(STATUS_PATH + "{requestId}/restore")
    public ResponseEntity<DeletionRestoreResponse> restore(
        @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("requestId") String requestId) {

        CurrentUser caller = deletionCaller();
        String caseId = deletions.restore(caller, requestId(requestId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(DeletionRestoreResponse.of(caseId));
    }

    /**
     * The body, read and decoded only once the caller is known to hold {@code DELETE_APPLICATION}: no more than
     * {@link #MAX_BODY_BYTES} of it is ever held, and only JSON is decoded. An absent body is null, which the
     * service refuses field by field. Unparseable JSON is the service's ordinary unreadable-body 400, which names
     * nothing the caller sent.
     */
    private CreateDeletionRequest decode(HttpServletRequest http) throws HttpMediaTypeNotSupportedException {
        refuseForm(http.getHeader(HttpHeaders.CONTENT_TYPE));
        byte[] raw;
        try (InputStream in = http.getInputStream()) {
            raw = in.readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException unreadable) {
            throw new HttpMessageNotReadableException("The deletion request body could not be read", unreadable, null);
        }
        if (raw.length == 0) {
            return null;
        }
        if (raw.length > MAX_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        requireJson(http.getHeader(HttpHeaders.CONTENT_TYPE));
        try {
            return json.readValue(raw, CreateDeletionRequest.class);
        } catch (IOException unreadable) {
            throw new HttpMessageNotReadableException("The deletion request body is not valid JSON", unreadable, null);
        }
    }

    /**
     * Refuses 415 for a form body before the stream is read. The dispatcher has already parsed a multipart body into
     * parts, so its stream reads as empty and would otherwise reach the service's field-by-field 400s; a urlencoded
     * body is refused alike, so neither form is ever answered as though it were a missing JSON body.
     */
    private static void refuseForm(String header) throws HttpMediaTypeNotSupportedException {
        if (header == null) {
            return;
        }
        String type = header.trim().toLowerCase(Locale.ROOT);
        if (type.startsWith("multipart/") || type.startsWith("application/x-www-form-urlencoded")) {
            throw new HttpMediaTypeNotSupportedException((MediaType) null, List.of(MediaType.APPLICATION_JSON));
        }
    }

    /**
     * Refuses 415 unless the body is {@code application/json} or an {@code application/*+json}.
     */
    private static void requireJson(String header) throws HttpMediaTypeNotSupportedException {
        List<MediaType> supported = List.of(MediaType.APPLICATION_JSON);
        if (header == null || header.isBlank()) {
            throw new HttpMediaTypeNotSupportedException((MediaType) null, supported);
        }
        MediaType type;
        try {
            type = MediaType.parseMediaType(header);
        } catch (InvalidMediaTypeException unparseable) {
            throw new HttpMediaTypeNotSupportedException((MediaType) null, supported);
        }
        boolean isJson = "application".equals(type.getType())
            && ("json".equals(type.getSubtype()) || type.getSubtype().endsWith("+json"));
        if (!isJson) {
            throw new HttpMediaTypeNotSupportedException(type, supported);
        }
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
