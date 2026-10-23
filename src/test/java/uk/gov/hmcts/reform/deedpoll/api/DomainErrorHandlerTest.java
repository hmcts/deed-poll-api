package uk.gov.hmcts.reform.deedpoll.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewFailure;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status code and body each domain error maps to.
 *
 * <p>Asserted directly rather than through a probe route, because what is being tested is a table: six
 * exception types and the answer each one gets. {@code CaseworkerReadControllerTest} is what proves the advice
 * is actually registered on the dispatcher -- a 404 carrying the domain's message, and a 400 carrying the
 * offending parameter name, both over real HTTP. The two together cover more than a set of probe routes would,
 * and cost less.
 *
 * <p>Two of the domain errors still have no route that can throw them -- {@link UnauthorizedError} and
 * {@link ConflictError} -- and are mapped and tested anyway. The mapping is one class either way, and an untested
 * branch in an error handler is exactly the branch that turns out to answer 500.
 *
 * <p>The two multipart branches are here for the table's sake and pinned to the dispatcher elsewhere:
 * {@code CaseworkerDocumentUploadLimitTest} proves this advice is consulted for a refusal raised while the
 * request was still being resolved, when no handler method had been chosen for a controller-local handler to sit
 * on. It covers both, because an over-size file and an unparseable body take different paths to the same place
 * and one being mapped has never implied the other is.
 */
class DomainErrorHandlerTest {

    private final DomainErrorHandler handler = new DomainErrorHandler();

    @Test
    @DisplayName("a rejected payload is a 400 carrying the failures attributed to their fields")
    void invalidInputIsABadRequestWithFieldErrors() {
        List<FieldError> errors = List.of(
            new FieldError("dateReceived", "is required"),
            new FieldError("state", "cannot be both Enroled and Returned"));

        ResponseEntity<ApiError> response =
            handler.invalidInput(new InvalidInputError("Case dates are inconsistent", errors));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("Case dates are inconsistent");
        assertThat(response.getBody().errors()).isEqualTo(errors);
    }

