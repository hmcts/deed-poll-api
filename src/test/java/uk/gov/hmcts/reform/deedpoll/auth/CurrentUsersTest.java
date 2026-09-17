package uk.gov.hmcts.reform.deedpoll.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports {@code userFromRequest} from {@code apps/api/src/auth/current-user.ts}.
 *
 * <p>The reference's request-scoped symbol is Spring's security context here, so the behaviour worth keeping is
 * the failure: reading a caller on a route that was never authenticated throws rather than answering "nobody".
 */
class CurrentUsersTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static Jwt token() {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject("idam-user-123").build();
    }

    @Test
    @DisplayName("gives the caller the request was authenticated as")
    void givesTheAuthenticatedCaller() {
        CurrentUser caller = CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "A Caseworker", "idam-user-123");
        SecurityContextHolder.getContext().setAuthentication(new IdamAuthentication(token(), caller));

        assertThat(CurrentUsers.required()).isEqualTo(caller);
    }

    @Test
    @DisplayName("publishes the caller's roles as authorities too, so Spring's own checks can see them")
    void publishesRolesAsAuthorities() {
        IdamAuthentication authentication = new IdamAuthentication(
            token(), CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "A Caseworker", "idam-user-123"));

        assertThat(authentication.getAuthorities()).extracting(Object::toString)
            .containsExactly(Role.DEEDPOLL_ADMIN.claimValue());
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo("idam-user-123");
    }

    @Test
    @DisplayName("throws on an unauthenticated request rather than reporting no roles")
    void throwsWhenNothingIsAuthenticated() {
        // Returning a role-less caller is also fail-closed, but it fails as a 403 on a route that should have
        // worked -- which reads like a permissions bug and sends whoever debugs it to IDAM's role assignments.
        assertThatThrownBy(CurrentUsers::required)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No authenticated caller");
    }

    @Test
    @DisplayName("throws on an authentication this service did not resolve")
    void throwsOnAForeignAuthentication() {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("someone", "password", List.of()));

        assertThatThrownBy(CurrentUsers::required).isInstanceOf(IllegalStateException.class);
    }
}
