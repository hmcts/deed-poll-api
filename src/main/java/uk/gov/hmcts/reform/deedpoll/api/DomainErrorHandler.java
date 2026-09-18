package uk.gov.hmcts.reform.deedpoll.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.IncompleteDataError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.UpstreamFailureError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;

import java.util.List;

/**
 * Turns the domain's errors into status codes, once for the whole service.
 *
 * <p>Ported from {@code apps/api/src/errors.ts}, and the mapping is that file's: 400 for
 * {@link InvalidInputError}, 404 for {@link NotFoundError}, 401 for {@link UnauthorizedError}, 403 for
 * {@link ForbiddenError}, 409 for {@link ConflictError}. Two are not the reference's, because the reference sent
 * no email and so had nothing to answer them for: 422 for {@link IncompleteDataError} and 502 for
 * {@link UpstreamFailureError}, each argued on the error itself. Doing it in one place rather than per handler is
 * why every controller can call the domain and throw, and why a missing case cannot answer 404 on one route and
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
     * The request is fine and the record it is about is missing something the operation needs.
     *
     * <p>422 rather than 409 or 400, and the argument is on {@link IncompleteDataError}: there is nothing in the
     * payload to correct and nothing that will change by retrying, because the missing value is a column of the
     * stored record. The fields are published exactly as a 400's are -- they are what lets a client say which
     * column is empty rather than "something went wrong".
     */
    @ExceptionHandler(IncompleteDataError.class)
    public ResponseEntity<ApiError> incompleteData(IncompleteDataError error) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ApiError.of(error.getMessage(), error.errors()));
    }

    /**
     * A service this one depends on refused or could not answer.
     *
     * <p>502 rather than 500, so the answer names where the failure was: this service worked and its dependency
     * did not. The message is the domain's own composed text, because an outbound client's exception message
     * quotes the request that was made -- the point {@link #unreadableBody()} makes about the framework's
     * messages, with more at stake.
     */
    @ExceptionHandler(UpstreamFailureError.class)
    public ResponseEntity<ApiError> upstreamFailure(UpstreamFailureError error) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiError.of(error.getMessage()));
    }

    /**
     * A request body that is not readable at all -- absent, truncated, or not a JSON object.
     *
     * <p>Spring already answers 400 for this, so what is added is the SHAPE: without it a caseworker client
     * gets {@link ApiError} for every refusal this service reasons about and Boot's default error attributes for
     * the one refusal it does not, which means two things to parse for the same status.
     *
     * <p><strong>The reference gets this wrong, and the difference is deliberate.</strong> Its body parser
     * raises an error {@code errorHandler()} does not recognise, so it falls to the catch-all and an unparseable
     * body is answered 500 -- a client error reported as a server error, which sends whoever is paged looking at
     * this service. Mapping the one framework signal narrowly fixes that without reintroducing the catch-all,
     * whose own problems are set out below.
     *
     * <p><strong>The framework's message is deliberately not echoed.</strong> It quotes the offending payload
     * and names Java types, and this body is rendered by whatever is on the other end. A caller who sent
     * unparseable JSON does not need to be told what they sent.
     *
     * <p>Note the narrowness: this is a body that could not be turned into an object at all. A body that parses
     * and then says something unacceptable is a {@link InvalidInputError} from the ported schema, with the
     * failure attributed to its field, which is the answer a form can render.
     *
     * <p>The exception is not taken as a parameter, which is how the paragraph above is enforced rather than
     * merely intended: there is nothing of it to leak into the answer.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadableBody() {
        return ResponseEntity.badRequest().body(ApiError.of("Request body is missing or is not valid JSON"));
    }

    /**
     * A file over the upload limit.
     *
     * <p>Translated into the domain's own refusal rather than answered from here, so the message a caller reads is
     * the policy's -- derived from {@link uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy#MAX_UPLOAD_BYTES}
     * and identical to the one the sibling web app's form gives -- and attributed to the file input a form renders
     * it against. The reference records three defects that fell into the trap this avoids: the transport's own
     * rejection is not a domain error, so unwrapped it reaches the error layer unrecognised and becomes a 500,
     * telling a caseworker the server has broken when in fact their file was too big.
     *
     * <p><strong>It has to live here rather than on the controller, and that is not a stylistic choice.</strong>
     * The exception is raised while the request is being resolved as multipart, which happens before a handler
     * method is selected -- so there is no handler for a controller-local {@code @ExceptionHandler} to be found
     * on, and only a {@code @ControllerAdvice} is consulted. A handler written next to the upload route would
     * compile, never fire, and answer 500.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> uploadTooLarge() {
        return invalidInput(UploadPolicy.uploadLimitRejection("LIMIT_FILE_SIZE"));
    }

    /**
     * A multipart request that could not be parsed at all -- no {@code boundary} parameter, a body truncated by a
     * dropped connection, a malformed part header.
     *
     * <p><strong>Without this the size limit above is the only multipart refusal that is a 400, and every other
     * one is a 500.</strong> {@link MaxUploadSizeExceededException} is a subclass of
     * {@link org.springframework.web.multipart.MultipartException}, and the parent is a
     * {@code NestedRuntimeException} rather than an {@code ErrorResponse} or a {@code ServletException} -- so it
     * appears nowhere in {@code DefaultHandlerExceptionResolver}'s chain, and {@code ResponseEntityExceptionHandler}
     * is deliberately not extended here. Nothing else in the service handles it. That is exactly the trap the
     * javadoc above says this class exists to avoid: a client error reported as a server error, which sends
     * whoever is paged looking at this service. A caller aborting an upload mid-body is ordinary and would
     * otherwise page on-call.
     *
     * <p>Registered alongside the subclass rather than instead of it, and the ordering needs no care: Spring
     * resolves the most specific {@code @ExceptionHandler} by exception-hierarchy depth, so an over-size file
     * still gets the size message and only the unparseable ones reach here.
     *
     * <p>The exception is not taken as a parameter, for the reason {@link #unreadableBody()} gives: the
     * framework's message quotes the offending request, and this body is rendered by whatever is on the other
     * end. The policy's own wording is used instead, attributed to the file input a form renders it against.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> unreadableMultipart() {
        return invalidInput(UploadPolicy.uploadLimitRejection(UploadPolicy.UNREADABLE_UPLOAD));
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
