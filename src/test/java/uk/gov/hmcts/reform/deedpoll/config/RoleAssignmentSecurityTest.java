package uk.gov.hmcts.reform.deedpoll.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AM roles through the real filter chain: stub IDAM, stub RAS, and the switch on.
 *
 * <p>What the converter test cannot show is that the union reaches the two places a role is enforced, the staff gate
 * in front of {@code /caseworker/**} and the capability check inside a Gazette operation, and that a RAS failure
 * withdraws only what AM added. {@code GET /caseworker/gazette-settings} needs {@code ACCESS_GAZETTE}, and
 * {@code GET /caseworker/document-types} needs no more than the staff gate, so the pair tells the two apart.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoleAssignmentSecurityTest extends AbstractPostgresContainerTest {

    private static final String GAZETTE = "/caseworker/gazette-settings";
    private static final String STAFF_ONLY = "/caseworker/document-types";
    private static final String SKILL = "SKILL:DEEDPOLL:GAZETTE";
    private static final String ACTOR_PATH = "/am/role-assignments/actors/" + StubIdam.SUBJECT;

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
        // Enforced, not the default report mode, so a refusal at the gate is a refusal rather than a log line.
        registry.add("deedpoll.security.staff-gate", () -> "enforce");
        registry.add("deedpoll.roles.am.enabled", () -> "true");
        registry.add("deedpoll.roles.am.url", RAS::baseUrl);
        registry.add("deedpoll.roles.am.gazette-skill", () -> SKILL);
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthTokenGenerator serviceTokens;

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        RAS.resetAll();
        when(serviceTokens.generate()).thenReturn("Bearer s2s-token");
    }

    private static void teamLeaderWithSkill() {
        RAS.stubFor(WireMock.get(urlPathEqualTo(ACTOR_PATH)).willReturn(okJson("""
            {"roleAssignmentResponse":[{"roleName":"ctsc-team-leader","roleType":"ORGANISATION",
             "grantType":"STANDARD","attributes":{"jurisdiction":"DEEDPOLL"},"authorisations":["%s"]}]}"""
            .formatted(SKILL))));
    }

    private static void rasFails() {
        RAS.stubFor(WireMock.get(urlPathEqualTo(ACTOR_PATH)).willReturn(aResponse().withStatus(503)));
    }

    private ResultActions authorised(String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + IDAM.accessToken()));
    }

    @Test
    @DisplayName("a ctsc-team-leader with the Gazette skill in AM, and only the sign-in role in IDAM, reaches the "
        + "Gazette")
    void amTeamLeaderWithSkillReachesTheGazette() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Team Leader", List.of("caseworker-deedpoll"));
        teamLeaderWithSkill();

        authorised(GAZETTE).andExpect(status().isOk());
        RAS.verify(1, getRequestedFor(urlPathEqualTo(ACTOR_PATH))
            .withHeader("ServiceAuthorization", equalTo("Bearer s2s-token")));
    }

    @Test
    @DisplayName("an AM-only team leader with no deed poll role in IDAM passes the staff gate on AM's word")
    void amAlonePassesTheStaffGate() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Team Leader", List.of("caseworker-civil"));
        teamLeaderWithSkill();

        authorised(STAFF_ONLY).andExpect(status().isOk());
        authorised(GAZETTE).andExpect(status().isOk());
    }

    @Test
    @DisplayName("with RAS failing, the same user loses the Gazette but keeps what IDAM grants")
    void rasFailureWithdrawsOnlyWhatAmAdded() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Team Leader", List.of("caseworker-deedpoll"));
        rasFails();

        authorised(GAZETTE).andExpect(status().isForbidden());
        authorised(STAFF_ONLY).andExpect(status().isOk());
    }

    @Test
    @DisplayName("with RAS failing and no deed poll role in IDAM, the staff gate refuses")
    void rasFailureWithNoIdamRoleIsRefusedAtTheGate() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Team Leader", List.of("caseworker-civil"));
        rasFails();

        authorised(STAFF_ONLY).andExpect(status().isForbidden());
    }
}
