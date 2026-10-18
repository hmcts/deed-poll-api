package uk.gov.hmcts.reform.deedpoll.notify;

import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteDeliveryConfiguration;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRequest;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRequestState;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GeneratedGazetteSpreadsheet;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GazetteNotifyTest {
    private final StubNotify stub = new StubNotify();
    private GazetteNotify sender;
    private GazetteRequest row;
    private GazetteNotify.Prepared files;

    @BeforeEach
    void start() {
        stub.start();
        sender = new GazetteNotify(stub.client());
        LocalDateTime now = LocalDateTime.now();
        row = new GazetteRequest(UUID.randomUUID(), UUID.randomUUID(), "0".repeat(64), "subject", "Admin", now,
            "gazette@example.com", 1, 1, 0, new GazetteDeliveryConfiguration(UUID.fromString(StubNotify.TEMPLATE_ID),
                UUID.fromString(StubNotify.REPLY_TO_ID), 4), UUID.randomUUID(), now.plusMinutes(5),
            GazetteRequestState.Sending, now, null, null);
        files = sender.prepare(row, List.of(new GeneratedGazetteSpreadsheet("gazette-adult.xlsx", 1,
            "synthetic file".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @AfterEach
    void stop() {
        stub.stop();
    }

    @Test
    void localPreparationUsesSdkEncodingAndNoNetworkAndRedactsDiagnostics() {
        assertThat(stub.emailRequests()).isEmpty();
        assertThat(stub.server().getAllServeEvents()).isEmpty();
        assertThat(files.toString()).doesNotContain("synthetic", "file=", "@", "spreadsheet", "base64");
        assertThat(files.evidence()).hasSize(1);
        assertThat(files.evidence().getFirst().byteSize()).isEqualTo(14);
        assertThat(sender.send(row, files).notificationId()).isEqualTo(StubNotify.NOTIFICATION_ID);
        assertThat(stub.emailRequests()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308, 401, 407, 408, 421, 429, 500, 502, 503})
    void neverReplaysEvenWhenServerSuggestsRetryOrRedirect(int status) {
        stub.server().stubFor(post(urlPathEqualTo("/v2/notifications/email"))
            .willReturn(aResponse().withStatus(status).withHeader("Retry-After", "0")
                .withHeader("Location", stub.baseUrl() + "/v2/notifications/email")
                .withBody("private provider response must not escape")));
        assertThatThrownBy(() -> sender.send(row, files)).isInstanceOfSatisfying(GazetteNotify.Failure.class,
            error -> {
                assertThat(error.status()).isEqualTo(status == 407 ? 0 : status);
                assertThat(error).hasMessage("Gazette Notify call did not return a verified acceptance");
                assertThat(error.getCause()).isNull();
            });
        assertThat(stub.emailRequests()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY_RESPONSE", "CONNECTION_RESET_BY_PEER", "MALFORMED_RESPONSE_CHUNK"})
    void transportFailureAfterRequestIsNotReplayed(String fault) {
        stub.server().stubFor(post(urlPathEqualTo("/v2/notifications/email"))
            .willReturn(aResponse().withFault(Fault.valueOf(fault))));
        assertThatThrownBy(() -> sender.send(row, files)).isInstanceOfSatisfying(GazetteNotify.Failure.class,
            error -> assertThat(NotificationFailure.classify(error.status())).isEqualTo(NotificationFailure.TECHNICAL));
        assertThat(stub.emailRequests()).hasSize(1);
    }

    @Test
    void wholeCallDeadlineEndsASlowResponseWithoutReplay() {
        stub.server().stubFor(post(urlPathEqualTo("/v2/notifications/email"))
            .willReturn(aResponse().withFixedDelay(1000).withStatus(201).withBody("{}")));
        long start = System.nanoTime();
        assertThatThrownBy(() -> sender.send(row, files, GazetteNotify.transport(Duration.ofMillis(150))))
            .isInstanceOfSatisfying(GazetteNotify.Failure.class, error -> assertThat(error.status()).isZero());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(stub.emailRequests()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not json", "{\"id\":\"private-invalid-id\"}"})
    void malformedAcceptanceIsUncertainWithoutExposingItsBody(String body) {
        stub.server().stubFor(post(urlPathEqualTo("/v2/notifications/email"))
            .willReturn(aResponse().withStatus(201).withBody(body)));
        assertThatThrownBy(() -> sender.send(row, files)).isInstanceOfSatisfying(GazetteNotify.Failure.class,
            error -> assertThat(error.status()).isZero());
        assertThat(stub.emailRequests()).hasSize(1);
    }

    @Test
    void transportBoundsAndReplayControlsAreExplicit() {
        var client = GazetteNotify.transport(Duration.ofSeconds(60));
        assertThat(client.retryOnConnectionFailure()).isFalse();
        assertThat(client.followRedirects()).isFalse();
        assertThat(client.followSslRedirects()).isFalse();
        assertThat(client.proxy()).isEqualTo(java.net.Proxy.NO_PROXY);
        assertThat(client.connectTimeoutMillis()).isEqualTo(5000);
        assertThat(client.readTimeoutMillis()).isEqualTo(30000);
        assertThat(client.writeTimeoutMillis()).isEqualTo(30000);
        assertThat(client.callTimeoutMillis()).isEqualTo(60000);
        assertThat(client.interceptors()).isEmpty();
        assertThat(client.networkInterceptors()).isEmpty();
    }
}
