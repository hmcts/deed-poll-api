package uk.gov.hmcts.reform.deedpoll.notify;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import uk.gov.service.notify.NotificationClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/**
 * A stub GOV.UK Notify: the send endpoint, the template-preview endpoint, and any status either of them should
 * answer with.
 *
 * <p>Modelled on {@link uk.gov.hmcts.reform.deedpoll.auth.StubIdam}, and here for the same reason. There is no
 * Notify service for deed poll and no API key, so a test that talked to the real one is a test nobody could
 * run -- and the interesting cases are ones the real Notify will not produce to order: the rate limit, an
 * outage, a status nobody enumerated. {@link NotificationFailure#classify(int)} states the mapping; this is
 * what proves the mapping survives a real HTTP round trip through the Notify client, whose exception carries
 * the status only if it actually parsed the response.
 *
 * <p>Its {@link #apiKey()} is well-formed -- {@code <name>-<service uuid>-<secret uuid>}, the shape the client
 * splits to mint its JWT -- and entirely invented, so nothing here is a credential that also exists somewhere
 * real. Paths are the ones the 6.x client actually calls, read off the jar's constant pool rather than guessed:
 * {@code POST /v2/notifications/email} and {@code POST /v2/template/{id}/preview}.
 */
public final class StubNotify {

    /**
     * The service ID inside {@link #apiKey()} -- invented, and the only place it is written down.
     */
    public static final String SERVICE_ID = "8e0a5a1b-6ba2-4b0a-9a24-9f2a19a3aa11";

    /**
     * The secret half of {@link #apiKey()}. Invented.
     */
    private static final String SECRET_ID = "4d0f1c73-8c1b-4d9b-9f54-0b4a1f77bb22";

    /** A template ID the stub will answer for: a real-looking UUID, so a test can assert one was sent. */
    public static final String TEMPLATE_ID = "c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e55";

    /** The notification ID the stub returns from a send, unless a test asks for another. */
    public static final UUID NOTIFICATION_ID = UUID.fromString("6f0b9d3a-1e2c-4b5d-8a7f-3c4d5e6f7a88");

    /** The template version the stub's preview and send responses agree on. */
    public static final int TEMPLATE_VERSION = 7;

    // Response templating on, so a send's response can echo the `reference` the request carried instead of a
    // fixed one. The reference is the outbox row id (plan decision 14 and task 5), and a stub that echoed
    // something else would let a sender that sent the wrong reference pass.
    private final WireMockServer server =
        new WireMockServer(WireMockConfiguration.options().dynamicPort().globalTemplating(true));

    public void start() {
        server.start();
        stubDefaults();
    }

    public void stop() {
        server.stop();
    }

    /** A Notify that accepts a send and renders a preview. */
    public void stubDefaults() {
        server.resetAll();
        stubSend(NOTIFICATION_ID);
        stubPreview("Your application has been rejected", "<p>Because the Master said so.</p>", TEMPLATE_VERSION);
    }

    /**
     * A Notify that accepts every send and answers with {@code notificationId}.
     *
     * <p><strong>201, not 200.</strong> The client checks the status of a send against 201 exactly and treats
     * anything else -- a 200 included -- as a failure, with the status on the exception. A stub answering 200
     * therefore looks like an outage rather than a success, which is a confusing half-hour to lose.
     */
    public void stubSend(UUID notificationId) {
        stubSend(notificationId, "Your application has been rejected", "Because the Master said so.");
    }

    /**
     * A Notify that accepts a send and returns the supplied rendered wording.
     */
    public void stubSend(UUID notificationId, String subject, String body) {
        server.stubFor(post(urlPathEqualTo("/v2/notifications/email")).willReturn(createdJson("""
            {
              "id": "%s",
              "reference": "{{jsonPath request.body '$.reference'}}",
              "content": {
                "subject": %s,
                "body": %s,
                "from_email": "deed-poll@notifications.service.gov.uk"
              },
              "template": {
                "id": "%s",
                "version": %d,
                "uri": "%s/v2/template/%s"
              },
              "uri": "%s/v2/notifications/%s"
            }
            """.formatted(notificationId, quote(subject), quote(body), TEMPLATE_ID, TEMPLATE_VERSION,
            baseUrl(), TEMPLATE_ID, baseUrl(), notificationId))));
    }

    /**
     * Whatever Notify should answer a send with, when the answer is a refusal, a rate limit or an outage.
     *
     * <p>The body is Notify's own error shape, so the client parses it the way it would parse a real refusal
     * rather than taking a different path for an unreadable body.
     */
    public void stubSendStatus(int status) {
        server.stubFor(post(urlPathEqualTo("/v2/notifications/email"))
            .willReturn(errorResponse(status, "the stub was asked for " + status)));
    }

    /**
     * A Notify that renders any template ID as {@code subject} and {@code html}.
     */
    public void stubPreview(String subject, String html, int version) {
        server.stubFor(post(urlPathMatching("/v2/template/[^/]+/preview")).willReturn(okJson("""
            {
              "id": "%s",
              "type": "email",
              "version": %d,
              "body": %s,
              "subject": %s,
              "html": %s
            }
            """.formatted(TEMPLATE_ID, version, quote(html.replaceAll("<[^>]*>", "")), quote(subject),
            quote(html)))));
    }

    /**
     * Whatever Notify should answer a preview with.
     *
     * <p>400 is the one that matters most: it is what a real Notify answers for the placeholder template ID
     * this service carries until notify-0 provisions a real one.
     */
    public void stubPreviewStatus(int status) {
        server.stubFor(post(urlPathMatching("/v2/template/[^/]+/preview"))
            .willReturn(errorResponse(status, "the stub was asked for " + status)));
    }

    /** Every send this stub was asked to make, in order, for asserting on what was actually sent. */
    public List<LoggedRequest> emailRequests() {
        return server.findAll(postRequestedFor(urlPathEqualTo("/v2/notifications/email")));
    }

    /** Every preview this stub was asked to render. A preview that wrote an outbox row would show up here. */
    public List<LoggedRequest> previewRequests() {
        return server.findAll(postRequestedFor(urlPathMatching("/v2/template/[^/]+/preview")));
    }

    public WireMockServer server() {
        return server;
    }

    public String baseUrl() {
        return server.baseUrl();
    }

    /**
     * A well-formed Notify API key naming {@link #SERVICE_ID}. Invented, not a credential: the client splits
     * the last two UUIDs out to sign its JWT and never checks that either exists.
     */
    public String apiKey() {
        return "deed-poll-api-" + SERVICE_ID + "-" + SECRET_ID;
    }

    /**
     * A client pointed at this stub, configured exactly as {@link NotifyConfiguration} configures the real one.
     */
    public NotificationClient client() {
        return new NotifyConfiguration().notificationClient(properties());
    }

    /** The properties a service under test should be configured with to reach this stub. */
    public NotifyProperties properties() {
        return new NotifyProperties(baseUrl(), apiKey(), Map.of("application-rejected", TEMPLATE_ID));
    }

    /** A 201 with a JSON body -- what the real Notify answers a send with, and what its client insists on. */
    private static ResponseDefinitionBuilder createdJson(String body) {
        return aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static ResponseDefinitionBuilder errorResponse(int status, String message) {
        return aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"status_code": %d, "errors": [{"error": "StubNotifyError", "message": %s}]}
                """.formatted(status, quote(message)));
    }

    /** JSON-quotes a value, so an html body full of angle brackets and quotes survives being embedded. */
    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> quoted.append(character);
            }
        }
        return quoted.append('"').toString();
    }
}
