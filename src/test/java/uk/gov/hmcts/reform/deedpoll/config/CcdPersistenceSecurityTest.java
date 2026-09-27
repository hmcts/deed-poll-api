package uk.gov.hmcts.reform.deedpoll.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.authorisation.filters.ServiceAuthFilter;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.auth.StubS2s;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.List;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who reaches {@code /ccd-persistence/**}: data-store's S2S token, carrying a user the definition grants something
 * to, and nobody else (plan 02, Task 5).
 *
 * <p>Boots the whole context, never a {@code @WebMvcTest} slice, because a slice does not get
 * {@link SecurityConfiguration}'s chains (see its Javadoc). The profiles are {@code config-gen} and {@code test},
 * as in {@link CcdSdkRoutesTest}: no database is needed, so Docker-free CI runs this. IDAM and S2S are both
 * WireMock.
 *
 * <p><strong>The success path reaches a stand-in, not the SDK's controller.</strong> The runtime is not
 * component-scanned until Task 6, because it refuses to start without a {@code CaseView}. So nothing is mapped
 * under the path yet, and {@link PersistenceStandIn} is. It echoes the authorities the chain granted, which is what
 * a real handler would be trusting. Task 6 adds the scan and asserts the SDK's own route behind this same chain.
 *
 * <p>The system role is set to a value that is not the placeholder, so a pass cannot come from a default. It proves
 * the chain reads the role from configuration, through the same mapping the definition writes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"config-gen", "test"})
@Import(CcdPersistenceSecurityTest.PersistenceStandIn.class)
class CcdPersistenceSecurityTest {

    private static final String PROBE = "/ccd-persistence/stand-in";

    private static final String SYSTEM_ROLE = "deed-poll-system-under-test";

    private static final String CCD_DATA = "ccd-data-s2s-token";
    private static final String OURSELVES = "deed-poll-api-s2s-token";
    private static final String XUI = "xui-webapp-s2s-token";

    private static final StubIdam IDAM = new StubIdam();
    private static final StubS2s S2S = new StubS2s();

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
        registry.add("deedpoll.ccd.system-role", () -> SYSTEM_ROLE);
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void stubs() {
        IDAM.stubDefaults();
        S2S.reset();
        S2S.token(CCD_DATA, "ccd_data");
        S2S.token(OURSELVES, "deed_poll_api");
        S2S.token(XUI, "xui_webapp");
    }

    @Test
    @DisplayName("admits data-store carrying the system user, as DEED_POLL_SYSTEM")
    void admitsDataStoreWithTheSystemUser() throws Exception {
        systemUser();

        mockMvc.perform(get(PROBE).headers(headers(CCD_DATA, IDAM.accessToken())))
            .andExpect(status().isOk())
            .andExpect(content().string("DEED_POLL_SYSTEM"));
    }

    @Test
    @DisplayName("accepts a ServiceAuthorization sent without its Bearer prefix, as the S2S library does")
    void acceptsAnUnprefixedServiceToken() throws Exception {
        systemUser();
        HttpHeaders headers = new HttpHeaders();
        headers.set(ServiceAuthFilter.AUTHORISATION, CCD_DATA);
        headers.setBearerAuth(IDAM.accessToken());

        mockMvc.perform(get(PROBE).headers(headers)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("refuses a missing ServiceAuthorization with 401, before reading the user at all")
    void refusesNoServiceToken() throws Exception {
        systemUser();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());

        mockMvc.perform(get(PROBE).headers(headers)).andExpect(status().isUnauthorized());
        assertThat(userInfoCalls()).isZero();
    }

    @Test
    @DisplayName("refuses a ServiceAuthorization that S2S cannot verify with 401")
    void refusesAnUnverifiableServiceToken() throws Exception {
        systemUser();

        mockMvc.perform(get(PROBE).headers(headers("not-a-token", IDAM.accessToken())))
            .andExpect(status().isUnauthorized());
        assertThat(userInfoCalls()).isZero();
    }

    @Test
    @DisplayName("refuses a verified service other than ccd_data with 403")
    void refusesAnotherService() throws Exception {
        systemUser();

        mockMvc.perform(get(PROBE).headers(headers(XUI, IDAM.accessToken()))).andExpect(status().isForbidden());
        assertThat(userInfoCalls()).isZero();
    }

    @Test
    @DisplayName("refuses this service's own name, which reaches CCD through data-store and never around it")
    void refusesOurOwnService() throws Exception {
        systemUser();

        mockMvc.perform(get(PROBE).headers(headers(OURSELVES, IDAM.accessToken()))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("refuses data-store with no user with 401")
    void refusesNoUser() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ServiceAuthFilter.AUTHORISATION, "Bearer " + CCD_DATA);

        mockMvc.perform(get(PROBE).headers(headers)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refuses a user token the IDAM decoder rejects with 401")
    void refusesAnInvalidUserToken() throws Exception {
        systemUser();

        mockMvc.perform(get(PROBE).headers(headers(CCD_DATA, IDAM.tokenSignedByAnotherKey())))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refuses a valid user the definition grants nothing to -- an admin caseworker included -- with 403")
    void refusesAnUnauthorisedUser() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of(Role.DEEDPOLL_ADMIN.claimValue()));

        mockMvc.perform(get(PROBE).headers(headers(CCD_DATA, IDAM.accessToken()))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("refuses the placeholder role once a real one is configured, because the mapping is configuration")
    void refusesThePlaceholderRole() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Not The System", List.of("DEED_POLL_PLACEHOLDER"));

        mockMvc.perform(get(PROBE).headers(headers(CCD_DATA, IDAM.accessToken()))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("refuses a user who names the access profile as an IDAM role, because only the mapping confers it")
    void refusesTheProfileNameAsARole() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Not The System", List.of("DEED_POLL_SYSTEM"));

        mockMvc.perform(get(PROBE).headers(headers(CCD_DATA, IDAM.accessToken()))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("leaves the caseworker namespace requiring a user token and no S2S")
    void leavesTheCaseworkerNamespaceAlone() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of(Role.DEEDPOLL_ADMIN.claimValue()));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());

        // Past authentication and into routing: 404, not the 401 a missing ServiceAuthorization gets above.
        mockMvc.perform(get("/caseworker/nothing-here").headers(headers)).andExpect(status().isNotFound());
        // And data-store's S2S token alone is still not a way in anywhere else.
        HttpHeaders serviceOnly = new HttpHeaders();
        serviceOnly.set(ServiceAuthFilter.AUTHORISATION, "Bearer " + CCD_DATA);
        mockMvc.perform(get("/caseworker/nothing-here").headers(serviceOnly)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/judicial/referrals/mine").headers(serviceOnly)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("keeps the generator's callback routes refused even to data-store with the system user")
    void keepsTheCallbacksRefused() throws Exception {
        systemUser();

        mockMvc.perform(get("/callbacks/about-to-submit").headers(headers(CCD_DATA, IDAM.accessToken())))
            .andExpect(status().isForbidden());
    }

    private static void systemUser() {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Deed Poll System", List.of("caseworker", SYSTEM_ROLE));
    }

    private static int userInfoCalls() {
        return IDAM.server().findAll(getRequestedFor(urlPathEqualTo("/o/userinfo"))).size();
    }

    private static HttpHeaders headers(String serviceToken, String userToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ServiceAuthFilter.AUTHORISATION, "Bearer " + serviceToken);
        headers.setBearerAuth(userToken);
        return headers;
    }

    /**
     * Stands where the SDK's persistence controller will stand from Task 6, and reports which access profiles the
     * chain granted the caller. Nested in a test class, so component scanning skips it and only this test's
     * {@code @Import} maps it.
     */
    @RestController
    static class PersistenceStandIn {

        @GetMapping(PROBE)
        String profiles(Authentication authentication) {
            return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("DEED_POLL_"))
                .sorted()
                .collect(Collectors.joining(","));
        }
    }
}
