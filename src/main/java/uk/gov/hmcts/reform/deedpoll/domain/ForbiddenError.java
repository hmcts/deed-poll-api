package uk.gov.hmcts.reform.deedpoll.domain;

import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

/**
 * The caller is known but does not hold a role the operation requires.
 *
 * <p>Ported from {@code reference/ts/errors.ts}, and named as the reference names it, for the reason given
 * on {@link InvalidInputError}. The transport layer maps it to a 403.
 *
 * <p>Authorisation is a domain rule here, not a transport one -- which {@link Role} gates which operation
 * is part of the deed-poll model -- so the error belongs with the other domain errors, and
 * {@link CurrentUser#requireAnyRole} is what throws it.
 *
 * <p>See {@link UnauthorizedError} for why the two are not one error.
 */
public class ForbiddenError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ForbiddenError(String message) {
        super(message);
    }
}
