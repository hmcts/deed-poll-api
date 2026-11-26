package uk.gov.hmcts.reform.deedpoll.storage;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
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
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Permanent document deletion plan (20261125), Task 1: the stubbed proof of CDAM's permanent delete and of what a
 * retry can and cannot tell us, against the client that will actually send it -- the pinned
 * {@code ccd-case-document-am-client} 1.59.2's {@link CaseDocumentClientApi}, over a real Feign client carrying this
 * service's {@link NoConnectionRetry} capability, against a WireMock CDAM.
 *
 * <p>The stub models the platform as {@code docs/DOCUMENT-DELETION.md} cites it (CDAM
 * {@code CaseDocumentAmController.deleteDocumentByDocumentId} and dm-store {@code StoredDocumentService.findOne} /
 * {@code AuditedStoredDocumentOperationsService.deleteStoredDocument}): CDAM reads the document's metadata from
 * dm-store <em>before</em> forwarding the delete, and dm-store hides a soft- or hard-deleted document from that read.
 * So a live document answers {@code 204} to {@code permanent=true}, and afterwards every delete of it -- permanent or
 * not -- answers {@code 404} without reaching dm-store. A document somebody else soft-deleted answers the same
 * {@code 404}, with its blob still in dm-store. Hence the contract this test pins:
 *
 * <ul>
 *   <li>{@code 204} on {@code DELETE /cases/documents/{id}?permanent=true} is the only receipt of a permanent
 *       delete;</li>
 *   <li>a {@code 404} is never a receipt: it reads the same after our own lost {@code 204}, after an earlier soft
 *       delete and for an id CDAM never held, so it leaves the request needing attention;</li>
 *   <li>a dropped connection or a read timeout is an unknown outcome, sent exactly once (no transport resend), and
 *       re-sending the same permanent delete is safe: it either deletes (204) or meets the 404 above;</li>
 *   <li>400, 401, 403 and 5xx are failures, never absence.</li>
 * </ul>
 *
 * <p>Not a Spring component (see {@code RetainAndDisposeContractTest}); container-free.
 */
