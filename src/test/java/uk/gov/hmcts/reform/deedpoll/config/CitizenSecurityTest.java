package uk.gov.hmcts.reform.deedpoll.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.authorisation.filters.ServiceAuthFilter;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.auth.StubS2s;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who reaches {@code /citizen/**}: {@code deed_poll_web}'s S2S token, and nothing else -- not an IDAM bearer on its
 * own, not another service, and not this service.
 *
 * <p>Boots the whole context under {@code config-gen} and {@code test}, as {@link CcdPersistenceSecurityTest} does,
 * because a {@code @WebMvcTest} slice does not get {@link SecurityConfiguration}'s chains. No database, so
 * Docker-free CI runs it. IDAM and S2S are both WireMock.
 *
 * <p>The namespace has no endpoint yet, so {@link CitizenStandIn} stands at {@link #PROBE}. It counts its calls, so a
 * refusal is shown to be the chain's and not the handler's, and it echoes who the security context says the caller
 * is, so an IDAM bearer sent alongside S2S is shown to be ignored rather than used.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"config-gen", "test"})
@Import(CitizenSecurityTest.CitizenStandIn.class)
class CitizenSecurityTest {

    private static final String PROBE = "/citizen/stand-in";

    private static final String WEB = "deed-poll-web-s2s-token";
    private static final String OURSELVES = "deed-poll-api-s2s-token";
    private static final String XUI = "xui-webapp-s2s-token";

    private static final StubIdam IDAM = new StubIdam();
    private static final StubS2s S2S = new StubS2s();

    private static final AtomicInteger REACHED = new AtomicInteger();

    static {
        // Started before the context reads their URLs, as SecurityConfigurationTest explains.
        IDAM.start();
        S2S.start();
    }

    @AfterAll
    static void stopStubs() {
        IDAM.stop();
        S2S.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
        registry.add("idam.s2s-auth.url", S2S::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void stubs() {
        IDAM.stubDefaults();
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of(Role.CASEWORKER.claimValue()));
        S2S.reset();
        S2S.token(WEB, "deed_poll_web");
        S2S.token(OURSELVES, "deed_poll_api");
        S2S.token(XUI, "xui_webapp");
        REACHED.set(0);
    }

    @Test
    @DisplayName("refuses a missing ServiceAuthorization with 401, without reaching the handler")
    void refusesNoServiceToken() throws Exception {
        mockMvc.perform(get(PROBE)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(PROBE)).andExpect(status().isUnauthorized());

        assertThat(REACHED).hasValue(0);
    }

    @Test
    @DisplayName("refuses a ServiceAuthorization that S2S cannot verify with 401")
    void refusesAnUnverifiableServiceToken() throws Exception {
        mockMvc.perform(get(PROBE).headers(service("not-a-token"))).andExpect(status().isUnauthorized());

        assertThat(REACHED).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {XUI, OURSELVES})
    @DisplayName("refuses a verified service other than deed_poll_web, this service included, with 403")
    void refusesAnotherService(String token) throws Exception {
        mockMvc.perform(get(PROBE).headers(service(token))).andExpect(status().isForbidden());
        mockMvc.perform(post(PROBE).headers(service(token))).andExpect(status().isForbidden());

        assertThat(REACHED).hasValue(0);
    }

    @Test
    @DisplayName("admits deed_poll_web on its S2S token alone, for GET and POST, as an anonymous caller")
    void admitsTheWebApp() throws Exception {
        mockMvc.perform(get(PROBE).headers(service(WEB)))
            .andExpect(status().isOk())
            .andExpect(content().string(CitizenStandIn.ANONYMOUS));
        mockMvc.perform(post(PROBE).headers(service(WEB)))
            .andExpect(status().isOk())
            .andExpect(content().string(CitizenStandIn.ANONYMOUS));

        assertThat(REACHED).hasValue(2);
    }

    @Test
    @DisplayName("accepts a ServiceAuthorization sent without its Bearer prefix, as the S2S library does")
    void acceptsAnUnprefixedServiceToken() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ServiceAuthFilter.AUTHORISATION, WEB);

        mockMvc.perform(get(PROBE).headers(headers)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("refuses a caseworker's valid IDAM bearer with no S2S with 401, because a user token is no way in")
    void refusesAnIdamBearerAlone() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());

        mockMvc.perform(get(PROBE).headers(headers)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(PROBE).headers(headers)).andExpect(status().isUnauthorized());

        assertThat(REACHED).hasValue(0);
        assertThat(userInfoCalls()).isZero();
    }

    @Test
    @DisplayName("ignores a caseworker's bearer sent with deed_poll_web's S2S: admitted, and still anonymous")
    void ignoresABearerBesideTheServiceToken() throws Exception {
        HttpHeaders headers = service(WEB);
        headers.setBearerAuth(IDAM.accessToken());

        mockMvc.perform(get(PROBE).headers(headers))
            .andExpect(status().isOk())
            .andExpect(content().string(CitizenStandIn.ANONYMOUS));

        assertThat(userInfoCalls()).isZero();
    }

    @Test
    @DisplayName("refuses an unknown citizen path with no S2S with 401, not 404: authorisation comes before routing")
    void refusesAnUnknownPathBeforeRouting() throws Exception {
        mockMvc.perform(get("/citizen/no-such-route")).andExpect(status().isUnauthorized());
        // Admitted, it is the router that answers.
        mockMvc.perform(get("/citizen/no-such-route").headers(service(WEB))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("leaves the caseworker namespace refusing deed_poll_web's S2S token without a user")
    void leavesTheCaseworkerNamespaceAlone() throws Exception {
        mockMvc.perform(get("/caseworker/no-such-route").headers(service(WEB))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/judicial/referrals/mine").headers(service(WEB))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("leaves /ccd-persistence/** refusing deed_poll_web with 403, under its own allowlist")
    void leavesCcdPersistenceAlone() throws Exception {
        HttpHeaders headers = service(WEB);
        headers.setBearerAuth(IDAM.accessToken());

        mockMvc.perform(get("/ccd-persistence/stand-in").headers(headers)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("leaves /health public")
    void leavesHealthPublic() throws Exception {
        // config-gen has no database, so the probe itself may report 503. Public means the chain let it answer.
        int status = mockMvc.perform(get("/health")).andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    private static HttpHeaders service(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ServiceAuthFilter.AUTHORISATION, "Bearer " + token);
        return headers;
    }

    private static int userInfoCalls() {
        return IDAM.server().findAll(getRequestedFor(urlPathEqualTo("/o/userinfo"))).size();
    }

    /**
     * Stands where the first citizen endpoint will, counts its calls, and says who the security context holds.
     * Nested in a test class, so component scanning skips it and only this test's {@code @Import} maps it -- which is
     * also why {@code StaffAccessRulesTest} never sees it.
     */
    @RestController
    static class CitizenStandIn {

        static final String ANONYMOUS = "anonymous";

        @GetMapping(PROBE)
        String read() {
            return caller();
        }

        @PostMapping(PROBE)
        String write() {
            return caller();
        }

        private static String caller() {
            REACHED.incrementAndGet();
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return authentication == null || authentication instanceof AnonymousAuthenticationToken
                ? ANONYMOUS
                : authentication.getName();
        }
    }
}
