package uk.gov.hmcts.reform.deedpoll.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shipped default, with no override: the caseworker gate enforces (OWASP plan 01 Task 5, 2026-11-09).
 *
 * <p>Sets no {@code deedpoll.security.staff-gate} property on purpose, so this class is what proves
 * {@code application.yaml}'s default is {@code enforce}. Report mode, a rollback setting only, is
 * {@link StaffGateReportModeTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"config-gen", "test"})
class StaffGateDefaultModeTest {

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

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        logs = new ListAppender<>();
        logs.start();
        logger = (Logger) LoggerFactory.getLogger(StaffGate.class);
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    @DisplayName("refuses a role-less caller on the caseworker surface by default, and reports nothing")
    void enforcesByDefault() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Someone Else", List.of("caseworker-divorce"));

        mockMvc.perform(get("/caseworker/no-such-route").headers(bearer())).andExpect(status().isForbidden());

        assertThat(logs.list).isEmpty();
    }

    @Test
    @DisplayName("lets a staff caller through by default, to the route's own answer")
    void admitsStaffByDefault() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of(Role.CASEWORKER.claimValue()));

        mockMvc.perform(get("/caseworker/no-such-route").headers(bearer())).andExpect(status().isNotFound());

        assertThat(logs.list).isEmpty();
    }

    private static HttpHeaders bearer() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return headers;
    }
}