@SpringBootTest(
    classes = CdamPermanentDeleteContractTest.Wiring.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CdamPermanentDeleteContractTest {

    private static final UUID DOCUMENT = UUID.fromString("5f0c8f1e-9a43-4f53-8d5e-2c7c4b9d1a60");
    private static final String PATH = "/cases/documents/" + DOCUMENT;
    private static final String SYSTEM_TOKEN = "Bearer system-user-token";
    private static final String SERVICE_TOKEN = "Bearer service-token";
    private static final String SCENARIO = "dm-store document";
    private static final String HARD_DELETED = "hard deleted";

    private static final WireMockServer CDAM = new WireMockServer(options().dynamicPort());

    static {
        CDAM.start();
    }

    // Not a @Configuration: see the class comment.
    @EnableFeignClients(clients = CaseDocumentClientApi.class)
    @ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        FeignAutoConfiguration.class})
    static class Wiring {
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("case_document_am.url", CDAM::baseUrl);
        // As application.yaml applies it, so the transport under test is the one production uses.
        registry.add("spring.cloud.openfeign.client.config.case-document-am-client-api.capabilities[0]",
            NoConnectionRetry.class::getName);
        registry.add("spring.cloud.openfeign.client.config.case-document-am-client-api.read-timeout", () -> "500");
    }

    @AfterAll
    static void stop() {
        CDAM.stop();
    }

    @Autowired
    private CaseDocumentClientApi cdam;

    @BeforeEach
    void setUp() {
        CDAM.resetAll();
    }

    @Test
    @DisplayName("a permanent delete is DELETE ?permanent=true as the system user with S2S, and 204 is its receipt")
    void permanentDeleteIsAcceptedWith204() {
        liveDocument();

        cdam.deleteDocument(SYSTEM_TOKEN, SERVICE_TOKEN, DOCUMENT, true);

        CDAM.verify(1, deleteRequestedFor(urlPathEqualTo(PATH))
            .withQueryParam("permanent", equalTo("true"))
            .withHeader("Authorization", equalTo(SYSTEM_TOKEN))
            .withHeader("ServiceAuthorization", equalTo(SERVICE_TOKEN)));
        CDAM.verify(0, deleteRequestedFor(urlPathEqualTo(PATH)).withQueryParam("permanent", equalTo("false")));
    }

    @Test
    @DisplayName("after a permanent delete, a replay is 404: CDAM's metadata read no longer finds the document")
    void replayAfterPermanentDeleteIs404() {
        liveDocument();
        cdam.deleteDocument(SYSTEM_TOKEN, SERVICE_TOKEN, DOCUMENT, true);

        assertThatThrownBy(() -> cdam.deleteDocument(SYSTEM_TOKEN, SERVICE_TOKEN, DOCUMENT, true))
            .isInstanceOf(FeignException.NotFound.class);
    }

    @Test
    @DisplayName("404 is not a receipt: a document soft-deleted elsewhere, its blob kept, answers the same 404")
    void softDeletedElsewhereReadsTheSame() {
        liveDocument();
        cdam.deleteDocument(SYSTEM_TOKEN, SERVICE_TOKEN, DOCUMENT, true);
        Throwable afterOurPermanentDelete = catchThrowable(
            () -> cdam.deleteDocument(SYSTEM_TOKEN, SERVICE_TOKEN, DOCUMENT, true));

        CDAM.resetAll();
        softDeletedDocument();
        Throwable afterSomebodyElsesSoftDelete = catchThrowable(
            () -> cdam.deleteDocument(SYSTEM_TOKEN, SERVICE_TOKEN, DOCUMENT, true));

        assertThat(afterOurPermanentDelete).isInstanceOf(FeignException.NotFound.class);
        assertThat(afterSomebodyElsesSoftDelete).isInstanceOf(FeignException.NotFound.class);
        assertThat(((FeignException) afterSomebodyElsesSoftDelete).status())
            .isEqualTo(((FeignException) afterOurPermanentDelete).status());
    }

    @Test
    @DisplayName("a dropped connection is unknown, sent once; the retry deletes when the first never landed")
    void lostRequestThenRetryDeletes() {
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).inScenario(SCENARIO)
            .whenScenarioStateIs(Scenario.STARTED)
            .withQueryParam("permanent", equalTo("true"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
            .willSetStateTo("reset once"));
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).inScenario(SCENARIO)
            .whenScenarioStateIs("reset once")
            .withQueryParam("permanent", equalTo("true"))
            .willReturn(aResponse().withStatus(204))
            .willSetStateTo(HARD_DELETED));

        Throwable lost = catchThrowable(() -> cdam.deleteDocument(SYSTEM_TOKEN, SERVICE_TOKEN, DOCUMENT, true));

        assertThat(lost).isNotNull().isNotInstanceOf(FeignException.NotFound.class);
        CDAM.verify(1, deleteRequestedFor(urlPathEqualTo(PATH)));
        cdam.deleteDocument(SYSTEM_TOKEN, SERVICE_TOKEN, DOCUMENT, true);
        CDAM.verify(2, deleteRequestedFor(urlPathEqualTo(PATH)).withQueryParam("permanent", equalTo("true")));
    }

    @Test
    @DisplayName("a lost answer to a delete that landed: the retry is safe, and its 404 proves nothing")
    void lostAnswerThenRetryIsAmbiguous() {
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).inScenario(SCENARIO)
            .whenScenarioStateIs(Scenario.STARTED)
            .withQueryParam("permanent", equalTo("true"))
            .willReturn(aResponse().withStatus(204).withFixedDelay(1_500))
            .willSetStateTo(HARD_DELETED));
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).inScenario(SCENARIO)
            .whenScenarioStateIs(HARD_DELETED)
            .willReturn(aResponse().withStatus(404)));

        Throwable timedOut = catchThrowable(() -> cdam.deleteDocument(SYSTEM_TOKEN, SERVICE_TOKEN, DOCUMENT, true));

        assertThat(timedOut).isNotNull().isNotInstanceOf(FeignException.NotFound.class);
        CDAM.verify(1, deleteRequestedFor(urlPathEqualTo(PATH)));
        assertThatThrownBy(() -> cdam.deleteDocument(SYSTEM_TOKEN, SERVICE_TOKEN, DOCUMENT, true))
            .isInstanceOf(FeignException.NotFound.class);
    }

    @ParameterizedTest(name = "{0} is a failure, never absence and never a receipt")
    @ValueSource(ints = {400, 401, 403, 500, 502, 503, 504})
    void otherStatusesAreFailures(int status) {
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> cdam.deleteDocument(SYSTEM_TOKEN, SERVICE_TOKEN, DOCUMENT, true))
            .isInstanceOf(FeignException.class)
            .isNotInstanceOf(FeignException.NotFound.class)
            .extracting(failure -> ((FeignException) failure).status()).isEqualTo(status);
        CDAM.verify(1, deleteRequestedFor(urlPathEqualTo(PATH)));
    }

    /** A live document: the first permanent delete is 204 and hard-deletes it; anything after is CDAM's 404. */
    private static void liveDocument() {
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).inScenario(SCENARIO)
            .whenScenarioStateIs(Scenario.STARTED)
            .withQueryParam("permanent", equalTo("true"))
            .willReturn(aResponse().withStatus(204))
            .willSetStateTo(HARD_DELETED));
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).inScenario(SCENARIO)
            .whenScenarioStateIs(HARD_DELETED)
            .willReturn(aResponse().withStatus(404)));
    }

    /** A document soft-deleted before we asked: dm-store keeps its blob, and CDAM's metadata read is 404. */
    private static void softDeletedDocument() {
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));
    }
}
