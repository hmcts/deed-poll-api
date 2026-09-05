package uk.gov.hmcts.reform.deedpoll.domain;

/**
 * A record that was asked for does not exist, or is not visible in the given scope.
 *
 * <p>Ported from {@code reference/ts/errors.ts}, and named as the reference names it, for the reason
 * given on {@link InvalidInputError}. The route layer maps it to a 404; nothing about it is HTTP, which
 * is why it lives in the domain rather than being a {@code ResponseStatusException}.
 *
 * <p>The reference declares two more errors alongside these three -- {@code ForbiddenError} and
 * {@code UnauthorizedError} -- plus a {@code zodErrorToFieldErrors} helper. They still are not ported:
 * each arrives with the ported module that throws it, so the vocabulary stays a record of what the
 * service actually refuses. {@link ConflictError} arrived with the documents port for exactly that reason.
 */
public class NotFoundError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotFoundError(String message) {
        super(message);
    }
}
