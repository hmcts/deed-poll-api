package uk.gov.hmcts.ccd.sdk.retention;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import feign.FeignException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Application deletion plan (20261118), Task 1: the stubbed contract proof for CCD Retain and Dispose, against the
 * code that will actually run it -- the pinned SDK's own {@code CoreCaseDataRetainAndDisposeClient}
 * (decentralised-runtime 7.1.0), over a real Feign {@link CoreCaseDataApi}, against a WireMock data-store.
 *
 * <p>It pins the two halves {@code docs/APPLICATION-DELETION.md} depends on. First, the system events: the client
 * starts {@code MarkForDisposal} and {@code ConfirmDisposal} as the configured system user, submits them with the
 * start's own data and token, and refuses any answer that does not leave the case in {@code PendingDisposal}. (CCD
 * sets {@code TTL.SystemTTL} itself, from {@code ConfirmDisposal}'s {@code TTLIncrement} of 0; that is data-store's
 * code, cited in the document, and cannot run here.) Second, the "case gone" signal: only a {@code 404} for the
 * reference reads as absent. A 401, 403, 5xx or a dropped connection is a failure, never absence, which is what keeps
 * local finalisation from running on an outage or a permission fault.
 *
 * <p>In the SDK's package because the client is package-private; nothing here is a Spring component, so the main
 * application's scan of {@code uk.gov.hmcts.ccd.sdk} finds nothing in it. Container-free: no database is involved.
 */
