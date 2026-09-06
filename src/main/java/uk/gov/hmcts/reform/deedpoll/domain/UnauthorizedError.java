package uk.gov.hmcts.reform.deedpoll.domain;

/**
 * The caller is not identified at all -- no credential, or one that does not hold up.
 *
 * <p>Ported from {@code reference/ts/errors.ts}, and named as the reference names it, for the reason
 * given on {@link InvalidInputError}. The transport layer maps it to a 401.
 *
 * <p>The distinction from {@link ForbiddenError} is the one HTTP draws between 401 and 403, and it is
 * worth keeping: a 401 tells the caller to present a credential, a 403 tells them theirs will never be
 * enough for this operation. Collapsing the two sends a caseworker whose token has simply expired to ask
 * for a role they already have.
 *
 * <p>It arrives with bearer-token authentication, which is the only thing that throws it: a missing
 * token, a token IDAM will not resolve, or a userinfo response describing somebody else. Anything the
 * caller could <em>not</em> fix by presenting a different token -- a missing audience configuration, IDAM
 * being unreachable -- is deliberately not this error, because answering 401 for our own fault sends them
 * off to re-authenticate against a service that will refuse them just as firmly the second time.
 *
 * <p>With this and {@link ForbiddenError} the reference's error vocabulary is fully ported; only
 * {@code zodErrorToFieldErrors} has no equivalent, because Bean Validation replaces Zod.
 */
public class UnauthorizedError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnauthorizedError(String message) {
        super(message);
    }
}
