package uk.gov.hmcts.reform.deedpoll.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /me} through the real filter chain: stub IDAM, stub RAS, and the shipped default gate mode.
 *
 * <p>The response test shows the rule; this shows that the authorities the chain authorised the caller on are the
 * ones the answer is computed from, AM's included, and that the gate's {@code report} mode does not leak into the
 * flags. The mode is left unset on purpose, so the probe below runs against {@code application.yaml}'s default.
 *
 * <p>AM is switched on for the whole class. RAS answers 404, which means no assignments, unless a test says
 * otherwise, so the role cases are IDAM's word alone.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CallerAccessSecurityTest extends AbstractPostgresContainerTest {

    private static final String SKILL = "SKILL:DEEDPOLL:GAZETTE";
    private static final String ACTOR_PATH = "/am/role-assignments/actors/" + StubIdam.SUBJECT;
    private static final String TEAM_LEADER_SET =
        "[\"OVERRIDE_PRIVACY\",\"DELETE_NOTE\",\"DELETE_WITNESS\",\"MANAGE_SHARED_QUERIES\",\"REPAIR_CCD\","
            + "\"ALLOCATE_REFERRALS\"]";
    private static final String DELIVERY_MANAGER_SET =
        "[\"OVERRIDE_PRIVACY\",\"DELETE_NOTE\",\"DELETE_WITNESS\",\"MANAGE_SHARED_QUERIES\",\"REPAIR_CCD\","
            + "\"ACCESS_GAZETTE\",\"MANAGE_PROCESS_CONFIG\",\"ALLOCATE_REFERRALS\"]";

    private static final StubIdam IDAM = new StubIdam();
    private static final WireMockServer RAS = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        IDAM.start();
        RAS.start();
    }

    @AfterAll
    static void stopStubs() {
        IDAM.stop();
        RAS.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
        registry.add("deedpoll.roles.am.enabled", () -> "true");
        registry.add("deedpoll.roles.am.url", RAS::baseUrl);
        registry.add("deedpoll.roles.am.gazette-skill", () -> SKILL);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private AuthTokenGenerator serviceTokens;

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        RAS.resetAll();
        RAS.stubFor(WireMock.get(urlPathEqualTo(ACTOR_PATH)).willReturn(aResponse().withStatus(404)));
        when(serviceTokens.generate()).thenReturn("Bearer s2s-token");
    }

    private ResultActions me() throws Exception {
        return mvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + IDAM.accessToken()));
    }

    static Stream<Arguments> callers() {
        return Stream.of(
            Arguments.of(List.of(Role.CASEWORKER.claimValue()),
                "{\"staff\":true,\"judicial\":false,\"capabilities\":[]}"),
            Arguments.of(List.of(Role.TEAM_LEADER.claimValue()),
                "{\"staff\":true,\"judicial\":false,\"capabilities\":" + TEAM_LEADER_SET + "}"),
            Arguments.of(List.of(Role.DELIVERY_MANAGER.claimValue()),
                "{\"staff\":true,\"judicial\":false,\"capabilities\":" + DELIVERY_MANAGER_SET + "}"),
            Arguments.of(List.of("caseworker-deedpoll-admin"),
                "{\"staff\":true,\"judicial\":false,\"capabilities\":" + TEAM_LEADER_SET + "}"),
            Arguments.of(List.of(Role.JUDICIAL.claimValue()),
                "{\"staff\":false,\"judicial\":true,\"capabilities\":[]}"),
            Arguments.of(List.of("caseworker-civil"),
                "{\"staff\":false,\"judicial\":false,\"capabilities\":[]}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("callers")
    @DisplayName("answers each kind of caller with what the api will enforce for them")
    void answersEachCaller(List<String> roles, String expected) throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caller", roles);

        me().andExpect(status().isOk()).andExpect(content().json(expected, JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("refuses a request with no token, as everywhere else")
    void refusesNoToken() throws Exception {
        mvc.perform(get("/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    @DisplayName("publishes exactly the three properties, and forbids caching them")
    void publishesExactlyThreePropertiesUncached() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Team Leader", List.of(Role.TEAM_LEADER.claimValue()));

        String body = me()
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
            .andReturn().getResponse().getContentAsString();

        JsonNode tree = json.readTree(body);
        assertThat(tree.properties()).extracting(Map.Entry::getKey)
            .containsExactlyInAnyOrder("staff", "judicial", "capabilities");
    }

    @Test
    @DisplayName("in report mode, says staff false for a roleless caller the gate nonetheless admits")
    void flagsIgnoreTheReportMode() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Someone Else", List.of("caseworker-divorce"));

        me().andExpect(status().isOk()).andExpect(content().json("{\"staff\":false}"));
        mvc.perform(get("/caseworker/no-such-route")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + IDAM.accessToken()))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("includes AM's word: a ctsc-team-leader with the Gazette skill gets the Delivery Manager set")
    void includesAmRoles() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Team Leader", List.of(Role.CASEWORKER.claimValue()));
        RAS.stubFor(WireMock.get(urlPathEqualTo(ACTOR_PATH)).willReturn(okJson("""
            {"roleAssignmentResponse":[{"roleName":"ctsc-team-leader","roleType":"ORGANISATION",
             "grantType":"STANDARD","attributes":{"jurisdiction":"DEEDPOLL"},"authorisations":["%s"]}]}"""
            .formatted(SKILL))));

        me().andExpect(status().isOk()).andExpect(content().json(
            "{\"staff\":true,\"judicial\":false,\"capabilities\":" + DELIVERY_MANAGER_SET + "}",
            JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("with RAS failing, answers on IDAM's word alone")
    void rasFailureGivesTheIdamAnswer() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Team Leader", List.of(Role.CASEWORKER.claimValue()));
        RAS.stubFor(WireMock.get(urlPathEqualTo(ACTOR_PATH)).willReturn(aResponse().withStatus(500)));

        me().andExpect(status().isOk())
            .andExpect(content().json("{\"staff\":true,\"judicial\":false,\"capabilities\":[]}",
                JsonCompareMode.STRICT));
    }
}
