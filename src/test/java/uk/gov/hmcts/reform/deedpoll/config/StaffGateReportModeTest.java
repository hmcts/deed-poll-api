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
 * The shipped default, with no override: the caseworker gate reports and lets through, the judicial rule refuses.
 *
 * <p>Separate from {@link StaffAccessRulesTest} because the mode is bound once, when the chain is built, so the two
 * settings need two contexts. This class is what proves {@code application.yaml}'s default is still {@code report};
 * the plan's Task 5 changes that default, and this class with it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"config-gen", "test"})
class StaffGateReportModeTest {

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
    @DisplayName("lets a role-less caller onto the caseworker surface by default, and logs who they are")
    void reportsByDefault() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Someone Else", List.of("caseworker-divorce"));

        mockMvc.perform(get("/caseworker/no-such-route").headers(bearer())).andExpect(status().isNotFound());

        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("staff-gate report subject=" + StubIdam.SUBJECT
                + " method=GET path=/caseworker/no-such-route");
    }

    @Test
    @DisplayName("enforces the judicial rule regardless, because it changes no outcome the services did not already")
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
