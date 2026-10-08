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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.reform.authorisation.filters.ServiceAuthFilter;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.auth.StubS2s;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdProperties;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.List;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 * <p><strong>Two kinds of route are probed.</strong> The SDK's own {@code POST /ccd-persistence/cases} is asserted
 * behind the chain without a database: a request the chain admits reaches the SDK's controller and is refused there
 * for its missing {@code Idempotency-Key}, which is a 400 no filter produces. {@link PersistenceStandIn} stays for
 * what the real route cannot show -- which access profiles the chain granted -- by echoing them.
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

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CcdProperties ccd;

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

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll", "caseworker-deedpoll-admin", "caseworker-deedpoll-judicial"})
    void admitsStaffAsReadersOnly(String role) throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Staff Reader", List.of(role));
        mockMvc.perform(get(PROBE).headers(headers(CCD_DATA, IDAM.accessToken())))
            .andExpect(status().isOk())
            .andExpect(content().string("DEED_POLL_READER"));
        mockMvc.perform(get(PROBE).headers(headers(OURSELVES, IDAM.accessToken())))
            .andExpect(status().isForbidden());
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
    @DisplayName("refuses a valid token IDAM will not resolve, or resolves to somebody else, with 401 and not 500")
    void refusesAUserIdamWillNotResolve() throws Exception {
        IDAM.stubUserInfoStatus(401);
        mockMvc.perform(get(PROBE).headers(headers(CCD_DATA, IDAM.accessToken())))
            .andExpect(status().isUnauthorized());

        IDAM.stubUserInfo("somebody-else", "Deed Poll System", List.of("caseworker", SYSTEM_ROLE));
        mockMvc.perform(get(PROBE).headers(headers(CCD_DATA, IDAM.accessToken())))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refuses a valid user with no deed-poll role with 403")
    void refusesAnUnauthorisedUser() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of("caseworker", "citizen"));

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

    @Test
    @DisplayName("admits data-store with the system user through to the SDK's own POST /ccd-persistence/cases")
    void admitsDataStoreToTheSdkRoute() throws Exception {
        systemUser();

        // A 400 for the missing Idempotency-Key is the SDK's controller answering: no filter in the chain produces it.
        mockMvc.perform(sdkSubmission(headers(CCD_DATA, IDAM.accessToken())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    @Test
    @DisplayName("refuses the SDK's own route to another service, and to a caller with no S2S token")
    void refusesOthersTheSdkRoute() throws Exception {
        systemUser();
        HttpHeaders userOnly = new HttpHeaders();
        userOnly.setBearerAuth(IDAM.accessToken());

        mockMvc.perform(sdkSubmission(headers(XUI, IDAM.accessToken()))).andExpect(status().isForbidden());
        mockMvc.perform(sdkSubmission(userOnly)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the runtime has exactly one CaseView, and it answers for the configured case type")
    void oneCaseViewForTheCaseType() {
        String[] views = context.getBeanNamesForType(CaseView.class);

        assertThat(views).hasSize(1);
        CaseView<?, ?> view = context.getBean(views[0], CaseView.class);
        assertThat(view.caseTypeIds()).containsExactly(ccd.caseTypeId());
    }

    private static MockHttpServletRequestBuilder sdkSubmission(HttpHeaders headers) {
        return post("/ccd-persistence/cases").headers(headers).contentType(MediaType.APPLICATION_JSON).content("{}");
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