@SpringBootTest(
    classes = RetainAndDisposeContractTest.Wiring.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RetainAndDisposeContractTest {

    private static final long REFERENCE = 1_763_000_000_000_001L;
    private static final String JURISDICTION = "DEEDPOLL";
    private static final String CASE_TYPE = "DeedPoll";
    private static final String SYSTEM_UID = "system-user-uid";
    private static final String SYSTEM_TOKEN = "Bearer system-user-token";
    private static final String SERVICE_TOKEN = "Bearer service-token";
    private static final String CASE_PATH = "/caseworkers/" + SYSTEM_UID + "/jurisdictions/" + JURISDICTION
        + "/case-types/" + CASE_TYPE + "/cases/" + REFERENCE;

    private static final WireMockServer DATA_STORE = new WireMockServer(options().dynamicPort());

    static {
        DATA_STORE.start();
    }

    // Not a @Configuration: see the class comment.
    @EnableFeignClients(clients = CoreCaseDataApi.class)
    @ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        FeignAutoConfiguration.class})
    static class Wiring {
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("core_case_data.api.url", DATA_STORE::baseUrl);
    }

    @AfterAll
    static void stop() {
        DATA_STORE.stop();
    }

    @Autowired
    private CoreCaseDataApi coreCaseDataApi;

    private CoreCaseDataRetainAndDisposeClient client;

    private final RetainAndDisposeCase disposalCase =
        new RetainAndDisposeCase(REFERENCE, JURISDICTION, CASE_TYPE, "Registered");

    @BeforeEach
    void setUp() {
        DATA_STORE.resetAll();
        IdamClient idam = mock(IdamClient.class);
        when(idam.getAccessToken("system-user", "not-a-real-password")).thenReturn(SYSTEM_TOKEN);
        when(idam.getUserInfo(SYSTEM_TOKEN)).thenReturn(UserInfo.builder().uid(SYSTEM_UID).build());
        client = new CoreCaseDataRetainAndDisposeClient(
            coreCaseDataApi, () -> SERVICE_TOKEN, idam, "system-user", "not-a-real-password");
    }

    @ParameterizedTest(name = "{0} is started and submitted as the system user and lands in PendingDisposal")
    @ValueSource(strings = {RetainAndDisposePolicy.DISPOSAL_EVENT_ID, RetainAndDisposePolicy.CONFIRM_DISPOSAL_EVENT_ID})
    void systemEventMovesTheCaseToPendingDisposal(String eventId) {
        stubStart(eventId);
        stubSubmit(RetainAndDisposePolicy.DISPOSAL_STATE_ID);

        if (RetainAndDisposePolicy.DISPOSAL_EVENT_ID.equals(eventId)) {
            client.markForDisposal(disposalCase);
        } else {
            client.confirmDisposal(disposalCase);
        }

        DATA_STORE.verify(getRequestedFor(urlPathEqualTo(CASE_PATH + "/event-triggers/" + eventId + "/token"))
            .withHeader("Authorization", equalTo(SYSTEM_TOKEN))
            .withHeader("ServiceAuthorization", equalTo(SERVICE_TOKEN)));
        DATA_STORE.verify(postRequestedFor(urlPathEqualTo(CASE_PATH + "/events"))
            .withQueryParam("ignore-warning", equalTo("false"))
            .withHeader("Authorization", equalTo(SYSTEM_TOKEN))
            .withRequestBody(equalToJson("""
                {"event": {"id": "%s"}, "event_token": "start-token", "data": {"deedPollId": "record-uuid"}}
                """.formatted(eventId), true, true)));
    }

    @Test
    @DisplayName("an event CCD answers in any other state is a failure, not a disposal")
    void anotherStateIsRefused() {
        stubStart(RetainAndDisposePolicy.CONFIRM_DISPOSAL_EVENT_ID);
        stubSubmit("Registered");

        assertThatThrownBy(() -> client.confirmDisposal(disposalCase))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("instead of " + RetainAndDisposePolicy.DISPOSAL_STATE_ID);
    }

    @Test
    @DisplayName("a case data-store still serves is present")
    void presentCaseIsReadable() {
        DATA_STORE.stubFor(get(urlPathEqualTo("/cases/" + REFERENCE))
            .willReturn(okJson(caseDetails("PendingDisposal"))));

        assertThat(client.isReadable(disposalCase)).isTrue();
        DATA_STORE.verify(getRequestedFor(urlPathEqualTo("/cases/" + REFERENCE))
            .withHeader("Authorization", equalTo(SYSTEM_TOKEN))
            .withHeader("ServiceAuthorization", equalTo(SERVICE_TOKEN)));
    }

    @Test
    @DisplayName("404 for the reference is the one answer that reads as gone")
    void notFoundIsGone() {
        DATA_STORE.stubFor(get(urlPathEqualTo("/cases/" + REFERENCE)).willReturn(aResponse().withStatus(404)));

        assertThat(client.isReadable(disposalCase)).isFalse();
    }

    @ParameterizedTest(name = "{0} is a failure, never absence")
    @ValueSource(ints = {400, 401, 403, 409, 500, 502, 503})
    void otherStatusesAreFailures(int status) {
        DATA_STORE.stubFor(get(urlPathEqualTo("/cases/" + REFERENCE)).willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> client.isReadable(disposalCase))
            .isInstanceOf(FeignException.class)
            .isNotInstanceOf(FeignException.NotFound.class);
    }

    @Test
    @DisplayName("a dropped connection is a failure, never absence")
    void droppedConnectionIsAFailure() {
        DATA_STORE.stubFor(get(urlPathEqualTo("/cases/" + REFERENCE))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> client.isReadable(disposalCase))
            .isNotInstanceOf(FeignException.NotFound.class);
    }

    private void stubStart(String eventId) {
        DATA_STORE.stubFor(get(urlPathEqualTo(CASE_PATH + "/event-triggers/" + eventId + "/token"))
            .willReturn(okJson("""
                {"token": "start-token", "event_id": "%s", "case_details": %s}
                """.formatted(eventId, caseDetails("Registered")))));
    }

    private void stubSubmit(String state) {
        DATA_STORE.stubFor(post(urlPathEqualTo(CASE_PATH + "/events")).willReturn(okJson(caseDetails(state))));
    }

    private static String caseDetails(String state) {
        return """
            {"id": %d, "jurisdiction": "%s", "case_type_id": "%s", "state": "%s",
             "case_data": {"deedPollId": "record-uuid"}}
            """.formatted(REFERENCE, JURISDICTION, CASE_TYPE, state);
    }
}
