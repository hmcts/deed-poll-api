package uk.gov.hmcts.reform.deedpoll.auth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports the assertions {@code fetchUserInfo()} in {@code apps/api/src/auth/idam.ts} makes.
 *
 * <p>The one worth reading twice is the subject check: without it, a userinfo response for the wrong user is
 * accepted as this caller's identity, which is a privilege escalation with no bad token involved.
 */
class IdamUserInfoClientTest {

    private static final StubIdam IDAM = new StubIdam();

    @BeforeAll
    static void startIdam() {
        IDAM.start();
    }

    @AfterAll
    static void stopIdam() {
        IDAM.stop();
    }

    @BeforeEach
    void resetIdam() {
        IDAM.stubDefaults();
    }

    private static IdamUserInfoClient client() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.profiles.active", "test");
        IdamProperties properties = new IdamProperties(IDAM.issuerUrl(), List.of(StubIdam.AUDIENCE), List.of());
        return new IdamUserInfoClient(new IdamDiscovery(properties, environment));
    }

    @Test
    @DisplayName("reads the caller's roles and name from userinfo, not from the token")
    void readsRolesFromUserInfo() {
        // An IDAM access token carries no roles at all -- the sibling web app read them from an ID token,
        // passed against a stub that put them there, and would have found every caseworker role-less against
        // real IDAM.
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of(Role.DEEDPOLL_ADMIN.claimValue()));

        CurrentUser user = client().currentUser("token", StubIdam.SUBJECT);

        assertThat(user.roles()).containsExactly(Role.DEEDPOLL_ADMIN);
        assertThat(user.isAdmin()).isTrue();
        assertThat(user.name()).isEqualTo("A Caseworker");
    }

    @Test
    @DisplayName("forwards the caller's own token, so IDAM answers about them")
    void forwardsTheCallersToken() {
        client().currentUser("a-token", StubIdam.SUBJECT);

        IDAM.server().verify(getRequestedFor(urlPathEqualTo("/o/userinfo"))
                                 .withHeader("Authorization", equalTo("Bearer a-token")));
    }

    @Test
    @DisplayName("refuses a token IDAM will not resolve")
    void refusesARevokedToken() {
        // A token that verified but that IDAM refuses has been revoked, or belongs to a user who no longer
        // exists: caller-fixable, so a 401 rather than a 500.
        IDAM.server().stubFor(get(urlPathEqualTo("/o/userinfo")).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client().currentUser("token", StubIdam.SUBJECT))
            .isInstanceOf(UnauthorizedError.class);
    }

    @Test
    @DisplayName("refuses a token whose user IDAM has since deleted")
    void refusesATokenForADeletedUser() {
        // AAT's answer, measured on 2026-10-07 by deleting a testing-support user and presenting its still-valid
        // token: a 400, not a 401. Every AAT persona is swept within three hours, so this is a 500 on every page
        // for any tester whose session outlived their persona unless it is recognised.
        IDAM.stubUserInfoForDeletedUser();

        assertThatThrownBy(() -> client().currentUser("token", StubIdam.SUBJECT))
            .isInstanceOf(UnauthorizedError.class);
    }

    @Test
    @DisplayName("does not answer 401 for a 400 that does not say the user is unknown")
    void doesNotBlameTheCallerForAnyOther400() {
        // A 400 can be this service's malformed request, which the caller cannot fix by signing in again.
        IDAM.server().stubFor(get(urlPathEqualTo("/o/userinfo")).willReturn(aResponse()
            .withStatus(400)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"error":"invalid_request"}""")));

        assertThatThrownBy(() -> client().currentUser("token", StubIdam.SUBJECT))
            .isInstanceOf(IllegalStateException.class)
            .isNotInstanceOf(UnauthorizedError.class);
    }

    @Test
    @DisplayName("refuses a userinfo response describing somebody else")
    void refusesAMismatchedSubject() {
        IDAM.stubUserInfo("somebody-else", "Another Caseworker", List.of());

        assertThatThrownBy(() -> client().currentUser("token", StubIdam.SUBJECT))
            .isInstanceOf(UnauthorizedError.class)
            .hasMessageContaining("different subject");
    }

    @Test
    @DisplayName("does not answer 401 when the fault is IDAM's rather than the caller's")
    void doesNotBlameTheCallerForAnOutage() {
        // Answering 401 for an outage sends the caller off to re-authenticate against a service that will
        // refuse them just as firmly the second time.
        IDAM.server().stubFor(get(urlPathEqualTo("/o/userinfo")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client().currentUser("token", StubIdam.SUBJECT))
            .isInstanceOf(IllegalStateException.class)
            .isNotInstanceOf(UnauthorizedError.class);
    }

    @Test
    @DisplayName("asks IDAM every time, rather than caching a caller's roles")
    void doesNotCacheRoles() {
        // A cache here is a cache of authorisation data: a caseworker whose admin role is removed would keep
        // it for the cache's lifetime, and the operations it gates are the ones worth removing it for.
        IdamUserInfoClient client = client();

        client.currentUser("token", StubIdam.SUBJECT);
        client.currentUser("token", StubIdam.SUBJECT);

        IDAM.server().verify(2, getRequestedFor(urlPathEqualTo("/o/userinfo")));
    }
}
