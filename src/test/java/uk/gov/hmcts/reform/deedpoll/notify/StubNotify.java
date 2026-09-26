package uk.gov.hmcts.reform.deedpoll.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import uk.gov.service.notify.NotificationClient;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /**
     * A reply-to address id the stub's properties carry, for the templates whose words invite a reply. A
     * real-looking UUID and entirely invented -- Notify never sees it here, the assertion is that the send body
     * carried it.
     */
    public static final String REPLY_TO_ID = "9b7c5d21-3f4e-4a6b-8c0d-1e2f3a4b5c66";

    /** The notification ID the stub returns from a send, unless a test asks for another. */
    public static final UUID NOTIFICATION_ID = UUID.fromString("6f0b9d3a-1e2c-4b5d-8a7f-3c4d5e6f7a88");

    /** The template version the stub's preview and send responses agree on. */
    public static final int TEMPLATE_VERSION = 7;

    /**
     * How long the stub's properties say Notify keeps a document, in weeks.
     *
     * <p>Invented, and deliberately not a round number a real environment would plausibly use: the number reaches
     * the send body as part of the upload, so a test asserting on it must be asserting on <em>this</em> value
     * rather than on a default somebody guessed.
     */
    public static final int RETENTION_WEEKS = 17;

    // Response templating on, so a send's response can echo the `reference` the request carried instead of a
    // fixed one. The reference is the outbox row id (plan decision 14 and task 5), and a stub that echoed
    // something else would let a sender that sent the wrong reference pass.
    private final WireMockServer server =
        new WireMockServer(WireMockConfiguration.options().dynamicPort().globalTemplating(true)
            .extensions(new RequiredPlaceholders()));

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
     * A Notify whose template has these placeholders, all required: a preview lacking any of them is Notify's 400,
     * and a send is accepted either way.
     *
     * <p><strong>That asymmetry is the real Notify's, measured in notify-4a Task 3.</strong> A file attached to a
     * template in the portal is a placeholder named after the file, and a real send fills it in as a download link
     * while the preview endpoint answers {@code Missing personalisation: <names>} without a value for it. A stub that
     * rendered every preview would let a service that previewed such a template pass a test and 502 in production.
     *
     * <p>The rendering prints each placeholder's value, as Notify does, so a test can see what stood in for it.
     */
    public void stubTemplateRequiring(String... placeholders) {
        server.stubFor(post(urlPathMatching("/v2/template/[^/]+/preview")).willReturn(
            aResponse().withTransformers(RequiredPlaceholders.NAME)
                .withTransformerParameter(RequiredPlaceholders.REQUIRED, List.of(placeholders))));
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

    /**
     * The properties a service under test should be configured with to reach this stub.
     *
     * <p>Every template points at {@link #TEMPLATE_ID}, because the stub answers any template id: what a test
     * asserts about a send is the request body, and a second id would only be a second thing to keep in step.
     * {@link #REPLY_TO_ID} is set, so a template that invites a reply is provisioned here -- the unconfigured
     * case is its own assertion rather than the default this stub imposes on every test.
     *
     * <p>{@link #RETENTION_WEEKS} is set for the same reason: a template that carries a document is unprovisioned
     * without a retention period, so leaving it out would make every forms-email test assert the unconfigured case
     * by accident.
     */
    public NotifyProperties properties() {
        return new NotifyProperties(
            baseUrl(),
            apiKey(),
            Arrays.stream(EmailTemplate.values())
                .collect(Collectors.toMap(EmailTemplate::key, template -> TEMPLATE_ID)),
            REPLY_TO_ID,
            RETENTION_WEEKS);
    }

    /**
     * Answers a preview the way Notify does for a template whose placeholders are all required.
     *
     * <p>Applied only to the stub {@link #stubTemplateRequiring} writes, so every other test's preview is
     * unchanged. The request's personalisation is read as JSON rather than matched as text, because a placeholder
     * named after a file has spaces and dots in it.
     */
    private static final class RequiredPlaceholders implements ResponseDefinitionTransformerV2 {

        static final String NAME = "stub-notify-required-placeholders";

        static final String REQUIRED = "required";

        private static final ObjectMapper JSON = new ObjectMapper();

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public ResponseDefinition transform(ServeEvent event) {
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) event.getTransformerParameters().get(REQUIRED);
            JsonNode personalisation;
            try {
                personalisation = JSON.readTree(event.getRequest().getBodyAsString()).path("personalisation");
            } catch (JsonProcessingException unreadable) {
                return errorResponse(400, "the stub could not read the request").build();
            }
            List<String> missing = required.stream().filter(key -> !personalisation.has(key)).toList();
            if (!missing.isEmpty()) {
                // Notify's own error class and wording, so the client and the service read it as the real one.
                return errorResponse(400, "BadRequestError", "Missing personalisation: " + String.join(", ", missing))
                    .build();
            }
            String html = required.stream()
                .map(key -> "<p>" + personalisation.path(key).asText() + "</p>")
                .collect(Collectors.joining());
            return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                {
                  "id": "%s",
                  "type": "email",
                  "version": %d,
                  "body": %s,
                  "subject": "Forms for completion",
                  "html": %s
                }
                """.formatted(TEMPLATE_ID, TEMPLATE_VERSION, quote(html.replaceAll("<[^>]*>", "")), quote(html)))
                .build();
        }
    }

    /** A 201 with a JSON body -- what the real Notify answers a send with, and what its client insists on. */
    private static ResponseDefinitionBuilder createdJson(String body) {
        return aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static ResponseDefinitionBuilder errorResponse(int status, String message) {
        return errorResponse(status, "StubNotifyError", message);
    }

    private static ResponseDefinitionBuilder errorResponse(int status, String error, String message) {
        return aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"status_code": %d, "errors": [{"error": %s, "message": %s}]}
                """.formatted(status, quote(error), quote(message)));
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
