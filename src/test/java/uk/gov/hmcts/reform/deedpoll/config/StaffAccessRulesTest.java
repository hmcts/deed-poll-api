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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who the real filter chain lets into each staff area, with the caseworker gate enforcing.
 *
 * <p>Booted under {@code config-gen}, which has no database, so CI runs it: the container-backed security tests do
 * not run there, and this is the rule whose failure is a disclosure. A request for a path inside an area that no
 * handler maps answers 404 once the chain admits the caller, so 404 is "let in" and 403 is "refused by the
 * chain", without needing a case to exist.
 */
@SpringBootTest(properties = "deedpoll.security.staff-gate=enforce")
@AutoConfigureMockMvc
@ActiveProfiles({"config-gen", "test"})
class StaffAccessRulesTest {

    private static final String CASEWORKER_PATH = "/caseworker/no-such-route";
    private static final String JUDICIAL_PATH = "/judicial/no-such-route";
    private static final String APPLICATION_PACKAGE = "uk.gov.hmcts.reform.deedpoll";

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
    void stubIdam() {
        IDAM.stubDefaults();
    }

    @Test
    @DisplayName("every route this service maps is inside a staff area, apart from the public root and the allowlist")
    void everyRouteIsInAStaffArea() {
        List<String> patterns = applicationMappings().stream()
            .flatMap(mapping -> mapping.getPatternValues().stream())
            .toList();

        assertThat(patterns).as("the application's routes").isNotEmpty()
            .anyMatch(pattern -> pattern.startsWith(StaffArea.CASEWORKER.prefix() + "/"))
            .anyMatch(pattern -> pattern.startsWith(StaffArea.JUDICIAL.prefix() + "/"));
        assertThat(patterns)
            .as("a route outside every StaffArea is open to any IDAM token: add it to StaffArea or the allowlist")
            .allMatch(pattern -> "/".equals(pattern) || StaffArea.of(pattern).isPresent()
                || List.of(SecurityConfiguration.ANY_AUTHENTICATED_PATHS).contains(pattern));
    }

    @Test
    @DisplayName("the routes any authenticated caller may reach are exactly /me, so another is a reviewed change")
    void anyAuthenticatedPathsAreExactlyMe() {
        assertThat(SecurityConfiguration.ANY_AUTHENTICATED_PATHS).containsExactly("/me");
    }

    /**
     * The allowlist names a path, and the path is what {@link #everyRouteIsInAStaffArea} exempts, so on its own it
     * would exempt any handler later mapped there: a {@code @PostMapping("/me")}, or a {@code @RequestMapping} with
     * no method, would reach every authenticated token with no change to the reviewed set. What was agreed is one
     * read, so that is what is pinned: exactly one handler, and it answers GET alone.
     */
    @Test
    @DisplayName("the allowlisted path has exactly one handler, and it answers GET alone")
    void theAllowlistedPathIsOneGet() {
        List<RequestMappingInfo> allowlisted = applicationMappings().stream()
            .filter(mapping -> mapping.getPatternValues().stream()
                .anyMatch(List.of(SecurityConfiguration.ANY_AUTHENTICATED_PATHS)::contains))
            .toList();

        assertThat(allowlisted).as("handlers mapped to an allowlisted path").hasSize(1);
        assertThat(allowlisted.get(0).getPatternValues()).containsExactly("/me");
        assertThat(allowlisted.get(0).getMethodsCondition().getMethods()).containsExactly(RequestMethod.GET);
    }

    @Test
    @DisplayName("with the gate enforcing, /me still tells a roleless caller they are not staff, rather than refusing")
    void meAnswersARolelessCallerUnderEnforce() throws Exception {
        as(List.of("caseworker-divorce"), "/me")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.staff").value(false))
            .andExpect(jsonPath("$.judicial").value(false))
            .andExpect(jsonPath("$.capabilities").isEmpty());
        as(List.of("caseworker-divorce"), CASEWORKER_PATH).andExpect(status().isForbidden());
    }

    private List<RequestMappingInfo> applicationMappings() {
        return handlerMapping.getHandlerMethods().entrySet().stream()
            .filter(entry -> entry.getValue().getBeanType().getName().startsWith(APPLICATION_PACKAGE))
            .map(Map.Entry::getKey)
            .toList();
    }

    @Test
    @DisplayName("refuses a caller holding no deed-poll role, with the domain's error body")
    void refusesARolelessCaller() throws Exception {
        as(List.of("caseworker-divorce"), CASEWORKER_PATH)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(StaffArea.CASEWORKER.refusal()))
            // The shape the published 403 declares, and nothing beside it: no trace and no exception detail.
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.*", hasSize(1)));
    }

    @Test
    @DisplayName("admits each caseworker, Team leader, Delivery Manager and legacy role alone")
    void admitsEachStaffRole() throws Exception {
        for (Role role : List.of(Role.CASEWORKER, Role.TEAM_LEADER, Role.DELIVERY_MANAGER, Role.DEEDPOLL_ADMIN)) {
            as(List.of(role.claimValue()), CASEWORKER_PATH).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("refuses a judge on the caseworker surface, because judicial is not caseworker membership")
    void refusesAJudgeOnTheCaseworkerSurface() throws Exception {
        as(List.of(Role.JUDICIAL.claimValue()), CASEWORKER_PATH).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("refuses every non-judicial staff role on the judicial surface")
    void refusesNonJudgesOnTheJudicialSurface() throws Exception {
        for (Role role : List.of(Role.CASEWORKER, Role.TEAM_LEADER, Role.DELIVERY_MANAGER, Role.DEEDPOLL_ADMIN)) {
            as(List.of(role.claimValue()), JUDICIAL_PATH)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Requires one of the following roles: "
                    + Role.JUDICIAL.claimValue()));
        }
    }

    @Test
    @DisplayName("admits a judge to the judicial surface")
    void admitsAJudge() throws Exception {
        as(List.of(Role.JUDICIAL.claimValue()), JUDICIAL_PATH).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("still answers 401, not 403, to a caller with no token on either surface")
    void stillAsksAnonymousCallersToAuthenticate() throws Exception {
        mockMvc.perform(get(CASEWORKER_PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(JUDICIAL_PATH)).andExpect(status().isUnauthorized());
    }

    private ResultActions as(List<String> roles, String path) throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caller", roles);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mockMvc.perform(get(path).headers(headers));
    }
}
