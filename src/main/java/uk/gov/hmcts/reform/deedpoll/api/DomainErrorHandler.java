package uk.gov.hmcts.reform.deedpoll.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;

import java.util.List;

/**
 * Turns the domain's errors into status codes, once for the whole service.
 *
 * <p>Ported from {@code apps/api/src/errors.ts}, and the mapping is that file's: 400 for
 * {@link InvalidInputError}, 404 for {@link NotFoundError}, 401 for {@link UnauthorizedError}, 403 for
 * {@link ForbiddenError}, 409 for {@link ConflictError}. Doing it in one place rather than per handler is why
 * every controller can call the domain and throw, and why a missing case cannot answer 404 on one route and
 * 500 on another.
 *
 * <p>This is also what keeps the domain free of HTTP. {@code NotFoundError} is not a
 * {@code ResponseStatusException} and none of the ported services knows what a status code is -- the same
 * services will later be called by a CCD callback and by the citizen namespace, where the right answer to a
 * missing record is not necessarily a 404.
 *
 * <p><strong>There is deliberately no catch-all {@code Exception} handler</strong>, which the reference has and
 * needs. Express leaks a stack trace from an unhandled rejection, so {@code errors.ts} intercepts everything
 * and substitutes a generic message. Spring Boot already answers 500 with a body carrying no message and no
 * trace, and a handler broad enough to catch an unrecognised failure here would also catch Spring MVC's own
 * signalling -- {@code NoResourceFoundException} for an unknown path, for one -- and turn a correct 404 into a
 * 500. The narrow mapping is the safer one; the generic answer is the framework's default rather than
 * something restated.
 */
@RestControllerAdvice
public class DomainErrorHandler {

    /**
     * A rejected payload, with the failures attributed to their fields.
     *
     * <p>The field list is not decoration: the reference records that a message-only error quietly downgrades
     * every field-level failure to an unattributed banner, and the forms render each one against its own input.
     */
    @ExceptionHandler(InvalidInputError.class)
    public ResponseEntity<ApiError> invalidInput(InvalidInputError error) {
        return ResponseEntity.badRequest().body(ApiError.of(error.getMessage(), error.errors()));
    }

    /** No such record, or none visible in the scope it was asked for. */
    @ExceptionHandler(NotFoundError.class)
    public ResponseEntity<ApiError> notFound(NotFoundError error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(error.getMessage()));
    }

    /**
     * The caller has not proved who they are.
     *
     * <p>{@code WWW-Authenticate} is what makes this a 401 rather than just a number: RFC 9110 section 11.6.1
     * requires it, and it is how a client knows which scheme to retry with. Spring Security's entry point sets
     * it for a request the filter chain refuses; this covers the case a handler refuses after being admitted.
     */
    @ExceptionHandler(UnauthorizedError.class)
    public ResponseEntity<ApiError> unauthorized(UnauthorizedError error) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .body(ApiError.of(error.getMessage()));
    }

    /**
     * The caller is known and lacks a role this operation requires.
     *
     * <p>Not collapsed into the 401: a caseworker whose token has simply expired needs to re-authenticate,
     * and one without the admin role never will succeed however often they do.
     */
    @ExceptionHandler(ForbiddenError.class)
    public ResponseEntity<ApiError> forbidden(ForbiddenError error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(error.getMessage()));
    }

    /**
     * The request is well-formed and permitted, and the current state of the data forbids it.
     *
     * <p>409 rather than 400 because there is nothing in the payload to correct: it would succeed unchanged
     * once the conflicting data is gone.
     */
    @ExceptionHandler(ConflictError.class)
    public ResponseEntity<ApiError> conflict(ConflictError error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(error.getMessage()));
    }

    /**
     * A query or path parameter that will not convert -- an unknown enum member, or a repeated parameter.
     *
     * <p>Spring already answers 400 for this, so what is added is the attribution: which parameter was wrong.
     * The reference makes the same point about the endpoint it bit on, {@code field-rules}, where the prototype
     * cast an unvalidated {@code type} and {@code ?type=nonsense} fell through the rule matrix to answer 200
     * with the rules for no type at all -- a wrong answer presented as a right one.
     *
     * <p>The rejected value is deliberately not echoed. It is caller-supplied text and this body is rendered
     * by whatever is on the other end; naming the parameter is what makes the error actionable, and repeating
     * the value adds nothing the caller does not already have.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> unconvertibleParameter(MethodArgumentTypeMismatchException error) {
        FieldError field = new FieldError(error.getName(), "is not a valid value for this parameter");
        return ResponseEntity.badRequest().body(ApiError.of(ApiError.INVALID_PARAMETER, List.of(field)));
    }
}
