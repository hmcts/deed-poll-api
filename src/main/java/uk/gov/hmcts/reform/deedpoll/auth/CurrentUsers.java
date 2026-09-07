package uk.gov.hmcts.reform.deedpoll.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;

/**
 * Reads the authenticated caller wherever a handler needs one.
 *
 * <p>Ported from {@code apps/api/src/auth/current-user.ts}, where the caller was resolved once per request
 * and stashed on the request object. Here Spring's {@code SecurityContextHolder} is that place, so there is
 * nothing to stash -- but the reason the reference gives for reading rather than resolving still holds:
 * identity is decided before routing, not per handler.
 *
 * <p><strong>Reading on an unauthenticated request throws.</strong> The alternative -- returning a caller
 * with no roles -- is also fail-closed, but it fails as a 403 on a route that should have worked, which reads
 * like a permissions bug and sends whoever debugs it to IDAM's role assignments. A thrown error names the
 * actual mistake, which is a route that was never authenticated.
 */
public final class CurrentUsers {

    private CurrentUsers() {
    }

    /** The caller on the current request. */
    public static CurrentUser required() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof IdamAuthentication idam) {
            return idam.currentUser();
        }

        throw new IllegalStateException(
            "No authenticated caller on the request. Every route but the explicitly public ones is "
                + "authenticated, so a route reading a caller must not be one of those.");
    }
}
