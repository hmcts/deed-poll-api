package uk.gov.hmcts.reform.deedpoll.domain;

/**
 * A record that was asked for does not exist, or is not visible in the given scope.
 *
 * <p>Ported from {@code reference/ts/errors.ts}, and named as the reference names it, for the reason
 * given on {@link InvalidInputError}. {@code DomainErrorHandler} maps it to a 404; nothing about it is
 * HTTP, which is why it lives in the domain rather than being a {@code ResponseStatusException} -- the
 * same services answer a CCD callback and, later, the citizen namespace, where a missing record is not
 * necessarily a 404.
 *
 * <p>The reference's error vocabulary is now fully ported: {@link ForbiddenError} and
 * {@link UnauthorizedError} arrived with authentication and {@link ConflictError} with the documents
 * port, each with the module that throws it, so the set stays a record of what the service actually
 * refuses. Only {@code zodErrorToFieldErrors} has no equivalent, because Bean Validation replaces Zod.
 */
public class NotFoundError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotFoundError(String message) {
        super(message);
    }
}