    @Test
    @DisplayName("a rejected payload with no field to blame still answers 400, with an empty error list")
    void invalidInputWithoutFieldsIsStillABadRequest() {
        ResponseEntity<ApiError> response = handler.invalidInput(new InvalidInputError("Nothing to update"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errors()).isEmpty();
    }

    @Test
    @DisplayName("a missing record is a 404 carrying the domain's message")
    void notFoundIsA404() {
        ResponseEntity<ApiError> response = handler.notFound(new NotFoundError("DeedPoll abc not found"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("DeedPoll abc not found");
    }

    @Test
    @DisplayName("an unidentified caller is a 401, and names the scheme to retry with")
    void unauthorizedIsA401WithAChallenge() {
        // RFC 9110 section 11.6.1 requires WWW-Authenticate on a 401. Without it the status is a number and
        // a client has to guess how to authenticate.
        ResponseEntity<ApiError> response = handler.unauthorized(new UnauthorizedError("No token presented"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("a caller without the role is a 403, not folded into the 401")
    void forbiddenIsA403() {
        // Kept apart deliberately: a 401 tells a caseworker to re-authenticate, and one who simply lacks the
        // admin role would do that forever without getting any further.
        ResponseEntity<ApiError> response = handler.forbidden(new ForbiddenError("Requires the admin role"),
            new MockHttpServletRequest("GET", "/caseworker/cases"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("Requires the admin role");
    }

    @Test
    @DisplayName("a state that forbids the request is a 409, since the payload has nothing to correct")
    void conflictIsA409() {
        ResponseEntity<ApiError> response =
            handler.conflict(new ConflictError("Cannot delete this document type: 2 document(s) reference it"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errors()).isEmpty();
    }

    @Test
    @DisplayName("an unconvertible parameter is a 400 naming the parameter, and not echoing its value")
    void unconvertibleParameterIsA400NamingTheParameter() {
        MethodArgumentTypeMismatchException mismatch = new MethodArgumentTypeMismatchException(
            "nonsense", String.class, "type", null, new IllegalArgumentException("no enum constant"));

        ResponseEntity<ApiError> response = handler.unconvertibleParameter(mismatch);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errors()).hasSize(1);
        assertThat(response.getBody().errors().get(0).field()).isEqualTo("type");
        // The rejected value is caller-supplied text and this body is rendered by whatever is on the other
        // end. Naming the parameter is what makes the error actionable; repeating the value adds nothing.
        assertThat(response.getBody().errors().get(0).message()).doesNotContain("nonsense");
        assertThat(response.getBody().error()).doesNotContain("nonsense");
    }

    @Test
    @DisplayName("a file over the limit is the upload policy's own 400, attributed to the file input")
    void tooLargeAnUploadIsThePolicysBadRequest() {
        // Translated into the domain's refusal rather than answered from here, so the sentence a caller reads is
        // the one the sibling web app's form gives and is derived from the same constant. The transport's own
        // rejection is not a domain error: unwrapped it becomes a 500, telling a caseworker the server has
        // broken when in fact their file was too big.
        ResponseEntity<ApiError> response = handler.uploadTooLarge();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error())
            .isEqualTo(UploadPolicy.uploadLimitRejection("LIMIT_FILE_SIZE").getMessage());
        assertThat(response.getBody().errors()).hasSize(1);
        assertThat(response.getBody().errors().get(0).field()).isEqualTo(UploadPolicy.UPLOAD_FILE_FIELD);
    }

    @Test
    @DisplayName("an unparseable multipart request is a 400, not the 500 nothing else would have caught")
    void anUnparseableMultipartIsABadRequest() {
        // MultipartException is a NestedRuntimeException, not an ErrorResponse and not a ServletException, so it
        // is absent from DefaultHandlerExceptionResolver's chain -- and ResponseEntityExceptionHandler is
        // deliberately not extended here. Unmapped, a caller aborting an upload mid-body pages on-call.
        ResponseEntity<ApiError> response = handler.unreadableMultipart();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errors()).hasSize(1);
        assertThat(response.getBody().errors().get(0).field()).isEqualTo(UploadPolicy.UPLOAD_FILE_FIELD);
        // The policy's vague message, which is what its unenumerated-code fallback exists to give. Read from the
        // policy rather than transcribed, so correcting the wording there corrects this too.
        assertThat(response.getBody().error())
            .isEqualTo(UploadPolicy.uploadLimitRejection(UploadPolicy.UNREADABLE_UPLOAD).getMessage());
    }

    @Test
    @DisplayName("the two multipart refusals give different messages, so the size limit is not swallowed")
    void theSizeLimitIsNotSwallowedByTheGenericMultipartHandler() {
        // MaxUploadSizeExceededException is a subclass of MultipartException, so registering the parent risks the
        // specific message being lost. Spring resolves by hierarchy depth, and the two messages differing is what
        // makes that visible if the resolution ever changes.
        assertThat(handler.uploadTooLarge().getBody()).isNotNull();
        assertThat(handler.unreadableMultipart().getBody()).isNotNull();
        assertThat(handler.uploadTooLarge().getBody().error())
            .isNotEqualTo(handler.unreadableMultipart().getBody().error());
    }

    @Test
    @DisplayName("a busy preview's Retry-After is whole seconds, rounded up so a client never retries early")
    void previewRetryAfterRoundsUp() {
        assertThat(handler.preview(PreviewError.busy(Duration.ofMillis(1500))).getHeaders()
                       .getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(handler.preview(PreviewError.busy(Duration.ofSeconds(1))).getHeaders()
                       .getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(handler.preview(new PreviewError(PreviewFailure.LIMIT_EXCEEDED)).getHeaders()
                       .containsKey(HttpHeaders.RETRY_AFTER)).isFalse();
    }
}
