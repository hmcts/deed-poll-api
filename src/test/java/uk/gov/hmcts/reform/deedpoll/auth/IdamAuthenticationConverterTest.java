package uk.gov.hmcts.reform.deedpoll.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The caller the converter builds once AM is a second source of roles, against a stub RAS.
 *
 * <p>The real {@link RoleAssignmentClient} is used, so a 404 and a 500 are RAS's answers rather than a mock's, and
 * what is asserted is the whole path a request takes: IDAM's claims, the lookup, the mapping and the union. IDAM
 * itself is a mock, because what it answers is {@code IdamUserInfoClientTest}'s subject.
 */
class IdamAuthenticationConverterTest {

    private static final WireMockServer RAS = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final String SUBJECT = "0b8d9a4e-4a8c-4f42-9a62-5c1f3e4d2b10";
    private static final String TOKEN = "caller-token";
    private static final String SYSTEM_ROLE = "caseworker-deedpoll-system";
    private static final String SKILL = "SKILL:DEEDPOLL:GAZETTE";
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);

    private final IdamUserInfoClient idam = mock(IdamUserInfoClient.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(AmRoleSource.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeAll
    static void startRas() {
        RAS.start();
    }

    @AfterAll
    static void stopRas() {
        RAS.stop();
    }

    @BeforeEach
    void setUp() {
        RAS.resetAll();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    private IdamAuthenticationConverter converter(boolean enabled) {
        RoleAssignmentProperties properties = new RoleAssignmentProperties(enabled, RAS.baseUrl(), null, SKILL);
        RoleAssignmentClient client = new RoleAssignmentClient(properties, () -> "s2s-token");
        return new IdamAuthenticationConverter(idam, new AmRoleSource(properties, client, SYSTEM_ROLE, NOW));
    }

    private void idamRoles(String... roles) {
        when(idam.claims(TOKEN, SUBJECT))
            .thenReturn(Map.of("sub", SUBJECT, "name", "Ada Caseworker", "roles", List.of(roles)));
    }

    private static void rasAnswers(String... roleNamesWithAuthorisations) {
        StringBuilder list = new StringBuilder();
        for (String entry : roleNamesWithAuthorisations) {
            String[] parts = entry.split("\\+", 2);
            String authorisations = parts.length == 2 ? "\"" + parts[1] + "\"" : "";
            if (!list.isEmpty()) {
                list.append(',');
            }
            list.append("""
                {"roleName":"%s","roleType":"ORGANISATION","grantType":"STANDARD",
                 "attributes":{"jurisdiction":"DEEDPOLL"},"authorisations":[%s]}"""
                .formatted(parts[0], authorisations));
        }
        rasStatus(200, "{\"roleAssignmentResponse\":[" + list + "]}");
    }

    private static void rasStatus(int status, String body) {
        RAS.stubFor(get(anyUrl()).willReturn(aResponse()
            .withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue(TOKEN).header("alg", "RS256").subject(SUBJECT).build();
    }

    private IdamAuthentication convert(boolean enabled) {
        return (IdamAuthentication) converter(enabled).convert(jwt());
    }

    private static List<String> authorities(IdamAuthentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    @DisplayName("switched off, it makes no RAS call and the caller has their IDAM roles alone")
    void disabledMakesNoCall() {
        idamRoles("caseworker-deedpoll");
        rasAnswers("ctsc-team-leader+" + SKILL);

        IdamAuthentication authentication = convert(false);

        assertThat(authentication.currentUser().roles()).containsExactly(Role.CASEWORKER);
        RAS.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("the CCD system user never calls RAS")
    void systemUserMakesNoCall() {
        idamRoles(SYSTEM_ROLE);
        rasAnswers("ctsc");

        IdamAuthentication authentication = convert(true);

        assertThat(authentication.currentUser().roles()).isEmpty();
        RAS.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("a ctsc assignment with only the sign-in role in IDAM is a caseworker, from one RAS call")
    void ctscIsACaseworker() {
        idamRoles("caseworker-deedpoll");
        rasAnswers("ctsc");

        IdamAuthentication authentication = convert(true);

        assertThat(authentication.currentUser().roles()).containsExactly(Role.CASEWORKER);
        assertThat(authorities(authentication)).containsExactly("caseworker-deedpoll");
        RAS.verify(1, getRequestedFor(urlPathEqualTo("/am/role-assignments/actors/" + SUBJECT)));
    }

    @Test
    @DisplayName("AM alone can make a caller a caseworker, with identity still IDAM's")
    void amAloneGrantsTheRole() {
        idamRoles("caseworker-civil");
        rasAnswers("ctsc");

        CurrentUser user = convert(true).currentUser();

        assertThat(user.roles()).containsExactly(Role.CASEWORKER);
        assertThat(user.name()).isEqualTo("Ada Caseworker");
        assertThat(user.userId()).isEqualTo(SUBJECT);
    }

    @Test
    @DisplayName("a team leader holding the Gazette skill can access the Gazette")
    void teamLeaderWithSkillAccessesGazette() {
        idamRoles("caseworker-deedpoll");
        rasAnswers("ctsc-team-leader+" + SKILL);

        IdamAuthentication authentication = convert(true);

        assertThat(authentication.currentUser().roles())
            .containsExactly(Role.CASEWORKER, Role.TEAM_LEADER, Role.DELIVERY_MANAGER);
        assertThat(authentication.currentUser().can(Capability.ACCESS_GAZETTE)).isTrue();
        assertThat(authentication.currentUser().isAdmin()).isTrue();
        assertThat(authorities(authentication)).contains(Role.DELIVERY_MANAGER.claimValue());
    }

    @Test
    @DisplayName("a RAS 500 falls back to the IDAM roles, warning with a hash of the subject and nothing else")
    void serverErrorFallsBack() {
        idamRoles("caseworker-deedpoll");
        rasStatus(500, "{\"secret\":\"private response body\"}");

        IdamAuthentication authentication = convert(true);

        assertThat(authentication.currentUser().roles()).containsExactly(Role.CASEWORKER);
        assertThat(authentication.currentUser().can(Capability.ACCESS_GAZETTE)).isFalse();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("WARN");
            assertThat(event.getFormattedMessage())
                .contains(AmRoleSource.hash(SUBJECT), "status 500")
                .doesNotContain(SUBJECT, TOKEN, "s2s-token", "private response body");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    @DisplayName("an unreachable RAS falls back too, without logging the request URL that names the subject")
    void unreachableFallsBack() {
        idamRoles("caseworker-deedpoll");
        RoleAssignmentProperties properties = new RoleAssignmentProperties(true, "http://127.0.0.1:1", null, SKILL);
        IdamAuthenticationConverter converter = new IdamAuthenticationConverter(idam, new AmRoleSource(
            properties, new RoleAssignmentClient(properties, () -> "s2s-token"), SYSTEM_ROLE, NOW));

        IdamAuthentication authentication = (IdamAuthentication) converter.convert(jwt());

        assertThat(authentication.currentUser().roles()).containsExactly(Role.CASEWORKER);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("status none").doesNotContain(SUBJECT);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    @DisplayName("a RAS 404 is no assignments: IDAM roles only, and no warning")
    void notFoundIsEmpty() {
        idamRoles("caseworker-deedpoll");
        rasStatus(404, "{}");

        IdamAuthentication authentication = convert(true);

        assertThat(authentication.currentUser().roles()).containsExactly(Role.CASEWORKER);
        assertThat(logs.list).isEmpty();
    }

    @Test
    @DisplayName("the legacy IDAM admin role and an AM ctsc assignment union, each role once")
    void legacyAdminUnionsWithAm() {
        idamRoles("caseworker-deedpoll", "caseworker-deedpoll-admin");
        rasAnswers("ctsc", "ctsc");

        CurrentUser user = convert(true).currentUser();

        assertThat(user.roles()).containsExactly(Role.CASEWORKER, Role.DEEDPOLL_ADMIN);
        assertThat(user.isAdmin()).isTrue();
        assertThat(user.can(Capability.ACCESS_GAZETTE)).isFalse();
    }

    @Test
    @DisplayName("another jurisdiction's team leader adds nothing")
    void otherJurisdictionAddsNothing() {
        idamRoles("caseworker-deedpoll");
        rasStatus(200, """
            {"roleAssignmentResponse":[{"roleName":"ctsc-team-leader","roleType":"ORGANISATION",
             "grantType":"STANDARD","attributes":{"jurisdiction":"PROBATE"},"authorisations":["%s"]}]}"""
            .formatted(SKILL));

        assertThat(convert(true).currentUser().roles()).containsExactly(Role.CASEWORKER);
    }

    @Test
    @DisplayName("a token IDAM refuses is still a 401, and RAS is never asked")
    void idamRefusalIsUnauthorised() {
        when(idam.claims(anyString(), anyString())).thenThrow(new UnauthorizedError("IDAM rejected the access token"));

        assertThatThrownBy(() -> converter(true).convert(jwt())).isInstanceOf(InvalidBearerTokenException.class);
        RAS.verify(0, anyRequestedFor(anyUrl()));
    }
}
