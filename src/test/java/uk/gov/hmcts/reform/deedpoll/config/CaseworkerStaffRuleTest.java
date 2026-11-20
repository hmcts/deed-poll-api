package uk.gov.hmcts.reform.deedpoll.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@code /caseworker/**} rule: only the caseworker area's staff roles get through (OWASP plan 01, H1).
 *
 * <p>Since Task 12 (2026-11-17) this is a plain {@code hasAnyAuthority} rule, as {@code /judicial/**} is, with no
 * report mode and no setting. These cases are the role table the deleted {@code StaffGateTest} pinned, now over HTTP
 * through the real chain, so the refusal's status and body are what a caller sees. {@link StaffAccessRulesTest} pins
 * which roles each area admits; {@link CaseworkerStaffRuleIgnoresReportTest} that the retired setting opens nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"config-gen", "test"})
class CaseworkerStaffRuleTest {

    private static final StubIdam IDAM = new StubIdam();

    static {
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

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll", "caseworker-deedpoll-team-leader",
        "caseworker-deedpoll-delivery-manager", "caseworker-deedpoll-admin"})
    @DisplayName("lets each caseworker-area staff role through, to the route's own answer")
    void admitsEachStaffRole(String role) throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of(role));

        mockMvc.perform(get("/caseworker/no-such-route").headers(bearer())).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-divorce", "caseworker-deedpoll-judicial", "caseworker", "CASEWORKER-DEEDPOLL",
        "caseworker-deedpoll-judiciary"})
    @DisplayName("refuses anyone else with 403 and the area's refusal: no role, a judge alone, and near misses")
    void refusesEveryOtherRole(String role) throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Someone Else", List.of(role));

        mockMvc.perform(get("/caseworker/no-such-route").headers(bearer()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(StaffArea.CASEWORKER.refusal()));
    }

    private static HttpHeaders bearer() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return headers;
    }
}
