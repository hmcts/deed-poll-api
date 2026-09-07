package uk.gov.hmcts.reform.deedpoll.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What {@link SecurityConfiguration} actually enforces, against a stub IDAM.
 *
 * <p>The two assertions plan 01 task 12 asks for are "an unauthenticated request to a {@code /caseworker/**}
 * route is rejected" and, in {@code NamespaceSeparationSecurityTest}, that the citizen namespace shares no
 * caseworker DTO. The rest is here because a service that only refuses anonymous callers is not yet
 * authenticating anybody: a decoder that accepted any signature, or an ID token presented as an access token,
 * would pass the first assertion and nothing else.
 *
 * <p>The probe route below exists only in this test's context. Real caseworker routes arrive in tasks 13 to 15,
 * and asserting the rule on a route that does not exist yet would be asserting nothing -- the point is that a
 * route added under {@code /caseworker} is protected by having been added, not by having been listed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigurationTest extends AbstractPostgresContainerTest {

    private static final StubIdam IDAM = new StubIdam();

    static {
        // Started here rather than in @BeforeAll: @DynamicPropertySource is read while the context is being
        // built, which is before any @BeforeAll callback could have started the stub.
        IDAM.start();
    }

    @AfterAll
    static void stopIdam() {
        // A static start needs an explicit stop, or the class leaves a Jetty server, its thread pool and a
        // port behind for the life of the test JVM.
        IDAM.stop();
    }

    @DynamicPropertySource
    static void idamProperties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetIdam() {
        IDAM.stubDefaults();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @Nested
    @DisplayName("without a token")
    class Unauthenticated {

        @Test
        @DisplayName("refuses a caseworker route")
        void refusesACaseworkerRoute() throws Exception {
            mockMvc.perform(get("/caseworker/probe/current-user")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses a caseworker path that is not even a route, rather than confirming it does not exist")
        void refusesAnUnknownCaseworkerPath() throws Exception {
            // Authorisation happens before routing, which is the right way round: which paths exist is not
            // something an unauthenticated caller needs to learn.
            mockMvc.perform(get("/caseworker/cases/does-not-exist")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses the citizen namespace, which is empty and protected in advance")
        void refusesTheCitizenNamespace() throws Exception {
            mockMvc.perform(get("/citizen/applications")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses anything not on the public allowlist, so a new route is protected by omission")
        void refusesAnythingElse() throws Exception {
            mockMvc.perform(get("/some/route/nobody/has/written/yet")).andExpect(status().isUnauthorized());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/", "/health", "/v3/api-docs"})
        @DisplayName("permits the infrastructure endpoints, which have no credential to present")
        void permitsInfrastructureEndpoints(String path) throws Exception {
            mockMvc.perform(get(path)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("refuses a credential that is not a bearer token at all")
        void refusesANonBearerCredential() throws Exception {
            mockMvc.perform(get("/caseworker/probe/current-user").header(HttpHeaders.AUTHORIZATION, "Basic abc123"))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("with a valid access token")
    class Authenticated {

        @Test
        @DisplayName("admits the caller, named as IDAM names them")
        void admitsTheCaller() throws Exception {
            mockMvc.perform(get("/caseworker/probe/current-user").headers(bearer(IDAM.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("A Caseworker"))
                .andExpect(jsonPath("$.isAdmin").value(false));
        }

        @Test
        @DisplayName("recognises the admin role from userinfo, not from the token")
        void recognisesTheAdminRole() throws Exception {
            IDAM.stubUserInfo(StubIdam.SUBJECT, "An Admin", List.of(Role.DEEDPOLL_ADMIN.claimValue()));

            mockMvc.perform(get("/caseworker/probe/current-user").headers(bearer(IDAM.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAdmin").value(true))
                .andExpect(jsonPath("$.roles[0]").value(Role.DEEDPOLL_ADMIN.claimValue()));
        }

        @Test
        @DisplayName("does not take roles from the token, where an attacker could put them")
        void ignoresRolesInTheToken() throws Exception {
            // An IDAM access token carries no roles, so anything claiming to is not IDAM's assertion.
            String token = IDAM.accessToken(
                claims -> claims.claim("roles", List.of(Role.DEEDPOLL_ADMIN.claimValue())));

            mockMvc.perform(get("/caseworker/probe/current-user").headers(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAdmin").value(false));
        }

        @Test
        @DisplayName("still answers 404 for a path that is not a route")
        void stillAnswers404ForAnUnknownPath() throws Exception {
            // The error dispatch has to be permitted for this: authorising it as if it were a fresh request
            // turns every 404 and 400 in the service into a 401.
            mockMvc.perform(get("/caseworker/nothing-here").headers(bearer(IDAM.accessToken())))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("with a token that does not hold up")
    class RejectedTokens {

        @Test
        @DisplayName("refuses a token signed by a key IDAM does not publish")
        void refusesAForgedSignature() throws Exception {
            mockMvc.perform(get("/caseworker/probe/current-user").headers(bearer(IDAM.tokenSignedByAnotherKey())))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses an expired token")
        void refusesAnExpiredToken() throws Exception {
            String token = IDAM.accessToken(claims -> claims
                .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .expirationTime(Date.from(Instant.now().minusSeconds(3600))));

            mockMvc.perform(get("/caseworker/probe/current-user").headers(bearer(token)))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses a token minted for another service")
        void refusesAnotherServicesToken() throws Exception {
            // IDAM signs the whole estate's tokens with one key set, so without the audience check this is a
            // valid signature from a service that has nothing to do with deed polls.
            String token = IDAM.accessToken(claims -> claims.audience("some_other_service"));

            mockMvc.perform(get("/caseworker/probe/current-user").headers(bearer(token)))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses an ID token presented as an access token")
        void refusesAnIdToken() throws Exception {
            // It carries the same audience, so only `tokenName` separates the two -- and an ID token is issued
            // to the client to describe the user, not as a credential for calling an API.
            String token = IDAM.accessToken(claims -> claims.claim("tokenName", "id_token"));

            mockMvc.perform(get("/caseworker/probe/current-user").headers(bearer(token)))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses a token from another issuer")
        void refusesAnotherIssuersToken() throws Exception {
            String token = IDAM.accessToken(claims -> claims.issuer("https://idam.example.invalid/o"));

            mockMvc.perform(get("/caseworker/probe/current-user").headers(bearer(token)))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses a token with no subject, which identifies nobody")
        void refusesASubjectlessToken() throws Exception {
            String token = IDAM.accessToken(claims -> claims.subject(null));

            mockMvc.perform(get("/caseworker/probe/current-user").headers(bearer(token)))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses a token IDAM will not resolve to a user")
        void refusesAnUnresolvableToken() throws Exception {
            IDAM.stubUserInfoStatus(401);

            mockMvc.perform(get("/caseworker/probe/current-user").headers(bearer(IDAM.accessToken())))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses a token that is not a JWT at all")
        void refusesGarbage() throws Exception {
            mockMvc.perform(get("/caseworker/probe/current-user").headers(bearer("not-a-token")))
                .andExpect(status().isUnauthorized());
        }
    }

    /** A stand-in caseworker route, so what a handler sees can be asserted. */
    @TestConfiguration
    static class ProbeConfiguration {

        /**
         * Registered by being a nested component of a configuration class, not by a {@code @Bean} method.
         */
        @RestController
        static class CaseworkerProbe {

            @GetMapping("/caseworker/probe/current-user")
            Map<String, Object> currentUser() {
                CurrentUser caller = CurrentUsers.required();
                return Map.of(
                    "name", caller.name(),
                    "isAdmin", caller.isAdmin(),
                    "roles", caller.roles().stream().map(Role::claimValue).toList()
                );
            }
        }
    }
}
