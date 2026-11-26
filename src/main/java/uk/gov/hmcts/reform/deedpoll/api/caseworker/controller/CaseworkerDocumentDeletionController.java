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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.ApiError;
import uk.gov.hmcts.reform.deedpoll.api.AuditedAs;
import uk.gov.hmcts.reform.deedpoll.api.BoundedJsonBodies;
import uk.gov.hmcts.reform.deedpoll.api.DocumentDeletionError;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CreateDocumentDeletionRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.DocumentDeletionAdmissionResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.DocumentDeletionRequestResponse;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionBodies;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionBodies;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRequest;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionStatus;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletions;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * A member of staff's permanent deletion of one supporting document, with their reason (plan
 * 20261125-permanent-document-deletion, Task 6; contracts in docs/DOCUMENT-DELETION.md).
 *
 * <p><strong>Who.</strong> Every staff role the {@code /caseworker/**} rule admits, and no capability beyond it:
 * the filter chain refuses everyone else (a judge, a citizen service, an unauthenticated caller) before this is
 * entered, and {@code DocumentDeletionService} asks the role again, then the case, before it looks at a replay. There
 * is no judicial route: Masters are read-only.
 *
 * <p><strong>The deletion runs in this request.</strong> 200 {@code Completed} means every step is confirmed and the
 * audit event and case note are written. 202 means a step is not yet confirmed, out of budget or held by another
 * worker ({@code InProgress}, which the recovery sweep finishes) or needs somebody ({@code NeedsAttention}); its
 * {@code Location} is the status resource. A replay of the same key and body answers the same request in the same
 * way, even after the document is gone.
 *
 * <p>The body is read by hand, bounded and closed, as application deletion's is (see {@link BoundedJsonBodies}), and
 * no author, actor or time is ever taken from it. Every answer is {@code no-store}.
 */
@RestController
public class CaseworkerDocumentDeletionController {

    /** The header admission identifies a request with. */
    public static final String IDEMPOTENCY_KEY = DocumentDeletionService.KEY_FIELD;

    /** The most of an admission body that is ever read: a 500-code-point reason fully escaped is about 6 KB. */
    static final int MAX_BODY_BYTES = 16 * 1024;

    private final DocumentDeletions deletions;
    private final DocumentDeletionService service;
    private final ObjectMapper json;

    public CaseworkerDocumentDeletionController(DocumentDeletions deletions, DocumentDeletionService service,
                                                ObjectMapper json) {
        this.deletions = deletions;
        this.service = service;
        this.json = json;
    }

    /**
     * Where a request is read back: the {@code Location} of every 202.
     */
    public static String statusPath(String caseId, UUID requestId) {
        return "/caseworker/cases/" + caseId + "/document-deletions/" + requestId;
    }

    @Operation(operationId = "requestDocumentDeletion", summary = "Delete a supporting document permanently",
        description = "Any staff role that can open the case (caseworker, Team leader, Delivery Manager); never a "
            + "judge. Permanently deletes the document's file and its record, with the reason in the staff member's "
            + "own words; this cannot be undone, and there is no restore. Runs while the caller waits: 200 Completed "
            + "means it is done and recorded once in the audit trail and as a case note under the caller. 202 means "
            + "it is not yet confirmed (InProgress, finished by recovery) or needs somebody (NeedsAttention): read "
            + "the Location; neither is deleted. Send one UUID Idempotency-Key per confirmation journey and reuse it, "
            + "with the same body, on every retry, including after a timeout: the same key and body answers the "
            + "same request, even after the document is gone; the same key with a different body is 409 KEY_REUSED. "
            + "Never retry with a new key to get past a refusal. Send the document's documentVersion from the case "
            + "detail as expectedDocumentVersion.",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = CreateDocumentDeletionRequest.class))))
    @ApiResponse(responseCode = "200", description = "Completed: the document is permanently deleted, or the same "
        + "completed request replayed.",
        content = @Content(schema = @Schema(implementation = DocumentDeletionAdmissionResponse.class)))
    @ApiResponse(responseCode = "202", description = "Admitted, or replayed, and not yet Completed: InProgress or "
        + "NeedsAttention. The document is no longer available, but it is not deleted yet.",
        headers = @Header(name = "Location", description = "The request's status resource.",
            schema = @Schema(type = "string")),
        content = @Content(schema = @Schema(implementation = DocumentDeletionAdmissionResponse.class)))
    @ApiResponse(responseCode = "400", description = "The Idempotency-Key is missing or not a UUID, the body has a "
        + "key other than reason and expectedDocumentVersion, or a field is refused, each field's error named. "
        + "Nothing is written.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "There is no such application or document on it, in the words "
        + "the other document routes use.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Refused, with a code and fixed words, and nothing written: "
        + "DOCUMENT_IN_USE (show its words; try again once the operation has finished), DOCUMENT_CHANGED (review "
        + "the document again), DOCUMENT_STORAGE_UNRESOLVED, DOCUMENT_SHARED, APPLICATION_DELETION_PENDING or "
        + "KEY_REUSED.", content = @Content(schema = @Schema(implementation = DocumentDeletionError.class)))
    @ApiResponse(responseCode = "413", description = "The body is over 16 KB. Nothing is written.")
    @ApiResponse(responseCode = "415", description = "The body is not application/json. Nothing is written.")
    @AuditedAs(AuditAction.DOCUMENT_DELETION_REQUESTED)
    @PostMapping("/caseworker/cases/{id}/documents/{documentId}/deletion-requests")
    public ResponseEntity<DocumentDeletionAdmissionResponse> request(
        @PathVariable("id") String id,
        @PathVariable("documentId") String documentId,
        @Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER, required = true,
            schema = @Schema(type = "string", format = "uuid"),
            description = "One UUID per confirmation journey, reused with the same body on every retry of it. "
                + "Missing or not a UUID is a 400 naming the header. The same key with a different body is 409.")
        @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
        HttpServletRequest http) throws HttpMediaTypeNotSupportedException {

        CreateDocumentDeletionRequest body =
            BoundedJsonBodies.read(http, json, CreateDocumentDeletionRequest.class, MAX_BODY_BYTES);
        Map<String, Object> fields = body == null ? Map.of() : body.fields();
        DocumentDeletionRequest request = deletions.delete(CurrentUsers.required(), id, documentId,
                                                           DocumentDeletionBodies.command(key, fields)).request();
        DocumentDeletionAdmissionResponse answer = DocumentDeletionAdmissionResponse.from(request);
        if (request.status() == DocumentDeletionStatus.Completed) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(answer);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .location(URI.create(statusPath(request.caseId(), request.id())))
            .cacheControl(CacheControl.noStore())
            .body(answer);
    }

    @Operation(operationId = "getDocumentDeletion", summary = "Read a document deletion request's status",
        description = "Any staff role that can open the case. Answers whether or not the document still exists; only "
            + "Completed means it is deleted. InProgress is still being carried out and NeedsAttention needs "
            + "somebody, with failure set only then. Reading never starts or retries work. Carries no reason, "
            + "document name, requester or storage locator. An id that is not a UUID, a request of another "
            + "application, and an application the caller cannot open are 404; an application later deleted as a "
            + "whole is a 404 too, which is not evidence about the document.")
    @ApiResponse(responseCode = "200",
        content = @Content(schema = @Schema(implementation = DocumentDeletionRequestResponse.class)))
    @ApiResponse(responseCode = "404", description = "There is no such application, or no such request on it.",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/caseworker/cases/{id}/document-deletions/{requestId}")
    public ResponseEntity<DocumentDeletionRequestResponse> status(
        @PathVariable("id") String id,
        @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable("requestId") String requestId) {

        // A malformed id is passed on as null, so it is the service's 404, after its role and case checks.
        DocumentDeletionRequest request = service.read(CurrentUsers.required(), id, DeletionBodies.key(requestId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(DocumentDeletionRequestResponse.from(request));
    }
}
