package uk.gov.hmcts.reform.deedpoll.auth;

import java.util.OptionalInt;

/**
 * The Role Assignment Service could not say which roles a caller holds.
 *
 * <p>Its own type, rather than the {@code IllegalStateException} {@link IdamUserInfoClient} throws, because the two
 * failures are answered differently: IDAM's is a 500, since nobody can be identified without it, but this one falls
 * back to the IDAM roles (plan 20261014, decision 4). A caller that catches this cannot also swallow a fault of its
 * own by accident.
 *
 * <p>The message never carries the token or RAS's response body, and the status is a separate field so that the
 * warning the fallback logs can name it without reading either.
 */
public class RoleAssignmentUnavailable extends RuntimeException {

    private final Integer status;

    RoleAssignmentUnavailable(String message, Integer status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** The HTTP status RAS answered with, or empty when it gave none: unreachable, timed out, or S2S failed. */
    public OptionalInt status() {
        return status == null ? OptionalInt.empty() : OptionalInt.of(status);
    }
}
