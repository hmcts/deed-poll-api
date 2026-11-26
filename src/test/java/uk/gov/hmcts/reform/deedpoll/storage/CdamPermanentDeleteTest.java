package uk.gov.hmcts.reform.deedpoll.storage;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.deedpoll.ccd.SystemUserTokens;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionFailure;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRemote.StepOutcome;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRemote.Verdict;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CdamPermanentDelete} over its own Feign client against a WireMock CDAM (plan
 * 20261125-permanent-document-deletion, Task 4): {@code permanent=true} with both tokens, {@code 204} the only
 * receipt, a {@code 404} never one, the timeouts its own, and every send exactly one send.
 */
class CdamPermanentDeleteTest {

    private static final UUID DOCUMENT = UUID.fromString("0b7c2a1e-4f3d-4a8b-9c6d-1e2f3a4b5c6d");
    private static final String PATH = "/cases/documents/" + DOCUMENT;

    private static final WireMockServer CDAM = new WireMockServer(options().dynamicPort());

    static {
        CDAM.start();
    }

    private final SystemUserTokens systemUser = mock(SystemUserTokens.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AuthTokenGenerator> serviceTokens = mock(ObjectProvider.class);
    private final AuthTokenGenerator generator = mock(AuthTokenGenerator.class);

    private CdamPermanentDelete cdam;

    @AfterAll
    static void stop() {
        CDAM.stop();
    }

    @BeforeEach
    void setUp() {
        CDAM.resetAll();
        when(systemUser.accessToken()).thenReturn("Bearer system-user");
        when(serviceTokens.getObject()).thenReturn(generator);
        when(generator.generate()).thenReturn("Bearer service");
        cdam = new CdamPermanentDelete(
            CdamPermanentDelete.client(CDAM.baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(500)), systemUser,
            serviceTokens);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 202})
    @DisplayName("A 2xx other than 204 is not a receipt: unknown, retried, and sent once")
    void otherSuccessIsNotAReceipt(int status) {
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status)));

        assertThat(cdam.delete(DOCUMENT)).isEqualTo(StepOutcome.retry("NOT_A_RECEIPT"));

        CDAM.verify(1, deleteRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("204 to DELETE ?permanent=true, as the system user with S2S, is the receipt")
    void deleted() {
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(204)));

        assertThat(cdam.delete(DOCUMENT)).isEqualTo(StepOutcome.done("DELETED"));

        CDAM.verify(1, deleteRequestedFor(urlPathEqualTo(PATH))
            .withQueryParam("permanent", equalTo("true"))
            .withHeader("Authorization", equalTo("Bearer system-user"))
            .withHeader("ServiceAuthorization", equalTo("Bearer service")));
        CDAM.verify(0, deleteRequestedFor(urlPathEqualTo(PATH)).withQueryParam("permanent", equalTo("false")));
    }

    @Test
    @DisplayName("a budget spent by the token grants sends no delete: not sent, and not a try")
    void outOfTimeAfterTokens() {
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(204)));

        assertThat(cdam.delete(DOCUMENT, () -> Duration.ofMillis(-1))).isEqualTo(StepOutcome.notSent("OUT_OF_TIME"));

        // Time left for the first grant, gone by the second: the service's grant is never waited for.
        Iterator<Duration> left = List.of(Duration.ofSeconds(5), Duration.ofMillis(-1)).iterator();
        assertThat(cdam.delete(DOCUMENT, left::next)).isEqualTo(StepOutcome.notSent("OUT_OF_TIME"));
        verify(generator, never()).generate();

        CDAM.verify(0, deleteRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("a token grant that hangs is waited for only as long as the budget allows, and sends nothing")
    void hungGrantIsBounded() throws InterruptedException {
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(204)));
        CountDownLatch release = new CountDownLatch(1);
        when(systemUser.accessToken()).thenAnswer(invocation -> {
            release.await(10, TimeUnit.SECONDS);
            return "Bearer system-user";
        });
        long deadline = System.nanoTime() + Duration.ofMillis(200).toNanos();

        long started = System.nanoTime();
        try {
            assertThat(cdam.delete(DOCUMENT, () -> Duration.ofNanos(deadline - System.nanoTime())))
                .isEqualTo(StepOutcome.notSent("OUT_OF_TIME"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown();
        }
        CDAM.verify(0, deleteRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("a token grant that fails within the budget is a try, as it was")
    void failedGrantWithinBudget() {
        when(generator.generate()).thenThrow(new IllegalStateException("s2s down"));

        assertThat(cdam.delete(DOCUMENT, () -> Duration.ofSeconds(5)))
            .isEqualTo(StepOutcome.retry("TOKEN_UNAVAILABLE"));
        CDAM.verify(0, deleteRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("tokens in hand within the budget: the delete is sent with them")
    void withinBudget() {
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(204)));

        assertThat(cdam.delete(DOCUMENT, () -> Duration.ofSeconds(5))).isEqualTo(StepOutcome.done("DELETED"));
        CDAM.verify(1, deleteRequestedFor(urlPathEqualTo(PATH))
            .withHeader("Authorization", equalTo("Bearer system-user"))
            .withHeader("ServiceAuthorization", equalTo("Bearer service")));
    }

    @Test
    @DisplayName("404 is never a receipt: the request needs a person to confirm the purge")
    void notFoundIsUnproven() {
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThat(cdam.delete(DOCUMENT))
            .isEqualTo(StepOutcome.failed("NOT_FOUND", DocumentDeletionFailure.CDAM_PURGE_UNPROVEN));
    }

    @Test
    @DisplayName("403 is CDAM's service permission: an activation blocker, not a retry")
    void forbidden() {
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(403)));

        assertThat(cdam.delete(DOCUMENT))
            .isEqualTo(StepOutcome.failed("FORBIDDEN", DocumentDeletionFailure.CDAM_PERMISSION));
    }

    @Test
    @DisplayName("401 is retried, a 400 refused, and neither is absence")
    void unauthorisedAndBadRequest() {
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)));
        assertThat(cdam.delete(DOCUMENT)).isEqualTo(StepOutcome.retry("UNAUTHORISED"));

        CDAM.resetAll();
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(400)));
        assertThat(cdam.delete(DOCUMENT))
            .isEqualTo(StepOutcome.failed("REFUSED", DocumentDeletionFailure.CDAM_DELETE_FAILED));
    }

    @ParameterizedTest(name = "{0} is an unknown outcome, retried")
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void unknownStatuses(int status) {
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status)));

        assertThat(cdam.delete(DOCUMENT)).isEqualTo(StepOutcome.retry("UNKNOWN"));
        CDAM.verify(1, deleteRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("a read timeout is unknown, sent once, inside the deletion's own read timeout")
    void readTimeout() {
        CDAM.stubFor(delete(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(204).withFixedDelay(2_000)));

        long started = System.nanoTime();
        StepOutcome outcome = cdam.delete(DOCUMENT);

        assertThat(outcome.verdict()).isEqualTo(Verdict.RETRY);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_800));
        CDAM.verify(1, deleteRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("a reset connection is unknown and is not re-sent underneath")
    void connectionReset() {
        CDAM.stubFor(delete(urlPathEqualTo(PATH))
                         .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(cdam.delete(DOCUMENT)).isEqualTo(StepOutcome.retry("UNKNOWN"));
        CDAM.verify(1, deleteRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("no tokens, no send: retried, and CDAM hears nothing")
    void tokensUnavailable() {
        when(systemUser.accessToken()).thenThrow(new IllegalStateException("IDAM down"));

        assertThat(cdam.delete(DOCUMENT)).isEqualTo(StepOutcome.retry("TOKEN_UNAVAILABLE"));
        CDAM.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("an unreachable CDAM is unknown, never absence")
    void unreachable() {
        CdamPermanentDelete nowhere = new CdamPermanentDelete(
            CdamPermanentDelete.client("http://127.0.0.1:1", Duration.ofMillis(200), Duration.ofMillis(200)),
            systemUser, serviceTokens);

        assertThat(nowhere.delete(DOCUMENT).verdict()).isEqualTo(Verdict.RETRY);
    }
}
