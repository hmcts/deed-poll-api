package uk.gov.hmcts.reform.deedpoll.ccd;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCreationAttemptRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The committed default: the linking sweep is off, and while it is off the case list request touches nothing in
 * {@code ccd/} -- no selection, no attempt, no call to data-store (plan 02b, decision 3).
 *
 * <p>Its own context, with the application's own value for the switch rather than one set here: the point is that
 * an environment given nothing does not link. {@code CcdLinkingSweepTest} pins the same, container-free.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CcdLinkingSweepDisabledTest extends AbstractPostgresContainerTest {

    private static final StubIdam IDAM = new StubIdam();
    private static final WireMockServer DATA_STORE = new WireMockServer(options().dynamicPort());

    static {
        IDAM.start();
        DATA_STORE.start();
        DATA_STORE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(500)));
    }

    @AfterAll
    static void stopStubs() {
        IDAM.stop();
        DATA_STORE.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
        registry.add("idam.api.url", IDAM::baseUrl);
        registry.add("core_case_data.api.url", DATA_STORE::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private CcdCreationAttemptRepository attemptRepository;

    @MockitoSpyBean
    private CcdLinkingService linking;

    @MockitoSpyBean
    private CcdCreationAttempts attempts;

    @BeforeEach
    void stubs() {
        IDAM.stubDefaults();
        DATA_STORE.resetRequests();
    }

    @BeforeEach
    @AfterEach
    void clearTables() {
        attemptRepository.deleteAll();
        deedPolls.deleteAll();
    }

    @Test
    @DisplayName("switched off by default, the case list links nothing and calls nothing in ccd/")
    void theListTouchesNothingInCcd() throws Exception {
        final String unlinked = deedPolls.save(new DeedPoll()).getId();

        mockMvc.perform(get("/caseworker/cases").header("Authorization", "Bearer " + IDAM.accessToken()))
            .andExpect(status().isOk());

        verifyNoInteractions(linking, attempts);
        assertThat(DATA_STORE.getAllServeEvents()).isEmpty();
        assertThat(attemptRepository.findByDeedPollIdOrderByCreatedOnAsc(unlinked)).isEmpty();
    }
}
