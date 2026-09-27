package uk.gov.hmcts.reform.deedpoll.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the CCD SDK adds to this service's HTTP surface, and that none of it is open.
 *
 * <p>{@code /ccd-persistence/**} is where data-store posts a case event into the decentralised runtime. The
 * existing chain ends in {@code anyRequest().authenticated()}, so a valid IDAM token alone must never be enough
 * to post an event straight into the SDK. Plan 02 Task 5 gave the path its own chain requiring S2S from
 * {@code ccd_data} (what gets through it is {@code CcdPersistenceSecurityTest}). This class asserts that a
 * caseworker's real token -- signed by the stub IDAM, resolved through userinfo -- still gets nowhere on it.
 * {@code /callbacks/**} stays refused to everybody by {@link SecurityConfiguration#CCD_SDK_PATHS}.
 *
 * <p>Booted under the {@code config-gen} profile, which needs no database, so Docker-free CI runs it -- plus
 * {@code test}, the only profile that accepts the stub IDAM's cleartext issuer. Which paths
 * are refused, and which the SDK maps, are decided before any request reaches a repository.
 *
 * <p>The SDK's components are not yet scanned -- the runtime refuses to start without a {@code CaseView}, which
 * arrives with the case definition -- so today the mapping assertions hold of an empty set. They are written for
 * the day the scan is added: every route the SDK maps must fall under the refused or S2S-guarded prefixes, and
 * {@code /noc/**} must stay unregistered (plan 02, decision 4).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"config-gen", "test"})
class CcdSdkRoutesTest {

    private static final String SDK_PACKAGE = "uk.gov.hmcts.ccd.sdk";

    private static final StubIdam IDAM = new StubIdam();

    static {
        // Started before the context reads the issuer URL, as SecurityConfigurationTest explains.
        IDAM.start();
    }

    @AfterAll
    static void stopIdam() {
        IDAM.stop();
    }

    @DynamicPropertySource
    static void idamProperties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @BeforeEach
    void caseworker() {
        IDAM.stubDefaults();
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of(Role.DEEDPOLL_ADMIN.claimValue()));
    }

    @Test
    @DisplayName("refuses a caseworker's valid token on every persistence route, because it carries no S2S")
    void refusesACaseworkerOnThePersistenceApi() throws Exception {
        for (MockHttpServletRequestBuilder request : persistenceRequests()) {
            mockMvc.perform(request.headers(bearer(IDAM.accessToken()))).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("refuses a caseworker's valid token on the generator's callback routes")
    void refusesACaseworkerOnTheCallbacks() throws Exception {
        mockMvc.perform(post("/callbacks/about-to-submit").headers(bearer(IDAM.accessToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("refuses an anonymous caller on the persistence API as it would anywhere else")
    void refusesAnonymousCallers() throws Exception {
        mockMvc.perform(post("/ccd-persistence/cases").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the caseworker token is otherwise a good one")
    void theTokenIsGoodElsewhere() throws Exception {
        // Without this the 403s above could be a token the chain never accepted.
        mockMvc.perform(get("/caseworker/nothing-here").headers(bearer(IDAM.accessToken())))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("maps nothing under /noc")
    void mapsNoNoticeOfChange() {
        assertThat(patterns(handlerMapping.getHandlerMethods()))
            .noneMatch(pattern -> pattern.startsWith("/noc"));
    }

    @Test
    @DisplayName("every route the SDK maps is one the chains refuse or guard with S2S")
    void everySdkRouteIsRefused() {
        Map<RequestMappingInfo, HandlerMethod> sdkHandlers = handlerMapping.getHandlerMethods().entrySet().stream()
            .filter(entry -> entry.getValue().getBeanType().getName().startsWith(SDK_PACKAGE))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        List<String> guardedPrefixes = Stream.concat(Arrays.stream(SecurityConfiguration.CCD_SDK_PATHS),
                Stream.of(SecurityConfiguration.CCD_PERSISTENCE_PATH))
            .map(path -> path.substring(0, path.length() - "/**".length()))
            .toList();

        assertThat(patterns(sdkHandlers))
            .as("a route the SDK maps outside CCD_SDK_PATHS and CCD_PERSISTENCE_PATH is open to any IDAM token")
            .allMatch(pattern -> guardedPrefixes.stream().anyMatch(pattern::startsWith));
    }

    private static List<MockHttpServletRequestBuilder> persistenceRequests() {
        return List.of(
            post("/ccd-persistence/cases")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{}"),
            get("/ccd-persistence/cases").param("case-refs", "1234567890123456"),
            get("/ccd-persistence/cases/1234567890123456/history"),
            post("/ccd-persistence/cases/1234567890123456/supplementary-data")
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    private static List<String> patterns(Map<RequestMappingInfo, HandlerMethod> handlers) {
        return handlers.keySet().stream()
            .flatMap(info -> info.getPatternValues().stream())
            .toList();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
