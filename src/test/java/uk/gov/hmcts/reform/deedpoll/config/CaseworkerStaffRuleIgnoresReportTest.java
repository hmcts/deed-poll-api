package uk.gov.hmcts.reform.deedpoll.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The retired report setting opens nothing (OWASP plan 01 Task 12, 2026-11-17).
 *
 * <p>{@code deedpoll.security.staff-gate=report} ({@code STAFF_GATE_MODE}) used to let a caller with no staff role onto
 * the caseworker surface and log them. Task 12 removed the mode and the setting, so a flux overlay or environment that
 * still sets it must change nothing. Its own context, because a property is bound when the context starts.
 */
@SpringBootTest(properties = "deedpoll.security.staff-gate=report")
@AutoConfigureMockMvc
@ActiveProfiles({"config-gen", "test"})
class CaseworkerStaffRuleIgnoresReportTest {

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

    @Test
    @DisplayName("still refuses a role-less caller on the caseworker surface while the retired report setting is set")
    void refusesARolelessCallerWhateverTheSetting() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Someone Else", List.of("caseworker-divorce"));

        mockMvc.perform(get("/caseworker/no-such-route").headers(bearer()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(StaffArea.CASEWORKER.refusal()));
    }

    @Test
    @DisplayName("enforces the judicial rule regardless, as it always did")
    void enforcesTheJudicialRuleRegardless() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of(Role.CASEWORKER.claimValue()));

        mockMvc.perform(get("/judicial/no-such-route").headers(bearer())).andExpect(status().isForbidden());
    }

    private static HttpHeaders bearer() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return headers;
    }
}
