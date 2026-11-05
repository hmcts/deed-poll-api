package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code deedpoll.cases.creation-enabled=false}, as prod runs until the later migration phases: a create is a 409 that
 * allocates no reference and writes no row, whatever it carries. The default ({@code true}) is
 * {@code CaseworkerCaseControllerTest}'s, which creates.
 *
 * <p>Reads the stored state rather than the response, for {@code CaseworkerCaseControllerTest}'s reason: the point is
 * that the counter never moved, because a reference allocated in prod would very likely duplicate a Power App case.
 */
@SpringBootTest(properties = "deedpoll.cases.creation-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseCreationSwitchTest extends AbstractPostgresContainerTest {

    private static final StubIdam IDAM = new StubIdam();

    static {
        // Started here rather than in @BeforeAll, because @DynamicPropertySource is read while the context is built.
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
    private DeedPollRepository deedPolls;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @BeforeEach
    void setUp() {
        clearTables();
        IDAM.stubDefaults();
    }

    @AfterEach
    void tearDown() {
        clearTables();
    }

    @Test
    @DisplayName("refuses a valid create with 409 and its fixed sentence, allocating no reference and writing no case")
    void refusesAValidCreate() throws Exception {
        create("{\"dateReceived\":\"2026-07-01T00:00:00.000Z\",\"type\":\"Adult\",\"newForename\":\"Ada\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value(DeedPollService.CREATION_DISABLED));

        assertThat(deedPolls.count()).isZero();
        assertThat(referenceCounters.count()).isZero();
    }

    @Test
    @DisplayName("refuses before reading the payload, so a create with no Date Received is the same 409, not a 400")
    void refusesBeforeReadingThePayload() throws Exception {
        create("{}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value(DeedPollService.CREATION_DISABLED));

        assertThat(deedPolls.count()).isZero();
        assertThat(referenceCounters.count()).isZero();
    }

    private void clearTables() {
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    private ResultActions create(String body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mockMvc.perform(post("/caseworker/cases").headers(headers).contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }
}
