package uk.gov.hmcts.reform.deedpoll.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;

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
 * <p>Three of the six have no route that can throw them yet, and are mapped and tested anyway. The mapping is
 * one class either way, tasks 14 and 15 will reach {@link InvalidInputError} and {@link ConflictError}, and an
 * untested branch in an error handler is exactly the branch that turns out to answer 500.
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
        ResponseEntity<ApiError> response = handler.forbidden(new ForbiddenError("Requires the admin role"));

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
}
